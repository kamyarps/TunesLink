using Drawing = System.Drawing;
using Forms = System.Windows.Forms;

namespace TunesLinkBridge;

internal sealed class TrayService : IDisposable
{
    private readonly Forms.NotifyIcon icon;

    public TrayService(Action open, Action exit)
    {
        Forms.ContextMenuStrip menu = new();
        menu.Items.Add(UiStrings.Get("TrayOpen", "Open TunesLink Bridge"), null, (_, _) => open());
        menu.Items.Add(UiStrings.Get("TrayOpenDiagnostics", "Open diagnostics folder"), null, (_, _) => OpenDiagnostics());
        menu.Items.Add(new Forms.ToolStripSeparator());
        menu.Items.Add(UiStrings.Get("TrayExit", "Exit"), null, (_, _) => exit());
        icon = new Forms.NotifyIcon
        {
            Icon = new Drawing.Icon(Path.Combine(AppContext.BaseDirectory, "Assets", "tunelink.ico")),
            Text = UiStrings.Get("AppDisplayName", "TunesLink Bridge"),
            Visible = true,
            ContextMenuStrip = menu
        };
        icon.DoubleClick += (_, _) => open();
    }

    public void ShowFirstCloseTip() => icon.ShowBalloonTip(
        1800,
        UiStrings.Get("TrayStillRunningTitle", "TunesLink is still running"),
        UiStrings.Get("TrayStillRunningDetail", "Use the notification-area icon to reopen or exit."),
        Forms.ToolTipIcon.Info);

    private static void OpenDiagnostics()
    {
        try
        {
            string directory = BrandPaths.UserConfigDirectory();
            Directory.CreateDirectory(directory);
            System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo
            {
                FileName = directory,
                UseShellExecute = true
            });
        }
        catch (Exception exception)
        {
            BridgeDiagnostics.Record("diagnostics.open", exception);
        }
    }

    public void Dispose() => icon.Dispose();
}
