using System.Security.Cryptography;
using System.Text;

namespace TunesLinkBridge;

internal sealed record DemoTrack(
    string Title,
    string Artist,
    string Album,
    double Duration,
    string Genre = "Electronic",
    string AlbumArtist = "",
    bool Compilation = false)
{
    public string GroupingArtist =>
        LibraryGrouping.AlbumArtist(Artist, AlbumArtist, Compilation);

    public string AlbumKey => LibraryGrouping.AlbumKey(GroupingArtist, Album);
}

internal static class DemoLibrary
{
    public static DemoTrack[] CreateTracks(bool includeArchive)
    {
        List<DemoTrack> tracks =
        [
            new("Midnight Drive", "Neon Palms", "After Hours", 244, "Electronic"),
            new("Golden Static", "The Satellites", "Signals", 198, "Alternative")
        ];
        if (includeArchive)
        {
            for (int index = 3; index <= 83; index++)
            {
                tracks.Add(new DemoTrack($"Archive Track {index:D3}", "Load Test Ensemble",
                    $"Archive Volume {((index - 3) / 12) + 1}", 150 + index,
                    index % 2 == 0 ? "Ambient" : "Electronic"));
            }
        }
        return [.. tracks];
    }

    /// <summary>
    /// A library whose albums only group correctly when the album artist and compilation tags are
    /// honoured: one album shared by two performers, and one compilation of unrelated performers.
    /// </summary>
    public static DemoTrack[] CreateGroupingTracks() =>
    [
        new("Harbour Lights", "Neon Palms", "Night Ferry", 212, "Electronic",
            AlbumArtist: "Neon Palms", Compilation: false),
        new("Harbour Lights (Reprise)", "Neon Palms feat. Ida Vance", "Night Ferry", 176,
            "Electronic", AlbumArtist: "Neon Palms", Compilation: false),
        new("Coast Road", "Miriam Okonkwo", "Summer Sessions", 205, "Ambient",
            AlbumArtist: "", Compilation: true),
        new("Salt Flats", "The Satellites", "Summer Sessions", 231, "Ambient",
            AlbumArtist: "", Compilation: true),
    ];

    public static LibraryPage GetTracks(IEnumerable<DemoTrack> tracks, string query, int offset,
        int limit)
    {
        DemoTrack[] catalog = tracks.ToArray();
        IEnumerable<DemoTrack> matching = catalog;
        if (!string.IsNullOrWhiteSpace(query))
        {
            string term = query.Trim();
            matching = matching.Where(track =>
                track.Title.Contains(term, StringComparison.OrdinalIgnoreCase)
                || track.Artist.Contains(term, StringComparison.OrdinalIgnoreCase)
                || track.Album.Contains(term, StringComparison.OrdinalIgnoreCase));
        }
        return PageTracks(matching, offset, limit, Revision(catalog));
    }

    public static LibraryCollectionPage GetCollections(IEnumerable<DemoTrack> tracks, string kind,
        string query, int offset, int limit)
    {
        DemoTrack[] catalog = tracks.ToArray();
        IEnumerable<LibraryCollection> collections = kind switch
        {
            "artists" => catalog.GroupBy(track => track.GroupingArtist)
                .Select(group => Collection(kind, group.Key, group.Key, "", group)),
            "albums" => catalog.GroupBy(track => track.AlbumKey)
                .Select(group => Collection(kind, group.Key, group.First().Album,
                    group.First().GroupingArtist, group)),
            "genres" => catalog.GroupBy(track => track.Genre)
                .Select(group => Collection(kind, group.Key, group.Key, "", group)),
            "playlists" =>
            [
                Collection(kind, "All Songs", "All Songs", "", catalog),
                Collection(kind, "Night Drive", "Night Drive", "", catalog.Take(1)),
            ],
            _ => throw new ArgumentException("Unknown library collection"),
        };
        string term = query.Trim();
        if (term.Length > 0)
        {
            collections = collections.Where(item => item.Title.Contains(term,
                StringComparison.OrdinalIgnoreCase));
        }
        LibraryCollection[] all = collections.OrderBy(item => item.Title,
            StringComparer.OrdinalIgnoreCase).ToArray();
        int safeOffset = Math.Clamp(offset, 0, all.Length);
        int safeLimit = Math.Clamp(limit, 1, 60);
        LibraryCollection[] page = all.Skip(safeOffset).Take(safeLimit).ToArray();
        return new LibraryCollectionPage(page, safeOffset, safeLimit, all.Length,
            safeOffset + page.Length < all.Length, Revision(catalog));
    }

    public static bool TryGetCollectionTracks(IEnumerable<DemoTrack> tracks, string kind, string id,
        string query, int offset, int limit, out LibraryPage page)
    {
        DemoTrack[] catalog = tracks.ToArray();
        if (!ItunesCollectionId.TryDecodeText(id, kind, out string value))
        {
            page = new LibraryPage([], 0, Math.Clamp(limit, 1, 60), 0, false,
                Revision(catalog));
            return false;
        }
        IEnumerable<DemoTrack> matching = kind switch
        {
            "artists" => catalog.Where(track => track.GroupingArtist.Equals(value,
                StringComparison.OrdinalIgnoreCase)),
            "albums" => catalog.Where(track => track.AlbumKey.Equals(value,
                StringComparison.OrdinalIgnoreCase)),
            "genres" => catalog.Where(track => track.Genre.Equals(value,
                StringComparison.OrdinalIgnoreCase)),
            "playlists" when value == "Night Drive" => catalog.Take(1),
            "playlists" => catalog,
            _ => throw new ArgumentException("Unknown library collection"),
        };
        string term = query.Trim();
        if (term.Length > 0)
        {
            matching = matching.Where(track => track.Title.Contains(term,
                StringComparison.OrdinalIgnoreCase));
        }
        page = PageTracks(matching, offset, limit, Revision(catalog));
        return true;
    }

    public static string TrackId(DemoTrack track) =>
        Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(track.Title)))[..20]
            .ToLowerInvariant();

    public static string Revision(IEnumerable<DemoTrack> tracks)
    {
        string catalog = string.Join('\u001e', tracks.Select(track => string.Join('\u001f',
            track.Title, track.Artist, track.Album, track.Duration, track.Genre,
            track.GroupingArtist)));
        return Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(catalog)))
            .ToLowerInvariant();
    }

    private static LibraryPage PageTracks(IEnumerable<DemoTrack> source, int offset, int limit,
        string revision)
    {
        DemoTrack[] results = source.ToArray();
        int safeOffset = Math.Clamp(offset, 0, results.Length);
        int safeLimit = Math.Clamp(limit, 1, 60);
        LibraryTrack[] items = results.Skip(safeOffset).Take(safeLimit)
            .Select((track, itemIndex) => new LibraryTrack(
                TrackId(track), track.Title, track.Artist, track.Album, track.Duration,
                safeOffset + itemIndex + 1, 1, TrackId(track)))
            .ToArray();
        return new LibraryPage(items, safeOffset, safeLimit, results.Length,
            safeOffset + items.Length < results.Length, revision);
    }

    private static LibraryCollection Collection(string kind, string key, string title,
        string subtitle, IEnumerable<DemoTrack> source)
    {
        DemoTrack[] collectionTracks = source.ToArray();
        return new LibraryCollection(ItunesCollectionId.EncodeText(kind, key), title, subtitle,
            collectionTracks.Length,
            collectionTracks.FirstOrDefault() is { } track ? TrackId(track) : "");
    }
}
