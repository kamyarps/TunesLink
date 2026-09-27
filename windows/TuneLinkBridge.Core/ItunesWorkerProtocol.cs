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
    Unavailable = 9,
    // iTunes rejected the call, usually because it is showing a dialog. The worker is healthy.
    ItunesBusy = 10,
    // A COM call failed for a reason that does not affect the connection, such as one track's
    // artwork failing to save.
    ComFailure = 11
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
            or ItunesWorkerFailureCategory.Unavailable
            or ItunesWorkerFailureCategory.ItunesBusy
            or ItunesWorkerFailureCategory.ComFailure;

    // Only a dead server or a severed connection makes the worker's COM state unusable. Every
    // other HRESULT is a failure of one call, so recycling the worker would only lose its caches.
    internal static ItunesWorkerFailureCategory ClassifyComFailure(int hresult) => hresult switch
    {
        unchecked((int)0x80010007) => ItunesWorkerFailureCategory.ItunesTerminated, // RPC_E_SERVER_DIED
        unchecked((int)0x80010012) => ItunesWorkerFailureCategory.ItunesTerminated, // RPC_E_SERVER_DIED_DNE
        unchecked((int)0x800706BA) => ItunesWorkerFailureCategory.ItunesTerminated, // RPC_S_SERVER_UNAVAILABLE
        unchecked((int)0x800706BE) => ItunesWorkerFailureCategory.ItunesTerminated, // RPC_S_CALL_FAILED
        unchecked((int)0x800706BF) => ItunesWorkerFailureCategory.ItunesTerminated, // RPC_S_CALL_FAILED_DNE
        unchecked((int)0x80010108) => ItunesWorkerFailureCategory.ComDisconnected, // RPC_E_DISCONNECTED
        unchecked((int)0x800401FD) => ItunesWorkerFailureCategory.ComDisconnected, // CO_E_OBJNOTCONNECTED
        unchecked((int)0x80010001) => ItunesWorkerFailureCategory.ItunesBusy, // RPC_E_CALL_REJECTED
        unchecked((int)0x8001010A) => ItunesWorkerFailureCategory.ItunesBusy, // RPC_E_SERVERCALL_RETRYLATER
        _ => ItunesWorkerFailureCategory.ComFailure
    };

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
