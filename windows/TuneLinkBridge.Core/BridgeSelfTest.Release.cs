using System.Dynamic;
using System.Net.Http.Headers;
using System.Reflection;
using System.Text.Json;

namespace TunesLinkBridge;

internal static partial class BridgeSelfTest
{
    private static async Task TestReleaseRegressionsAsync(string root)
    {
        TestAbsoluteCacheExpiry(Path.Combine(root, "cache-expiry"));
        TestAlbumPlaybackSearch(Path.Combine(root, "album-search"));
        using ReleaseProbeMedia media = new();
        using PlaybackStateHub hub = new(media);
        using CancellationTokenSource timeout = new(TimeSpan.FromSeconds(10));
        await using (PlaybackStateSubscription subscription = await hub.SubscribeAsync(timeout.Token))
        {
            PlaybackStateUpdate initial = await subscription.Reader.ReadAsync(timeout.Token);
            Ensure(initial.State.ITunesAvailable, "initial backend available");
            media.FailState = true;
            hub.Wake();
            PlaybackStateUpdate failed = await subscription.Reader.ReadAsync(timeout.Token);
            Ensure(!failed.State.ITunesAvailable && !failed.State.Playing
                && failed.State.Title == initial.State.Title && failed.Sequence > initial.Sequence,
                "worker failure reaches SSE subscribers and preserves last metadata");
            media.FailState = false;
            hub.Wake();
            PlaybackStateUpdate recovered = await subscription.Reader.ReadAsync(timeout.Token);
            Ensure(recovered.State.ITunesAvailable && recovered.Sequence > failed.Sequence,
                "worker recovery reaches SSE subscribers");
        }

        media.FailState = true;
        using (PlaybackStateHub initiallyFailed = new(media))
        {
            PlaybackState unavailable = await initiallyFailed.GetStateAsync(timeout.Token);
            Ensure(!unavailable.ITunesAvailable && unavailable.TrackId.Length == 0,
                "initial worker failure produces a usable unavailable state");
        }
        media.FailState = false;

        string directory = Path.Combine(root, "large-offsets");
        BridgeSecurity security = new(directory, "123456");
        Ensure(security.TryPair("123456", "release-regression-client", "Regression", out string token),
            "large offset test pairing");
        using BridgeTlsIdentity identity = new(directory);
        int port = FreePort();
        using BridgeServer server = new(security, identity, media, hub,
            new BridgeOptions(port, FreeUdpPort(), true, true, false, "123456", directory));
        server.Start();
        using HttpClientHandler handler = new()
        {
            ServerCertificateCustomValidationCallback = (_, certificate, _, _) =>
                certificate is not null && identity.Matches(certificate)
        };
        using HttpClient client = new(handler) { BaseAddress = new Uri($"https://127.0.0.1:{port}") };
        client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", token);
        foreach (string route in new[] {
            "/api/library?", "/api/library?collectionKind=albums&collectionId=album-001&",
            "/api/collections?kind=albums&" })
        {
            foreach (int offset in new[] { -1, 100_000, 100_060, 100_200, int.MaxValue })
            {
                using JsonDocument page = JsonDocument.Parse(await client.GetStringAsync(
                    route + "offset=" + offset + "&limit=60", timeout.Token));
                Ensure(media.LastOffset == Math.Max(0, offset), "HTTP route preserves large requested offset");
                Ensure(page.RootElement.GetProperty("offset").GetInt32() == Math.Clamp(offset, 0, 100_200),
                    "large library pages reach the requested position or the end");
                Ensure(page.RootElement.GetProperty("hasMore").GetBoolean() == (offset < 100_140),
                    "large library eventually terminates pagination");
            }
        }
    }

