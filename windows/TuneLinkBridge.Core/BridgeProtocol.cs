using System.Reflection;

namespace TunesLinkBridge;

internal static class BridgeProtocol
{
    public const string Id = "TunesLink-3";
    public const int DefaultPort = 45832;
    public const int DefaultDiscoveryPort = 45831;
    public static readonly TimeSpan HandshakeTimeout = TimeSpan.FromSeconds(18);
    public static readonly TimeSpan RequestIdleTimeout = TimeSpan.FromSeconds(125);
    public static readonly TimeSpan ConnectionLifetime = TimeSpan.FromMinutes(10);
    public static readonly TimeSpan SseWriteTimeout = TimeSpan.FromSeconds(5);
    public static readonly TimeSpan SseHeartbeatInterval = TimeSpan.FromSeconds(15);
    public static readonly TimeSpan StateTimeout = TimeSpan.FromSeconds(6);
    public static readonly TimeSpan MediaTimeout = TimeSpan.FromSeconds(8);
    public static readonly TimeSpan LibraryTimeout = TimeSpan.FromSeconds(45);
    public static readonly TimeSpan CollectionTimeout = TimeSpan.FromSeconds(120);

    public static string ProductVersion
    {
        get
        {
            Version? version = Assembly.GetExecutingAssembly().GetName().Version;
            return version is null ? "1.0.0" : $"{version.Major}.{version.Minor}.{version.Build}";
        }
    }
}
