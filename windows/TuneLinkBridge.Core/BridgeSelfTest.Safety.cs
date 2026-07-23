using System.Globalization;
using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Net.NetworkInformation;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Text;
using System.Text.Json;

namespace TunesLinkBridge;

internal static partial class BridgeSelfTest
{
    private static async Task TestSafetyRegressionsAsync(string rootDirectory)
    {
        Ensure(ItunesController.SearchAllFields == 0, "iTunes search covers all metadata fields");
        Ensure(ItunesController.NormalizeArtwork("invalid", [1, 2, 3, 4], 180) is null,
            "invalid artwork fails closed");
        Ensure(ItunesController.NormalizeArtwork("large",
            new byte[ItunesController.MaxArtworkSourceBytes + 1], 180) is null,
            "oversized artwork rejected before decoding");

        string libraryIndexDirectory = Path.Combine(rootDirectory, "library-index");
        LibraryIndexStore libraryIndexStore = new(libraryIndexDirectory);
        LibraryTrack cachedTrack = new("track-1", "Song", "Artist", "Album", 180, 1, 1,
            "track-1");
        LibraryIndexData cachedIndex = new(
            [cachedTrack],
            [new LibraryCollection("artist-1", "Artist", "", 1, "track-1")],
            [new LibraryCollection("album-1", "Album", "Artist", 1, "track-1")],
            new string('a', 64),
            new string('b', 64),
            DateTimeOffset.UtcNow);
        libraryIndexStore.Save(cachedIndex);
        LibraryIndexData? reloadedIndex = new LibraryIndexStore(libraryIndexDirectory).Load();
        Ensure(reloadedIndex is not null && reloadedIndex.Tracks.SequenceEqual([cachedTrack])
            && reloadedIndex.Revision == cachedIndex.Revision,
            "library index survives a complete store restart");
        string libraryIndexPath = Path.Combine(libraryIndexDirectory, "Cache",
            "library-index-v1.json");
        File.WriteAllText(libraryIndexPath, "{not-json");
        Ensure(new LibraryIndexStore(libraryIndexDirectory).Load() is null,
            "corrupt library index fails closed");

        bool oversizedWorkerMessageRejected = false;
        try
        {
            using StringReader oversized = new(new string('x',
                ItunesWorkerProtocol.MaxRequestCharacters + 1));
            await ItunesWorkerProtocol.ReadBoundedLineAsync(oversized,
                ItunesWorkerProtocol.MaxRequestCharacters);
        }
        catch (IOException)
        {
            oversizedWorkerMessageRejected = true;
        }
        Ensure(oversizedWorkerMessageRejected, "oversized worker message rejected");

        string corruptDirectory = Path.Combine(rootDirectory, "corrupt-config");
        Directory.CreateDirectory(corruptDirectory);
        File.WriteAllText(Path.Combine(corruptDirectory, "config.json"), "{not-json");
        _ = new BridgeSecurity(corruptDirectory, "123456");
        Ensure(Directory.GetFiles(corruptDirectory, "config.json.invalid-*").Length == 1,
            "corrupt configuration preserved for recovery");

        string invalidIdDirectory = Path.Combine(rootDirectory, "invalid-bridge-id");
        Directory.CreateDirectory(invalidIdDirectory);
        File.WriteAllText(Path.Combine(invalidIdDirectory, "config.json"),
            "{\"BridgeId\":\"invalid bridge id\",\"Devices\":[]}");
        BridgeSecurity replacementIdentity = new(invalidIdDirectory, "123456");
        Ensure(BridgeSecurity.IsValidBridgeId(replacementIdentity.BridgeId),
            "invalid bridge identity replaced with protocol-safe identity");
        Ensure(Directory.GetFiles(invalidIdDirectory, "config.json.invalid-*").Length == 1,
            "invalid bridge identity configuration preserved for recovery");

        string invalidDeviceDirectory = Path.Combine(rootDirectory, "invalid-paired-device");
        Directory.CreateDirectory(invalidDeviceDirectory);
        File.WriteAllText(Path.Combine(invalidDeviceDirectory, "config.json"),
            """
            {
              "BridgeId": "valid-bridge-id",
              "Devices": [{
                "ClientId": "77777777-7777-4777-8777-777777777777",
                "Name": "Android phone",
                "TokenHash": "not-a-token-hash",
                "PairedAt": "2026-01-01T00:00:00+00:00",
                "LastSeenAt": "2026-01-01T00:00:00+00:00"
              }]
            }
            """);
        BridgeSecurity sanitizedDevices = new(invalidDeviceDirectory, "123456");
        Ensure(sanitizedDevices.Devices.Count == 0,
            "invalid paired-device records are not loaded");
        Ensure(Directory.GetFiles(invalidDeviceDirectory, "config.json.invalid-*").Length == 1,
            "invalid paired-device configuration preserved for recovery");

        TestTransactionalPersistence(rootDirectory);

        string diagnosticsDirectory = Path.Combine(rootDirectory, "diagnostics");
        BridgeDiagnostics.Record("self-test unsafe!", new InvalidOperationException(
            "sensitive message"), diagnosticsDirectory);
        string diagnostics = File.ReadAllText(Path.Combine(diagnosticsDirectory,
            "diagnostics.log"));
        Ensure(diagnostics.Contains("self-testunsafe", StringComparison.Ordinal)
               && diagnostics.Contains(nameof(InvalidOperationException), StringComparison.Ordinal)
               && !diagnostics.Contains("sensitive message", StringComparison.Ordinal),
            "diagnostics are useful and redact exception messages");
    }

