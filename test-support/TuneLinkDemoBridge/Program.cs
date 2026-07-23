using System.Net.Sockets;

namespace TunesLinkBridge;

internal static class Program
{
    private static async Task<int> Main(string[] args)
    {
        int port = IntValueAfter(args, "--port", BridgeProtocol.DefaultPort);
        int discoveryPort = IntValueAfter(args, "--discovery-port", 0);
        string pairCode = ValueAfter(args, "--pair-code") ?? "123456";
        string configDirectory = ValueAfter(args, "--config-directory")
            ?? Path.Combine(Path.GetTempPath(), "TunesLink-integration-" + Environment.ProcessId);
        string computerName = ValueAfter(args, "--computer-name") ?? "TunesLink-PC";
        bool legacyState = args.Contains("--legacy-state", StringComparer.OrdinalIgnoreCase);
        int libraryDelay = IntValueAfter(args, "--library-delay-ms", 0);
        BridgeOptions options = new(port, discoveryPort, true, true, false, pairCode,
            configDirectory, legacyState, computerName);

        BridgeSecurity security = new(configDirectory, pairCode);
        security.Changed += () =>
        {
            Console.WriteLine($"paired-devices:{security.Devices.Count}");
            Console.Out.Flush();
        };
        using BridgeTlsIdentity identity = new(configDirectory);
        using PortableDemoController media = new(libraryDelay);
        using PlaybackStateHub stateHub = new(media);
        using BridgeServer server = new(security, identity, media, stateHub, options);
        try
        {
            server.Start();
        }
        catch (SocketException exception)
        {
            Console.Error.WriteLine(exception);
            return 2;
        }

        Console.WriteLine($"ready:{port}:{identity.DisplayFingerprint}:{pairCode}");
        Console.Out.Flush();

        TaskCompletionSource stopped = new(TaskCreationOptions.RunContinuationsAsynchronously);
        Console.CancelKeyPress += (_, eventArgs) =>
        {
            eventArgs.Cancel = true;
            stopped.TrySetResult();
        };
        AppDomain.CurrentDomain.ProcessExit += (_, _) => stopped.TrySetResult();
        await stopped.Task.ConfigureAwait(false);
        return 0;
    }

    private static int IntValueAfter(string[] args, string name, int fallback) =>
        int.TryParse(ValueAfter(args, name), out int parsed) ? parsed : fallback;

    private static string? ValueAfter(string[] args, string name)
    {
        for (int index = 0; index < args.Length - 1; index++)
            if (string.Equals(args[index], name, StringComparison.OrdinalIgnoreCase))
                return args[index + 1];
        return null;
    }
}
