using System.Text;

namespace TunesLinkBridge;

internal readonly record struct ItunesPlaylistLocator(int SourceId, int PlaylistId);

internal static class ItunesCollectionId
{
    private const int MaxDecodedCharacters = 512;

    public static string EncodeText(string kind, string value)
    {
        string payload = kind + "\n" + value;
        return "c_" + Base64UrlEncode(Encoding.UTF8.GetBytes(payload));
    }

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
