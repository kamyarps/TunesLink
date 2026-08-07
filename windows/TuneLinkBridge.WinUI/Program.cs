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

        AlignResourceIndexWithProcessName();
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

    private static void AlignResourceIndexWithProcessName()
    {
        string? processPath = Environment.ProcessPath;
        string? assemblyName = System.Reflection.Assembly.GetEntryAssembly()?.GetName().Name;
        if (processPath is null || string.IsNullOrEmpty(assemblyName)) return;
        string processName = Path.GetFileNameWithoutExtension(processPath);
        if (processName.Length == 0
            || string.Equals(processName, assemblyName, StringComparison.OrdinalIgnoreCase))
        {
            return;
        }
        try
        {
            string source = Path.Combine(AppContext.BaseDirectory, assemblyName + ".pri");
            string target = Path.Combine(AppContext.BaseDirectory, processName + ".pri");
            if (File.Exists(source) && !File.Exists(target)) File.Copy(source, target);
        }
        catch (Exception exception)
        {
            BridgeDiagnostics.Record("startup.resource-index", exception);
        }
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