    private static void TestTransactionalPersistence(string rootDirectory)
    {
        string securityDirectory = Path.Combine(rootDirectory, "security-rollback");
        BridgeSecurity writable = new(securityDirectory, "123456");
        Ensure(writable.TryPair("123456", "77777777-7777-4777-8777-777777777777",
            "Rollback phone", out string rollbackToken), "security rollback fixture paired");
        string securityPath = Path.Combine(securityDirectory, "config.json");
        string beforeSecurity = File.ReadAllText(securityPath);

        BridgeSecurity failing = new(securityDirectory, "654321",
            persistence: new FailingPersistence());
        int changes = 0;
        failing.Changed += () => changes++;
        BridgeSecurity.PairingResult pair = failing.Pair("654321",
            "88888888-8888-4888-8888-888888888888", "Unpersisted phone");
        Ensure(pair.Status == BridgeSecurity.PairingStatus.PersistenceFailed
               && pair.Token.Length == 0 && failing.Devices.Count == 1,
            "failed pairing persistence rolls back token and memory");
        PersistenceResult forgotDevice = failing.TryForgetDevice(
            failing.Devices[0].TokenHash);
        PersistenceResult forgotToken = failing.TryForgetToken(rollbackToken);
        PersistenceResult forgot = failing.TryForgetAll();
        Ensure(!forgotDevice.Succeeded && !forgotToken.Succeeded && !forgot.Succeeded
               && failing.Devices.Count == 1 && changes == 0,
            "failed security mutations do not publish or raise change events");
        Ensure(File.ReadAllText(securityPath) == beforeSecurity,
            "failed security persistence leaves disk unchanged");

        string preferencesDirectory = Path.Combine(rootDirectory, "preferences-rollback");
        BridgePreferences writablePreferences = new(preferencesDirectory);
        Ensure(writablePreferences.TrySetKeepRunningOnClose(false).Succeeded,
            "preferences rollback fixture saved");
        string preferencesPath = Path.Combine(preferencesDirectory, "ui.json");
        string beforePreferences = File.ReadAllText(preferencesPath);
        BridgePreferences failingPreferences = new(preferencesDirectory,
            new FailingPersistence());
        PersistenceResult preference = failingPreferences.TrySetKeepRunningOnClose(true);
        Ensure(!preference.Succeeded && !failingPreferences.KeepRunningOnClose,
            "failed preference persistence rolls back memory");
        Ensure(File.ReadAllText(preferencesPath) == beforePreferences,
            "failed preference persistence leaves disk unchanged");

        string cleanupDirectory = Path.Combine(rootDirectory, "temporary-cleanup");
        Directory.CreateDirectory(cleanupDirectory);
        string invalidDestination = Path.Combine(cleanupDirectory, "destination");
        Directory.CreateDirectory(invalidDestination);
        try { AtomicFilePersistence.Instance.WriteText(invalidDestination, "test"); }
        catch (IOException) { }
        Ensure(Directory.GetFiles(cleanupDirectory, "*.tmp").Length == 0,
            "failed atomic replacement cleans temporary files");
    }