    private static void TestAbsoluteCacheExpiry(string directory)
    {
        DateTimeOffset createdAt = DateTimeOffset.UtcNow;
        MutableTimeProvider clock = new(createdAt);
        dynamic tracks = new ExpandoObject();
        tracks.Count = 1;
        tracks.Title = "Old title";
        dynamic playlist = new ExpandoObject();
        playlist.SourceID = 1;
        playlist.PlaylistID = 2;
        playlist.Duration = 180.0;
        playlist.Size = 1000.0;
        playlist.Tracks = tracks;
        dynamic app = new ExpandoObject();
        app.LibraryPlaylist = playlist;
        app.LibraryXMLPath = "";
        Type type = typeof(ItunesController);
        MethodInfo signatureMethod = type.GetMethod("ComputeLibrarySourceSignature",
            BindingFlags.Static | BindingFlags.NonPublic)!;
        string signature = (string)signatureMethod.Invoke(null, [(object)app, (object)playlist, (object)tracks])!;
        new LibraryIndexStore(directory).Save(new LibraryIndexData(
            [new LibraryTrack("track-001", "Old title", "Artist", "Album", 180, 1, 1, "", "Artist")],
            ["Rock"], [], [], [], new string('a', 64), signature, createdAt));
        using ItunesController controller = new(directory, timeProvider: clock);
        MethodInfo validate = type.GetMethod("ValidatePersistedLibrarySnapshot", BindingFlags.Instance | BindingFlags.NonPublic)!;
        MethodInfo current = type.GetMethod("CurrentLibrarySnapshot", BindingFlags.Instance | BindingFlags.NonPublic)!;
        clock.Advance(TimeSpan.FromMinutes(4));
        Ensure(validate.Invoke(controller, [(object)app, CancellationToken.None]) is not null,
            "recent persisted snapshot is reusable");
        tracks.Title = "Changed tag without count or file-size changes";
        Ensure(signature == (string)signatureMethod.Invoke(null, [(object)app, (object)playlist, (object)tracks])!,
            "aggregate signature alone cannot observe tag edits");
        clock.Advance(TimeSpan.FromMinutes(1));
        Ensure(current.Invoke(controller, null) is null
            && validate.Invoke(controller, [(object)app, CancellationToken.None]) is null,
            "recent validation cannot extend original snapshot expiry");

        // A separate browsing worker can replace the file while this playback worker lives.
        // The next cache miss must see that replacement without extending the old index.
        new LibraryIndexStore(directory).Save(new LibraryIndexData(
            [new LibraryTrack("track-002", "Fresh title", "Artist", "Album", 180, 1, 1, "", "Artist")],
            ["Rock"], [], [], [], new string('b', 64), signature, clock.GetUtcNow()));
        object? reloaded = validate.Invoke(controller, [(object)app, CancellationToken.None]);
        LibraryTrack[]? reloadedTracks = (LibraryTrack[]?)reloaded?.GetType()
            .GetProperty("Tracks")?.GetValue(reloaded);
        Ensure(reloadedTracks is [{ Id: "track-002" }],
            "playback worker reloads an index written by the browsing worker");

        clock.Advance(TimeSpan.FromMinutes(1));
        new LibraryIndexStore(directory).Save(new LibraryIndexData(
            [new LibraryTrack("track-003", "Another title", "Artist", "Album", 180, 1, 1, "", "Artist")],
            ["Rock"], [], [], [], new string('c', 64), signature, clock.GetUtcNow()));
        Ensure(current.Invoke(controller, null) is null,
            "a newer browsing index invalidates the playback worker's still-fresh copy");
        object? replaced = validate.Invoke(controller, [(object)app, CancellationToken.None]);
        LibraryTrack[]? replacedTracks = (LibraryTrack[]?)replaced?.GetType()
            .GetProperty("Tracks")?.GetValue(replaced);
        Ensure(replacedTracks is [{ Id: "track-003" }],
            "playback worker adopts a newer browsing index before the old copy expires");
        File.WriteAllText(Path.Combine(directory, "Cache", "library-index-v1.json"), "invalid");
        Ensure(current.Invoke(controller, null) is null
            && validate.Invoke(controller, [(object)app, CancellationToken.None]) is null
            && validate.Invoke(controller, [(object)app, CancellationToken.None]) is null,
            "an invalid replacement cannot revive the previous in-memory snapshot");

        MethodInfo saveArtwork = type.GetMethod("CacheArtwork", BindingFlags.Instance | BindingFlags.NonPublic)!;
        MethodInfo readArtwork = type.GetMethod("FreshArtwork", BindingFlags.Instance | BindingFlags.NonPublic)!;
        ArtworkData oldArtwork = new("track-001", [1, 2, 3], "image/jpeg");
        saveArtwork.Invoke(controller, ["track-001:256", oldArtwork]);
        clock.Advance(TimeSpan.FromMinutes(4));
        Ensure(ReferenceEquals(readArtwork.Invoke(controller, ["track-001:256"]), oldArtwork),
            "unexpired artwork is reused");
        clock.Advance(TimeSpan.FromMinutes(1));
        Ensure(readArtwork.Invoke(controller, ["track-001:256"]) is null,
            "frequently viewed artwork expires from its original fetch");
        ArtworkData changedArtwork = new("track-001", [4, 5, 6], "image/jpeg");
        saveArtwork.Invoke(controller, ["track-001:256", changedArtwork]);
        Ensure(ReferenceEquals(readArtwork.Invoke(controller, ["track-001:256"]), changedArtwork),
            "replacement artwork is served after expiry");
    }

