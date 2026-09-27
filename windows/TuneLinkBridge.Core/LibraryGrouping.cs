using System.Globalization;

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

    // What a listener types or scans for: "beyonce" finds "Beyoncé", and full-width or kana
    // variants match their ordinary forms.
    private const CompareOptions ListenerOptions = CompareOptions.IgnoreCase
        | CompareOptions.IgnoreNonSpace | CompareOptions.IgnoreWidth | CompareOptions.IgnoreKanaType;
    private static readonly CompareInfo Collation = CultureInfo.InvariantCulture.CompareInfo;

    /// <summary>
    /// Orders artist, album, and genre titles as a listener expects: linguistically, accents
    /// alongside their base letters, and a leading "The " ignored. Titles equal under that rule
    /// fall back to a case-insensitive ordinal comparison, so the order is deterministic and
    /// never interleaves two titles that group separately.
    /// </summary>
    internal static readonly IComparer<string> TitleOrder = Comparer<string>.Create((left, right) =>
    {
        int result = Collation.Compare(WithoutLeadingArticle(left), WithoutLeadingArticle(right),
            ListenerOptions);
        return result != 0 ? result : StringComparer.OrdinalIgnoreCase.Compare(left, right);
    });

    /// <summary>Orders names the user chose, such as playlists, linguistically as written.</summary>
    internal static readonly IComparer<string> NameOrder = Comparer<string>.Create((left, right) =>
    {
        int result = Collation.Compare(left, right, ListenerOptions);
        return result != 0 ? result : StringComparer.OrdinalIgnoreCase.Compare(left, right);
    });

    internal static bool Matches(string text, string term) =>
        term.Length == 0 || Collation.IndexOf(text, term, ListenerOptions) >= 0;

    /// <summary>The fields a song search looks in, shared by every search path.</summary>
    internal static bool MatchesTrack(string title, string artist, string album,
        string albumArtist, string term) =>
        Matches(title, term) || Matches(artist, term) || Matches(album, term)
        || Matches(albumArtist, term);

    private static string WithoutLeadingArticle(string? value)
    {
        value ??= "";
        return value.Length > 4 && value.StartsWith("The ", StringComparison.OrdinalIgnoreCase)
            ? value[4..].TrimStart() : value;
    }

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
    /// ties for untagged songs. Two albums that share a title but not an album artist stay
    /// separate. The browse list and the playback queue share this ordering so a song started
    /// from a list carries on in the order that list showed.
    /// </summary>
    internal static IEnumerable<T> InCollectionOrder<T>(IEnumerable<T> tracks,
        Func<T, string> album, Func<T, string> albumArtist, Func<T, int> discNumber,
        Func<T, int> trackNumber, Func<T, int> libraryIndex) => tracks
        .OrderBy(album, TitleOrder)
        .ThenBy(albumArtist, TitleOrder)
        .ThenBy(track => discNumber(track) > 0 ? discNumber(track) : int.MaxValue)
        .ThenBy(track => trackNumber(track) > 0 ? trackNumber(track) : int.MaxValue)
        .ThenBy(libraryIndex);
}
