using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml;

namespace TunesLinkBridge;

internal static class Program
{
    [STAThread]
    private static void Main(string[] args)
    {
        if (TryRunCommandMode(args, out int result))
        {
            Environment.ExitCode = result;
            return;
        }

        WinRT.ComWrappersSupport.InitializeComWrappers();
        Application.Start(initialization =>
        {
            DispatcherQueueSynchronizationContext context = new(
                DispatcherQueue.GetForCurrentThread());
            SynchronizationContext.SetSynchronizationContext(context);
            _ = initialization;
            _ = new App();
        });
    }

    private static bool TryRunCommandMode(string[] args, out int result)
    {
        if (args.Contains("--itunes-worker", StringComparer.OrdinalIgnoreCase))
        {
            result = ItunesWorkerHost.RunAsync(new ItunesController()).GetAwaiter().GetResult();
            return true;
        }
        if (args.Contains("--itunes-worker-demo", StringComparer.OrdinalIgnoreCase))
        {
            result = ItunesWorkerHost.RunAsync(new DemoController()).GetAwaiter().GetResult();
            return true;
        }
        if (args.Contains("--self-test", StringComparer.OrdinalIgnoreCase))
        {
            VisualContract.VerifyContrast();
            result = BridgeSelfTest.RunAsync().GetAwaiter().GetResult();
            return true;
        }
        if (args.Contains("--itunes-smoke-test", StringComparer.OrdinalIgnoreCase))
        {
            using IsolatedItunesController controller = new();
            PlaybackState state = controller.GetStateAsync().WaitAsync(TimeSpan.FromSeconds(15))
                .GetAwaiter().GetResult();
            result = state.ITunesAvailable ? 0 : 1;
            return true;
        }
        if (args.Contains("--itunes-library-smoke-test", StringComparer.OrdinalIgnoreCase))
        {
            using IsolatedItunesController controller = new();
            LibraryPage page = controller.GetLibraryAsync("", 0, 10)
                .WaitAsync(TimeSpan.FromSeconds(20)).GetAwaiter().GetResult();
            result = page.Total > 0 ? 0 : 1;
            return true;
        }
        if (args.Contains("--live-itunes-test", StringComparer.OrdinalIgnoreCase))
        {
            result = LiveItunesTest.RunAsync().GetAwaiter().GetResult();
            return true;
        }
        result = 0;
        return false;
    }
}