    private static void TestAlbumPlaybackSearch(string directory)
    {
        AlbumSearchProbe playlist = new();
        dynamic app = new ExpandoObject();
        app.LibraryPlaylist = playlist;
        using ItunesController controller = new(directory);
        MethodInfo select = typeof(ItunesController).GetMethod("SelectCollectionTracks",
            BindingFlags.Instance | BindingFlags.NonPublic)!;
        string albumKey = LibraryGrouping.AlbumKey("Artist", "Shared Album");
        var selected = (System.Collections.IList)select.Invoke(controller,
            [(object)app, "albums", albumKey, CancellationToken.None])!;
        Ensure(playlist.SearchCount == 1 && playlist.LastSearchKind == ItunesController.SearchAlbums
            && selected.Count == 1, "album playback narrows candidates then filters exact album artist");
    }

    public sealed class AlbumSearchProbe
    {
        public int SearchCount { get; private set; }
        public int LastSearchKind { get; private set; }
        public List<AlbumSearchTrack> Tracks { get; } =
        [
            new("Artist", "Shared Album", 1),
            new("Other Artist", "Shared Album", 2),
            new("Artist", "Another Album", 3)
        ];

        public IEnumerable<AlbumSearchTrack> Search(string term, int kind)
        {
            SearchCount++;
            LastSearchKind = kind;
            return Tracks.Where(track => track.Album.Contains(term,
                StringComparison.OrdinalIgnoreCase));
        }
    }

    public sealed record AlbumSearchTrack(string Artist, string Album, int TrackID,
        int SourceID = 1, int PlaylistID = 2, int DiscNumber = 1,
        bool Compilation = false)
    {
        public string AlbumArtist => Artist;
        public int TrackDatabaseID => TrackID;
        public int TrackNumber => TrackID;
    }

    private sealed class ReleaseProbeMedia : IMediaController
    {
        public volatile bool FailState;
        public int LastOffset { get; private set; }

        public Task<PlaybackState> GetStateAsync(CancellationToken cancellationToken = default)
        {
            if (FailState) throw new IOException("Injected backend failure");
            return Task.FromResult(new PlaybackState(true, true, "Song", "Artist", "Album",
                180, 30, 50, "", "track-001", false, "off"));
        }

        public Task<LibraryPage> GetLibraryAsync(string query, int offset, int limit,
            CancellationToken cancellationToken = default)
        {
            LastOffset = offset;
            int start = Math.Clamp(offset, 0, 100_200);
            int count = Math.Min(limit, 100_200 - start);
            LibraryTrack[] tracks = Enumerable.Range(start, count).Select(index =>
                new LibraryTrack("track-" + index, "Song " + index, "Artist", "Album",
                    180, 1, 1, "", "Artist")).ToArray();
            return Task.FromResult(new LibraryPage(tracks, start, limit, 100_200, start + count < 100_200));
        }

        public async Task<LibraryCollectionPage> GetCollectionsAsync(string kind, string query,
            int offset, int limit, CancellationToken cancellationToken = default)
        {
            LibraryPage page = await GetLibraryAsync(query, offset, limit, cancellationToken);
            return new LibraryCollectionPage(page.Items.Select(track =>
                new LibraryCollection(track.Id, track.Title, track.Artist, 1, "")).ToArray(),
                page.Offset, limit, page.Total, page.HasMore);
        }

        public Task<LibraryPage> GetCollectionTracksAsync(string kind, string id, string query,
            int offset, int limit, CancellationToken cancellationToken = default) =>
            GetLibraryAsync(query, offset, limit, cancellationToken);
        public Task PlayTrackAsync(PlaybackSelection selection, CancellationToken cancellationToken = default) => Task.CompletedTask;
        public Task ExecuteAsync(PlayerCommand command, CancellationToken cancellationToken = default) => Task.CompletedTask;
        public Task<ArtworkData?> GetArtworkAsync(string id, int maxSize, CancellationToken cancellationToken = default) => Task.FromResult<ArtworkData?>(null);
        public void Dispose() { }
    }
}
