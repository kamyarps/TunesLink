using System.Diagnostics;
using System.Text.Json;

namespace TunesLinkBridge;

internal sealed class IsolatedItunesController : IMediaController
{
    private readonly ItunesWorkerClient interactiveWorker;
    private readonly ItunesWorkerClient libraryWorker;

    internal IsolatedItunesController(string workerArgument = "--itunes-worker",
                                      bool diagnostics = true,
                                      TimeSpan? workerIdleTimeout = null)
    {
        interactiveWorker = new(workerArgument, diagnostics, workerIdleTimeout);
        libraryWorker = new(workerArgument, diagnostics, workerIdleTimeout);
    }

    public Task<PlaybackState> GetStateAsync(CancellationToken cancellationToken = default) =>
        interactiveWorker.GetStateAsync(cancellationToken);

    public Task<LibraryPage> GetLibraryAsync(string query, int offset, int limit,
        CancellationToken cancellationToken = default) =>
        libraryWorker.GetLibraryAsync(query, offset, limit, cancellationToken);

    public Task<LibraryCollectionPage> GetCollectionsAsync(string kind, string query, int offset,
        int limit, CancellationToken cancellationToken = default) =>
        libraryWorker.GetCollectionsAsync(kind, query, offset, limit, cancellationToken);

    public Task<LibraryPage> GetCollectionTracksAsync(string kind, string id, string query,
        int offset, int limit, CancellationToken cancellationToken = default) =>
        libraryWorker.GetCollectionTracksAsync(kind, id, query, offset, limit, cancellationToken);

    public Task PlayTrackAsync(string id, CancellationToken cancellationToken = default) =>
        interactiveWorker.PlayTrackAsync(id, cancellationToken);

    public Task ExecuteAsync(PlayerCommand command, CancellationToken cancellationToken = default) =>
        interactiveWorker.ExecuteAsync(command, cancellationToken);

    public Task<ArtworkData?> GetArtworkAsync(string id, int maxSize,
        CancellationToken cancellationToken = default) =>
        interactiveWorker.GetArtworkAsync(id, maxSize, cancellationToken);

    internal Task ExerciseHangForSelfTestAsync(CancellationToken cancellationToken) =>
        interactiveWorker.ExerciseHangForSelfTestAsync(cancellationToken);

    internal Task ExerciseLibraryHangForSelfTestAsync(CancellationToken cancellationToken) =>
        libraryWorker.ExerciseHangForSelfTestAsync(cancellationToken);

    internal Task ExerciseInternalFailureForSelfTestAsync() =>
        interactiveWorker.ExerciseInternalFailureForSelfTestAsync();

    internal Task ExerciseMalformedResponseForSelfTestAsync() =>
        interactiveWorker.ExerciseMalformedResponseForSelfTestAsync();

    internal bool WorkerRunningForTest => interactiveWorker.WorkerRunningForTest;
    internal int WorkerGenerationForTest => interactiveWorker.WorkerGenerationForTest;
    internal void TerminateWorkerForTest() => interactiveWorker.TerminateWorkerForTest();

    public void Dispose()
    {
        libraryWorker.Dispose();
        interactiveWorker.Dispose();
    }
}

internal sealed class ItunesWorkerClient : IDisposable
{
    private readonly SemaphoreSlim requestGate = new(1, 1);
    private readonly string workerArgument;
    private readonly bool diagnostics;
    private readonly TimeSpan workerIdleTimeout;
    private readonly System.Threading.Timer idleTimer;
    private Process? worker;
    private int nextRequestId;
    private int workerGeneration;
    private bool disposed;

    internal ItunesWorkerClient(string workerArgument, bool diagnostics,
                                TimeSpan? workerIdleTimeout)
    {
        this.workerArgument = workerArgument;
        this.diagnostics = diagnostics;
        // Keep the isolated worker alive long enough to reuse its bounded library snapshot.
        // The process still exits after inactivity and remains isolated from the WinUI host.
        this.workerIdleTimeout = workerIdleTimeout ?? ItunesController.LibrarySnapshotLifetime;
        idleTimer = new(_ => TerminateIdleWorker(), null,
            Timeout.InfiniteTimeSpan, Timeout.InfiniteTimeSpan);
    }

    public Task<PlaybackState> GetStateAsync(CancellationToken cancellationToken = default) =>
        CallAsync(new ItunesWorkerRequest(NextId(), "state"),
            response => response.State ?? throw MissingPayload("state"),
            BridgeProtocol.StateTimeout, cancellationToken);

