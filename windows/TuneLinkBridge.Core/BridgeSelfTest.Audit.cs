using System.Diagnostics;
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
        await TestProgressiveQueueAsync(Path.Combine(root, "queue-progressive"));
        await TestQueueAbandonedByNewerSelectionAsync(Path.Combine(root, "queue-abandon"));
        await TestQueueSurvivesFailedReplacementAsync(Path.Combine(root, "queue-failed-replacement"));
        await TestPreviousAndModeChangesAsync(Path.Combine(root, "queue-previous"));
        await TestModeChangesInPlaceAsync(Path.Combine(root, "queue-in-place"));
        await TestRepeatOffAfterWrappingAsync(Path.Combine(root, "queue-repeat-wrapped"));
        await TestExpiredIndexRefreshesInBackgroundAsync(Path.Combine(root, "index-refresh"));
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
        using ItunesController controller = new(directory, itunesProcesses: () => []);
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

    private const string QueueAlbumTitle = "Big Album";

    private static string QueueAlbumId() => ItunesCollectionId.EncodeText("albums",
        LibraryGrouping.AlbumKey("Artist", QueueAlbumTitle));

    private static string QueueTrackId(int trackId) =>
        ItunesTrackId.Encode(new ItunesTrackLocator(1, 2, trackId, trackId));

    private static PlaybackSelection QueueSelection(int trackId) =>
        new(QueueTrackId(trackId), "albums", QueueAlbumId());

    // Never lets a fake-COM test reach the real iTunes on this computer: with no iTunes process
    // reported, a released fake cannot be replaced by a live connection.
    private static ItunesController FakeComController(string directory, object app,
        TimeProvider? timeProvider = null, Func<ItunesController.ItunesProcess[]>? processes = null)
    {
        ItunesController controller = new(directory, timeProvider: timeProvider,
            itunesProcesses: processes ?? (() => []));
        typeof(ItunesController).GetField("itunes", BindingFlags.Instance | BindingFlags.NonPublic)!
            .SetValue(controller, app);
        return controller;
    }

    private static async Task WaitUntilAsync(Func<bool> condition, string step)
    {
        Stopwatch waited = Stopwatch.StartNew();
        while (!condition() && waited.Elapsed < TimeSpan.FromSeconds(10)) await Task.Delay(10);
        Ensure(condition(), step);
    }

    private static async Task TestExpiredIndexRefreshesInBackgroundAsync(string directory)
    {
        QueueAppProbe app = new(ItunesController.LibraryRefreshBatch * 3);
        MutableTimeProvider clock = new(DateTimeOffset.UtcNow);
        using ItunesController controller = FakeComController(directory, app, clock);
        LibraryCollectionPage first = await controller.GetCollectionsAsync("albums", "", 0, 60);
        Ensure(first.Total == 1, "the first index is read before browsing");

        app.LibraryPlaylist.Tracks.Add(new AlbumSearchTrack("Second Artist", "Second Album", 1000));
        clock.Advance(ItunesController.LibrarySnapshotLifetime + TimeSpan.FromMinutes(1));
        LibraryCollectionPage stale = await controller.GetCollectionsAsync("albums", "", 0, 60);
        Ensure(stale.Total == 1 && stale.Revision == first.Revision,
            "an expired index still answers while its replacement is read");
        await WaitUntilAsync(() => controller.GetCollectionsAsync("albums", "", 0, 60)
                .GetAwaiter().GetResult().Total == 2,
            "the replacement index is read in the background and then served");
        LibraryPage songs = await controller.GetLibraryAsync("", 0, 60);
        Ensure(songs.Total == ItunesController.LibraryRefreshBatch * 3 + 1,
            "songs come from the refreshed index");
    }

    private static async Task TestProgressiveQueueAsync(string directory)
    {
        QueueAppProbe app = new(130);
        app.Missing.Add(10);
        using (ItunesController controller = FakeComController(directory, app))
        {
            await controller.PlayTrackAsync(QueueSelection(1));
            QueuePlaylistProbe queue = app.Created.Single();
            Ensure(queue.AddedBeforePlay == ItunesController.FirstQueueChunk,
                "a large collection starts playing after its first songs are queued");
            await WaitUntilAsync(() => queue.AddedCount == 129, "the rest of the queue is appended");
            // Runs behind the batch that added the last song, so the final save has happened.
            await controller.ExecuteAsync(new PlayerCommand("volume", 40));
            int[] queued = queue.TrackIds();
            Ensure(queued.SequenceEqual(Enumerable.Range(1, 130).Where(id => id != 10)),
                "the queue keeps collection order across batches and skips a deleted song");

            await controller.ExecuteAsync(new PlayerCommand("repeat", 1));
            Ensure(app.Created.Count == 1 && queue.SongRepeat == 1,
                "repeat one is a playlist setting, not a queue rebuild");
            await controller.ExecuteAsync(new PlayerCommand("repeat", 0));
            Ensure(app.Created.Count == 1 && queue.SongRepeat == 0, "repeat off needs no rebuild");

            app.Missing.Add(7);
            try
            {
                await controller.PlayTrackAsync(QueueSelection(7));
                throw new InvalidOperationException("Self-test failed: expected a missing selection");
            }
            catch (MediaNotFoundException) { }
            Ensure(app.Created.Count == 2 && app.Created[1].Deleted && !queue.Deleted,
                "a deleted selected song fails without replacing the playing queue");
        }
        using ItunesController restarted = new(directory, itunesProcesses: () => []);
        object? recovered = typeof(ItunesController).GetMethod("LoadManagedQueue",
            BindingFlags.Instance | BindingFlags.NonPublic)!.Invoke(restarted, null);
        Ensure(recovered is not null,
            "a queue that skipped a deleted song still survives a worker restart");
    }

    private static async Task TestQueueAbandonedByNewerSelectionAsync(string directory)
    {
        QueueAppProbe app = new(130);
        using ItunesController controller = FakeComController(directory, app);
        using ManualResetEventSlim gate = new(false);
        app.CreateGate = gate;
        // Both selections are queued before the first starts, so the first queue's remaining
        // batches can only run after the newer selection.
        Task first = controller.PlayTrackAsync(QueueSelection(1));
        Task second = controller.PlayTrackAsync(QueueSelection(50));
        gate.Set();
        await Task.WhenAll(first, second);
        QueuePlaylistProbe abandoned = app.Created[0];
        QueuePlaylistProbe current = app.Created[1];
        await WaitUntilAsync(() => current.AddedCount == 81, "the newer queue is completed");
        await controller.ExecuteAsync(new PlayerCommand("volume", 40));
        Ensure(abandoned.AddedCount == ItunesController.FirstQueueChunk && abandoned.Deleted,
            "a newer selection abandons the older queue's remaining songs");
        Ensure(current.TrackIds().SequenceEqual(Enumerable.Range(50, 81)),
            "the newer queue is built from its own selection");
    }

    private static async Task TestQueueSurvivesFailedReplacementAsync(string directory)
    {
        foreach (bool cancelReplacement in new[] { false, true })
        {
            QueueAppProbe app = new(130);
            if (!cancelReplacement) app.Missing.Add(7);
            using ItunesController controller = FakeComController(
                Path.Combine(directory, cancelReplacement ? "cancelled" : "missing"), app);
            using CancellationTokenSource cancellation = new();
            using ManualResetEventSlim gate = new(false);
            app.CreateGate = gate;
            app.PlaylistCreated = () =>
            {
                // Cancel inside activation, after the replacement playlist exists.
                if (cancelReplacement && app.Created.Count == 2) cancellation.Cancel();
            };
            // Keep the replacement ahead of the first queue's continuation batches.
            Task first = controller.PlayTrackAsync(QueueSelection(1));
            Task second = controller.PlayTrackAsync(QueueSelection(7), cancellation.Token);
            gate.Set();
            await first;
            try
            {
                await second;
                throw new InvalidOperationException("Self-test failed: expected a failed replacement");
            }
            catch (MediaNotFoundException) when (!cancelReplacement) { }
            catch (OperationCanceledException) when (cancelReplacement) { }

            QueuePlaylistProbe original = app.Created[0];
            int[] expected = [.. Enumerable.Range(1, 130).Where(id => !app.Missing.Contains(id))];
            await WaitUntilAsync(() => original.TrackIds().SequenceEqual(expected),
                "a failed or cancelled replacement lets the original queue finish building");
            await controller.ExecuteAsync(new PlayerCommand("volume", 40));
            Ensure(ReferenceEquals(app.CurrentPlaylist, original) && original.PlayCount == 1
                && !original.Deleted && app.Created[1].Deleted,
                "a failed or cancelled replacement leaves the original playback intact");
        }
    }

    private static async Task TestPreviousAndModeChangesAsync(string directory)
    {
        QueueAppProbe app = new(40);
        using ItunesController controller = FakeComController(directory, app);
        await controller.PlayTrackAsync(QueueSelection(3));
        await WaitUntilAsync(() => app.Created[0].AddedCount == 38, "the first queue is completed");

        app.PlayerPosition = 10;
        await controller.ExecuteAsync(new PlayerCommand("previous", null));
        Ensure(app.PlayerPosition == 0 && app.Created.Count == 1 && app.PreviousTrackCalls == 0,
            "previous restarts a song that has played for a few seconds");

        app.PlayerPosition = 1;
        app.Missing.Add(2);
        await controller.ExecuteAsync(new PlayerCommand("previous", null));
        Ensure(app.Created.Count == 2 && app.Created[1].TrackIds()[0] == 1,
            "previous near the start goes back, past a song deleted since browsing");

        QueuePlaylistProbe queue = app.Created[1];
        await WaitUntilAsync(() => queue.AddedCount == 39, "the queue from song 1 is completed");
        await controller.ExecuteAsync(new PlayerCommand("shuffle", 1));
        await controller.ExecuteAsync(new PlayerCommand("volume", 40));
        Ensure(app.Created.Count == 2 && queue.PlayCount == 1 && queue.Shuffle
            && queue.TrackIds().Order().SequenceEqual(Enumerable.Range(1, 40).Where(id => id != 2)),
            "shuffle is turned on in place, without restarting the song");

        await controller.ExecuteAsync(new PlayerCommand("repeat", 2));
        Ensure(app.Created.Count == 2 && queue.SongRepeat == 2,
            "repeat all under shuffle keeps the queue, which already holds every song");
        await controller.ExecuteAsync(new PlayerCommand("shuffle", 0));
        Ensure(app.Created.Count == 2 && queue.PlayCount == 1 && !queue.Shuffle,
            "turning shuffle off keeps the song playing");
    }

    private static async Task TestModeChangesInPlaceAsync(string directory)
    {
        QueueAppProbe app = new(40);
        using ItunesController controller = FakeComController(directory, app);
        await controller.PlayTrackAsync(QueueSelection(10));
        QueuePlaylistProbe queue = app.Created.Single();
        await WaitUntilAsync(() => queue.AddedCount == 31, "the queue from song 10 is completed");

        await controller.ExecuteAsync(new PlayerCommand("repeat", 2));
        await controller.ExecuteAsync(new PlayerCommand("volume", 40));
        Ensure(app.Created.Count == 1 && queue.PlayCount == 1 && queue.SongRepeat == 2
            && queue.TrackIds().SequenceEqual([.. Enumerable.Range(10, 31), .. Enumerable.Range(1, 9)]),
            "repeat all adds the songs before the selection after the rest, in place");

        // Playing song 15, then repeat off: the wrapped songs after the collection end go.
        app.CurrentTrack = queue.Entries()[5].Track;
        await controller.ExecuteAsync(new PlayerCommand("repeat", 0));
        await controller.ExecuteAsync(new PlayerCommand("volume", 40));
        Ensure(app.Created.Count == 1 && queue.PlayCount == 1
            && queue.TrackIds().SequenceEqual(Enumerable.Range(10, 31)),
            "repeat off removes the wrapped songs without restarting");

        await controller.ExecuteAsync(new PlayerCommand("shuffle", 1));
        await controller.ExecuteAsync(new PlayerCommand("volume", 40));
        int[] shuffled = queue.TrackIds();
        Ensure(queue.Shuffle && shuffled.Take(31).SequenceEqual(Enumerable.Range(10, 31))
            && shuffled.Order().SequenceEqual(Enumerable.Range(1, 40)),
            "shuffle keeps the queued songs and adds the rest");

        // Shuffle off on a song in the ordered part keeps it and drops the shuffled additions.
        app.CurrentTrack = queue.Entries()[20].Track;
        await controller.ExecuteAsync(new PlayerCommand("shuffle", 0));
        await controller.ExecuteAsync(new PlayerCommand("volume", 40));
        Ensure(app.Created.Count == 1 && queue.PlayCount == 1 && !queue.Shuffle
            && queue.TrackIds().SequenceEqual(Enumerable.Range(10, 31)),
            "shuffle off keeps the ordered songs and removes the shuffled additions");

        // A queue built shuffled has no collection-order history, so shuffle off keeps only the
        // current song and continues in collection order after it.
        await controller.ExecuteAsync(new PlayerCommand("shuffle", 1));
        await controller.PlayTrackAsync(QueueSelection(10));
        QueuePlaylistProbe random = app.Created[1];
        await WaitUntilAsync(() => random.AddedCount == 40, "the shuffled queue is completed");
        await controller.ExecuteAsync(new PlayerCommand("volume", 40));
        int[] ids = random.TrackIds();
        // Prefer a song whose successor is in the shuffled history that gets dropped: that song
        // must be queued again, not passed over as if it had left the library.
        int[] order = [.. ids.Select(id => id - 1)];
        int[] candidates = [.. Enumerable.Range(2, ids.Length - 2)
            .Where(index => !ItunesController.IsCollectionOrder(order, index))];
        int position = candidates.FirstOrDefault(index => ids.Take(index).Contains(ids[index] + 1),
            candidates[0]);
        app.CurrentTrack = random.Entries()[position].Track;
        int current = ids[position];
        await controller.ExecuteAsync(new PlayerCommand("shuffle", 0));
        await WaitUntilAsync(() => random.TrackIds().Length == 41 - current, "the ordered queue is completed");
        await controller.ExecuteAsync(new PlayerCommand("volume", 40));
        Ensure(random.PlayCount == 1 && !random.Shuffle
            && random.TrackIds().SequenceEqual(Enumerable.Range(current, 41 - current)),
            "shuffle off continues in collection order from the current song");

        Ensure(ItunesController.IsCollectionOrder([5, 6, 9, 1, 3], 4)
            && !ItunesController.IsCollectionOrder([5, 6, 2, 7], 3)
            && !ItunesController.IsCollectionOrder([5, 1, 2, 6], 3)
            && !ItunesController.IsCollectionOrder([5, 7, 6], 2),
            "collection order allows a single wrap to songs before the first");
    }

    private static async Task TestRepeatOffAfterWrappingAsync(string directory)
    {
        foreach (int repeat in new[] { 0, 1 })
            foreach (bool playing in new[] { false, true })
            {
                QueueAppProbe app = new(40);
                using ItunesController controller = FakeComController(
                    Path.Combine(directory, $"repeat-{repeat}-playing-{playing}"), app);
                await controller.PlayTrackAsync(QueueSelection(10));
                QueuePlaylistProbe playlist = app.Created.Single();
                await WaitUntilAsync(() => playlist.AddedCount == 31, "the original queue completes");
                await controller.ExecuteAsync(new PlayerCommand("repeat", 2));
                await WaitUntilAsync(() => playlist.AddedCount == 40, "repeat all queues the wrapped songs");

                AlbumSearchTrack current = playlist.Entries().Single(entry => entry.TrackID == 5).Track;
                app.CurrentTrack = current;
                app.PlayerPosition = 74.5;
                app.PlayerState = playing ? 1 : 0;
                await controller.ExecuteAsync(new PlayerCommand("repeat", repeat));
                await WaitUntilAsync(() => playlist.TrackIds().SequenceEqual(Enumerable.Range(5, 36)),
                    "leaving repeat all after wrapping queues through the collection end");
                // Repeat one must retain the same remaining songs when subsequently switched off.
                if (repeat == 1) await controller.ExecuteAsync(new PlayerCommand("repeat", 0));
                await controller.ExecuteAsync(new PlayerCommand("volume", 40));
                Ensure(playlist.TrackIds().SequenceEqual(Enumerable.Range(5, 36))
                    && playlist.SongRepeat == 0 && app.Created.Count == 1 && playlist.PlayCount == 1
                    && ReferenceEquals(app.CurrentTrack, current) && ReferenceEquals(app.CurrentPlaylist, playlist)
                    && app.PlayerPosition == 74.5 && app.PlayerState == (playing ? 1 : 0),
                    "repeat off after wrapping preserves the current song, position and pause state");
            }
    }

    /// <summary>A minimal iTunes for managed-queue tests: one album of numbered songs.</summary>
    public sealed class QueueAppProbe
    {
        public QueueAppProbe(int trackCount)
        {
            LibraryPlaylist.Tracks.Clear();
            for (int id = 1; id <= trackCount; id++)
                LibraryPlaylist.Tracks.Add(new AlbumSearchTrack("Artist", QueueAlbumTitle, id));
            LibrarySource = new QueueSourceProbe(this);
        }

        public AlbumSearchProbe LibraryPlaylist { get; } = new();
        public QueueSourceProbe LibrarySource { get; }
        public List<QueuePlaylistProbe> Created { get; } = [];
        public HashSet<int> Missing { get; } = [];
        public ManualResetEventSlim? CreateGate { get; set; }
        public Action? PlaylistCreated { get; set; }
        public object? CurrentPlaylist { get; set; }
        public object? CurrentTrack { get; set; }
        public double PlayerPosition { get; set; }
        public int PlayerState { get; set; }
        public int SoundVolume { get; set; }
        public int PreviousTrackCalls { get; private set; }

        public object CreatePlaylist(string name)
        {
            CreateGate?.Wait(TimeSpan.FromSeconds(10));
            QueuePlaylistProbe playlist = new(this, name, 1000 + Created.Count);
            Created.Add(playlist);
            PlaylistCreated?.Invoke();
            return playlist;
        }

        public object? GetITObjectByID(int source, int playlist, int track, int database) =>
            Missing.Contains(track) ? null : LibraryPlaylist.Tracks.FirstOrDefault(item => item.TrackID == track);

        public void Pause() => PlayerState = 0;
        public void PreviousTrack() => PreviousTrackCalls++;
    }

    public sealed class QueueSourceProbe(QueueAppProbe app)
    {
        public QueueSourceProbe Playlists => this;
        public int Count => app.Created.Count;
        public object Item(int index) => app.Created[index - 1];
    }

    public sealed class QueuePlaylistProbe(QueueAppProbe app, string name, int playlistId)
    {
        private readonly List<QueueEntryProbe> entries = [];
        private int added;
        public string Name { get; } = name;
        public int PlaylistID { get; } = playlistId;
        public int Kind { get; } = 2;
        public bool Shuffle { get; set; }
        public int SongRepeat { get; set; }
        public bool Deleted { get; private set; }
        public int AddedBeforePlay { get; private set; }
        public int AddedCount => Volatile.Read(ref added);
        public int PlayCount { get; private set; }
        public QueueEntriesProbe Tracks => new(this);

        public object AddTrack(object track)
        {
            QueueEntryProbe entry = new(this, (AlbumSearchTrack)track);
            lock (entries) entries.Add(entry);
            Interlocked.Increment(ref added);
            return entry;
        }

        public int[] TrackIds()
        {
            lock (entries) return [.. entries.Select(entry => entry.TrackID)];
        }

        public QueueEntryProbe[] Entries()
        {
            lock (entries) return [.. entries];
        }

        internal void Remove(QueueEntryProbe entry)
        {
            lock (entries) entries.Remove(entry);
        }

        public void PlayFirstTrack()
        {
            AddedBeforePlay = AddedCount;
            PlayCount++;
            app.CurrentPlaylist = this;
            lock (entries) app.CurrentTrack = entries[0].Track;
            app.PlayerState = 1;
            app.PlayerPosition = 0;
        }

        public void Delete() => Deleted = true;
    }

    public sealed class QueueEntriesProbe(QueuePlaylistProbe playlist) : IEnumerable<QueueEntryProbe>
    {
        public int Count => playlist.Entries().Length;
        public QueueEntryProbe Item(int index) => playlist.Entries()[index - 1];
        public IEnumerator<QueueEntryProbe> GetEnumerator() =>
            ((IEnumerable<QueueEntryProbe>)playlist.Entries()).GetEnumerator();
        System.Collections.IEnumerator System.Collections.IEnumerable.GetEnumerator() => GetEnumerator();
    }

    /// <summary>A song's entry in a queue playlist; deleting it leaves the library song.</summary>
    public sealed class QueueEntryProbe(QueuePlaylistProbe playlist, AlbumSearchTrack track)
    {
        public AlbumSearchTrack Track { get; } = track;
        public int TrackID => Track.TrackID;
        public void Delete() => playlist.Remove(this);
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
