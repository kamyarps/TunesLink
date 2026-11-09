namespace TunesLinkBridge;

internal static class WorkerTestExtensions
{
    public static Task ExerciseHangForSelfTestAsync(this IsolatedItunesController controller,
        CancellationToken token) => Fault(controller.InteractiveWorker, "selfTestHang", token);

    public static Task ExerciseCancellableHangForSelfTestAsync(this IsolatedItunesController controller,
        CancellationToken token) => Fault(controller.InteractiveWorker, "selfTestCancellableHang", token);

    public static Task ExerciseLibraryHangForSelfTestAsync(this IsolatedItunesController controller,
        CancellationToken token) => Fault(controller.LibraryWorker, "selfTestHang", token);

    public static Task ExerciseInternalFailureForSelfTestAsync(this IsolatedItunesController controller) =>
        Fault(controller.InteractiveWorker, "selfTestInternalFailure", CancellationToken.None);

    public static Task ExerciseMalformedResponseForSelfTestAsync(this IsolatedItunesController controller) =>
        Fault(controller.InteractiveWorker, "selfTestMalformedResponse", CancellationToken.None);

    private static Task<bool> Fault(ItunesWorkerClient worker, string operation, CancellationToken token) =>
        worker.CallAsync(new ItunesWorkerRequest(worker.NextId(), operation), _ => true,
            TimeSpan.FromSeconds(30), token);
}
