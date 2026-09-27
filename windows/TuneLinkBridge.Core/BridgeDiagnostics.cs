using System.Globalization;
using System.Text;

namespace TunesLinkBridge;

internal static class BridgeDiagnostics
{
    private const long MaxLogBytes = 256 * 1024;
    private static readonly object Gate = new();

    internal static void Record(string eventCode, Exception exception, string? directory = null)
    {
        // An HRESULT identifies a COM failure without recording its (possibly personal) message.
        string detail = exception is System.Runtime.InteropServices.COMException
            ? exception.GetType().Name + " 0x" + exception.HResult.ToString("X8", CultureInfo.InvariantCulture)
            : exception.GetType().Name;
        Append(eventCode, detail, directory);
    }

    internal static void RecordEvent(string eventCode, string detail, string? directory = null)
    {
        Append(eventCode, SafeCode(detail), directory);
    }

    internal static void RecordDuration(string eventCode, long elapsedMilliseconds,
        string? directory = null)
    {
        Append(eventCode, Math.Max(0, elapsedMilliseconds)
            .ToString(CultureInfo.InvariantCulture), directory);
    }

    private static void Append(string eventCode, string detail, string? directory)
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
                    $"{DateTimeOffset.UtcNow:O}\t{SafeCode(eventCode)}\t{detail}{Environment.NewLine}");
                using FileStream stream = new(path, FileMode.Append, FileAccess.Write,
                    FileShare.ReadWrite);
                stream.Write(Encoding.UTF8.GetBytes(line));
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
