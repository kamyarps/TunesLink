using System.Text.Json;
using System.Runtime.InteropServices;

namespace TunesLinkBridge;

internal static class ItunesWorkerHost
{
    internal static async Task<int> RunAsync(IMediaController media)
    {
        using (media)
        {
            Console.OutputEncoding = System.Text.Encoding.UTF8;
            using StreamReader input = new(Console.OpenStandardInput(),
                System.Text.Encoding.UTF8, detectEncodingFromByteOrderMarks: false);
            BoundedLineReader requests = new(input, ItunesWorkerProtocol.MaxRequestCharacters);
            Task<string?>? pendingRead = null;
            while (true)
            {
                string? line;
                if (pendingRead is not null)
                {
                    line = await pendingRead.ConfigureAwait(false);
                    pendingRead = null;
                }
                else
                {
                    line = await requests.ReadLineAsync().ConfigureAwait(false);
                }
                if (line is null) return 0;
                int requestId = 0;
                ItunesWorkerRequest request;
                try
                {
                    request = JsonSerializer.Deserialize<ItunesWorkerRequest>(
                        line, ItunesWorkerProtocol.JsonOptions)
                        ?? throw new JsonException("Worker request is empty");
                    requestId = request.Id;
                }
                catch (JsonException exception)
                {
                    await WriteFailureAsync(requestId, exception,
                        ItunesWorkerFailureCategory.Validation).ConfigureAwait(false);
                    continue;
                }
                catch (Exception exception)
                {
                    await WriteFailureAsync(requestId, exception,
                        ItunesWorkerFailureCategory.Internal).ConfigureAwait(false);
                    return 1;
                }

                if (request.Operation == "cancel") continue;

                if (request.Operation == "selfTestMalformedResponse")
                {
                    await Console.Out.WriteLineAsync("{").ConfigureAwait(false);
                    return 1;
                }

                using CancellationTokenSource cancellation = new();
                Task<ItunesWorkerResponse> execution =
                    ExecuteAsync(media, request, cancellation.Token);
                while (!execution.IsCompleted)
                {
                    pendingRead ??= requests.ReadLineAsync();
                    Task completed = await Task.WhenAny(execution, pendingRead)
                        .ConfigureAwait(false);
                    if (completed != pendingRead) break;
                    string? next = await pendingRead.ConfigureAwait(false);
                    pendingRead = null;
                    if (next is null)
                    {
                        cancellation.Cancel();
                        try { await execution.ConfigureAwait(false); } catch { }
                        return 0;
                    }
                    ItunesWorkerRequest? control = TryParseRequest(next);
                    if (control is { Operation: "cancel" }
                        && control.CancelTargetId == request.Id)
                    {
                        cancellation.Cancel();
                    }
                }

                ItunesWorkerResponse response;
                try
                {
                    response = await execution.ConfigureAwait(false);
                }
                catch (Exception exception)
                {
                    ItunesWorkerFailureCategory category =
                        cancellation.IsCancellationRequested
                        && exception is OperationCanceledException
                            ? ItunesWorkerFailureCategory.Cancelled
                            : ClassifyFailure(exception);
                    await WriteFailureAsync(requestId, exception, category).ConfigureAwait(false);
                    if (!ItunesWorkerProtocol.CanReuseWorker(category)) return 1;
                    continue;
                }

                await WriteAsync(response).ConfigureAwait(false);
                if (request.Operation == "shutdown") return 0;
            }
        }
    }

    private static ItunesWorkerRequest? TryParseRequest(string line)
    {
        try
        {
            return JsonSerializer.Deserialize<ItunesWorkerRequest>(
                line, ItunesWorkerProtocol.JsonOptions);
        }
        catch (JsonException)
        {
            return null;
        }
    }

