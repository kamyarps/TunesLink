using System.Text;
using System.Security.Cryptography;

namespace TunesLinkBridge;

internal readonly record struct ItunesPlaylistLocator(int SourceId, int PlaylistId);

internal static class ItunesCollectionId
{
    private const int MaxDecodedCharacters = 512;

    public static string EncodeText(string kind, string value)
    {
        string payload = kind + "\n" + value;
        string encoded = "c_" + Base64UrlEncode(Encoding.UTF8.GetBytes(payload));
        return payload.Length <= MaxDecodedCharacters && encoded.Length <= 1024
            ? encoded : HashId(kind, value);
    }

    // Short legacy identifiers remain stable. Long metadata uses a bounded, case-insensitive
    // key which can be matched against a fresh library without an in-memory lookup table.
    private static string HashId(string kind, string value) => "h_" + kind + "_" +
        Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(value.ToUpperInvariant())));

    public static bool IsValidText(string id, string kind) =>
        TryDecodeText(id, kind, out _) || (id.StartsWith("h_" + kind + "_", StringComparison.Ordinal)
            && id.Length == kind.Length + 67 && id[(kind.Length + 3)..].All(char.IsAsciiHexDigit));

    public static bool MatchesText(string id, string kind, string value) =>
        id.StartsWith("h_", StringComparison.Ordinal)
            ? string.Equals(id, HashId(kind, value), StringComparison.Ordinal)
            : TryDecodeText(id, kind, out string decoded)
                && string.Equals(decoded, value, StringComparison.OrdinalIgnoreCase);

    public static bool TryDecodeText(string id, string expectedKind, out string value)
    {
        value = "";
        if (!id.StartsWith("c_", StringComparison.Ordinal) || id.Length > 1024) return false;
        try
        {
            string payload = Encoding.UTF8.GetString(Base64UrlDecode(id[2..]));
            if (payload.Length > MaxDecodedCharacters) return false;
            string prefix = expectedKind + "\n";
            if (!payload.StartsWith(prefix, StringComparison.Ordinal)) return false;
            value = payload[prefix.Length..];
            return value.Length > 0;
        }
        catch (FormatException)
        {
            return false;
        }
    }

    public static string EncodePlaylist(ItunesPlaylistLocator locator) =>
        "p_" + ItunesTrackId.Encode(new ItunesTrackLocator(
            locator.SourceId, locator.PlaylistId, 0, 0));

    public static bool TryDecodePlaylist(string id, out ItunesPlaylistLocator locator)
    {
        locator = default;
        if (!id.StartsWith("p_", StringComparison.Ordinal)
            || !ItunesTrackId.TryDecode(id[2..], out ItunesTrackLocator decoded)
            || decoded.TrackId != 0 || decoded.DatabaseId != 0)
            return false;
        locator = new ItunesPlaylistLocator(decoded.SourceId, decoded.PlaylistId);
        return true;
    }

    private static string Base64UrlEncode(byte[] bytes) =>
        Convert.ToBase64String(bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_');

    private static byte[] Base64UrlDecode(string value)
    {
        string padded = value.Replace('-', '+').Replace('_', '/');
        padded += new string('=', (4 - padded.Length % 4) % 4);
        return Convert.FromBase64String(padded);
    }
}