    public Task<LibraryPage> GetLibraryAsync(string query, int offset, int limit,
        CancellationToken cancellationToken = default) =>
        CallAsync(new ItunesWorkerRequest(NextId(), "library", query, offset, limit),
            response => response.Library ?? throw MissingPayload("library"),
            BridgeProtocol.LibraryTimeout, cancellationToken);

    public Task<LibraryCollectionPage> GetCollectionsAsync(string kind, string query, int offset,
        int limit, CancellationToken cancellationToken = default) =>
        CallAsync(new ItunesWorkerRequest(NextId(), "collections", query, offset, limit,
                CollectionKind: kind),
            response => response.Collections ?? throw MissingPayload("collections"),
            BridgeProtocol.CollectionTimeout, cancellationToken);

    public Task<LibraryPage> GetCollectionTracksAsync(string kind, string id, string query,
        int offset, int limit, CancellationToken cancellationToken = default) =>
        CallAsync(new ItunesWorkerRequest(NextId(), "collectionTracks", query, offset, limit,
                CollectionKind: kind, CollectionId: id),
            response => response.Library ?? throw MissingPayload("collection tracks"),
            BridgeProtocol.CollectionTimeout, cancellationToken);

    public Task PlayTrackAsync(string id, CancellationToken cancellationToken = default) =>
        CallAsync(new ItunesWorkerRequest(NextId(), "play", TrackId: id),
            _ => true, BridgeProtocol.MediaTimeout, cancellationToken);

    public Task ExecuteAsync(PlayerCommand command, CancellationToken cancellationToken = default) =>
        CallAsync(new ItunesWorkerRequest(NextId(), "command", Command: command),
            _ => true, BridgeProtocol.StateTimeout, cancellationToken);

    public Task<ArtworkData?> GetArtworkAsync(string id, int maxSize,
        CancellationToken cancellationToken = default) =>
        CallAsync(new ItunesWorkerRequest(NextId(), "artwork", TrackId: id, MaxSize: maxSize),
            response => response.Artwork, BridgeProtocol.MediaTimeout, cancellationToken);

    internal Task ExerciseHangForSelfTestAsync(CancellationToken cancellationToken) =>
        CallAsync(new ItunesWorkerRequest(NextId(), "selfTestHang"),
            _ => true, TimeSpan.FromSeconds(30), cancellationToken);

    internal Task ExerciseInternalFailureForSelfTestAsync() =>
        CallAsync(new ItunesWorkerRequest(NextId(), "selfTestInternalFailure"),
            _ => true, TimeSpan.FromSeconds(5), CancellationToken.None);

    internal Task ExerciseMalformedResponseForSelfTestAsync() =>
        CallAsync(new ItunesWorkerRequest(NextId(), "selfTestMalformedResponse"),
            _ => true, TimeSpan.FromSeconds(5), CancellationToken.None);

    internal bool WorkerRunningForTest => worker is { HasExited: false };

    internal int WorkerGenerationForTest => workerGeneration;

    internal void TerminateWorkerForTest() => TerminateWorker();

    private int NextId() => Interlocked.Increment(ref nextRequestId);

    private async Task<T> CallAsync<T>(ItunesWorkerRequest request,
                                       Func<ItunesWorkerResponse, T> select,
                                       TimeSpan timeout,
                                       CancellationToken cancellationToken)
    {
        ObjectDisposedException.ThrowIf(disposed, this);
        idleTimer.Change(Timeout.InfiniteTimeSpan, Timeout.InfiniteTimeSpan);
        using CancellationTokenSource operation =
            CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        operation.CancelAfter(timeout);
        bool entered = false;
        bool terminateOnFailure = true;
        try
        {
            await requestGate.WaitAsync(operation.Token).ConfigureAwait(false);
            entered = true;
            Process process = EnsureWorker();
            string requestJson = JsonSerializer.Serialize(request, ItunesWorkerProtocol.JsonOptions);
            await process.StandardInput.WriteLineAsync(requestJson.AsMemory(), operation.Token)
                .ConfigureAwait(false);
            await process.StandardInput.FlushAsync(operation.Token).ConfigureAwait(false);
            string? line = await ItunesWorkerProtocol.ReadBoundedLineAsync(
                process.StandardOutput, ItunesWorkerProtocol.MaxResponseCharacters,
                operation.Token).ConfigureAwait(false);
            if (line is null) throw new IOException("The iTunes worker stopped unexpectedly");
            ItunesWorkerResponse response;
            try
            {
                response = JsonSerializer.Deserialize<ItunesWorkerResponse>(
                    line, ItunesWorkerProtocol.JsonOptions)
                    ?? throw MalformedResponse("The iTunes worker returned an empty response");
            }
            catch (JsonException exception)
            {
                throw MalformedResponse("The iTunes worker returned malformed data", exception);
            }
            if (response.Id != request.Id)
                throw MalformedResponse("The iTunes worker response was out of sequence");
            if (!response.Ok)
            {
                ItunesWorkerFailureCategory category = FailureCategory(response);
                terminateOnFailure = !ItunesWorkerProtocol.CanReuseWorker(category);
                throw WorkerException(response);
            }
            return select(response);
        }
        catch (Exception exception)
        {
            if (diagnostics && exception is not OperationCanceledException)
                BridgeDiagnostics.Record("itunes.worker", exception);
            if (entered && terminateOnFailure) TerminateWorker();
            throw;
        }
        finally
        {
            if (entered)
            {
                requestGate.Release();
                if (!disposed)
                    idleTimer.Change(workerIdleTimeout, Timeout.InfiniteTimeSpan);
            }
        }
    }

