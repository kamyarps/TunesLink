using Microsoft.UI;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Automation.Peers;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Animation;
using Microsoft.UI.Xaml.Media.Imaging;
using Microsoft.Win32;
using Windows.ApplicationModel.DataTransfer;
using Windows.Foundation;
using Windows.Graphics;
using Windows.Graphics.Imaging;
using Windows.Storage.Streams;
using Windows.UI;
using Windows.UI.ViewManagement;
using System.Runtime.InteropServices.WindowsRuntime;
using System.Runtime.InteropServices;
using System.Collections.ObjectModel;

namespace TunesLinkBridge;

public sealed partial class MainWindow
{
    private void SettingsFlyout_Opened(object sender, object args) =>
        DispatcherQueue.TryEnqueue(() => KeepRunningToggle.Focus(FocusState.Programmatic));
    private void KeepRunningToggle_Toggled(object sender, RoutedEventArgs eventArgs)
    {
        if (restoringPreferences) return;
        if (!TryGetRuntime("Keep running preference", out BridgeRuntime availableRuntime))
        {
            RestoreKeepRunningToggle(false);
            return;
        }
        bool previous = availableRuntime.Preferences.KeepRunningOnClose;
        PersistenceResult outcome = availableRuntime.Preferences.TrySetKeepRunningOnClose(
            KeepRunningToggle.IsOn);
        if (!outcome.Succeeded)
        {
            RestoreKeepRunningToggle(previous);
            Announce(UiStrings.Get("KeepRunningChangeFailed", "Keep running could not be changed"),
                AutomationNotificationKind.ActionAborted);
        }
    }

    private void RestoreKeepRunningToggle(bool value)
    {
        restoringPreferences = true;
        KeepRunningToggle.IsOn = value;
        restoringPreferences = false;
    }

    private void OpenAtLoginToggle_Toggled(object sender, RoutedEventArgs eventArgs)
    {
        if (restoringPreferences) return;
        if (!TryGetRuntime("Open at login", out _))
        {
            restoringPreferences = true;
            OpenAtLoginToggle.IsOn = false;
            restoringPreferences = false;
            return;
        }
        bool requested = OpenAtLoginToggle.IsOn;
        try { StartupRegistration.SetEnabled(requested); }
        catch (Exception exception)
        {
            BridgeDiagnostics.Record("startup.write", exception);
            restoringPreferences = true;
            OpenAtLoginToggle.IsOn = !requested;
            restoringPreferences = false;
            Announce(UiStrings.Get("OpenAtLoginChangeFailed", "Open at login could not be changed"),
                AutomationNotificationKind.ActionAborted);
        }
    }

    private void AppWindow_Closing(AppWindow sender, AppWindowClosingEventArgs args)
    {
        if (explicitExit || runtime is null || !runtime.Preferences.KeepRunningOnClose) return;
        args.Cancel = true;
        AppWindow.Hide();
        StopUiActivity();
        if (!runtime.Preferences.HasShownCloseToTrayTip)
        {
            _ = runtime.Preferences.TrySetHasShownCloseToTrayTip(true);
            tray.ShowFirstCloseTip();
        }
    }

    private void AppWindow_Changed(AppWindow sender, AppWindowChangedEventArgs args)
    {
        if (args.DidVisibilityChange) UpdateUiActivity();
    }

    private void ResizeWindowToRequestedSize()
    {
        ResizeClientDip(requestedClientSizeDip.Width, requestedClientSizeDip.Height,
            clampToWorkArea: true);
    }

    private void HideNativeMaximizeButton()
    {
        nint handle = WinRT.Interop.WindowNative.GetWindowHandle(this);
        nint style = GetWindowLongPtr(handle, WindowStyleIndex);
        SetWindowLongPtr(handle, WindowStyleIndex, style & ~(nint)MaximizeBoxStyle);
        _ = SetWindowPos(handle, 0, 0, 0, 0, 0, FrameStyleChangedFlags);
    }

    private bool HasNativeMaximizeButton()
    {
        nint handle = WinRT.Interop.WindowNative.GetWindowHandle(this);
        return (GetWindowLongPtr(handle, WindowStyleIndex) & (nint)MaximizeBoxStyle) != 0;
    }

