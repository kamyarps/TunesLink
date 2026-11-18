namespace TunesLinkBridge;

internal static partial class BridgeSelfTest
{
    internal static int RunLargeLibraryWorker(string directory)
    {
        LibraryAppProbe app = new(99_997);
        app.LibraryPlaylist.Tracks.BeforeEnumeration = () =>
        {
            if (!SpinWait.SpinUntil(() => File.Exists(Path.Combine(directory, "continue")), 30_000))
                throw new TimeoutException("The test did not release the first index scan");
        };
        return ItunesWorkerHost.RunAsync(FakeComController(directory, app)).GetAwaiter().GetResult();
    }

    private static async Task TestLargeLibraryWorkerAsync(string directory)
    {
        Directory.CreateDirectory(directory);
        using ItunesWorkerClient worker = new("--large-library-test=" + directory, false,
            TimeSpan.FromMilliseconds(150));
        using CancellationTokenSource timeout = new(TimeSpan.FromMinutes(2));
        try
        {
            await worker.GetCollectionsAsync("genres", "", 0, 60, timeout.Token);
            throw new InvalidOperationException("A cold library must report indexing");
        }
        catch (ItunesWorkerException exception)
            when (exception.Category == ItunesWorkerFailureCategory.LibraryIndexing)
        { }

        File.WriteAllText(Path.Combine(directory, "continue"), "");
        LibraryPage first = await worker.GetLibraryAsync("", 0, 60, timeout.Token);
        Ensure(first.Items.Count == 60 && first.Total == 100_001, "Songs remains available during indexing");
        // Leave the bridge alone for longer than its idle timeout. Only the bridge owns this job.
        string index = Path.Combine(directory, "Cache", "library-index-v1.json");
        while (!File.Exists(index) || worker.WorkerRunning)
            await Task.Delay(50, timeout.Token);
        Ensure(worker.WorkerGeneration == 1, "one worker finishes the unattended scan");
        LibraryCollectionPage genres = await worker.GetCollectionsAsync("genres", "", 0, 60, timeout.Token);
        LibraryPage tail = await worker.GetLibraryAsync("", 100_000, 60, timeout.Token);
        Ensure(genres.Items.Sum(g => g.TrackCount) == 100_001 && tail.Items.Count == 1
            && !tail.HasMore && genres.Revision == tail.Revision && worker.WorkerGeneration == 2,
            "a restarted worker immediately serves the persisted large index and its final page");
    }

