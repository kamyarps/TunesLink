namespace TunesLinkBridge;

internal static class BrandPaths
{
    public static string UserConfigDirectory()
    {
        string localAppData = Environment.GetFolderPath(
            Environment.SpecialFolder.LocalApplicationData);
        return Path.Combine(localAppData, "TunesLink Bridge");
    }
}
