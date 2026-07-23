using Microsoft.Win32;

namespace TunesLinkBridge;

internal static class StartupRegistration
{
    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string ValueName = "TunesLink Bridge";

    public static bool IsEnabled()
    {
        using RegistryKey? readKey = Registry.CurrentUser.OpenSubKey(RunKey, false);
        return readKey?.GetValue(ValueName) is string;
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
            return;
        }
        key.SetValue(ValueName, CurrentCommand(), RegistryValueKind.String);
    }

    private static string CurrentCommand()
    {
        string executable = Environment.ProcessPath
            ?? throw new InvalidOperationException("Could not locate TunesLink Bridge");
        return $"\"{executable}\" --background";
    }
}
