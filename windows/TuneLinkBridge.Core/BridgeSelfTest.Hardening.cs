using System.Diagnostics;
using System.Globalization;
using System.Net;
using System.Net.Http.Headers;
using System.Net.Security;
using System.Net.Sockets;
using System.Reflection;
using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;
using System.Security.Authentication;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.Json;

namespace TunesLinkBridge;

internal static partial class BridgeSelfTest
{
    private static async Task TestHardeningRegressionsAsync(string root)
    {
        TestListenerMatchingAndOrder();
        await TestStateHubClockAsync();
        TestPairingCodeRotation(Path.Combine(root, "pair-rotation"));
        await TestConfigurationReadRetryAsync(Path.Combine(root, "config-retry"));
        TestLegacyIdentityMigration(Path.Combine(root, "tls-migration"));
        TestComDiagnostics(Path.Combine(root, "com-diagnostics"));
        await TestItunesBusyAndArtworkAsync(Path.Combine(root, "itunes-busy"));
        await TestItunesQuitAsync(Path.Combine(root, "itunes-quit"));
        await TestLibraryContentAsync(Path.Combine(root, "library-content"));
        await TestErrorContractAsync(Path.Combine(root, "error-contract"));
        await TestDisconnectsReleaseSlotsAsync(Path.Combine(root, "disconnects"));
        await TestDiscoveryBindFailureAsync(Path.Combine(root, "discovery-bind"));
    }

    private static void TestListenerMatchingAndOrder()
    {
        Ensure(LibraryGrouping.Matches("Beyoncé", "beyonce")
               && LibraryGrouping.Matches("ＡＢＢＡ Gold", "abba")
               && !LibraryGrouping.Matches("Beyoncé", "beyonde"),
            "search ignores accents, case, and character width");
        Ensure(LibraryGrouping.MatchesTrack("Song", "Guest", "Album", "Aardvark Band", "aardvark"),
            "song search includes the album artist");
        string[] artists = ["Zebra", "The Beatles", "Élan Vital", "Beyoncé", "ABBA", "The"];
        Ensure(artists.Order(LibraryGrouping.TitleOrder)
                .SequenceEqual(["ABBA", "The Beatles", "Beyoncé", "Élan Vital", "The", "Zebra"]),
            "titles sort linguistically and ignore a leading The");
        Ensure(LibraryGrouping.InCollectionOrder(
                new[] { (Album: "Beyoncé", Disc: 1, Track: 2, Index: 0),
                        (Album: "Beyonce", Disc: 1, Track: 1, Index: 1),
                        (Album: "Beyoncé", Disc: 1, Track: 1, Index: 2),
                        (Album: "Beyonce", Disc: 1, Track: 2, Index: 3) },
                item => item.Album, _ => "Artist", item => item.Disc, item => item.Track,
                item => item.Index)
            .Select(item => item.Index).SequenceEqual([1, 3, 2, 0]),
            "albums differing only by an accent stay separate runs");
        DemoTrack[] catalog = [new("Halo", "Beyoncé", "I Am", 200, AlbumArtist: "Beyoncé"),
            new("Other", "Guest", "Élan", 200, AlbumArtist: "Aardvark Band")];
        Ensure(DemoLibrary.GetTracks(catalog, "beyonce", 0, 10).Total == 1
               && DemoLibrary.GetTracks(catalog, "aardvark", 0, 10).Total == 1,
            "the demo library searches like the iTunes bridge");
    }

    private static async Task TestStateHubClockAsync()
    {
        MutableTimeProvider clock = new(DateTimeOffset.Parse("2026-01-01T00:00:00Z",
            CultureInfo.InvariantCulture));
        using CountingMediaController media = new();
        using PlaybackStateHub hub = new(media, clock);
        _ = await hub.GetStateAsync();
        clock.StepWallClock(TimeSpan.FromHours(-1));
        clock.Advance(TimeSpan.FromSeconds(1));
        _ = await hub.GetStateAsync();
        Ensure(media.StateRequests == 2, "a backwards wall-clock step cannot freeze state updates");

        PairingRateLimiter limiter = new(clock);
        IPAddress phone = IPAddress.Parse("192.168.1.50");
        for (int attempt = 0; attempt < 5; attempt++) limiter.RecordFailure(phone);
        clock.StepWallClock(TimeSpan.FromHours(2));
        Ensure(!limiter.CanAttempt(phone, out int retry) && retry == 60,
            "a forward wall-clock step cannot lift a pairing cooldown");
    }

