using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using Microsoft.UI.Xaml;

namespace TunesLinkBridge;

public partial class App : Microsoft.UI.Xaml.Application, IDisposable
{
    private const int AllowAnyProcess = -1;
    private volatile MainWindow? window;
    private BridgeRuntime? runtime;
    private SingleInstanceCoordinator? singleton;
    private int startAttempts;

    public App()
    {
        // The preview harness can pin the theme; RequestedTheme is only settable this early.
        string? theme = BridgeLaunchOptions.ParseTheme(Environment.GetCommandLineArgs().Skip(1).ToArray());
        if (theme == "light") RequestedTheme = ApplicationTheme.Light;
        else if (theme == "dark") RequestedTheme = ApplicationTheme.Dark;
        InitializeComponent();
        // Crashes off the UI thread never reach the XAML handler below; record them too, and
        // take the notification-area icon down with a dying process so no ghost icon remains.
        AppDomain.CurrentDomain.UnhandledException += (_, eventArgs) =>
        {
            if (eventArgs.ExceptionObject is Exception exception)
                BridgeDiagnostics.Record("process.unhandled", exception);
            if (eventArgs.IsTerminating) TrayService.RemoveAllForCrash();
        };
        TaskScheduler.UnobservedTaskException += (_, eventArgs) =>
        {
            BridgeDiagnostics.Record("task.unobserved",
                eventArgs.Exception.InnerException ?? eventArgs.Exception);
            eventArgs.SetObserved();
        };
        UnhandledException += (_, eventArgs) =>
        {
            BridgeDiagnostics.Record("winui.unhandled", eventArgs.Exception);
            if (!Environment.GetCommandLineArgs().Contains("--verify-layout",
                    StringComparer.OrdinalIgnoreCase))
            {
                if (!eventArgs.Handled) TrayService.RemoveAllForCrash();
                return;
            }
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
        singleton = options.Headless || options.IsolatedPreview ? null : new SingleInstanceCoordinator();
        if (singleton is { IsPrimary: false })
        {
            // A login start while TunesLink already runs stays silent. A user's launch hands its
            // foreground right to the running instance, which Windows otherwise withholds from a
            // background process.
            if (!options.Background)
            {
                _ = AllowSetForegroundWindow(AllowAnyProcess);
                singleton.SignalPrimary();
            }
            Exit();
            return;
        }
        singleton?.Listen(() => window?.ShowFromExternalInstance());
        Launch(options);
    }

    private void Launch(BridgeLaunchOptions options)
    {
        startAttempts++;
        if (options.UiState == "runtime-unavailable")
        {
            ShowWindow(new MainWindow(null, options), options);
            return;
        }

        try
        {
            // The startup-failure preview fails its first start only, so Retry can be exercised.
            if (options.UiState == "startup-failure" && startAttempts == 1)
                throw new SocketException((int)SocketError.AddressAlreadyInUse);
            runtime = new BridgeRuntime(options);
            // A windowed bridge accepts pairing only while its code is on screen; the window
            // opens it once shown. Headless bridges have no screen and stay open.
            if (!options.Headless) runtime.Security.PairingOpen = false;
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
            MainWindow failed = new(null, options);
            ShowWindow(failed, options with { Background = false });
            _ = OfferStartupRetryAsync(failed, options, exception);
            return;
        }

        if (options.Headless)
        {
            Thread.Sleep(Timeout.Infinite);
            return;
        }

        ShowWindow(new MainWindow(runtime, options), options);
    }

    private void ShowWindow(MainWindow next, BridgeLaunchOptions options)
    {
        window = next;
        next.Closed += (_, _) =>
        {
            // A window replaced by Retry closes without ending the app.
            if (ReferenceEquals(window, next)) Dispose();
        };
        if (!options.Background) next.Activate();
        else next.InitializeHidden();
    }

    private async Task OfferStartupRetryAsync(MainWindow failed, BridgeLaunchOptions options,
                                             Exception exception)
    {
        bool retry;
        try
        {
            retry = await failed.ShowStartupFailureAsync(exception, options.Port);
        }
        catch (Exception dialogException)
        {
            BridgeDiagnostics.Record("ui.startup-failure", dialogException);
            retry = false;
        }
        if (!ReferenceEquals(window, failed)) return;
        if (!retry)
        {
            failed.ExitApplication();
            return;
        }
        // The replacement window opens before the failed one closes, so the app never runs
        // out of windows (which would end it).
        Launch(options with { Background = false });
        failed.CloseReplaced();
    }

    private static bool IsRecoverableStartupFailure(Exception exception) =>
        exception is SocketException or IOException or UnauthorizedAccessException
            or CryptographicException;

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool AllowSetForegroundWindow(int processId);

    public void Dispose()
    {
        runtime?.Dispose();
        runtime = null;
        singleton?.Dispose();
        singleton = null;
        GC.SuppressFinalize(this);
    }
}