    private static async Task WaitForActiveConnectionsAsync(BridgeServer server, int maximum)
    {
        for (int attempt = 0; attempt < 100; attempt++)
        {
            if (server.ActiveConnectionCountForTest <= maximum) return;
            await Task.Delay(20).ConfigureAwait(false);
        }
        Ensure(server.ActiveConnectionCountForTest <= maximum,
            "completed connections released before the next network phase");
    }

    private static void TestExclusivePortOwnership(int port, int discoveryPort)
    {
        if (!OperatingSystem.IsWindows()) return;
        bool tcpRejected = false;
        using (Socket competingTcp = new(AddressFamily.InterNetwork, SocketType.Stream,
                   ProtocolType.Tcp))
        {
            try { competingTcp.Bind(new IPEndPoint(IPAddress.Any, port)); }
            catch (SocketException) { tcpRejected = true; }
        }
        Ensure(tcpRejected, "TCP bridge port has exclusive ownership");

        bool udpRejected = false;
        using (Socket competingUdp = new(AddressFamily.InterNetwork, SocketType.Dgram,
                   ProtocolType.Udp))
        {
            try { competingUdp.Bind(new IPEndPoint(IPAddress.Any, discoveryPort)); }
            catch (SocketException) { udpRejected = true; }
        }
        Ensure(udpRejected, "UDP discovery port has exclusive ownership");
    }

