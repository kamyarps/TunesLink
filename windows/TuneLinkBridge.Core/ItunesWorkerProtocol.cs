using System.Text.Json;
using System.Text;

namespace TunesLinkBridge;

internal sealed record ItunesWorkerRequest(
    int Id,
    string Operation,
    string Query = "",
    int Offset = 0,
    int Limit = 0,
    string TrackId = "",
    string CollectionKind = "",
    string CollectionId = "",
    PlayerCommand? Command = null,
    int MaxSize = 0);

internal sealed record ItunesWorkerResponse(
    int Id,
    bool Ok,
    PlaybackState? State = null,
    LibraryPage? Library = null,
    LibraryCollectionPage? Collections = null,
    ArtworkData? Artwork = null,
    string? Error = null,
    string? ErrorType = null,
    ItunesWorkerFailureCategory? FailureCategory = null);

internal enum ItunesWorkerFailureCategory
{
    Unknown = 0,
    Validation = 1,
    NotFound = 2,
    ComDisconnected = 3,
    ItunesTerminated = 4,
    Timeout = 5,
    MalformedResponse = 6,
    Internal = 7
}

internal sealed class MediaNotFoundException(string message) : ArgumentException(message);

internal sealed class ItunesWorkerException(
    ItunesWorkerFailureCategory category, string message, Exception? innerException = null)
    : InvalidOperationException(message, innerException)
{
    internal ItunesWorkerFailureCategory Category { get; } = category;
}

internal static class ItunesWorkerProtocol
{
    internal const int MaxRequestCharacters = 64 * 1024;
    internal const int MaxResponseCharacters = 3 * 1024 * 1024;

    internal static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        PropertyNameCaseInsensitive = true
    };

    internal static bool CanReuseWorker(ItunesWorkerFailureCategory category) =>
        category is ItunesWorkerFailureCategory.Validation
            or ItunesWorkerFailureCategory.NotFound;

}

internal sealed class BoundedLineReader
{
    private readonly TextReader reader;
    private readonly int maxCharacters;
    private readonly char[] buffer = new char[4096];
    private string residual = "";

    internal BoundedLineReader(TextReader reader, int maxCharacters)
    {
        this.reader = reader;
        this.maxCharacters = maxCharacters;
    }

    internal async Task<string?> ReadLineAsync(CancellationToken cancellationToken = default)
    {
        StringBuilder value = new();
        while (true)
        {
            if (residual.Length > 0)
            {
                int newline = residual.IndexOf('\n', StringComparison.Ordinal);
                if (newline >= 0)
                {
                    Append(value, residual.AsSpan(0, newline));
                    residual = residual[(newline + 1)..];
                    return Complete(value);
                }
                Append(value, residual.AsSpan());
                residual = "";
            }
            int read = await reader.ReadAsync(buffer.AsMemory(), cancellationToken)
                .ConfigureAwait(false);
            if (read == 0) return value.Length == 0 ? null : Complete(value);
            residual = new string(buffer, 0, read);
        }
    }

    private void Append(StringBuilder value, ReadOnlySpan<char> chunk)
    {
        if (value.Length + chunk.Length > maxCharacters)
            throw new IOException("The iTunes worker message exceeded its safety limit");
        value.Append(chunk);
    }

    private static string Complete(StringBuilder value)
    {
        if (value.Length > 0 && value[^1] == '\r') value.Length--;
        return value.ToString();
    }
}
