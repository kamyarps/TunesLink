using Microsoft.Win32;

namespace TunesLinkBridge;

internal static class StartupRegistration
{
    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    // Task Manager's Startup page and Settings > Apps > Startup record the user's choice here,
    // beside the Run entry itself; Windows skips a Run entry whose approval says disabled.
    private const string ApprovedKey =
        @"Software\Microsoft\Windows\CurrentVersion\Explorer\StartupApproved\Run";
    private const string ValueName = "TunesLink Bridge";
    private const int ApprovalValueLength = 12;

    public static bool IsEnabled()
    {
        using RegistryKey? readKey = Registry.CurrentUser.OpenSubKey(RunKey, false);
        if (readKey?.GetValue(ValueName) is not string) return false;
        using RegistryKey? approvedKey = Registry.CurrentUser.OpenSubKey(ApprovedKey, false);
        return IsApproved(approvedKey?.GetValue(ValueName));
    }

    /// <summary>
    /// Reads an Explorer startup approval. The first byte is even when the entry may run (0x02,
    /// 0x06) and odd when the user disabled it (0x03, 0x07); the remaining bytes hold the time
    /// it was disabled. A missing or malformed value means no choice was recorded, which
    /// Windows treats as enabled.
    /// </summary>
    internal static bool IsApproved(object? approval) =>
        approval is not byte[] { Length: > 0 } bytes || (bytes[0] & 0x01) == 0;

    /// <summary>The value Explorer writes when the user enables a startup entry.</summary>
    internal static byte[] EnabledApprovalValue()
    {
        byte[] value = new byte[ApprovalValueLength];
        value[0] = 0x02;
        return value;
    }

    public static void RepairEnabledPath()
    {
        using RegistryKey? readKey = Registry.CurrentUser.OpenSubKey(RunKey, false);
        if (readKey?.GetValue(ValueName) is not string registered) return;
        string expected = CurrentCommand();
        if (string.Equals(registered, expected, StringComparison.Ordinal)) return;
        using RegistryKey writeKey = Registry.CurrentUser.CreateSubKey(RunKey, true);
        writeKey.SetValue(ValueName, expected, RegistryValueKind.String);
    }

    public static void SetEnabled(bool enabled)
    {
        using RegistryKey key = Registry.CurrentUser.CreateSubKey(RunKey, true);
        if (!enabled)
        {
            key.DeleteValue(ValueName, false);
            using RegistryKey? approvals = Registry.CurrentUser.OpenSubKey(ApprovedKey, true);
            approvals?.DeleteValue(ValueName, false);
            return;
        }
        key.SetValue(ValueName, CurrentCommand(), RegistryValueKind.String);
        // Turning the toggle on must also lift a disable recorded by Task Manager or Settings,
        // or the Run entry stays present but never starts.
        using RegistryKey approvedKey = Registry.CurrentUser.CreateSubKey(ApprovedKey, true);
        approvedKey.SetValue(ValueName, EnabledApprovalValue(), RegistryValueKind.Binary);
    }

    private static string CurrentCommand()
    {
        string executable = Environment.ProcessPath
            ?? throw new InvalidOperationException("Could not locate TunesLink Bridge");
        return $"\"{executable}\" --background";
    }
}
