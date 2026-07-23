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

    internal static async Task<string?> ReadBoundedLineAsync(TextReader reader, int maxCharacters,
        CancellationToken cancellationToken = default)
    {
        char[] buffer = new char[4096];
        StringBuilder value = new(Math.Min(maxCharacters, buffer.Length));
        while (true)
        {
            int read = await reader.ReadAsync(buffer.AsMemory(), cancellationToken)
                .ConfigureAwait(false);
            if (read == 0) return value.Length == 0 ? null : value.ToString();
            int newline = Array.IndexOf(buffer, '\n', 0, read);
            int count = newline >= 0 ? newline : read;
            if (count > 0 && buffer[count - 1] == '\r') count--;
            if (value.Length + count > maxCharacters)
                throw new IOException("The iTunes worker message exceeded its safety limit");
            value.Append(buffer, 0, count);
            if (newline >= 0) return value.ToString();
        }
    }
}
