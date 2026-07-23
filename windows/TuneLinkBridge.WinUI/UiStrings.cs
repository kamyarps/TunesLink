using System.Globalization;
using Microsoft.Windows.ApplicationModel.Resources;

namespace TunesLinkBridge;

internal static class UiStrings
{
    private static ResourceLoader? loader;

    public static string Get(string key, string fallback)
    {
        try
        {
            loader ??= new ResourceLoader();
            string value = loader.GetString(key);
            return string.IsNullOrWhiteSpace(value) ? fallback : value;
        }
        catch
        {
            // Command-mode self-tests and very early startup can run before MRT is ready.
            return fallback;
        }
    }

    public static string Format(string key, string fallback, params object?[] arguments) =>
        string.Format(CultureInfo.CurrentCulture, Get(key, fallback), arguments);
}
