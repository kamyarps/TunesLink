using System.Buffers.Binary;
using System.IO.Compression;
using System.Text;

namespace TunesLinkBridge;

internal sealed class PortableDemoController : IMediaController
{
    private static readonly byte[] Artwork = CreateArtworkPng();

    private readonly object gate = new();
    private readonly DemoTrack[] tracks;
    private readonly int libraryDelayMilliseconds;
    private readonly string? libraryFaultFile;
    private int delayedContinuation;
    private int index;
    private bool playing = true;
    private int volume = 64;
    private double position = 72;
    private bool shuffleEnabled;
    private string repeatMode = "off";

    internal PortableDemoController(int libraryDelayMilliseconds = 0, bool largeLibrary = false,
        string? libraryFaultFile = null)
    {
        this.libraryDelayMilliseconds = Math.Max(0, libraryDelayMilliseconds);
        this.libraryFaultFile = libraryFaultFile;
        tracks = largeLibrary
            ? Enumerable.Range(1, 120).Select(number => new DemoTrack($"Long Track {number:D3}",
                "Album Artist", "A Long Album", 180, "Long Collection"))
                .Concat(Enumerable.Range(1, 600).Select(number => new DemoTrack($"Archive Track {number:D3}",
                    $"Artist {number:D3}", $"Album {number:D3}", 180, $"Genre {number:D3}"))).ToArray()
            : DemoLibrary.CreateTracks(includeArchive: true);
    }

    public Task<PlaybackState> GetStateAsync(CancellationToken cancellationToken = default)
    {
        cancellationToken.ThrowIfCancellationRequested();
        lock (gate)
        {
            DemoTrack track = tracks[index];
            return Task.FromResult(new PlaybackState(true, playing, track.Title, track.Artist,
                track.Album, track.Duration, position, volume, DemoLibrary.TrackId(track),
                DemoLibrary.TrackId(track),
                shuffleEnabled, repeatMode));
        }
    }

    public async Task<LibraryPage> GetLibraryAsync(string query, int offset, int limit,
        CancellationToken cancellationToken = default)
    {
        cancellationToken.ThrowIfCancellationRequested();
        ThrowIfLibraryFaultRequested();
        if (offset > 0 && libraryDelayMilliseconds > 0
            && Interlocked.Exchange(ref delayedContinuation, 1) == 0)
        {
            Console.WriteLine($"library-delay:{offset}:{libraryDelayMilliseconds}");
            Console.Out.Flush();
            await Task.Delay(libraryDelayMilliseconds, cancellationToken).ConfigureAwait(false);
        }
        lock (gate)
        {
            Console.WriteLine($"library:{query}");
            LibraryPage page = DemoLibrary.GetTracks(tracks, query, offset, limit);
            bool hasMore = page.HasMore;
            Console.WriteLine($"library-page:{query}:{page.Offset}:{page.Limit}:{page.Items.Count}:{hasMore.ToString().ToLowerInvariant()}");
            return page;
        }
    }

    public Task<LibraryCollectionPage> GetCollectionsAsync(string kind, string query, int offset,
        int limit, CancellationToken cancellationToken = default)
    {
        cancellationToken.ThrowIfCancellationRequested();
        ThrowIfLibraryFaultRequested();
        lock (gate)
        {
            LibraryCollectionPage page = DemoLibrary.GetCollections(tracks, kind, query, offset, limit);
            Console.WriteLine($"collections-page:{kind}:{page.Offset}:{page.Items.Count}:{page.Total}");
            return Task.FromResult(page);
        }
    }

    public Task<LibraryPage> GetCollectionTracksAsync(string kind, string id, string query,
        int offset, int limit, CancellationToken cancellationToken = default)
    {
        cancellationToken.ThrowIfCancellationRequested();
        ThrowIfLibraryFaultRequested();
        lock (gate)
        {
            if (!DemoLibrary.TryGetCollectionTracks(tracks, kind, id, query, offset, limit,
                    out LibraryPage page))
            {
                throw new ArgumentException("That collection is no longer available");
            }
            Console.WriteLine($"collection-tracks-page:{kind}:{page.Offset}:{page.Items.Count}:{page.Total}");
            return Task.FromResult(page);
        }
    }

    private void ThrowIfLibraryFaultRequested()
    {
        if (libraryFaultFile is not null && File.Exists(libraryFaultFile))
            throw new IOException("Injected library failure for UI verification");
    }

