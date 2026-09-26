using System.Dynamic;
using System.Net;
using System.Net.Http.Headers;
using System.Reflection;

namespace TunesLinkBridge;

internal static partial class BridgeSelfTest
{
    private static async Task TestAuditRegressionsAsync(string root)
    {
        TestLongCollectionIds(Path.Combine(root, "long-collections"));
        TestQueueActivationDoesNotMute(Path.Combine(root, "queue-mute"));
        await TestPlaybackSupersessionAsync(Path.Combine(root, "playback-order"));
    }

    private static void TestLongCollectionIds(string directory)
    {
        foreach (string text in new[] { "Artist", new string('a', 600), new string('曲', 128),
            string.Concat(Enumerable.Repeat("e\u0301", 300)), string.Concat(Enumerable.Repeat("🎵", 300)) })
        {
            string album = LibraryGrouping.AlbumKey(text, text);
            foreach ((string kind, string value) in new[] { ("artists", text), ("albums", album), ("genres", text) })
            {
                string id = ItunesCollectionId.EncodeText(kind, value);
                Ensure(id.Length <= 1024 && ItunesCollectionId.IsValidText(id, kind),
                    "every emitted collection identifier is bounded and accepted");
                Ensure(ItunesCollectionId.MatchesText(id, kind, value)
                    && ItunesCollectionId.MatchesText(id, kind, value.ToUpperInvariant())
                    && !ItunesCollectionId.MatchesText(id, kind, value + " other"),
                    "long keys match complete metadata without truncation or case-splitting");
                Ensure(!ItunesCollectionId.IsValidText(id, "wrong-kind"), "collection kind is bound to its identifier");
            }
            AlbumSearchProbe playlist = new();
            playlist.Tracks.Clear();
            playlist.Tracks.Add(new AlbumSearchTrack(text, text, 1));
            playlist.Tracks.Add(new AlbumSearchTrack(text + " other", text, 2));
            dynamic app = new ExpandoObject();
            app.LibraryPlaylist = playlist;
            using ItunesController controller = new(directory);
            MethodInfo select = typeof(ItunesController).GetMethod("SelectCollectionTracks",
                BindingFlags.Instance | BindingFlags.NonPublic)!;
            var selected = (System.Collections.IList)select.Invoke(controller,
                [(object)app, "albums", ItunesCollectionId.EncodeText("albums", album), CancellationToken.None])!;
            Ensure(selected.Count == 1, "live COM collection matching accepts long album metadata");
        }
        Ensure(!ItunesCollectionId.IsValidText("h_albums_" + new string('z', 64), "albums"),
            "malformed collection digest is rejected");
    }

    private static void TestQueueActivationDoesNotMute(string directory)
    {
        using ItunesController controller = new(directory);
        QueueActivationProbe app = new();
        typeof(ItunesController).GetField("itunes", BindingFlags.Instance | BindingFlags.NonPublic)!
            .SetValue(controller, app);
        string id = ItunesTrackId.Encode(new ItunesTrackLocator(1, 2, 1, 1));
        try
        {
            controller.PlayTrackAsync(new PlaybackSelection(id, "albums",
                ItunesCollectionId.EncodeText("albums", LibraryGrouping.AlbumKey("Artist", "Shared Album"))))
                .GetAwaiter().GetResult();
            throw new InvalidOperationException("Expected injected queue activation failure");
        }
        catch (MediaUnavailableException)
        {
            Ensure(app.Queue.PlayAttempted && app.MuteWrites == 0 && !app.Mute && app.Queue.Deleted,
                "failed activation cleans up the queue without changing global mute");
        }
    }

    public sealed class QueueActivationProbe
    {
        public AlbumSearchProbe LibraryPlaylist { get; } = new();
        public ActivationPlaylistProbe Queue { get; } = new();
        public object? CurrentPlaylist { get; set; }
        public int MuteWrites { get; private set; }
        public bool Mute { get => false; set { MuteWrites++; } }
        public object CreatePlaylist(string name) => Queue;
        public object GetITObjectByID(int source, int playlist, int track, int database) =>
            LibraryPlaylist.Tracks.Single(item => item.TrackID == track);
    }

    public sealed class ActivationPlaylistProbe
    {
        public bool Shuffle { get; set; }
        public int SongRepeat { get; set; }
        public bool PlayAttempted { get; private set; }
        public bool Deleted { get; private set; }
        public int AddedTracks { get; private set; }
        public object AddTrack(object track)
        {
            AddedTracks++;
            return track;
        }
        public void PlayFirstTrack()
        {
            PlayAttempted = true;
            throw new MediaUnavailableException("Injected COM activation failure");
        }
        public void Delete() => Deleted = true;
    }

