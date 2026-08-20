namespace TunesLinkBridge;

/// <summary>
/// One definition of how tracks are grouped into artists, albums, and genres. The snapshot build,
/// the snapshot filter, the live COM filter, the playback queue, and the demo library all derive
/// their keys from here so the paths cannot drift apart.
/// </summary>
internal static class LibraryGrouping
{
    internal const string UnknownArtist = "Unknown Artist";
    internal const string UnknownAlbum = "Unknown Album";
    internal const string UnknownGenre = "Unknown Genre";
    internal const string CompilationArtist = "Various Artists";
    internal const char KeySeparator = '\u001f';

    internal static string DisplayArtist(string artist) =>
        string.IsNullOrWhiteSpace(artist) ? UnknownArtist : artist.Trim();

    internal static string DisplayAlbum(string album) =>
        string.IsNullOrWhiteSpace(album) ? UnknownAlbum : album.Trim();

    internal static string DisplayGenre(string genre) =>
        string.IsNullOrWhiteSpace(genre) ? UnknownGenre : genre.Trim();

    /// <summary>
    /// The artist an album is filed under. iTunes files a compilation under Various Artists and
    /// otherwise prefers the album artist, so an album recorded with guest performers stays one
    /// album instead of splitting into one album for every performer.
    /// </summary>
    internal static string AlbumArtist(string artist, string albumArtist, bool compilation) =>
        compilation ? CompilationArtist
        : string.IsNullOrWhiteSpace(albumArtist) ? DisplayArtist(artist)
        : albumArtist.Trim();

    /// <summary>
    /// Identifies one album. Albums stay distinct per album artist so unrelated records that share
    /// a title do not merge.
    /// </summary>
    internal static string AlbumKey(string albumArtist, string album) =>
        albumArtist + KeySeparator + DisplayAlbum(album);

    /// <summary>Recovers the album name from an album key for the iTunes album search.</summary>
    internal static string? AlbumNameFromKey(string key)
    {
        int separator = key.IndexOf(KeySeparator);
        return separator >= 0 && separator + 1 < key.Length ? key[(separator + 1)..] : null;
    }

    /// <summary>
    /// Orders the songs of an artist, album, or genre the way a listener expects to see and hear
    /// them: album by album, then in disc and track order, with the library's own order breaking
    /// ties for untagged songs. The browse list and the playback queue share this ordering so a
    /// song started from a list carries on in the order that list showed.
    /// </summary>
    internal static IEnumerable<T> InCollectionOrder<T>(IEnumerable<T> tracks,
        Func<T, string> album, Func<T, int> discNumber, Func<T, int> trackNumber,
        Func<T, int> libraryIndex) => tracks
        .OrderBy(album, StringComparer.OrdinalIgnoreCase)
        .ThenBy(track => discNumber(track) > 0 ? discNumber(track) : int.MaxValue)
        .ThenBy(track => trackNumber(track) > 0 ? trackNumber(track) : int.MaxValue)
        .ThenBy(libraryIndex);
}