    public Task PlayTrackAsync(PlaybackSelection selection,
        CancellationToken cancellationToken = default)
    {
        cancellationToken.ThrowIfCancellationRequested();
        lock (gate)
        {
            int selected = Array.FindIndex(tracks,
                track => DemoLibrary.TrackId(track) == selection.TrackId);
            if (selected < 0) throw new ArgumentException("That song is no longer available");
            index = selected;
            position = 0;
            playing = true;
            Console.WriteLine($"play:{tracks[index].Title}");
        }
        return Task.CompletedTask;
    }

    public Task ExecuteAsync(PlayerCommand command, CancellationToken cancellationToken = default)
    {
        cancellationToken.ThrowIfCancellationRequested();
        lock (gate)
        {
            switch (command.Command)
            {
                case "playPause": playing = !playing; break;
                case "next": index = (index + 1) % tracks.Length; position = 0; break;
                case "previous": index = (index + tracks.Length - 1) % tracks.Length; position = 0; break;
                case "shuffle":
                    if (command.Value is null) throw new ArgumentException("Shuffle requires a value");
                    shuffleEnabled = command.Value.Value >= 0.5;
                    break;
                case "repeat":
                    if (command.Value is null) throw new ArgumentException("Repeat requires a value");
                    repeatMode = Math.Clamp((int)Math.Round(command.Value.Value), 0, 2) switch
                    {
                        1 => "one",
                        2 => "all",
                        _ => "off",
                    };
                    break;
                case "volume":
                    if (command.Value is null) throw new ArgumentException("Volume requires a value");
                    volume = Math.Clamp((int)Math.Round(command.Value.Value), 0, 100);
                    break;
                case "position":
                    if (command.Value is null) throw new ArgumentException("Position requires a value");
                    position = Math.Clamp(command.Value.Value, 0, tracks[index].Duration);
                    break;
                default: throw new ArgumentException("Unknown command");
            }
            Console.WriteLine($"command:{command.Command}");
        }
        return Task.CompletedTask;
    }

    public Task<ArtworkData?> GetArtworkAsync(string id, int maxSize,
        CancellationToken cancellationToken = default)
    {
        cancellationToken.ThrowIfCancellationRequested();
        lock (gate)
        {
            if (!tracks.Any(track => DemoLibrary.TrackId(track) == id))
                return Task.FromResult<ArtworkData?>(null);
            Console.WriteLine($"artwork:{id}:{maxSize}");
            return Task.FromResult<ArtworkData?>(new ArtworkData(id, Artwork, "image/png"));
        }
    }

    private static byte[] CreateArtworkPng()
    {
        const int size = 96;
        byte[] pixels = new byte[(size * 4 + 1) * size];
        for (int y = 0; y < size; y++)
        {
            int row = y * (size * 4 + 1);
            pixels[row] = 0;
            for (int x = 0; x < size; x++)
            {
                int pixel = row + 1 + x * 4;
                pixels[pixel] = (byte)(52 + x);
                pixels[pixel + 1] = (byte)(32 + y / 2);
                pixels[pixel + 2] = (byte)(138 + y);
                pixels[pixel + 3] = 255;
            }
        }

        using MemoryStream compressed = new();
        using (ZLibStream zlib = new(compressed, CompressionLevel.SmallestSize, leaveOpen: true))
            zlib.Write(pixels);

        using MemoryStream png = new();
        png.Write([137, 80, 78, 71, 13, 10, 26, 10]);
        byte[] header = new byte[13];
        BinaryPrimitives.WriteInt32BigEndian(header.AsSpan(0, 4), size);
        BinaryPrimitives.WriteInt32BigEndian(header.AsSpan(4, 4), size);
        header[8] = 8;
        header[9] = 6;
        WriteChunk(png, "IHDR", header);
        WriteChunk(png, "IDAT", compressed.ToArray());
        WriteChunk(png, "IEND", []);
        return png.ToArray();
    }

    private static void WriteChunk(Stream stream, string type, byte[] data)
    {
        byte[] typeBytes = Encoding.ASCII.GetBytes(type);
        Span<byte> length = stackalloc byte[4];
        BinaryPrimitives.WriteInt32BigEndian(length, data.Length);
        stream.Write(length);
        stream.Write(typeBytes);
        stream.Write(data);

        uint crc = 0xffffffff;
        foreach (byte value in typeBytes) crc = UpdateCrc(crc, value);
        foreach (byte value in data) crc = UpdateCrc(crc, value);
        Span<byte> checksum = stackalloc byte[4];
        BinaryPrimitives.WriteUInt32BigEndian(checksum, ~crc);
        stream.Write(checksum);
    }

    private static uint UpdateCrc(uint crc, byte value)
    {
        crc ^= value;
        for (int bit = 0; bit < 8; bit++)
            crc = (crc & 1) == 0 ? crc >> 1 : 0xedb88320 ^ (crc >> 1);
        return crc;
    }

    public void Dispose() { }
}
