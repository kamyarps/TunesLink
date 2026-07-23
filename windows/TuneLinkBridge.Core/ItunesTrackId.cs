using System.Buffers.Binary;

namespace TunesLinkBridge;

internal readonly record struct ItunesTrackLocator(
    int SourceId,
    int PlaylistId,
    int TrackId,
    int DatabaseId);

internal static class ItunesTrackId
{
    public static string Encode(ItunesTrackLocator locator)
    {
        Span<byte> bytes = stackalloc byte[16];
        BinaryPrimitives.WriteInt32BigEndian(bytes[0..4], locator.SourceId);
        BinaryPrimitives.WriteInt32BigEndian(bytes[4..8], locator.PlaylistId);
        BinaryPrimitives.WriteInt32BigEndian(bytes[8..12], locator.TrackId);
        BinaryPrimitives.WriteInt32BigEndian(bytes[12..16], locator.DatabaseId);
        return Convert.ToBase64String(bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_');
    }

    public static bool TryDecode(string id, out ItunesTrackLocator locator)
    {
        locator = default;
        if (string.IsNullOrWhiteSpace(id) || id.Length is < 20 or > 24) return false;
        try
        {
            string padded = id.Replace('-', '+').Replace('_', '/');
            padded += new string('=', (4 - padded.Length % 4) % 4);
            byte[] bytes = Convert.FromBase64String(padded);
            if (bytes.Length != 16) return false;
            locator = new ItunesTrackLocator(
                BinaryPrimitives.ReadInt32BigEndian(bytes.AsSpan(0, 4)),
                BinaryPrimitives.ReadInt32BigEndian(bytes.AsSpan(4, 4)),
                BinaryPrimitives.ReadInt32BigEndian(bytes.AsSpan(8, 4)),
                BinaryPrimitives.ReadInt32BigEndian(bytes.AsSpan(12, 4)));
            return true;
        }
        catch (FormatException)
        {
            return false;
        }
    }
}
