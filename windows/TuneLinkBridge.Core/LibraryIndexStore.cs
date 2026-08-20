using System.Text;
using System.Text.Json;

namespace TunesLinkBridge;

internal sealed record LibraryIndexData(
    LibraryTrack[] Tracks,
    string[] TrackGenres,
    string[] TrackAlbumArtists,
    LibraryCollection[] Artists,
    LibraryCollection[] Albums,
    LibraryCollection[] Genres,
    string Revision,
    string SourceSignature,
    DateTimeOffset CreatedAt);

internal sealed class LibraryIndexStore
{
    // Version 3 added the album-artist column and changed how albums and artists are grouped.
    // An older index is discarded rather than reused so a stale grouping never survives an update.
    internal const int SchemaVersion = 3;
    internal const int MaxFileBytes = 128 * 1024 * 1024;
    internal const int MaxItems = 1_000_000;

    private sealed record Envelope(
        int SchemaVersion,
        LibraryTrack[] Tracks,
        string[] TrackGenres,
        string[] TrackAlbumArtists,
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
                value.TrackAlbumArtists, value.Artists, value.Albums, value.Genres,
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
            value.TrackAlbumArtists, value.Artists, value.Albums, value.Genres, value.Revision,
            value.SourceSignature, value.CreatedAt);
        if (!IsValid(envelope))
            throw new ArgumentException("The library index contains invalid data", nameof(value));
        if (EstimatedMaximumBytes(envelope) > MaxFileBytes)
            throw new ArgumentException("The library index exceeds its storage limit", nameof(value));
        string json = JsonSerializer.Serialize(envelope);
        if (Encoding.UTF8.GetByteCount(json) > MaxFileBytes)
            throw new ArgumentException("The library index exceeds its storage limit", nameof(value));
        persistence.WriteText(path, json);
    }

    private static bool IsValid(Envelope? value)
    {
        if (value is null || value.SchemaVersion != SchemaVersion
            || value.Tracks is null || value.TrackGenres is null
            || value.TrackAlbumArtists is null || value.Artists is null
            || value.Albums is null || value.Genres is null
            || value.TrackGenres.Length != value.Tracks.Length
            || value.TrackAlbumArtists.Length != value.Tracks.Length
            || value.Tracks.Length > MaxItems || value.Artists.Length > MaxItems
            || value.Albums.Length > MaxItems || value.Genres.Length > MaxItems
            || !IsSha256(value.Revision) || !IsSha256(value.SourceSignature))
            return false;
        return value.Tracks.All(IsValidTrack)
            && value.TrackGenres.All(IsValidText)
            && value.TrackAlbumArtists.All(IsValidText)
            && value.Artists.All(IsValidCollection)
            && value.Albums.All(IsValidCollection)
            && value.Genres.All(IsValidCollection);
    }

    private static bool IsValidTrack(LibraryTrack track) => track is not null
        && IsValidText(track.Id) && IsValidText(track.Title)
        && IsValidText(track.Artist) && IsValidText(track.Album)
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

    private static long EstimatedMaximumBytes(Envelope value)
    {
        long characters = value.Tracks.Sum(track => (long)track.Id.Length + track.Title.Length
            + track.Artist.Length + track.Album.Length + track.ArtworkId.Length)
            + value.TrackGenres.Sum(genre => (long)genre.Length)
            + value.TrackAlbumArtists.Sum(artist => (long)artist.Length)
            + value.Artists.Concat(value.Albums).Concat(value.Genres)
                .Sum(collection => (long)collection.Id.Length + collection.Title.Length
                    + collection.Subtitle.Length + collection.ArtworkId.Length);
        long structural = (long)value.Tracks.Length * 208
            + (long)(value.Artists.Length + value.Albums.Length + value.Genres.Length) * 128
            + 1024;
        return characters * 6 + structural;
    }
}
