using Drawing = System.Drawing;
using Forms = System.Windows.Forms;

namespace TunesLinkBridge;

internal sealed class TrayService : IDisposable
{
    private static readonly object LiveGate = new();
    private static readonly List<TrayService> Live = [];
    private readonly Forms.NotifyIcon icon;

    public TrayService(Action open, Action exit)
    {
        Forms.ContextMenuStrip menu = new();
        Forms.ToolStripItem openItem = menu.Items.Add(
            UiStrings.Get("TrayOpen", "Open TunesLink Bridge"), null, (_, _) => open());
        // The bold item is the one a click on the icon performs, as in Windows' own tray menus.
        openItem.Font = new Drawing.Font(menu.Font, Drawing.FontStyle.Bold);
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
        // Windows 11 opens an app from its notification-area icon on a single left click.
        icon.MouseClick += (_, eventArgs) =>
        {
            if (eventArgs.Button == Forms.MouseButtons.Left) open();
        };
        icon.DoubleClick += (_, _) => open();
        lock (LiveGate) Live.Add(this);
    }

    public void ShowFirstCloseTip() => icon.ShowBalloonTip(
        1800,
        UiStrings.Get("TrayStillRunningTitle", "TunesLink is still running"),
        UiStrings.Get("TrayStillRunningDetail", "Use the notification-area icon to reopen or exit."),
        Forms.ToolTipIcon.Info);

    /// <summary>
    /// Removes every notification-area icon before a fatal crash, from any thread; otherwise
    /// the shell keeps a ghost icon until the pointer passes over it.
    /// </summary>
    public static void RemoveAllForCrash()
    {
        TrayService[] services;
        lock (LiveGate) services = [.. Live];
        foreach (TrayService service in services)
        {
            try { service.icon.Visible = false; }
            catch { }
        }
    }

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

    public void Dispose()
    {
        lock (LiveGate) Live.Remove(this);
        icon.Dispose();
    }
}
