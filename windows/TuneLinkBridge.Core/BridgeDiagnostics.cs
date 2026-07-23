using System.Globalization;

namespace TunesLinkBridge;

internal static class BridgeDiagnostics
{
    private const long MaxLogBytes = 256 * 1024;
    private static readonly object Gate = new();

    internal static void Record(string eventCode, Exception exception, string? directory = null)
    {
        try
        {
            string logDirectory = directory ?? BrandPaths.UserConfigDirectory();
            Directory.CreateDirectory(logDirectory);
            string path = Path.Combine(logDirectory, "diagnostics.log");
            lock (Gate)
            {
                if (File.Exists(path) && new FileInfo(path).Length >= MaxLogBytes)
                    File.Move(path, path + ".previous", true);
                string line = string.Create(CultureInfo.InvariantCulture,
                    $"{DateTimeOffset.UtcNow:O}\t{SafeCode(eventCode)}\t{exception.GetType().Name}{Environment.NewLine}");
                File.AppendAllText(path, line);
            }
        }
        catch
        {
            // Diagnostics must never interfere with bridge operation.
        }
    }

    private static string SafeCode(string value) => new(value
        .Where(character => char.IsAsciiLetterOrDigit(character)
                            || character is '.' or '_' or '-')
        .Take(64).ToArray());
}