    private static void TestPairingCodeRotation(string directory)
    {
        BridgeSecurity security = new(directory);
        string original = security.PairCode;
        int changes = 0;
        security.Changed += () => changes++;
        string wrong = original == "000000" ? "111111" : "000000";
        for (int attempt = 1; attempt < BridgeSecurity.MaxFailedAttemptsPerCode; attempt++)
            _ = security.Pair(wrong, "99999999-9999-4999-8999-999999999999", "Guess");
        Ensure(security.PairCode == original && changes == 0, "a few wrong codes keep the code");
        _ = security.Pair(wrong, "99999999-9999-4999-8999-999999999999", "Guess");
        Ensure(security.PairCode != original && changes == 1,
            "the pairing code rotates after ten wrong attempts and tells the window");
        Ensure(!security.TryPair(original, "99999999-9999-4999-8999-999999999999", "Late", out _),
            "a rotated code is no longer accepted");

        security.PairingOpen = false;
        BridgeSecurity.PairingResult closed = security.Pair(security.PairCode,
            "99999999-9999-4999-8999-999999999999", "Closed");
        Ensure(closed.Status == BridgeSecurity.PairingStatus.PairingClosed && security.Devices.Count == 0,
            "a closed bridge refuses even the right code");
        security.PairingOpen = true;
        Ensure(security.TryPair(security.PairCode, "99999999-9999-4999-8999-999999999999", "Open",
            out _), "reopened pairing accepts the current code");
    }

    private static async Task TestConfigurationReadRetryAsync(string directory)
    {
        BridgeSecurity original = new(directory, "123456");
        Ensure(original.TryPair("123456", "12121212-1212-4212-8212-121212121212", "Kept", out _),
            "configuration retry fixture paired");
        string path = Path.Combine(directory, "config.json");
        Task<BridgeSecurity> reopening;
        using (new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.None))
        {
            reopening = Task.Run(() => new BridgeSecurity(directory, "123456"));
            await Task.Delay(150);
        }
        BridgeSecurity reopened = await reopening;
        Ensure(reopened.BridgeId == original.BridgeId && reopened.Devices.Count == 1,
            "a configuration briefly held by another program is read after a retry");

