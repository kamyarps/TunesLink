using System.Text;
using System.Text.Json;

namespace TunesLinkBridge;

internal sealed record LibraryIndexData(
    LibraryTrack[] Tracks,
    string[] TrackGenres,
    LibraryCollection[] Artists,
    LibraryCollection[] Albums,
    LibraryCollection[] Genres,
    string Revision,
    string SourceSignature,
    DateTimeOffset CreatedAt);

internal readonly record struct LibraryIndexFileStamp(
    long Length, long CreationUtcTicks, long LastWriteUtcTicks);

internal sealed class LibraryIndexStore
{
    // Version 4 moved the album artist onto each track so the wire model and the queue share it.
    // Version 6 leaves out videos, podcasts, and audiobooks and orders collections linguistically.
    // An older index is discarded rather than reused so a stale grouping never survives an update.
    internal const int SchemaVersion = 6;
    internal const int MaxFileBytes = 128 * 1024 * 1024;
    internal const int MaxItems = 1_000_000;

    private sealed record Envelope(
        int SchemaVersion,
        LibraryTrack[] Tracks,
        string[] TrackGenres,
        LibraryCollection[] Artists,
        LibraryCollection[] Albums,
        LibraryCollection[] Genres,
        string Revision,
        string SourceSignature,
        DateTimeOffset CreatedAt);

    private readonly string path;
    private readonly IAtomicFilePersistence persistence;

    internal LibraryIndexStore(string configDirectory,
                               IAtomicFilePersistence? persistence = null)
    {
        path = Path.Combine(configDirectory, "Cache", "library-index-v1.json");
        this.persistence = persistence ?? AtomicFilePersistence.Instance;
    }

    internal LibraryIndexFileStamp? Stamp()
    {
        try
        {
            FileInfo file = new(path);
            if (!file.Exists) return null;
            return new(file.Length, file.CreationTimeUtc.Ticks, file.LastWriteTimeUtc.Ticks);
        }
        catch
        {
            return null;
        }
    }

    internal LibraryIndexData? Load()
    {
        try
        {
            using FileStream stream = new(path, FileMode.Open, FileAccess.Read, FileShare.Read);
            if (stream.Length is <= 0 or > MaxFileBytes) return null;
            using StreamReader reader = new(stream, Encoding.UTF8, detectEncodingFromByteOrderMarks: true,
                bufferSize: 16 * 1024, leaveOpen: false);
            Envelope? value = JsonSerializer.Deserialize<Envelope>(
                reader.ReadToEnd());
            if (!IsValid(value)) return null;
            return new LibraryIndexData(value!.Tracks, value.TrackGenres,
                value.Artists, value.Albums, value.Genres,
                value.Revision, value.SourceSignature, value.CreatedAt);
        }
        catch
        {
            return null;
        }
    }

    internal void Save(LibraryIndexData value)
    {
        ArgumentNullException.ThrowIfNull(value);
        Envelope envelope = new(SchemaVersion, value.Tracks, value.TrackGenres,
            value.Artists, value.Albums, value.Genres, value.Revision,
            value.SourceSignature, value.CreatedAt);
        if (!IsValid(envelope))
            throw new ArgumentException("The library index contains invalid data", nameof(value));
        persistence.Write(path, stream =>
        {
            using BoundedIndexStream bounded = new(stream, MaxFileBytes);
            JsonSerializer.Serialize(bounded, envelope);
        });
    }

    private static bool IsValid(Envelope? value)
    {
        if (value is null || value.SchemaVersion != SchemaVersion
            || value.Tracks is null || value.TrackGenres is null
            || value.Artists is null
            || value.Albums is null || value.Genres is null
            || value.TrackGenres.Length != value.Tracks.Length
            || value.Tracks.Length > MaxItems || value.Artists.Length > MaxItems
            || value.Albums.Length > MaxItems || value.Genres.Length > MaxItems
            || !IsSha256(value.Revision) || !IsSha256(value.SourceSignature))
            return false;
        return value.Tracks.All(IsValidTrack)
            && value.TrackGenres.All(IsValidText)
            && value.Artists.All(IsValidCollection)
            && value.Albums.All(IsValidCollection)
            && value.Genres.All(IsValidCollection);
    }

    private static bool IsValidTrack(LibraryTrack track) => track is not null
        && IsValidText(track.Id) && IsValidText(track.Title)
        && IsValidText(track.Artist) && IsValidText(track.Album)
        && IsValidText(track.AlbumArtist)
        && IsValidText(track.ArtworkId) && double.IsFinite(track.Duration)
        && track.Duration >= 0 && track.TrackNumber >= 0 && track.DiscNumber >= 0;

    private static bool IsValidCollection(LibraryCollection collection) => collection is not null
        && IsValidText(collection.Id) && IsValidText(collection.Title)
        && IsValidText(collection.Subtitle) && IsValidText(collection.ArtworkId)
        && collection.TrackCount >= 0;

    private static bool IsValidText(string? value) =>
        value is not null && value.Length <= 16_384;

    private static bool IsSha256(string? value) => value is { Length: 64 }
        && value.All(Uri.IsHexDigit);

    internal sealed class BoundedIndexStream(Stream output, long limit) : Stream
    {
        public override void Write(byte[] buffer, int offset, int count) =>
            Write(buffer.AsSpan(offset, count));
        public override void Write(ReadOnlySpan<byte> buffer)
        {
            if (buffer.Length > limit - output.Position)
                throw new IOException("The library index exceeds its storage limit");
            output.Write(buffer);
        }
        public override void Flush() => output.Flush();
        public override bool CanRead => false;
        public override bool CanSeek => false;
        public override bool CanWrite => true;
        public override long Length => throw new NotSupportedException();
        public override long Position { get => throw new NotSupportedException(); set => throw new NotSupportedException(); }
        public override int Read(byte[] buffer, int offset, int count) => throw new NotSupportedException();
        public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
        public override void SetLength(long value) => throw new NotSupportedException();
    }
}
