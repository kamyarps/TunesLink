namespace TunesLinkBridge;

internal static partial class BridgeSelfTest
{
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
        Ensure(ItunesWorkerHost.ClassifyFailure(new TimeoutException("timeout"))
               == ItunesWorkerFailureCategory.Timeout, "worker timeout classification");
        Ensure(ItunesWorkerHost.ClassifyFailure(new InvalidOperationException("failure"))
               == ItunesWorkerFailureCategory.Internal, "worker internal classification");

        Ensure(ItunesWorkerProtocol.CanReuseWorker(ItunesWorkerFailureCategory.Validation)
               && ItunesWorkerProtocol.CanReuseWorker(ItunesWorkerFailureCategory.NotFound),
            "validation and not-found failures preserve worker");
        Ensure(!ItunesWorkerProtocol.CanReuseWorker(ItunesWorkerFailureCategory.ComDisconnected)
               && !ItunesWorkerProtocol.CanReuseWorker(ItunesWorkerFailureCategory.ItunesTerminated)
               && !ItunesWorkerProtocol.CanReuseWorker(ItunesWorkerFailureCategory.Timeout)
               && !ItunesWorkerProtocol.CanReuseWorker(ItunesWorkerFailureCategory.MalformedResponse)
               && !ItunesWorkerProtocol.CanReuseWorker(ItunesWorkerFailureCategory.Internal),
            "operational worker failures recycle worker");

        using IsolatedItunesController isolated = new("--itunes-worker-demo", diagnostics: false);
        Ensure(!isolated.WorkerRunningForTest && isolated.WorkerGenerationForTest == 0,
            "isolated controller does not start a worker before first request");
        _ = await isolated.GetStateAsync();
        int originalGeneration = isolated.WorkerGenerationForTest;

        try
        {
            await isolated.ExecuteAsync(new PlayerCommand("invalid", null));
            throw new InvalidOperationException("Self-test failed: expected validation failure");
        }
        catch (ArgumentException) { }
        Ensure(isolated.WorkerRunningForTest
               && isolated.WorkerGenerationForTest == originalGeneration,
            "validation failure keeps worker process");

        try
        {
            await isolated.PlayTrackAsync(new PlaybackSelection("missing-track"));
            throw new InvalidOperationException("Self-test failed: expected not-found failure");
        }
        catch (MediaNotFoundException) { }
        Ensure(isolated.WorkerRunningForTest
               && isolated.WorkerGenerationForTest == originalGeneration,
            "not-found failure keeps worker process");

        try
        {
            await isolated.ExerciseMalformedResponseForSelfTestAsync();
            throw new InvalidOperationException("Self-test failed: expected malformed response");
        }
        catch (ItunesWorkerException exception)
            when (exception.Category == ItunesWorkerFailureCategory.MalformedResponse)
        { }
        Ensure(!isolated.WorkerRunningForTest, "malformed response terminates worker process");

        _ = await isolated.GetStateAsync();
        Ensure(isolated.WorkerGenerationForTest == originalGeneration + 1,
            "request after malformed response starts a clean worker");

        try
        {
            await isolated.ExerciseInternalFailureForSelfTestAsync();
            throw new InvalidOperationException("Self-test failed: expected internal failure");
        }
        catch (ItunesWorkerException exception)
            when (exception.Category == ItunesWorkerFailureCategory.Internal)
        { }
        Ensure(!isolated.WorkerRunningForTest, "internal failure terminates worker process");

        _ = await isolated.GetStateAsync();
        Ensure(isolated.WorkerGenerationForTest == originalGeneration + 2,
            "request after internal failure starts a clean worker");
    }
}