        bool failed = false;
        using (new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.None))
        {
            try { _ = new BridgeSecurity(directory, "123456"); }
            catch (IOException) { failed = true; }
        }
        Ensure(failed && Directory.GetFiles(directory, "config.json.invalid-*").Length == 0
               && new BridgeSecurity(directory, "123456").Devices.Count == 1,
            "an unreadable configuration stops the bridge instead of discarding pairings");
    }

    private static void TestLegacyIdentityMigration(string directory)
    {
        if (!OperatingSystem.IsWindows()) return;
        Directory.CreateDirectory(directory);
        using RSA key = RSA.Create(2048);
        CertificateRequest request = new("CN=TunesLink Legacy", key, HashAlgorithmName.SHA256,
            RSASignaturePadding.Pkcs1);
        using X509Certificate2 legacy = request.CreateSelfSigned(DateTimeOffset.UtcNow.AddDays(-1),
            DateTimeOffset.UtcNow.AddYears(2));
        string path = Path.Combine(directory, "bridge-identity.pfx");
        File.WriteAllBytes(path, legacy.Export(X509ContentType.Pfx));
        using BridgeTlsIdentity migrated = new(directory);
        Ensure(migrated.Fingerprint == Convert.ToHexString(SHA256.HashData(legacy.RawData))
               && migrated.Certificate.HasPrivateKey
               && File.ReadAllBytes(path).AsSpan().StartsWith("TunesLink-DPAPI-V1\n"u8)
               && !File.Exists(path + ".invalid"),
            "a plaintext identity is protected in place and keeps its fingerprint");
    }

    private static void TestComDiagnostics(string directory)
    {
        Exception rejected = ComFailure(unchecked((int)0x80010001));
        BridgeDiagnostics.Record("itunes.worker.com", rejected, directory);
        BridgeDiagnostics.RecordEvent("bridge.start", "1.2.3", directory);
        string log = File.ReadAllText(Path.Combine(directory, "diagnostics.log"));
        Ensure(log.Contains("COMException 0x80010001", StringComparison.Ordinal)
               && log.Contains("bridge.start\t1.2.3", StringComparison.Ordinal)
               && !log.Contains(rejected.Message, StringComparison.Ordinal),
            "diagnostics record COM HRESULTs and session starts without messages");
    }

    private static async Task TestItunesBusyAndArtworkAsync(string directory)
    {
        BusyItunesProbe busy = new();
        using (ItunesController controller = FakeComController(directory, busy))
        {
            bool reportedBusy = false;
            try { _ = await controller.GetStateAsync(); }
            catch (COMException exception) when (exception.HResult == unchecked((int)0x80010001))
            {
                reportedBusy = true;
            }
            Ensure(reportedBusy && typeof(ItunesController).GetField("itunes",
                    BindingFlags.Instance | BindingFlags.NonPublic)!.GetValue(controller) is not null,
                "a dialog in iTunes is reported as busy and keeps the connection");
        }

        string id = ItunesTrackId.Encode(new ItunesTrackLocator(1, 2, 3, 4));
        using (ItunesController controller = FakeComController(directory,
                   new ArtworkAppProbe(unchecked((int)0x80004005))))
        {
            Ensure(await controller.GetArtworkAsync(id, 256) is null,
                "one track's failing artwork is recorded as missing instead of failing");
        }
        using (ItunesController controller = FakeComController(directory,
                   new ArtworkAppProbe(unchecked((int)0x80010001))))
        {
            bool propagated = false;
            try { _ = await controller.GetArtworkAsync(id, 256); }
            catch (COMException) { propagated = true; }
            Ensure(propagated, "a busy iTunes does not mark artwork missing");
        }
    }

    // The runtime's own COM exception, as an RPC failure would surface through interop.
    private static Exception ComFailure(int hresult) => Marshal.GetExceptionForHR(hresult)!;

    public sealed class BusyItunesProbe
    {
        private readonly int rejected = unchecked((int)0x80010001);
        public int PlayerState => throw ComFailure(rejected);
    }

    public sealed class ArtworkAppProbe(int hresult)
    {
        public object GetITObjectByID(int source, int playlist, int track, int database) =>
            new ArtworkTrackProbe(hresult);
    }

    public sealed class ArtworkTrackProbe(int hresult)
    {
        public ArtworkTrackProbe Artwork => this;
        public int Count { get; } = 1;
        public ArtworkTrackProbe Item(int index) => this;
        public void SaveArtworkToFile(string path) => throw ComFailure(hresult);
    }

    private static async Task TestItunesQuitAsync(string directory)
    {
        MutableTimeProvider clock = new(DateTimeOffset.UtcNow);
        ItunesController.ItunesProcess itunes = new(4242, new DateTime(2026, 1, 1));
        ItunesController.ItunesProcess[] running = [itunes];
        using ItunesController controller = FakeComController(directory, new QueueAppProbe(3),
            clock, () => running);
        FieldInfo connection = typeof(ItunesController).GetField("itunes",
            BindingFlags.Instance | BindingFlags.NonPublic)!;
        controller.OnItunesQuitting();
        PlaybackState quitting = await controller.GetStateAsync();
        Ensure(!quitting.ITunesAvailable && connection.GetValue(controller) is null,
            "iTunes quitting releases the bridge's reference to it");
        running = [];
        Ensure(!(await controller.GetStateAsync()).ITunesAvailable,
            "the bridge waits out a short grace after iTunes exits");
        clock.Advance(TimeSpan.FromSeconds(3));
        bool reported = false;
        try { await controller.PlayTrackAsync(new PlaybackSelection(QueueTrackId(1))); }
        catch (MediaUnavailableException) { reported = true; }
        Ensure(reported && typeof(ItunesController).GetField("quittingProcesses",
                BindingFlags.Instance | BindingFlags.NonPublic)!.GetValue(controller) is null,
            "after iTunes has quit the bridge asks for iTunes to be opened again");
    }

    private static async Task TestLibraryContentAsync(string directory)
    {
        MutableTimeProvider clock = new(DateTimeOffset.UtcNow);
        LibraryAppProbe app = new();
        using ItunesController controller = FakeComController(directory, app, clock);

        LibraryCollectionPage playlists = await controller.GetCollectionsAsync("playlists", "", 0, 60);
        Ensure(playlists.Items.Select(item => item.Title)
                .SequenceEqual(["Empty", "Purchased", "Road Trip"])
               && playlists.Revision.Length == 16,
            "only listener and Purchased playlists are listed, with a revision");

        LibraryPage songs = await controller.GetLibraryAsync("", 0, 60);
        Ensure(songs.Total == 4 && songs.Items.Select(TrackNumberOf).SequenceEqual([1, 2, 5, 6]),
            "songs leave out the library's movies and podcasts");
        LibraryPage window = await controller.GetLibraryAsync("", 1, 2);
        Ensure(window.Offset == 1 && window.HasMore
               && window.Items.Select(TrackNumberOf).SequenceEqual([2, 5]),
            "paging by position skips hidden media consistently");
        Ensure((await controller.GetLibraryAsync("beyonce", 0, 60)).Total == 1
               && (await controller.GetLibraryAsync("aardvark", 0, 60)).Total == 1
               && (await controller.GetLibraryAsync("film", 0, 60)).Total == 0,
            "live search matches accents and album artists and hides media");

        string roadTrip = playlists.Items.Single(item => item.Title == "Road Trip").Id;
        LibraryPage first = await controller.GetCollectionTracksAsync("playlists", roadTrip, "", 0, 2);
        LibraryPage second = await controller.GetCollectionTracksAsync("playlists", roadTrip, "", 2, 2);
        Ensure(first.Revision.Length == 16 && first.Revision == second.Revision,
            "pages of an unchanged playlist share its revision");
        app.RoadTrip.Tracks.Reverse();
        clock.Advance(TimeSpan.FromSeconds(6));
        LibraryPage shifted = await controller.GetCollectionTracksAsync("playlists", roadTrip, "", 0, 2);
        Ensure(shifted.Revision.Length == 16 && shifted.Revision != first.Revision,
            "a reordered playlist gets a new revision");

        LibraryCollectionPage artists = await controller.GetCollectionsAsync("artists", "", 0, 60);
        Ensure(artists.Items.Select(item => item.Title)
                .SequenceEqual(["Aardvark Band", "ABBA", "The Beatles", "Beyoncé"]),
            "artists are sorted linguistically without media-only artists");
        LibraryCollectionPage albums = await controller.GetCollectionsAsync("albums", "", 0, 60);
        Ensure(albums.Items.Select(item => item.Title)
                .SequenceEqual(["Abbey Road", "Arrival", "Élan", "I Am"]),
            "albums sort accented titles with their base letters");
        Ensure((await controller.GetCollectionsAsync("artists", "beyonce", 0, 60)).Total == 1,
            "collection search ignores accents");
        Ensure((await controller.GetLibraryAsync("", 0, 60)).Total == 4,
            "the library index leaves out media too");
    }

    private static int TrackNumberOf(LibraryTrack track) => track.TrackNumber;

    /// <summary>An iTunes library with songs, a movie, a podcast, and iTunes' own playlists.</summary>
    public sealed class LibraryAppProbe
    {
        public LibraryAppProbe()
        {
            List<LibraryTrackProbe> all =
            [
                new(1, "Halo", "Beyoncé", "I Am", "Pop"),
                new(2, "Come Together", "The Beatles", "Abbey Road", "Rock"),
                new(3, "Trailer", "Studio", "Film", "Movie"),
                new(4, "Episode", "Host", "Show", "Podcast"),
                new(5, "Five", "Guest", "Élan", "Pop", "Aardvark Band"),
                new(6, "Dancing Queen", "ABBA", "Arrival", "Pop"),
            ];
            LibraryPlaylist = new PlaylistProbe("Library", 2, -1, all, kind: 1);
            RoadTrip = new PlaylistProbe("Road Trip", 20, 0, [all[0], all[1], all[4]]);
            LibrarySource = new PlaylistListProbe(
            [
                new PlaylistProbe("Music", 10, 6, all),
                new PlaylistProbe("Movies", 11, 7, [all[2]]),
                new PlaylistProbe("Podcasts", 12, 3, [all[3]]),
                new PlaylistProbe("Genius", 13, 11, []),
                new PlaylistProbe("Folder", 14, 4, []),
                RoadTrip,
                new PlaylistProbe("Purchased", 21, 1, [all[5]]),
                new PlaylistProbe("Empty", 22, 0, []),
            ]);
        }

        public PlaylistProbe LibraryPlaylist { get; }
        public PlaylistProbe RoadTrip { get; }
        public PlaylistListProbe LibrarySource { get; }

        [IndexerName("ITObjectPersistentIDLow")]
        public int this[object track] => ((LibraryTrackProbe)track).TrackDatabaseID;

        public object? GetITObjectByID(int source, int playlist, int track, int database) =>
            track == 0
                ? LibrarySource.All.FirstOrDefault(item => item.PlaylistID == playlist)
                : LibraryPlaylist.Tracks.FirstOrDefault(item => item.TrackID == track);
    }

    public sealed class PlaylistListProbe(List<PlaylistProbe> playlists)
    {
        public List<PlaylistProbe> All => playlists;
        public PlaylistListProbe Playlists => this;
        public int Count => playlists.Count;
        public PlaylistProbe Item(int index) => playlists[index - 1];
    }

    public sealed class PlaylistProbe(string name, int playlistId, int specialKind,
        List<LibraryTrackProbe> tracks, int kind = 2)
    {
        public string Name => name;
        public int Kind => kind;
        public int SpecialKind => specialKind >= 0 ? specialKind
            : throw new InvalidOperationException("The library playlist has no special kind");
        public int SourceID { get; } = 1;
        public int PlaylistID => playlistId;
        public double Duration => tracks.Count * 100.0;
        public double Size => tracks.Count * 1000.0;
        public TrackListProbe Tracks { get; } = new(tracks);
        public IEnumerable<LibraryTrackProbe> Search(string term, int field) => tracks;
    }

    public sealed class TrackListProbe(List<LibraryTrackProbe> tracks) : IEnumerable<LibraryTrackProbe>
    {
        public int Count => tracks.Count;
        public LibraryTrackProbe Item(int index) => tracks[index - 1];
        public LibraryTrackProbe? ItemByPersistentID(int high, int low) =>
            tracks.FirstOrDefault(track => track.TrackDatabaseID == low);
        public void Reverse() => tracks.Reverse();
        public LibraryTrackProbe? FirstOrDefault(Func<LibraryTrackProbe, bool> match) =>
            tracks.FirstOrDefault(match);
        public IEnumerator<LibraryTrackProbe> GetEnumerator() => tracks.GetEnumerator();
        System.Collections.IEnumerator System.Collections.IEnumerable.GetEnumerator() =>
            GetEnumerator();
    }

    public sealed class LibraryTrackProbe(int id, string name, string artist, string album,
        string genre, string albumArtist = "")
    {
        public int SourceID { get; } = 1;
        public int PlaylistID { get; } = 2;
        public int TrackID => id;
        public int TrackDatabaseID => id;
        public int TrackNumber => id;
        public int DiscNumber { get; } = 1;
        public int Index => id;
        public double Duration { get; } = 100;
        public string Name => name;
        public string Artist => artist;
        public string Album => album;
        public string Genre => genre;
        public string AlbumArtist => albumArtist;
        public bool Compilation { get; }
    }

    private static async Task TestErrorContractAsync(string directory)
    {
        MutableTimeProvider clock = new(DateTimeOffset.Parse("2026-01-01T00:00:00Z",
            CultureInfo.InvariantCulture));
        BridgeSecurity security = new(directory, "123456", clock);
        Ensure(security.TryPair("123456", "contract-phone-a-000001", "Phone A", out string phoneA),
            "error contract pairing");
        Ensure(security.TryPair(security.PairCode, "contract-phone-b-000002", "Phone B",
            out string phoneB), "error contract second pairing");
        using BridgeTlsIdentity identity = new(directory);
        using FaultMedia media = new();
        using PlaybackStateHub hub = new(media);
        int port = FreePort();
        using BridgeServer server = new(security, identity, media, hub,
            new BridgeOptions(port, 0, true, true, false, "123456", directory));
        server.Start();
        Ensure(File.ReadAllText(Path.Combine(directory, "diagnostics.log"))
                .Contains("bridge.start\t" + BridgeProtocol.ProductVersion, StringComparison.Ordinal),
            "the bridge records each start with its version");
        using HttpClient client = TrustingClient(identity, port);
        client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", phoneA);

        (Exception Failure, int Status, string Code, string? RetryAfter)[] cases =
        [
            (new OperationCanceledException(), 503, "busy", "2"),
            (new ItunesWorkerException(ItunesWorkerFailureCategory.Timeout, "slow"), 503, "busy", "2"),
            (new ItunesWorkerException(ItunesWorkerFailureCategory.ItunesBusy, "busy"), 503,
                "itunes_busy", "3"),
            (new ItunesWorkerException(ItunesWorkerFailureCategory.ItunesTerminated, "gone"), 503,
                "itunes_unavailable", null),
            (new MediaUnavailableException("Open iTunes on this computer to continue"), 503,
                "itunes_unavailable", null),
            (new MediaNotFoundException("That playlist is no longer available"), 404, "not_found", null),
            (new InvalidOperationException("bug"), 500, "bridge_error", null),
        ];
        foreach ((Exception failure, int status, string code, string? retryAfter) in cases)
        {
            media.CollectionFailure = failure;
            using HttpResponseMessage response = await client.GetAsync(
                "/api/collections?kind=albums&offset=0&limit=10");
            using JsonDocument body = JsonDocument.Parse(await response.Content.ReadAsByteArrayAsync());
            Ensure((int)response.StatusCode == status
                   && body.RootElement.GetProperty("code").GetString() == code
                   && body.RootElement.GetProperty("error").GetString()!.Length > 0
                   && response.Headers.RetryAfter?.Delta?.TotalSeconds.ToString(CultureInfo.InvariantCulture)
                       == retryAfter,
                $"{failure.GetType().Name} becomes {status} {code}");
        }
        media.CollectionFailure = null;
        Ensure((await client.GetAsync("/api/collections?kind=albums")).IsSuccessStatusCode,
            "the connection keeps working after an error response");
        await EnsureCodeAsync(await client.GetAsync("/api/nowhere"), 404, "not_found");
        await EnsureCodeAsync(await client.GetAsync("/api/collections?kind=nope"), 400, "invalid_request");

        using (HttpClient anonymous = TrustingClient(identity, port))
        {
            await EnsureCodeAsync(await anonymous.GetAsync("/api/state"), 401, "not_paired");
            security.PairingOpen = false;
            for (int attempt = 0; attempt < 6; attempt++)
                await EnsureCodeAsync(await PostJsonAsync(anonymous, "/api/pair", new
                {
                    code = "000000",
                    clientId = "contract-phone-c-000003",
                    deviceName = "Closed"
                }), 403, "pairing_closed");
            security.PairingOpen = true;
            await EnsureCodeAsync(await PostJsonAsync(anonymous, "/api/pair", new
            {
                code = security.PairCode == "000000" ? "111111" : "000000",
                clientId = "contract-phone-c-000003",
                deviceName = "Wrong"
            }), 403, "pairing_code_incorrect");
            await EnsureCodeAsync(await PostJsonAsync(anonymous, "/api/pair", new
            {
                code = security.PairCode,
                clientId = "contract-phone-c-000003",
                deviceName = "Third"
            }), 409, "pairing_device_limit");
            await EnsureCodeAsync(await PostJsonAsync(anonymous, "/api/pair", new { code = "1" }),
                400, "invalid_request");
        }

        // A sweep of other phones' selections must not record them as recently used.
        DateTimeOffset phoneBSeen = security.Devices.Single(device => device.Name == "Phone B").LastSeenAt;
        using (HttpRequestMessage play = new(HttpMethod.Post, "/api/play"))
        {
            play.Headers.Authorization = new AuthenticationHeaderValue("Bearer", phoneB);
            play.Content = JsonContent("""{"trackId":"track-000001","sequence":1}""");
            Ensure((await client.SendAsync(play)).IsSuccessStatusCode, "phone B plays");
        }
        clock.Advance(TimeSpan.FromMinutes(10));
        Ensure((await PostJsonAsync(client, "/api/play", new { trackId = "track-000002", sequence = 1 }))
            .IsSuccessStatusCode, "phone A plays");
        Ensure(security.Devices.Single(device => device.Name == "Phone B").LastSeenAt == phoneBSeen
               && security.Devices.Single(device => device.Name == "Phone A").LastSeenAt > phoneBSeen,
            "one phone's play does not update another phone's last use");
        using (HttpResponseMessage stale = await PostJsonAsync(client, "/api/play",
                   new { trackId = "track-000003", sequence = 1 }))
            await EnsureCodeAsync(stale, 409, "superseded");

        (int infoStatus, string infoHeaders) = await RawExchangeAsync(port,
            "GET /api/info HTTP/1.1\r\nHost: localhost\r\n\r\n");
        Ensure(infoStatus == 200 && infoHeaders.Contains("Connection: keep-alive", StringComparison.Ordinal)
               && infoHeaders.Contains("Keep-Alive: timeout=120, max=63", StringComparison.Ordinal),
            "keep-alive advertises the idle timeout and requests the bridge will honour");
        (int closeStatus, string closeHeaders) = await RawExchangeAsync(port,
            "GET /api/info HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
        Ensure(closeStatus == 200 && closeHeaders.Contains("Connection: close", StringComparison.Ordinal)
               && !closeHeaders.Contains("Keep-Alive", StringComparison.Ordinal),
            "a closing response does not advertise keep-alive");
        (int conflictStatus, string conflictHeaders) = await RawExchangeAsync(port,
            "POST /api/play HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer " + phoneA
            + "\r\nContent-Length: 41\r\n\r\n{\"trackId\":\"track-000004\",\"sequence\":1}  ");
        Ensure(conflictStatus == 409 && conflictHeaders.StartsWith("HTTP/1.1 409 Conflict\r\n",
                StringComparison.Ordinal),
            "a conflict is sent with its reason phrase");

        // Revocation reaches an open stream at once rather than at the next heartbeat.
        using HttpResponseMessage stream = await client.SendAsync(
            new HttpRequestMessage(HttpMethod.Get, "/api/state/stream"),
            HttpCompletionOption.ResponseHeadersRead);
        using StreamReader events = new(await stream.Content.ReadAsStreamAsync(), Encoding.UTF8);
        (string initial, _) = await ReadSseEventAsync(events, TimeSpan.FromSeconds(5));
        Ensure(initial == "state", "revocation stream opened");
        Ensure(security.TryForgetToken(phoneA).Changed, "phone A revoked");
        (string revoked, _) = await ReadSseEventAsync(events, TimeSpan.FromSeconds(3));
        Ensure(revoked == "unauthorized", "revocation ends an open stream without waiting for a heartbeat");
    }

    private static async Task EnsureCodeAsync(HttpResponseMessage response, int status, string code)
    {
        using (response)
        {
            using JsonDocument body = JsonDocument.Parse(await response.Content.ReadAsByteArrayAsync());
            Ensure((int)response.StatusCode == status
                   && body.RootElement.GetProperty("code").GetString() == code
                   && body.RootElement.GetProperty("error").GetString()!.Length > 0,
                $"{status} responses carry code {code}");
        }
    }

    private static ByteArrayContent JsonContent(string json)
    {
        ByteArrayContent content = new(Encoding.UTF8.GetBytes(json));
        content.Headers.ContentType = new MediaTypeHeaderValue("application/json");
        return content;
    }

    private static HttpClient TrustingClient(BridgeTlsIdentity identity, int port)
    {
        HttpClientHandler handler = new()
        {
            ServerCertificateCustomValidationCallback = (_, certificate, _, _) =>
                certificate is not null && identity.Matches(certificate)
        };
        return new HttpClient(handler, disposeHandler: true)
        {
            BaseAddress = new Uri($"https://127.0.0.1:{port}"),
            Timeout = TimeSpan.FromSeconds(15)
        };
    }

    private sealed class FaultMedia : IMediaController
    {
        private readonly ReleaseProbeMedia inner = new();
        public volatile Exception? CollectionFailure;
        public TaskCompletionSource? LibraryGate;
        private int librariesStarted;
        private int librariesEnded;
        public int LibrariesStarted => Volatile.Read(ref librariesStarted);
        public int LibrariesEnded => Volatile.Read(ref librariesEnded);

        public Task<PlaybackState> GetStateAsync(CancellationToken cancellationToken = default) =>
            inner.GetStateAsync(cancellationToken);

        public async Task<LibraryPage> GetLibraryAsync(string query, int offset, int limit,
            CancellationToken cancellationToken = default)
        {
            Interlocked.Increment(ref librariesStarted);
            try
            {
                // Without a gate the request waits until the phone gives up.
                Task release = LibraryGate?.Task ?? Task.Delay(Timeout.Infinite, cancellationToken);
                await release.WaitAsync(cancellationToken);
                return await inner.GetLibraryAsync(query, offset, limit, cancellationToken);
            }
            finally { Interlocked.Increment(ref librariesEnded); }
        }

        public Task<LibraryCollectionPage> GetCollectionsAsync(string kind, string query, int offset,
            int limit, CancellationToken cancellationToken = default) =>
            CollectionFailure is { } failure
                ? Task.FromException<LibraryCollectionPage>(failure)
                : inner.GetCollectionsAsync(kind, query, offset, limit, cancellationToken);

        public Task<LibraryPage> GetCollectionTracksAsync(string kind, string id, string query,
            int offset, int limit, CancellationToken cancellationToken = default) =>
            inner.GetCollectionTracksAsync(kind, id, query, offset, limit, cancellationToken);
        public Task PlayTrackAsync(PlaybackSelection selection,
            CancellationToken cancellationToken = default) => Task.CompletedTask;
        public Task ExecuteAsync(PlayerCommand command, CancellationToken cancellationToken = default) =>
            Task.CompletedTask;
        public Task<ArtworkData?> GetArtworkAsync(string id, int maxSize,
            CancellationToken cancellationToken = default) => Task.FromResult<ArtworkData?>(null);
        public void Dispose() => inner.Dispose();
    }

    private static async Task TestDisconnectsReleaseSlotsAsync(string directory)
    {
        BridgeSecurity security = new(directory, "123456");
        Ensure(security.TryPair("123456", "disconnect-phone-000001", "Phone", out string bearer),
            "disconnect test pairing");
        using BridgeTlsIdentity identity = new(directory);
        using FaultMedia media = new();
        using PlaybackStateHub hub = new(media);
        int port = FreePort();
        using BridgeServer server = new(security, identity, media, hub,
            new BridgeOptions(port, 0, true, true, false, "123456", directory));
        server.Start();
        string library = "GET /api/library?offset=0&limit=1 HTTP/1.1\r\nHost: localhost\r\n"
            + "Authorization: Bearer " + bearer + "\r\n\r\n";

        // A phone that abandons its requests must get its connection slots back.
        List<(TcpClient Tcp, SslStream Tls)> abandoned = [];
        for (int index = 0; index < 16; index++)
        {
            (TcpClient tcp, SslStream tls) = await ConnectTlsAsync(port);
            await tls.WriteAsync(Encoding.ASCII.GetBytes(library));
            await tls.FlushAsync();
            abandoned.Add((tcp, tls));
        }
        await WaitUntilAsync(() => media.LibrariesStarted == 16, "sixteen library requests started");
        (int overStatus, string overHeaders) = await RawExchangeAsync(port, library);
        Ensure(overStatus == 503 && overHeaders.Contains("Retry-After: 2", StringComparison.Ordinal)
               && overHeaders.Contains("\"code\":\"busy\"", StringComparison.Ordinal),
            "a phone over its connection allowance is told to retry");
        foreach ((TcpClient tcp, SslStream tls) in abandoned)
        {
            tls.Dispose();
            tcp.Dispose();
        }
        await WaitUntilAsync(() => media.LibrariesEnded == 16 && server.ActiveConnectionCountForTest == 0,
            "closing abandoned requests cancels their work and frees their slots");
        (int infoStatus, _) = await RawExchangeAsync(port,
            "GET /api/info HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
        Ensure(infoStatus == 200, "a new connection works after abandoned ones close");

        // A request pipelined behind a long one is kept, not consumed by the disconnect watch.
        media.LibraryGate = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        (TcpClient pipelineTcp, SslStream pipeline) = await ConnectTlsAsync(port);
        using (pipelineTcp)
        using (pipeline)
        {
            int started = media.LibrariesStarted;
            await pipeline.WriteAsync(Encoding.ASCII.GetBytes(library));
            await pipeline.FlushAsync();
            await WaitUntilAsync(() => media.LibrariesStarted == started + 1, "gated library started");
            await pipeline.WriteAsync(Encoding.ASCII.GetBytes(
                "GET /api/info HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"));
            await pipeline.FlushAsync();
            await Task.Delay(100);
            media.LibraryGate.SetResult();
            using CancellationTokenSource timeout = new(TimeSpan.FromSeconds(10));
            Ensure(await ReadRawResponseAsync(pipeline, timeout.Token) == HttpStatusCode.OK
                   && await ReadRawResponseAsync(pipeline, timeout.Token) == HttpStatusCode.OK,
                "a request sent during a long request is answered after it");
        }

        // A state stream ends when the phone hangs up, not at the next failed heartbeat.
        await WaitUntilAsync(() => server.ActiveConnectionCountForTest == 0, "pipeline closed");
        (TcpClient streamTcp, SslStream stream) = await ConnectTlsAsync(port);
        await stream.WriteAsync(Encoding.ASCII.GetBytes("GET /api/state/stream HTTP/1.1\r\n"
            + "Host: localhost\r\nAuthorization: Bearer " + bearer + "\r\n\r\n"));
        await stream.FlushAsync();
        byte[] opened = new byte[256];
        _ = await stream.ReadAsync(opened);
        Ensure(server.ActiveConnectionCountForTest == 1, "state stream open");
        stream.Dispose();
        streamTcp.Dispose();
        Stopwatch closing = Stopwatch.StartNew();
        await WaitUntilAsync(() => server.ActiveConnectionCountForTest == 0, "state stream released");
        Ensure(closing.Elapsed < BridgeProtocol.SseHeartbeatInterval,
            "a closed state stream frees its slot before the next heartbeat");
    }

    private static async Task TestDiscoveryBindFailureAsync(string directory)
    {
        using Socket occupied = new(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp)
        {
            ExclusiveAddressUse = true
        };
        occupied.Bind(new IPEndPoint(IPAddress.Any, 0));
        int discoveryPort = ((IPEndPoint)occupied.LocalEndPoint!).Port;
        BridgeSecurity security = new(directory, "123456");
        using BridgeTlsIdentity identity = new(directory);
        using DemoController media = new();
        using PlaybackStateHub hub = new(media);
        int port = FreePort();
        using BridgeServer server = new(security, identity, media, hub,
            new BridgeOptions(port, discoveryPort, true, true, false, "123456", directory));
        server.Start();
        (int status, _) = await RawExchangeAsync(port,
            "GET /api/info HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
        Ensure(!server.DiscoveryAvailable && status == 200,
            "a busy discovery port leaves HTTPS serving phones");
    }

    private static async Task<(TcpClient Tcp, SslStream Tls)> ConnectTlsAsync(int port)
    {
        using CancellationTokenSource timeout = new(TimeSpan.FromSeconds(10));
        TcpClient tcp = new();
        await tcp.ConnectAsync(IPAddress.Loopback, port, timeout.Token);
        SslStream tls = new(tcp.GetStream(), false, (_, certificate, _, _) => certificate is not null);
        await tls.AuthenticateAsClientAsync(new SslClientAuthenticationOptions
        {
            TargetHost = "localhost",
            EnabledSslProtocols = SslProtocols.Tls12 | SslProtocols.Tls13,
            CertificateRevocationCheckMode = X509RevocationMode.NoCheck
        }, timeout.Token);
        return (tcp, tls);
    }

    /// <summary>Sends one raw request and returns the status and the full response text.</summary>
    private static async Task<(int Status, string Response)> RawExchangeAsync(int port, string request)
    {
        (TcpClient tcp, SslStream tls) = await ConnectTlsAsync(port);
        using (tcp)
        using (tls)
        {
            using CancellationTokenSource timeout = new(TimeSpan.FromSeconds(10));
            await tls.WriteAsync(Encoding.ASCII.GetBytes(request), timeout.Token);
            await tls.FlushAsync(timeout.Token);
            using MemoryStream received = new();
            byte[] buffer = new byte[4096];
            while (true)
            {
                int read = await tls.ReadAsync(buffer, timeout.Token);
                if (read == 0) break;
                received.Write(buffer, 0, read);
                string text = Encoding.UTF8.GetString(received.ToArray());
                int headerEnd = text.IndexOf("\r\n\r\n", StringComparison.Ordinal);
                if (headerEnd < 0) continue;
                string lengthLine = text[..headerEnd].Split("\r\n").FirstOrDefault(line =>
                    line.StartsWith("Content-Length:", StringComparison.OrdinalIgnoreCase)) ?? "";
                int length = lengthLine.Length > 0
                    ? int.Parse(lengthLine.Split(':')[1].Trim(), CultureInfo.InvariantCulture) : 0;
                if (Encoding.UTF8.GetByteCount(text) >= Encoding.UTF8.GetByteCount(text[..headerEnd]) + 4 + length)
                    break;
            }
            string response = Encoding.UTF8.GetString(received.ToArray());
            return (int.Parse(response.Split(' ', 3)[1], CultureInfo.InvariantCulture), response);
        }
    }
}
