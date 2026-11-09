namespace TunesLinkBridge;

internal static class Program
{
    [STAThread]
    private static int Main(string[] args)
    {
        if (args.Contains("--itunes-worker-demo", StringComparer.Ordinal))
            return ItunesWorkerHost.RunAsync(new DemoController(), FaultAsync).GetAwaiter().GetResult();
        if (args.Contains("--itunes-worker", StringComparer.Ordinal))
            return ItunesWorkerHost.RunAsync(new ItunesController()).GetAwaiter().GetResult();
        if (args.Contains("--live-itunes-test", StringComparer.Ordinal))
            return LiveItunesTest.RunAsync().GetAwaiter().GetResult();
        PresentationTests.Run();
        return BridgeSelfTest.RunAsync().GetAwaiter().GetResult();
    }

    private static async Task<ItunesWorkerResponse> FaultAsync(ItunesWorkerRequest request,
        CancellationToken cancellationToken)
    {
        switch (request.Operation)
        {
            case "selfTestHang":
                await Task.Delay(Timeout.InfiniteTimeSpan, CancellationToken.None).ConfigureAwait(false);
                break;
            case "selfTestCancellableHang":
                await Task.Delay(Timeout.InfiniteTimeSpan, cancellationToken).ConfigureAwait(false);
                break;
            case "selfTestMalformedResponse":
                await Console.Out.WriteLineAsync("{").ConfigureAwait(false);
                Environment.Exit(1);
                break;
            case "selfTestInternalFailure":
                throw new InvalidOperationException("Self-test worker failure");
        }
        throw new ArgumentException("Unknown worker operation");
    }
}