    private static async Task TestWorkerFailureCategoriesAsync()
    {
        Ensure(ItunesWorkerHost.ClassifyFailure(new ArgumentException("invalid"))
               == ItunesWorkerFailureCategory.Validation, "worker validation classification");
        Ensure(ItunesWorkerHost.ClassifyFailure(new MediaNotFoundException("missing"))
               == ItunesWorkerFailureCategory.NotFound, "worker not-found classification");
        Ensure(ItunesWorkerHost.ClassifyComFailure(unchecked((int)0x80010108))
               == ItunesWorkerFailureCategory.ComDisconnected,
            "worker COM disconnection classification");
        Ensure(ItunesWorkerHost.ClassifyComFailure(unchecked((int)0x80010007))
               == ItunesWorkerFailureCategory.ItunesTerminated,
            "worker iTunes termination classification");
        Ensure(ItunesWorkerHost.ClassifyComFailure(unchecked((int)0x80010001))
               == ItunesWorkerFailureCategory.ItunesBusy
               && ItunesWorkerHost.ClassifyComFailure(unchecked((int)0x8001010A))
               == ItunesWorkerFailureCategory.ItunesBusy,
            "a rejected COM call means iTunes is busy, not gone");
        Ensure(ItunesWorkerHost.ClassifyComFailure(unchecked((int)0x80004005))
               == ItunesWorkerFailureCategory.ComFailure
               && ItunesWorkerHost.ClassifyFailure(System.Runtime.InteropServices.Marshal
                   .GetExceptionForHR(unchecked((int)0x80004005))!) == ItunesWorkerFailureCategory.ComFailure,
            "an unknown COM failure is a failure of one call");
        Ensure(ItunesWorkerHost.ClassifyFailure(new TimeoutException("timeout"))
               == ItunesWorkerFailureCategory.Timeout, "worker timeout classification");
        Ensure(ItunesWorkerHost.ClassifyFailure(new MediaUnavailableException("closed"))
               == ItunesWorkerFailureCategory.Unavailable, "worker unavailable classification");
        Ensure(ItunesWorkerHost.ClassifyFailure(new InvalidOperationException("failure"))
               == ItunesWorkerFailureCategory.Internal, "worker internal classification");

        Ensure(ItunesWorkerProtocol.CanReuseWorker(ItunesWorkerFailureCategory.Validation)
               && ItunesWorkerProtocol.CanReuseWorker(ItunesWorkerFailureCategory.NotFound)
               && ItunesWorkerProtocol.CanReuseWorker(ItunesWorkerFailureCategory.Cancelled)
               && ItunesWorkerProtocol.CanReuseWorker(ItunesWorkerFailureCategory.Unavailable)
               && ItunesWorkerProtocol.CanReuseWorker(ItunesWorkerFailureCategory.ItunesBusy)
               && ItunesWorkerProtocol.CanReuseWorker(ItunesWorkerFailureCategory.ComFailure),
            "recoverable failures preserve worker");
        Ensure(!ItunesWorkerProtocol.CanReuseWorker(ItunesWorkerFailureCategory.ComDisconnected)
               && !ItunesWorkerProtocol.CanReuseWorker(ItunesWorkerFailureCategory.ItunesTerminated)
               && !ItunesWorkerProtocol.CanReuseWorker(ItunesWorkerFailureCategory.Timeout)
               && !ItunesWorkerProtocol.CanReuseWorker(ItunesWorkerFailureCategory.MalformedResponse)
               && !ItunesWorkerProtocol.CanReuseWorker(ItunesWorkerFailureCategory.Internal),
            "operational worker failures recycle worker");

        using IsolatedItunesController isolated = new("--itunes-worker-demo", diagnostics: false);
        Ensure(!isolated.InteractiveWorker.WorkerRunning && isolated.InteractiveWorker.WorkerGeneration == 0,
            "isolated controller does not start a worker before first request");
        _ = await isolated.GetStateAsync();
        int originalGeneration = isolated.InteractiveWorker.WorkerGeneration;

        try
        {
            await isolated.ExecuteAsync(new PlayerCommand("invalid", null));
            throw new InvalidOperationException("Self-test failed: expected validation failure");
        }
        catch (ArgumentException) { }
        Ensure(isolated.InteractiveWorker.WorkerRunning
               && isolated.InteractiveWorker.WorkerGeneration == originalGeneration,
            "validation failure keeps worker process");

        try
        {
            await isolated.PlayTrackAsync(new PlaybackSelection("missing-track"));
            throw new InvalidOperationException("Self-test failed: expected not-found failure");
        }
        catch (MediaNotFoundException) { }
        Ensure(isolated.InteractiveWorker.WorkerRunning
               && isolated.InteractiveWorker.WorkerGeneration == originalGeneration,
            "not-found failure keeps worker process");

        using (CancellationTokenSource cancelled = new(TimeSpan.FromMilliseconds(250)))
        {
            try
            {
                await isolated.ExerciseCancellableHangForSelfTestAsync(cancelled.Token);
                throw new InvalidOperationException(
                    "Self-test failed: expected cancelled operation");
            }
            catch (OperationCanceledException) when (cancelled.IsCancellationRequested) { }
        }
        Ensure(isolated.InteractiveWorker.WorkerRunning
               && isolated.InteractiveWorker.WorkerGeneration == originalGeneration,
            "cancelled operation keeps worker process");
        _ = await isolated.GetStateAsync();
        Ensure(isolated.InteractiveWorker.WorkerGeneration == originalGeneration,
            "worker answers normally after a cancelled operation");

        try
        {
            await isolated.ExerciseMalformedResponseForSelfTestAsync();
            throw new InvalidOperationException("Self-test failed: expected malformed response");
        }
        catch (ItunesWorkerException exception)
            when (exception.Category == ItunesWorkerFailureCategory.MalformedResponse)
        { }
        Ensure(!isolated.InteractiveWorker.WorkerRunning, "malformed response terminates worker process");

        _ = await isolated.GetStateAsync();
        Ensure(isolated.InteractiveWorker.WorkerGeneration == originalGeneration + 1,
            "request after malformed response starts a clean worker");

        try
        {
            await isolated.ExerciseInternalFailureForSelfTestAsync();
            throw new InvalidOperationException("Self-test failed: expected internal failure");
        }
        catch (ItunesWorkerException exception)
            when (exception.Category == ItunesWorkerFailureCategory.Internal)
        { }
        Ensure(!isolated.InteractiveWorker.WorkerRunning, "internal failure terminates worker process");

        _ = await isolated.GetStateAsync();
        Ensure(isolated.InteractiveWorker.WorkerGeneration == originalGeneration + 2,
            "request after internal failure starts a clean worker");
    }
}