    private void ResizeClientDip(int widthDip, int heightDip, bool clampToWorkArea)
    {
        nint handle = WinRT.Interop.WindowNative.GetWindowHandle(this);
        uint dpi = GetDpiForWindow(handle);
        double scale = (dpi == 0 ? 96 : dpi) / 96d;
        int width = (int)Math.Round(Math.Max(MinimumClientWidthDip, widthDip) * scale);
        int height = (int)Math.Round(Math.Max(MinimumClientHeightDip, heightDip) * scale);
        DisplayArea? display = clampToWorkArea
            ? DisplayArea.GetFromWindowId(AppWindow.Id, DisplayAreaFallback.Primary)
            : null;
        if (display is not null && clampToWorkArea)
        {
            width = Math.Min(width, Math.Max(1, display.WorkArea.Width - WorkAreaInsetPixels));
            height = Math.Min(height, Math.Max(1, display.WorkArea.Height - WorkAreaInsetPixels));
        }
        ResizeClientPixels(width, height);
    }

    private void ResizeClientPixels(int width, int height)
    {
        SizeInt32 target = new(Math.Max(1, width), Math.Max(1, height));
        if (AppWindow.ClientSize.Equals(target)) return;
        AppWindow.ResizeClient(target);
    }

    private void UpdateUiActivity()
    {
        if (suppressVisibilityLifecycle) return;
        if (AppWindow.IsVisible) StartUiActivity();
        else StopUiActivity();
    }

    private void StartUiActivity()
    {
        if (uiActivityActive || runtime is null) return;
        uiActivityActive = true;
        RefreshAll();
        pairTimer.Start();
        statusTimer.Start();
        relativeTimer.Start();
        runtime.AddressSelector.Refresh();
        _ = RefreshStatusAsync();
    }

    private void StopUiActivity()
    {
        if (!uiActivityActive) return;
        uiActivityActive = false;
        pairTimer.Stop();
        statusTimer.Stop();
        relativeTimer.Stop();
    }

    private void ShowFromExternalInstance()
    {
        DispatcherQueue.TryEnqueue(() =>
        {
            AppWindow.Show();
            Activate();
            StartUiActivity();
        });
    }

    private void ExitApplication()
    {
        DispatcherQueue.TryEnqueue(() =>
        {
            explicitExit = true;
            Close();
        });
    }

    private void Window_Closed(object sender, WindowEventArgs args)
    {
        if (runtime is not null)
        {
            runtime.Security.Changed -= SecurityChanged;
            runtime.AddressSelector.Changed -= AddressChanged;
        }
        pairTimer.Stop();
        statusTimer.Stop();
        relativeTimer.Stop();
        if (advancedEffectsSubscribed)
            uiSettings.AdvancedEffectsEnabledChanged -= PresentationSettingsChanged;
        if (highContrastSubscribed)
            accessibilitySettings.HighContrastChanged -= AccessibilitySettingsChanged;
        if (systemEventsSubscribed)
            SystemEvents.UserPreferenceChanged -= SystemPreferenceChanged;
        problemPresentationCancellation?.Cancel();
        problemPresentationCancellation?.Dispose();
        problemPresentationCancellation = null;
        copyFeedback.Dispose();
        Dispose();
    }

    private void Announce(string message, AutomationNotificationKind kind)
    {
        LiveRegionText.Text = message;
        AutomationPeer peer = FrameworkElementAutomationPeer.FromElement(LiveRegionText)
            ?? FrameworkElementAutomationPeer.CreatePeerForElement(LiveRegionText);
        peer?.RaiseNotificationEvent(kind, AutomationNotificationProcessing.ImportantMostRecent,
            message, "TunesLinkStatus");
    }

    private static SizeInt32? ParseViewport(string? value)
    {
        if (string.IsNullOrWhiteSpace(value)) return null;
        string[] parts = value.Split('x', 2, StringSplitOptions.TrimEntries);
        if (parts.Length != 2 || !int.TryParse(parts[0], out int width)
            || !int.TryParse(parts[1], out int height)
            || width < MinimumClientWidthDip || height < MinimumClientHeightDip)
            throw new ArgumentException($"Viewport must be at least {MinimumClientWidthDip}x{MinimumClientHeightDip}.");
        return new SizeInt32(width, height);
    }

    [DllImport("user32.dll")]
    private static extern uint GetDpiForWindow(nint window);

    [DllImport("user32.dll", EntryPoint = "GetWindowLongPtrW")]
    private static extern nint GetWindowLongPtr(nint window, int index);

    [DllImport("user32.dll", EntryPoint = "SetWindowLongPtrW")]
    private static extern nint SetWindowLongPtr(nint window, int index, nint value);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool SetWindowPos(nint window, nint insertAfter, int x, int y,
        int width, int height, uint flags);

    public void Dispose()
    {
        if (disposed) return;
        disposed = true;
        tray.Dispose();
        GC.SuppressFinalize(this);
    }
}
