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
    int MaxSize = 0,
    int CancelTargetId = 0);

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
    Internal = 7,
    Cancelled = 8,
    Unavailable = 9
}

internal sealed class MediaNotFoundException(string message) : ArgumentException(message);

internal sealed class MediaUnavailableException(string message)
    : InvalidOperationException(message);

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
            or ItunesWorkerFailureCategory.NotFound
            or ItunesWorkerFailureCategory.Cancelled
            or ItunesWorkerFailureCategory.Unavailable;

}

internal sealed class BoundedLineReader
{
    private readonly TextReader reader;
    private readonly int maxCharacters;
    private readonly char[] buffer = new char[4096];
    private readonly StringBuilder partial = new();
    private string residual = "";

    internal BoundedLineReader(TextReader reader, int maxCharacters)
    {
        this.reader = reader;
        this.maxCharacters = maxCharacters;
    }

    internal async Task<string?> ReadLineAsync(CancellationToken cancellationToken = default)
    {
        while (true)
        {
            if (residual.Length > 0)
            {
                int newline = residual.IndexOf('\n', StringComparison.Ordinal);
                if (newline >= 0)
                {
                    Append(residual.AsSpan(0, newline));
                    residual = residual[(newline + 1)..];
                    return Complete();
                }
                Append(residual.AsSpan());
                residual = "";
            }
            int read = await reader.ReadAsync(buffer.AsMemory(), cancellationToken)
                .ConfigureAwait(false);
            if (read == 0) return partial.Length == 0 ? null : Complete();
            residual = new string(buffer, 0, read);
        }
    }

    private void Append(ReadOnlySpan<char> chunk)
    {
        if (partial.Length + chunk.Length > maxCharacters)
            throw new IOException("The iTunes worker message exceeded its safety limit");
        partial.Append(chunk);
    }

    private string Complete()
    {
        if (partial.Length > 0 && partial[^1] == '\r') partial.Length--;
        string value = partial.ToString();
        partial.Clear();
        return value;
    }
}