    private static async Task<ItunesWorkerResponse> ExecuteAsync(
        IMediaController media, ItunesWorkerRequest request, CancellationToken cancellationToken)
    {
        switch (request.Operation)
        {
            case "state":
                return new ItunesWorkerResponse(request.Id, true,
                    State: await media.GetStateAsync(cancellationToken).ConfigureAwait(false));
            case "library":
                return new ItunesWorkerResponse(request.Id, true,
                    Library: await media.GetLibraryAsync(request.Query, request.Offset,
                        request.Limit, cancellationToken).ConfigureAwait(false));
            case "collections":
                return new ItunesWorkerResponse(request.Id, true,
                    Collections: await media.GetCollectionsAsync(request.CollectionKind,
                        request.Query, request.Offset, request.Limit, cancellationToken)
                        .ConfigureAwait(false));
            case "collectionTracks":
                return new ItunesWorkerResponse(request.Id, true,
                    Library: await media.GetCollectionTracksAsync(request.CollectionKind,
                        request.CollectionId, request.Query, request.Offset, request.Limit,
                        cancellationToken).ConfigureAwait(false));
            case "play":
                await media.PlayTrackAsync(new PlaybackSelection(
                    request.TrackId, request.CollectionKind, request.CollectionId),
                    cancellationToken).ConfigureAwait(false);
                return new ItunesWorkerResponse(request.Id, true);
            case "command":
                await media.ExecuteAsync(request.Command
                    ?? throw new ArgumentException("Player command is required"),
                    cancellationToken).ConfigureAwait(false);
                return new ItunesWorkerResponse(request.Id, true);
            case "artwork":
                return new ItunesWorkerResponse(request.Id, true,
                    Artwork: await media.GetArtworkAsync(request.TrackId, request.MaxSize,
                        cancellationToken).ConfigureAwait(false));
            case "selfTestHang":
                await Task.Delay(Timeout.InfiniteTimeSpan, CancellationToken.None)
                    .ConfigureAwait(false);
                throw new InvalidOperationException("Unreachable");
            case "selfTestCancellableHang":
                await Task.Delay(Timeout.InfiniteTimeSpan, cancellationToken)
                    .ConfigureAwait(false);
                throw new InvalidOperationException("Unreachable");
            case "selfTestInternalFailure":
                throw new InvalidOperationException("Self-test worker failure");
            case "shutdown":
                return new ItunesWorkerResponse(request.Id, true);
            default:
                throw new ArgumentException("Unknown worker operation");
        }
    }

    private static Task WriteAsync(ItunesWorkerResponse response)
    {
        string json = JsonSerializer.Serialize(response, ItunesWorkerProtocol.JsonOptions);
        return Console.Out.WriteLineAsync(json);
    }

    private static Task WriteFailureAsync(int requestId, Exception exception,
        ItunesWorkerFailureCategory category) =>
        WriteAsync(new ItunesWorkerResponse(requestId, false, Error: exception.Message,
            ErrorType: exception.GetType().Name, FailureCategory: category));

    internal static ItunesWorkerFailureCategory ClassifyFailure(Exception exception)
    {
        Exception failure = exception is AggregateException aggregate
            ? aggregate.GetBaseException()
            : exception;
        return failure switch
        {
            MediaNotFoundException => ItunesWorkerFailureCategory.NotFound,
            MediaUnavailableException => ItunesWorkerFailureCategory.Unavailable,
            ArgumentException => ItunesWorkerFailureCategory.Validation,
            OperationCanceledException => ItunesWorkerFailureCategory.Timeout,
            TimeoutException => ItunesWorkerFailureCategory.Timeout,
            COMException comException => ClassifyComFailure(comException.HResult),
            _ => ItunesWorkerFailureCategory.Internal
        };
    }

    internal static ItunesWorkerFailureCategory ClassifyComFailure(int hresult) => hresult switch
    {
        unchecked((int)0x80010007) => ItunesWorkerFailureCategory.ItunesTerminated,
        unchecked((int)0x80010012) => ItunesWorkerFailureCategory.ItunesTerminated,
        unchecked((int)0x800706BA) => ItunesWorkerFailureCategory.ItunesTerminated,
        unchecked((int)0x80010108) => ItunesWorkerFailureCategory.ComDisconnected,
        unchecked((int)0x800401FD) => ItunesWorkerFailureCategory.ComDisconnected,
        _ => ItunesWorkerFailureCategory.ComDisconnected
    };
}