    private void TerminateIdleWorker()
    {
        if (disposed) return;
        bool entered;
        try { entered = requestGate.Wait(0); }
        catch (ObjectDisposedException) { return; }
        if (!entered) return;
        try
        {
            if (!disposed) TerminateWorker();
        }
        finally { requestGate.Release(); }
    }

    private Process EnsureWorker()
    {
        if (worker is { HasExited: false }) return worker;
        TerminateWorker();

        string executable = Environment.ProcessPath
            ?? throw new InvalidOperationException("Could not locate TunesLink Bridge");
        ProcessStartInfo start = new()
        {
            FileName = executable,
            UseShellExecute = false,
            RedirectStandardInput = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            CreateNoWindow = true,
            WindowStyle = ProcessWindowStyle.Hidden
        };
        if (string.Equals(Path.GetFileNameWithoutExtension(executable), "dotnet",
                StringComparison.OrdinalIgnoreCase))
        {
            string assemblyName = System.Reflection.Assembly.GetEntryAssembly()?.GetName().Name
                ?? throw new InvalidOperationException("Could not identify the bridge assembly");
            string assemblyPath = Path.Combine(AppContext.BaseDirectory, assemblyName + ".dll");
            if (!File.Exists(assemblyPath))
                throw new InvalidOperationException("Could not locate the bridge assembly");
            start.ArgumentList.Add(assemblyPath);
        }
        start.ArgumentList.Add(workerArgument);
        worker = Process.Start(start)
            ?? throw new InvalidOperationException("Could not start the iTunes worker");
        workerGeneration++;
        worker.ErrorDataReceived += (_, _) => { };
        worker.BeginErrorReadLine();
        return worker;
    }

    private static Exception WorkerException(ItunesWorkerResponse response)
    {
        string message = string.IsNullOrWhiteSpace(response.Error)
            ? "iTunes automation failed" : response.Error;
        ItunesWorkerFailureCategory category = FailureCategory(response);
        return category switch
        {
            ItunesWorkerFailureCategory.Validation => new ArgumentException(message),
            ItunesWorkerFailureCategory.NotFound => new MediaNotFoundException(message),
            _ => new ItunesWorkerException(category, message)
        };
    }

    private static ItunesWorkerFailureCategory FailureCategory(ItunesWorkerResponse response) =>
        response.FailureCategory
        ?? (response.ErrorType == nameof(ArgumentException)
            ? ItunesWorkerFailureCategory.Validation
            : ItunesWorkerFailureCategory.Internal);

    private static ItunesWorkerException MissingPayload(string operation) =>
        MalformedResponse($"The iTunes worker did not return {operation} data");

    private static ItunesWorkerException MalformedResponse(string message,
        Exception? innerException = null) =>
        new(ItunesWorkerFailureCategory.MalformedResponse, message, innerException);

    private void TerminateWorker()
    {
        Process? process = worker;
        worker = null;
        if (process is null) return;
        try
        {
            if (!process.HasExited) process.Kill(entireProcessTree: true);
        }
        catch { }
        try { process.WaitForExit(1500); } catch { }
        process.Dispose();
    }

    public void Dispose()
    {
        if (disposed) return;
        disposed = true;
        using ManualResetEvent callbacksFinished = new(false);
        idleTimer.Dispose(callbacksFinished);
        callbacksFinished.WaitOne(TimeSpan.FromSeconds(2));
        bool entered = false;
        try { entered = requestGate.Wait(TimeSpan.FromSeconds(2)); }
        catch (ObjectDisposedException) { }
        try { TerminateWorker(); }
        finally
        {
            if (entered)
            {
                requestGate.Release();
                requestGate.Dispose();
            }
        }
    }
}