    private static async Task TestPlaybackSupersessionAsync(string directory)
    {
        BridgeSecurity security = new(directory, "123456");
        Ensure(security.TryPair("123456", "playback-order-client", "Regression", out string bearer),
            "playback order test pairing");
        using BridgeTlsIdentity identity = new(directory);
        using SupersessionProbeMedia media = new();
        using PlaybackStateHub hub = new(media);
        int port = FreePort();
        using BridgeServer server = new(security, identity, media, hub,
            new BridgeOptions(port, 0, true, true, false, "123456", directory));
        server.Start();
        using HttpClientHandler handler = new()
        {
            ServerCertificateCustomValidationCallback = (_, certificate, _, _) =>
                certificate is not null && identity.Matches(certificate)
        };
        using HttpClient client = new(handler)
        {
            BaseAddress = new Uri($"https://127.0.0.1:{port}"),
            Timeout = TimeSpan.FromSeconds(10)
        };
        client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", bearer);
        Task<HttpResponseMessage> first = PostJsonAsync(client, "/api/play", new { trackId = "slow-track", sequence = 1 });
        await media.Started.Task.WaitAsync(TimeSpan.FromSeconds(5));
        using HttpResponseMessage second = await PostJsonAsync(client, "/api/play", new { trackId = "new-track", sequence = 2 });
        using HttpResponseMessage superseded = await first;
        Ensure(second.StatusCode == HttpStatusCode.OK && superseded.StatusCode == HttpStatusCode.Conflict
            && media.Played.SequenceEqual(["new-track"]), "new selection cancels slow accepted playback before activation");
        using HttpResponseMessage stale = await PostJsonAsync(client, "/api/play", new { trackId = "late-track", sequence = 1 });
        Ensure(stale.StatusCode == HttpStatusCode.Conflict && media.Played.Count == 1,
            "a reordered older request cannot overwrite the newer selection");
        using HttpResponseMessage cancel = await PostJsonAsync(client, "/api/play/cancel", new { sequence = 3 });
        using HttpResponseMessage canceledBeforeArrival = await PostJsonAsync(client, "/api/play", new { trackId = "late-track", sequence = 3 });
        Ensure(cancel.StatusCode == HttpStatusCode.OK && canceledBeforeArrival.StatusCode == HttpStatusCode.Conflict,
            "cancellation arriving before playback leaves a rejection watermark");

        media.Started = new(TaskCreationOptions.RunContinuationsAsynchronously);
        Task<HttpResponseMessage> abandoned = PostJsonAsync(client, "/api/play", new { trackId = "slow-track", sequence = 4 });
        await media.Started.Task.WaitAsync(TimeSpan.FromSeconds(5));
        using HttpResponseMessage abort = await PostJsonAsync(client, "/api/play/cancel", new { sequence = 4 });
        using HttpResponseMessage aborted = await abandoned;
        Ensure(abort.StatusCode == HttpStatusCode.OK && aborted.StatusCode == HttpStatusCode.Conflict
            && media.Played.Count == 1, "explicit cancellation stops accepted work without requiring another selection");
        using HttpResponseMessage invalid = await PostJsonAsync(client, "/api/play/cancel", new { sequence = 0 });
        Ensure(invalid.StatusCode == HttpStatusCode.BadRequest, "invalid cancellation is rejected");
        using HttpResponseMessage legacy = await PostJsonAsync(client, "/api/play", new { trackId = "old-phone" });
        Ensure(legacy.StatusCode == HttpStatusCode.OK, "existing clients without sequence numbers remain compatible");

        PlaybackRequests requests = new(_ => true);
        using PlaybackRequests.Lease one = requests.Begin("phone-one", 1, CancellationToken.None)!;
        using PlaybackRequests.Lease two = requests.Begin("phone-two", 1, CancellationToken.None)!;
        requests.Cancel("phone-one", 1);
        Ensure(one.Token.IsCancellationRequested && !two.Token.IsCancellationRequested,
            "one phone cannot cancel the other phone's selection by sequence");
    }

    private sealed class SupersessionProbeMedia : IMediaController
    {
        private readonly DemoController demo = new();
        public TaskCompletionSource Started = new(TaskCreationOptions.RunContinuationsAsynchronously);
        public List<string> Played { get; } = [];
        public async Task PlayTrackAsync(PlaybackSelection selection, CancellationToken cancellationToken = default)
        {
            if (selection.TrackId == "slow-track")
            {
                Started.TrySetResult();
                await Task.Delay(Timeout.Infinite, cancellationToken);
            }
            cancellationToken.ThrowIfCancellationRequested();
            Played.Add(selection.TrackId);
        }
        public Task<PlaybackState> GetStateAsync(CancellationToken cancellationToken = default) => demo.GetStateAsync(cancellationToken);
        public Task<LibraryPage> GetLibraryAsync(string query, int offset, int limit, CancellationToken cancellationToken = default) => demo.GetLibraryAsync(query, offset, limit, cancellationToken);
        public Task<LibraryCollectionPage> GetCollectionsAsync(string kind, string query, int offset, int limit, CancellationToken cancellationToken = default) => demo.GetCollectionsAsync(kind, query, offset, limit, cancellationToken);
        public Task<LibraryPage> GetCollectionTracksAsync(string kind, string id, string query, int offset, int limit, CancellationToken cancellationToken = default) => demo.GetCollectionTracksAsync(kind, id, query, offset, limit, cancellationToken);
        public Task ExecuteAsync(PlayerCommand command, CancellationToken cancellationToken = default) => demo.ExecuteAsync(command, cancellationToken);
        public Task<ArtworkData?> GetArtworkAsync(string id, int maxSize, CancellationToken cancellationToken = default) => demo.GetArtworkAsync(id, maxSize, cancellationToken);
        public void Dispose() => demo.Dispose();
    }
}