    private static void TestPairCodeExpiry()
    {
        string directory = Path.Combine(Path.GetTempPath(), "TunesLink-expiry-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(directory);
        try
        {
            MutableTimeProvider time = new(DateTimeOffset.Parse("2026-01-01T00:00:00Z",
                CultureInfo.InvariantCulture));
            BridgeSecurity security = new(directory, "123456", time);
            DateTimeOffset initialExpiry = security.PairCodeExpiresAt;
            time.Advance(BridgeSecurity.PairCodeLifetime - TimeSpan.FromMilliseconds(1));
            Ensure(security.PairCode == "123456", "pair code valid before expiry boundary");
            time.Advance(TimeSpan.FromMilliseconds(1));
            Ensure(!security.TryPair("123456", "33333333-3333-4333-8333-333333333333",
                "Expired code", out _), "expired pair code rejected");
            Ensure(security.PairCodeExpiresAt > initialExpiry, "expired pair code rotated");
            string current = security.PairCode;
            Ensure(security.TryPair(current, "33333333-3333-4333-8333-333333333333",
                "Current code", out _), "current pair code accepted");
            Ensure(security.PairCodeExpiresAt > initialExpiry, "successful pairing rotates expiry");
        }
        finally
        {
            try { Directory.Delete(directory, true); } catch { }
        }
    }

    private static void TestAddressSelection()
    {
        NetworkAddressCandidate vpn = new("a", "VPN", "TAP virtual adapter",
            NetworkInterfaceType.Ethernet, IPAddress.Parse("10.8.0.2"), true, true);
        NetworkAddressCandidate wifi = new("b", "Wi-Fi", "Wireless adapter",
            NetworkInterfaceType.Wireless80211, IPAddress.Parse("192.168.1.10"), true, true);
        NetworkAddressCandidate apipa = new("c", "Ethernet", "Ethernet",
            NetworkInterfaceType.Ethernet, IPAddress.Parse("169.254.2.3"), false, false);
        NetworkAddressSelection selected = NetworkAddressSelector.Select([vpn, wifi, apipa]);
        Ensure(selected.Address == "192.168.1.10", "physical gateway-backed adapter preferred");
        NetworkAddressSelection routed = NetworkAddressSelector.Select([vpn, wifi], vpn.Address);
        Ensure(routed.Address == "10.8.0.2", "recent authenticated client route preferred");
        NetworkAddressCandidate cgnat = new("d", "Mobile", "Carrier network",
            NetworkInterfaceType.Ethernet, IPAddress.Parse("100.64.1.2"), true, true);
        Ensure(NetworkAddressSelector.Select([cgnat, apipa]).Address == "100.64.1.2",
            "carrier-grade NAT preferred over link-local");
        Ensure(NetworkAddressSelector.Select([]).Address is null, "missing private adapter diagnostic");
    }

    private static void TestPairingRateLimits()
    {
        MutableTimeProvider perAddressTime = new(DateTimeOffset.Parse("2026-01-01T00:00:00Z",
            CultureInfo.InvariantCulture));
        PairingRateLimiter perAddress = new(perAddressTime);
        IPAddress phone = IPAddress.Parse("192.168.1.40");
        for (int attempt = 0; attempt < 5; attempt++) perAddress.RecordFailure(phone);
        Ensure(!perAddress.CanAttempt(phone, out int addressRetry) && addressRetry == 60,
            "per-address pairing limit and Retry-After");
        perAddressTime.Advance(TimeSpan.FromSeconds(59));
        Ensure(!perAddress.CanAttempt(phone, out addressRetry) && addressRetry == 1,
            "per-address cooldown boundary");
        perAddressTime.Advance(TimeSpan.FromSeconds(1));
        Ensure(perAddress.CanAttempt(phone, out _), "per-address cooldown expiry");

        MutableTimeProvider globalTime = new(DateTimeOffset.Parse("2026-01-01T00:00:00Z",
            CultureInfo.InvariantCulture));
        PairingRateLimiter global = new(globalTime);
        for (int attempt = 1; attempt <= 20; attempt++)
            global.RecordFailure(IPAddress.Parse($"10.0.0.{attempt}"));
        global.ClearAddress(IPAddress.Parse("10.0.0.1"));
        Ensure(!global.CanAttempt(IPAddress.Parse("10.0.0.50"), out int globalRetry)
            && globalRetry == 300, "global pairing limit survives one successful address clear");
        globalTime.Advance(TimeSpan.FromMinutes(5));
        Ensure(global.CanAttempt(IPAddress.Parse("10.0.0.50"), out _),
            "global pairing cooldown expiry");
    }

    private static async Task TestSharedStateHubAsync()
    {
        using CountingMediaController media = new();
        using PlaybackStateHub hub = new(media);
        await Task.Delay(100);
        Ensure(media.StateRequests == 0, "state hub stays idle without subscribers");
        await using (PlaybackStateSubscription first =
            await hub.SubscribeAsync(CancellationToken.None))
        await using (PlaybackStateSubscription second =
            await hub.SubscribeAsync(CancellationToken.None))
        {
            _ = await first.Reader.ReadAsync();
            _ = await second.Reader.ReadAsync();
            await Task.Delay(100);
            Ensure(media.StateRequests == 1,
                "new subscribers share the fresh initial state without a duplicate sample");
            await Task.Delay(1700);
            Ensure(first.Reader.TryRead(out PlaybackStateUpdate? firstLatest)
                && second.Reader.TryRead(out PlaybackStateUpdate? secondLatest)
                && firstLatest.Sequence == secondLatest.Sequence,
                "slow subscribers retain the same latest state");
            Ensure(media.StateRequests <= 5, "one sampler serves multiple subscribers");
        }
        await Task.Delay(900);
        int requestsAfterUnsubscribe = media.StateRequests;
        await Task.Delay(900);
        Ensure(media.StateRequests == requestsAfterUnsubscribe,
            "state hub stops sampling after its last subscriber leaves");
    }

    private static async Task TestDiscoveryAsync(int port)
    {
        using CancellationTokenSource timeout = new(TimeSpan.FromSeconds(5));
        using UdpClient client = new(AddressFamily.InterNetwork);
        byte[] request = Encoding.ASCII.GetBytes("TunesLink_DISCOVER_V1");
        await client.SendAsync(request, new IPEndPoint(IPAddress.Loopback, port), timeout.Token);
        UdpReceiveResult response = await client.ReceiveAsync(timeout.Token);
        using JsonDocument json = JsonDocument.Parse(response.Buffer);
        Ensure(json.RootElement.GetProperty("protocol").GetString() == BridgeProtocol.Id,
            "real UDP discovery response");
    }

    private static async Task TestWorkerIsolationAsync()
    {
        ItunesTrackLocator locator = new(1, 2, 3, 4);
        string encoded = ItunesTrackId.Encode(locator);
        Ensure(ItunesTrackId.TryDecode(encoded, out ItunesTrackLocator decoded)
               && decoded == locator, "stable iTunes track identifier");
        Ensure(!ItunesTrackId.TryDecode("not-a-track", out _), "invalid track identifier");

        using IsolatedItunesController isolated = new("--itunes-worker-demo", diagnostics: false);
        PlaybackState initial = await isolated.GetStateAsync();
        Ensure(initial.Title == "Midnight Drive", "isolated worker state");
        LibraryPage library = await isolated.GetLibraryAsync("", 0, 1);
        string trackId = library.Items[0].Id;
        isolated.TerminateWorkerForTest();
        await isolated.PlayTrackAsync(new PlaybackSelection(trackId));
        PlaybackState restarted = await isolated.GetStateAsync();
        Ensure(restarted.Title == "Midnight Drive", "track remains playable after worker restart");

        using (CancellationTokenSource libraryHangTimeout = new(TimeSpan.FromSeconds(5)))
        {
            Task libraryHang = isolated.ExerciseLibraryHangForSelfTestAsync(
                libraryHangTimeout.Token);
            await Task.Delay(150);
            Task<PlaybackState> stateDuringLibraryWork = isolated.GetStateAsync();
            Ensure(await Task.WhenAny(stateDuringLibraryWork, Task.Delay(TimeSpan.FromSeconds(3)))
                   == stateDuringLibraryWork,
                "library work does not block playback state");
            Ensure((await stateDuringLibraryWork).ITunesAvailable,
                "playback state remains available during library work");
            libraryHangTimeout.Cancel();
            try { await libraryHang; }
            catch (OperationCanceledException) when (libraryHangTimeout.IsCancellationRequested) { }
        }

        using CancellationTokenSource timeout = new(TimeSpan.FromMilliseconds(250));
        try
        {
            await isolated.ExerciseHangForSelfTestAsync(timeout.Token);
            throw new InvalidOperationException("Self-test failed: worker timeout");
        }
        catch (OperationCanceledException) when (timeout.IsCancellationRequested)
        {
            Ensure(!isolated.WorkerRunningForTest, "hung worker terminated");
        }
        PlaybackState recovered = await isolated.GetStateAsync();
        Ensure(recovered.Title == "Midnight Drive", "worker recovery after timeout");

        using IsolatedItunesController idle = new("--itunes-worker-demo", diagnostics: false,
            workerIdleTimeout: TimeSpan.FromMilliseconds(100));
        _ = await idle.GetStateAsync();
        await Task.Delay(500);
        Ensure(!idle.WorkerRunningForTest, "idle worker terminates when demand stops");

        IsolatedItunesController disposing = new("--itunes-worker-demo", diagnostics: false);
        Task hung = disposing.ExerciseHangForSelfTestAsync(CancellationToken.None);
        for (int attempt = 0; attempt < 20 && !disposing.WorkerRunningForTest; attempt++)
            await Task.Delay(25);
        System.Diagnostics.Stopwatch shutdown = System.Diagnostics.Stopwatch.StartNew();
        disposing.Dispose();
        shutdown.Stop();
        Ensure(shutdown.Elapsed < TimeSpan.FromSeconds(3),
            "controller disposal is bounded while the worker is hung");
        try { await hung; } catch { }
    }

    private static async Task<HttpStatusCode> RawStatusAsync(int port, string request)
    {
        using CancellationTokenSource timeout = new(TimeSpan.FromSeconds(5));
        using TcpClient tcp = new();
        await tcp.ConnectAsync(IPAddress.Loopback, port, timeout.Token);
        using SslStream tls = new(tcp.GetStream(), false,
            (_, certificate, _, _) => certificate is not null);
        await tls.AuthenticateAsClientAsync(new SslClientAuthenticationOptions
        {
            TargetHost = "localhost",
            EnabledSslProtocols = System.Security.Authentication.SslProtocols.Tls12
                                  | System.Security.Authentication.SslProtocols.Tls13,
            CertificateRevocationCheckMode =
                System.Security.Cryptography.X509Certificates.X509RevocationMode.NoCheck
        }, timeout.Token);
        await tls.WriteAsync(System.Text.Encoding.ASCII.GetBytes(request), timeout.Token);
        await tls.FlushAsync(timeout.Token);
        byte[] response = new byte[1024];
        int read = await tls.ReadAsync(response, timeout.Token);
        string statusLine = System.Text.Encoding.ASCII.GetString(response, 0, read)
            .Split("\r\n", 2)[0];
        string[] fields = statusLine.Split(' ', 3);
        if (fields.Length < 2 || !int.TryParse(fields[1], out int status))
            throw new InvalidOperationException("Bridge returned an invalid HTTP status");
        return (HttpStatusCode)status;
    }

    private static async Task<IReadOnlyList<HttpStatusCode>> RawStatusesAsync(int port, int count)
    {
        using CancellationTokenSource timeout = new(TimeSpan.FromSeconds(15));
        using TcpClient tcp = new();
        await tcp.ConnectAsync(IPAddress.Loopback, port, timeout.Token);
        using SslStream tls = new(tcp.GetStream(), false, (_, certificate, _, _) => certificate is not null);
        await tls.AuthenticateAsClientAsync(new SslClientAuthenticationOptions
        {
            TargetHost = "localhost",
            EnabledSslProtocols = SslProtocols.Tls12 | SslProtocols.Tls13,
            CertificateRevocationCheckMode =
                System.Security.Cryptography.X509Certificates.X509RevocationMode.NoCheck
        }, timeout.Token);
        StringBuilder requests = new();
        for (int index = 0; index < count; index++)
        {
            requests.Append("GET /api/info HTTP/1.1\r\nHost: localhost\r\n");
            if (index == count - 1) requests.Append("Connection: close\r\n");
            requests.Append("\r\n");
        }
        await tls.WriteAsync(Encoding.ASCII.GetBytes(requests.ToString()), timeout.Token);
        await tls.FlushAsync(timeout.Token);
        List<HttpStatusCode> statuses = [];
        for (int index = 0; index < count; index++)
            statuses.Add(await ReadRawResponseAsync(tls, timeout.Token));
        return statuses;
    }

    private static async Task<HttpStatusCode> ReadRawResponseAsync(Stream stream, CancellationToken token)
    {
        List<byte> header = [];
        byte[] one = new byte[1];
        while (header.Count < 16 * 1024)
        {
            int read = await stream.ReadAsync(one, token);
            if (read == 0) throw new InvalidOperationException("Connection ended before an HTTP response.");
            header.Add(one[0]);
            int length = header.Count;
            if (length >= 4 && header[length - 4] == 13 && header[length - 3] == 10
                && header[length - 2] == 13 && header[length - 1] == 10) break;
        }
        string text = Encoding.ASCII.GetString([.. header]);
        string[] lines = text.Split("\r\n", StringSplitOptions.RemoveEmptyEntries);
        int status = int.Parse(lines[0].Split(' ')[1], CultureInfo.InvariantCulture);
        int contentLength = int.Parse(lines.Single(line =>
            line.StartsWith("Content-Length:", StringComparison.OrdinalIgnoreCase)).Split(':')[1].Trim(),
            CultureInfo.InvariantCulture);
        byte[] body = new byte[contentLength];
        await stream.ReadExactlyAsync(body, token);
        return (HttpStatusCode)status;
    }

    private static async Task<(string Event, string Data)> ReadSseEventAsync(StreamReader reader,
        TimeSpan timeout)
    {
        using CancellationTokenSource cancellation = new(timeout);
        string eventName = "message";
        List<string> data = [];
        while (true)
        {
            string? line = await reader.ReadLineAsync(cancellation.Token);
            if (line is null) throw new InvalidOperationException("SSE stream ended before an event.");
            if (line.Length == 0 && data.Count > 0) return (eventName, string.Join("\n", data));
            if (line.StartsWith("event:", StringComparison.Ordinal)) eventName = line[6..].TrimStart();
            else if (line.StartsWith("data:", StringComparison.Ordinal)) data.Add(line[5..].TrimStart());
        }
    }

}
