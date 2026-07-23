using System.Net.Sockets;
using System.Security.Cryptography;
using Microsoft.UI.Xaml;

namespace TunesLinkBridge;

public partial class App : Microsoft.UI.Xaml.Application, IDisposable
{
    private MainWindow? window;
    private BridgeRuntime? runtime;
    private SingleInstanceCoordinator? singleton;

    public App()
    {
        InitializeComponent();
        UnhandledException += (_, eventArgs) =>
        {
            BridgeDiagnostics.Record("winui.unhandled", eventArgs.Exception);
            if (!Environment.GetCommandLineArgs().Contains("--verify-layout",
                    StringComparer.OrdinalIgnoreCase)) return;
            try
            {
                File.WriteAllText(Path.Combine(Path.GetTempPath(),
                    $"TunesLink-layout-error-{Environment.ProcessId}.txt"),
                    eventArgs.Exception.ToString());
            }
            catch { }
            Environment.ExitCode = 1;
            eventArgs.Handled = true;
            window?.Close();
            Exit();
        };
    }

    protected override void OnLaunched(LaunchActivatedEventArgs args)
    {
        string[] commandLine = Environment.GetCommandLineArgs().Skip(1).ToArray();
        BridgeLaunchOptions options = BridgeLaunchOptions.Parse(commandLine);
        bool isolatedPreview = options.VerifyLayout || options.SnapshotPath is not null
            || options.UiState is not null;
        singleton = options.Headless || isolatedPreview ? null : new SingleInstanceCoordinator();
        if (singleton is { IsPrimary: false })
        {
            singleton.SignalPrimary();
            Exit();
            return;
        }

        if (options.UiState == "runtime-unavailable")
        {
            window = new MainWindow(null, options, singleton);
            window.Closed += (_, _) => Dispose();
            window.Activate();
            return;
        }

        try
        {
            runtime = new BridgeRuntime(options);
            runtime.Start();
        }
        catch (Exception exception) when (IsRecoverableStartupFailure(exception))
        {
            BridgeDiagnostics.Record("server.start", exception);
            try { runtime?.Dispose(); }
            catch (Exception cleanupException)
            {
                BridgeDiagnostics.Record("server.start.cleanup", cleanupException);
            }
            runtime = null;
            window = new MainWindow(null, options, singleton);
            window.Closed += (_, _) => Dispose();
            window.Activate();
            _ = window.ShowStartupFailureAsync(exception.Message);
            return;
        }

        if (options.Headless)
        {
            Thread.Sleep(Timeout.Infinite);
            return;
        }

        window = new MainWindow(runtime, options, singleton);
        window.Closed += (_, _) => Dispose();
        if (!options.Background) window.Activate();
        else window.InitializeHidden();
    }

    private static bool IsRecoverableStartupFailure(Exception exception) =>
        exception is SocketException or IOException or UnauthorizedAccessException
            or CryptographicException;

    public void Dispose()
    {
        runtime?.Dispose();
        runtime = null;
        singleton?.Dispose();
        singleton = null;
        GC.SuppressFinalize(this);
    }
}
