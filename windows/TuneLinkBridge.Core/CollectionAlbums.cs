namespace TunesLinkBridge;

internal static class CollectionAlbums
{
    internal static void Validate(string kind, string id)
    {
        if (kind is not ("artists" or "genres")
            || !ItunesCollectionId.TryDecodeText(id, kind, out _))
            throw new ArgumentException("Invalid album collection");
    }

    internal static LibraryCollectionPage Page(IEnumerable<LibraryTrack> tracks, string query,
        int offset, int limit, string revision)
    {
        LibraryCollection[] albums = tracks
            .GroupBy(track => LibraryGrouping.AlbumKey(track.AlbumArtist, track.Album),
                StringComparer.OrdinalIgnoreCase)
            .Select(group => new LibraryCollection(ItunesCollectionId.EncodeText("albums", group.Key),
                group.First().Album, group.First().AlbumArtist, group.Count(),
                group.FirstOrDefault(track => track.ArtworkId.Length > 0)?.ArtworkId ?? ""))
            .Where(album => album.Title.Contains(query.Trim(), StringComparison.OrdinalIgnoreCase)
                || album.Subtitle.Contains(query.Trim(), StringComparison.OrdinalIgnoreCase))
            .OrderBy(album => album.Title, StringComparer.OrdinalIgnoreCase)
            .ThenBy(album => album.Subtitle, StringComparer.OrdinalIgnoreCase).ToArray();
        int start = Math.Clamp(offset, 0, albums.Length);
        int size = Math.Clamp(limit, 1, 60);
        LibraryCollection[] page = albums.Skip(start).Take(size).ToArray();
        return new(page, start, size, albums.Length, start + page.Length < albums.Length, revision);
    }

    // The demo/test controllers use this fallback; iTunes groups its in-memory snapshot directly.
    internal static async Task<LibraryCollectionPage> ReadAsync(IMediaController media,
        string kind, string id, string query, int offset, int limit, CancellationToken cancellationToken)
    {
        Validate(kind, id);
        List<LibraryTrack> tracks = [];
        LibraryPage page;
        int next = 0;
        do
        {
            cancellationToken.ThrowIfCancellationRequested();
            page = await media.GetCollectionTracksAsync(kind, id, "", next, 60, cancellationToken)
                .ConfigureAwait(false);
            tracks.AddRange(page.Items);
            int end = page.Offset + page.Items.Count;
            if (page.HasMore && end <= next) throw new InvalidOperationException("Library page did not advance");
            next = end;
        } while (page.HasMore);
        HashSet<string> keys = tracks.Select(track => LibraryGrouping.AlbumKey(track.AlbumArtist, track.Album))
            .ToHashSet(StringComparer.OrdinalIgnoreCase);
        tracks.Clear();
        next = 0;
        // A genre can match only some songs on an album. Opening that album shows the whole
        // album, so its summary/count must describe the same complete collection.
        do
        {
            cancellationToken.ThrowIfCancellationRequested();
            page = await media.GetLibraryAsync("", next, 60, cancellationToken).ConfigureAwait(false);
            tracks.AddRange(page.Items.Where(track => keys.Contains(
                LibraryGrouping.AlbumKey(track.AlbumArtist, track.Album))));
            int end = page.Offset + page.Items.Count;
            if (page.HasMore && end <= next) throw new InvalidOperationException("Library page did not advance");
            next = end;
        } while (page.HasMore);
        return Page(tracks, query, offset, limit, page.Revision);
    }
}
