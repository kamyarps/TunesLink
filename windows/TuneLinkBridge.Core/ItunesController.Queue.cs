using System.Diagnostics;
using System.Text.Json;

namespace TunesLinkBridge;

internal sealed partial class ItunesController
{
    // Order is the whole planned queue; TrackIndices maps each song already added to the iTunes
    // playlist (by its TrackID there) to its position in Tracks. While a queue is still being
    // built, or after songs deleted from the library were skipped, it covers part of Order.
    private sealed record ManagedQueue(int PlaylistId, string Name, string Kind, string Filter,
        QueueTrack[] Tracks, int[] Order, Dictionary<int, int> TrackIndices)
    {
        // -1 permits recovery of records written before the cursor was persisted.
        public int Next { get; set; } = -1;
    }

    // The rest of a queue after the songs added before playback started.
    private sealed class QueueBuild(int generation, object playlist, ManagedQueue queue, int next,
        long startedAt)
    {
        public int Generation { get; } = generation;
        public object Playlist { get; } = playlist;
        public ManagedQueue Queue { get; } = queue;
        public int Next { get; set; } = next;
        public long StartedAt { get; } = startedAt;
        public long SavedAt { get; set; } = startedAt;
        public int BusyRetries { get; set; }
    }

    private sealed class QueueRecovery(int generation, object playlist, object entries,
        ManagedQueue queue, int count)
    {
        public int Generation { get; } = generation;
        public object Playlist { get; } = playlist;
        public object Entries { get; } = entries;
        public ManagedQueue Queue { get; } = queue;
        public int Count { get; } = count;
        public int Next { get; set; }
        public int Frontier { get; set; }
        public int BusyRetries { get; set; }
        public Dictionary<int, int> Indices { get; } = [];
    }

    // Adding a song to a playlist costs several milliseconds of COM work, so a large collection
    // cannot be copied before it starts playing. The first songs are added before playback, the
    // rest in small batches queued behind other requests so state and commands stay responsive.
    internal const int FirstQueueChunk = 20;
    internal const int QueueChunk = 25;
    private const int MaxQueueBusyRetries = 3;
    private static readonly TimeSpan QueueSaveInterval = TimeSpan.FromSeconds(1);

    private readonly string queueStatePath;
    private readonly IAtomicFilePersistence queuePersistence;
    // Changes whenever the queue is replaced or dropped, which abandons a build still underway.
    private int queueGeneration;
    private QueueBuild? queueBuild;
    private QueueRecovery? queueRecovery;
    private bool queueRecoveryChecked;

    // A selected song must be the first entry passed to PlayFirstTrack: Track.Play leaves
    // Up Next at its previous cursor, and walking NextTrack is too slow for large collections.
    internal static int[] QueueOrder(int count, int selected, bool shuffle, string repeat) =>
        selected < 0 || selected >= count ? throw new ArgumentOutOfRangeException(nameof(selected))
        : [.. Enumerable.Range(selected, count - selected),
            .. (shuffle || repeat == "all" ? Enumerable.Range(0, selected) : [])];

    private void PlayManagedCollection(object appObject, string trackId, string kind,
        string collectionId, CancellationToken cancellationToken)
    {
        if (!ItunesCollectionId.IsValidText(collectionId, kind))
            throw new MediaNotFoundException("That collection is no longer available");
        string filter = collectionId;
        long started = Stopwatch.GetTimestamp();
        QueueTrack[] tracks;
        try
        {
            tracks = [.. LibraryGrouping.InCollectionOrder(
                SelectCollectionTracks(appObject, kind, filter, cancellationToken),
                item => item.Album, item => item.AlbumArtist,
                item => item.DiscNumber, item => item.TrackNumber, item => item.OriginalIndex)];
        }
        finally
        {
            BridgeDiagnostics.RecordDuration("play.collection.select",
                (long)Stopwatch.GetElapsedTime(started).TotalMilliseconds);
        }
        PlayManagedSelection(appObject, tracks, trackId, kind, filter, cancellationToken);
    }

    private void PlayManagedPlaylist(object appObject, string trackId, string collectionId,
        CancellationToken cancellationToken)
    {
        if (!ItunesCollectionId.TryDecodePlaylist(collectionId, out ItunesPlaylistLocator locator))
            throw new MediaNotFoundException("That playlist is no longer available");
        dynamic? playlist = null;
        dynamic? sourceTracks = null;
        try
        {
            playlist = ResolvePlaylist(appObject, locator)
                ?? throw new MediaNotFoundException("That playlist is no longer available");
            sourceTracks = playlist.Tracks;
            List<QueueTrack> tracks = [];
            long started = Stopwatch.GetTimestamp();
            try
            {
                foreach (object track in ComItems((object?)sourceTracks))
                {
                    try
                    {
                        cancellationToken.ThrowIfCancellationRequested();
                        // A playlist's songs carry the playlist's own source and playlist IDs, so
                        // only the two per-song IDs are read to form the ID browsing showed.
                        tracks.Add(new QueueTrack(ItunesTrackId.Encode(new ItunesTrackLocator(
                                locator.SourceId, locator.PlaylistId, ReadInt(track, "TrackID"),
                                ReadInt(track, "TrackDatabaseID"))),
                            "", "", 0, 0, tracks.Count));
                    }
                    finally { ReleaseCom(track); }
                }
            }
            finally
            {
                BridgeDiagnostics.RecordDuration("play.collection.select",
                    (long)Stopwatch.GetElapsedTime(started).TotalMilliseconds);
            }
            PlayManagedSelection(appObject, [.. tracks], trackId, "playlists", collectionId,
                cancellationToken);
        }
        finally { ReleaseCom(sourceTracks); ReleaseCom(playlist); }
    }

    private void PlayManagedSelection(object appObject, QueueTrack[] tracks, string trackId,
        string kind, string filter, CancellationToken cancellationToken)
    {
        int selected = Array.FindIndex(tracks, track => track.Id == trackId);
        if (selected < 0) throw new MediaNotFoundException("That song is no longer in this collection");
        (bool shuffle, string repeat) = ReadPlaybackModes(appObject);
        ActivateManagedQueue(appObject, tracks, selected, kind, filter, shuffle, repeat,
            0, true, cancellationToken);
    }

    private void ActivateManagedQueue(object appObject, QueueTrack[] tracks, int selected,
        string kind, string filter, bool shuffle, string repeat, double position, bool playing,
        CancellationToken cancellationToken)
    {
        dynamic app = appObject;
        dynamic? playlist = null;
        bool activated = false;
        long started = Stopwatch.GetTimestamp();
        string name = managedQueuePrefix + Guid.NewGuid().ToString("N")[..8];
        int[] order = QueueOrder(tracks.Length, selected, shuffle, repeat);
        // Songs appended after playback starts may join the end of iTunes' own shuffle order, so a
        // shuffled queue is built in a random order rather than relying on iTunes alone.
        if (shuffle) Random.Shared.Shuffle(order.AsSpan(1));
        Dictionary<int, int> indices = [];
        try
        {
            playlist = app.CreatePlaylist(name);
            int next = 0;
            AppendQueueTracks(appObject, (object)playlist, tracks, order, ref next,
                FirstQueueChunk, indices, cancellationToken);
            SetProperty(playlist, "Shuffle", false);
            SetProperty(playlist, "SongRepeat", repeat == "one" ? 1 : repeat == "all" ? 2 : 0);
            cancellationToken.ThrowIfCancellationRequested();
            // Never change persistent, global mute while in a killable COM worker. A hung
            // PlayFirstTrack call can prevent any finally block from restoring the user's setting.
            playlist.PlayFirstTrack();
            if (!playing) app.Pause();
            if (position > 0) app.PlayerPosition = position;
            SetProperty(playlist, "Shuffle", shuffle);
            activated = true;
            int playlistId = ReadInt((object)playlist, "PlaylistID");
            // A failed or cancelled replacement must leave the playing queue's build intact.
            int generation = AbandonQueueBuild();
            managedQueue = new(playlistId, name, kind, filter, tracks, order, indices) { Next = next };
            queueRecoveryChecked = true;
            SaveManagedQueue();
            CleanupManagedQueues(appObject, playlistId);
            if (next < order.Length)
            {
                QueueBuild build = new(generation, (object)playlist, managedQueue, next, started);
                queueBuild = build;
                playlist = null;
                EnqueueInternal(() => ContinueQueueBuild(build));
            }
        }
        finally
        {
            BridgeDiagnostics.RecordDuration("play.queue.activate",
                (long)Stopwatch.GetElapsedTime(started).TotalMilliseconds);
            if (!activated && playlist is not null)
            {
                try { playlist.Delete(); } catch { }
            }
            ReleaseCom(playlist);
        }
    }

    /// <summary>
    /// Adds up to <paramref name="count"/> songs to the queue playlist, starting at
    /// <c>order[next]</c>, and leaves <paramref name="next"/> after the last song handled, even
    /// when a call fails part way. A song deleted since the collection was read is skipped; only
    /// the selected song, the first in the order, has to exist.
    /// </summary>
    private static void AppendQueueTracks(object appObject, object playlistObject,
        QueueTrack[] tracks, int[] order, ref int next, int count, Dictionary<int, int> indices,
        CancellationToken cancellationToken)
    {
        dynamic app = appObject;
        dynamic playlist = playlistObject;
        for (int added = 0; next < order.Length && added < count; next++)
        {
            cancellationToken.ThrowIfCancellationRequested();
            int index = order[next];
            dynamic? source = null;
            dynamic? copy = null;
            try
            {
                source = ResolveTrack(app, tracks[index].Id);
                if (source is null)
                {
                    if (next == 0) throw new MediaNotFoundException("That song is no longer available");
                    continue;
                }
                copy = playlist.AddTrack(source);
                indices[ReadInt((object)copy, "TrackID")] = index;
                added++;
            }
            finally { ReleaseCom(copy); ReleaseCom(source); }
        }
    }

    // Runs as its own work item, so requests that arrived meanwhile run between batches.
    private void ContinueQueueBuild(QueueBuild build)
    {
        if (!ReferenceEquals(queueBuild, build) || build.Generation != queueGeneration) return;
        if (disposed)
        {
            AbandonQueueBuild();
            return;
        }
        int next = build.Next;
        try
        {
            AppendQueueTracks((object)GetITunes(), build.Playlist, build.Queue.Tracks,
                build.Queue.Order, ref next, QueueChunk, build.Queue.TrackIndices,
                CancellationToken.None);
        }
        catch (System.Runtime.InteropServices.COMException exception)
            when (build.BusyRetries < MaxQueueBusyRetries
                  && ItunesWorkerProtocol.ClassifyComFailure(exception.HResult)
                      == ItunesWorkerFailureCategory.ItunesBusy)
        {
            // A dialog in iTunes; try this batch again after the requests now waiting.
            build.Next = next;
            build.BusyRetries++;
            EnqueueInternal(() => ContinueQueueBuild(build));
            return;
        }
        catch (Exception exception)
        {
            // The songs already queued keep playing; the queue only ends sooner.
            BridgeDiagnostics.Record("play.queue.append", exception);
            build.Next = next;
            SaveManagedQueue();
            AbandonQueueBuild();
            return;
        }
        build.Next = next;
        bool complete = next >= build.Queue.Order.Length;
        if (complete || Stopwatch.GetElapsedTime(build.SavedAt) >= QueueSaveInterval)
        {
            SaveManagedQueue();
            build.SavedAt = Stopwatch.GetTimestamp();
        }
        if (!complete)
        {
            EnqueueInternal(() => ContinueQueueBuild(build));
            return;
        }
        BridgeDiagnostics.RecordDuration("play.queue.complete",
            (long)Stopwatch.GetElapsedTime(build.StartedAt).TotalMilliseconds);
        queueBuild = null;
        ReleaseCom(build.Playlist);
    }

    /// <summary>Stops any queue build still underway and returns the new queue generation.</summary>
    private int AbandonQueueBuild()
    {
        if (queueRecovery is { } recovery)
        {
            queueRecovery = null;
            ReleaseCom(recovery.Entries);
            ReleaseCom(recovery.Playlist);
        }
        if (queueBuild is { } build)
        {
            queueBuild = null;
            ReleaseCom(build.Playlist);
        }
        return ++queueGeneration;
    }

    // Only interactive requests call this: the separate library worker shares the persisted
    // record but must not race the playback worker to append the same songs.
    private void RecoverManagedQueue(object appObject)
    {
        if (queueRecoveryChecked || queueBuild is not null) return;
        managedQueue ??= LoadManagedQueue();
        if (managedQueue is not { } queue) return;
        if (queue.Next == queue.Order.Length) { queueRecoveryChecked = true; return; }
        dynamic app = appObject;
        dynamic? playlist = null;
        dynamic? entries = null;
        try
        {
            playlist = app.CurrentPlaylist;
            if (playlist is null || ReadInt((object)playlist, "PlaylistID") != queue.PlaylistId
                || ReadString((object)playlist, "Name") != queue.Name) return;
            entries = playlist.Tracks;
            int count = Convert.ToInt32(entries.Count);
            if (count <= 0 || count > queue.Order.Length) return;
            QueueRecovery recovery = new(queueGeneration, (object)playlist, (object)entries, queue, count);
            queueRecovery = recovery;
            queueRecoveryChecked = true;
            playlist = null;
            entries = null;
            EnqueueInternal(() => ContinueQueueRecovery(recovery));
        }
        catch (Exception exception)
        {
            // Recovery is optional; busy iTunes can be retried on the next interactive request.
            BridgeDiagnostics.Record("play.queue.recover", exception);
        }
        finally { ReleaseCom(entries); ReleaseCom(playlist); }
    }

    // Native playlists can be large too. Reconcile them in bounded work items so a restart
    // does not turn the first state/volume request into a whole-playlist COM scan.
    private void ContinueQueueRecovery(QueueRecovery recovery)
    {
        if (!ReferenceEquals(queueRecovery, recovery) || recovery.Generation != queueGeneration) return;
        if (disposed) { AbandonQueueBuild(); return; }
        ManagedQueue queue = recovery.Queue;
        try
        {
            dynamic entries = recovery.Entries;
            int end = Math.Min(recovery.Count, recovery.Next + QueueChunk);
            while (recovery.Next < end)
            {
                object entry = entries.Item(recovery.Next + 1);
                try
                {
                    dynamic nativeEntry = entry;
                    // Required IDs must propagate busy COM failures to the retry path rather
                    // than turning a temporarily unreadable entry into an invalid playlist.
                    int id = Convert.ToInt32(nativeEntry.TrackID);
                    int databaseId = Convert.ToInt32(nativeEntry.TrackDatabaseID);
                    // Reconcile native entries written since the last save. Their persistent
                    // database IDs survive the playlist-specific TrackID assigned by AddTrack.
                    int match = recovery.Frontier;
                    while (match < queue.Order.Length)
                    {
                        int index = queue.Order[match];
                        ItunesTrackId.TryDecode(queue.Tracks[index].Id, out ItunesTrackLocator locator);
                        if (queue.TrackIndices.TryGetValue(id, out int savedIndex)
                            ? savedIndex == index && locator.DatabaseId == databaseId
                            : databaseId > 0 && locator.DatabaseId == databaseId) break;
                        match++;
                    }
                    // An edited or foreign playlist is not safe to extend from this record.
                    if (match == queue.Order.Length || id <= 0 || !recovery.Indices.TryAdd(id, queue.Order[match]))
                    {
                        AbandonQueueBuild();
                        return;
                    }
                    recovery.Frontier = match + 1;
                    recovery.Next++;
                }
                finally { ReleaseCom(entry); }
            }
            if (recovery.Next < recovery.Count)
            {
                EnqueueInternal(() => ContinueQueueRecovery(recovery));
                return;
            }
            int next = Math.Max(recovery.Frontier, queue.Next);
            managedQueue = queue with { TrackIndices = recovery.Indices, Next = next };
            SaveManagedQueue();
            queueRecovery = null;
            ReleaseCom(recovery.Entries);
            if (next < queue.Order.Length)
            {
                QueueBuild build = new(queueGeneration, recovery.Playlist, managedQueue, next,
                    Stopwatch.GetTimestamp());
                queueBuild = build;
                EnqueueInternal(() => ContinueQueueBuild(build));
            }
            else ReleaseCom(recovery.Playlist);
        }
        catch (System.Runtime.InteropServices.COMException exception)
            when (recovery.BusyRetries < MaxQueueBusyRetries
                  && ItunesWorkerProtocol.ClassifyComFailure(exception.HResult)
                      == ItunesWorkerFailureCategory.ItunesBusy)
        {
            recovery.BusyRetries++;
            EnqueueInternal(() => ContinueQueueRecovery(recovery));
        }
        catch (Exception exception)
        {
            BridgeDiagnostics.Record("play.queue.recover", exception);
            AbandonQueueBuild();
        }
    }

    private bool TryActiveQueue(object appObject, out ManagedQueue active, out int index)
    {
        // Previous and mode changes need a complete native-ID map before they can retarget
        // the playlist. These commands already have the longer playback deadline; state and
        // volume requests continue to yield between recovery batches.
        while (queueRecovery is { } recovery) ContinueQueueRecovery(recovery);
        managedQueue ??= LoadManagedQueue();
        active = managedQueue!;
        index = -1;
        if (active is null) return false;
        dynamic app = appObject;
        dynamic? playlist = null;
        dynamic? track = null;
        try
        {
            playlist = app.CurrentPlaylist;
            track = app.CurrentTrack;
            return playlist is not null && track is not null
                && ReadInt((object)playlist, "PlaylistID") == active.PlaylistId
                && ReadString((object)playlist, "Name") == active.Name
                && active.TrackIndices.TryGetValue(ReadInt((object)track, "TrackID"), out index);
        }
        finally { ReleaseCom(track); ReleaseCom(playlist); }
    }

    private bool TryManagedPrevious(object appObject, CancellationToken cancellationToken)
    {
        if (!TryActiveQueue(appObject, out ManagedQueue active, out int index)) return false;
        (bool shuffle, string repeat) = ReadPlaybackModes(appObject);
        if (shuffle || index == 0 || active.Order[0] != index || active.Order.Length == active.Tracks.Length)
            return false;
        // The song before may have been deleted since the collection was read.
        int previous = index - 1;
        while (previous >= 0 && !TrackExists(appObject, active.Tracks[previous].Id)) previous--;
        if (previous < 0) return false;
        dynamic app = appObject;
        ActivateManagedQueue(appObject, active.Tracks, previous, active.Kind, active.Filter,
            shuffle, repeat, 0, Convert.ToInt32(app.PlayerState) == 1, cancellationToken);
        return true;
    }

    private static bool TrackExists(object appObject, string trackId)
    {
        dynamic? track = ResolveTrack(appObject, trackId);
        try { return track is not null; }
        finally { ReleaseCom(track); }
    }

    private bool TryChangeManagedModes(object appObject, bool? requestedShuffle, int? requestedRepeat,
        CancellationToken cancellationToken)
    {
        if (!TryActiveQueue(appObject, out ManagedQueue active, out int index)) return false;
        (bool shuffle, string repeat) = ReadPlaybackModes(appObject);
        bool originalShuffle = shuffle;
        string originalRepeat = repeat;
        shuffle = requestedShuffle ?? shuffle;
        repeat = requestedRepeat is { } value ? value == 1 ? "one" : value == 2 ? "all" : "off" : repeat;
        if (shuffle == originalShuffle && repeat == originalRepeat) return true;
        // The queue holds the songs before the selection only with shuffle or repeat all, and its
        // order is shuffled only if shuffle was on when it was built. Any other change is a
        // playlist setting, so the queue, and a build still underway, stay as they are.
        if ((shuffle || repeat == "all") == (originalShuffle || originalRepeat == "all")
            && !(originalShuffle && !shuffle))
        {
            if (shuffle != originalShuffle) SetCurrentPlaylistProperty(appObject, "Shuffle", shuffle);
            if (repeat != originalRepeat)
                SetCurrentPlaylistProperty(appObject, "SongRepeat",
                    repeat == "one" ? 1 : repeat == "all" ? 2 : 0);
            return true;
        }
        RetargetManagedQueue(appObject, active, index, shuffle, originalShuffle, repeat,
            cancellationToken);
        return true;
    }

    // Changes which songs follow the current one by editing the playing queue in place. Starting
    // a new queue would restart the song, and iTunes announces every restart as a new song.
    private void RetargetManagedQueue(object appObject, ManagedQueue active, int current,
        bool shuffle, bool originalShuffle, string repeat, CancellationToken cancellationToken)
    {
        int generation = AbandonQueueBuild();
        dynamic app = appObject;
        dynamic? playlist = null;
        try
        {
            playlist = app.CurrentPlaylist
                ?? throw new MediaUnavailableException("Playback changed while updating the queue; try again");
            // The songs now in the playlist, as positions in the collection, in playlist order.
            List<int> entries = [];
            foreach (object entry in ComItems((object?)playlist.Tracks))
            {
                try
                {
                    entries.Add(active.TrackIndices.TryGetValue(ReadInt(entry, "TrackID"),
                        out int collectionIndex) ? collectionIndex : -1);
                }
                finally { ReleaseCom(entry); }
            }
            int position = entries.IndexOf(current);
            if (position < 0 || entries.Contains(-1))
                throw new MediaUnavailableException("Playback changed while updating the queue; try again");
            // Songs the playlist holds now. One of the collection's songs missing from it was
            // deleted from the library (or never added yet) rather than dropped below.
            HashSet<int> present = [.. entries];

            SetProperty(playlist, "SongRepeat", repeat == "one" ? 1 : repeat == "all" ? 2 : 0);
            List<int> upcoming;
            if (shuffle)
            {
                // Songs already queued are reordered by iTunes' own shuffle. iTunes plays songs
                // added later after those, so the rest are added in a random order of their own.
                if (!originalShuffle) SetProperty(playlist, "Shuffle", true);
                HashSet<int> queued = [.. entries];
                upcoming = [.. Enumerable.Range(0, active.Tracks.Length).Where(index => !queued.Contains(index))];
                Random.Shared.Shuffle(System.Runtime.InteropServices.CollectionsMarshal.AsSpan(upcoming));
            }
            else
            {
                if (originalShuffle) SetProperty(playlist, "Shuffle", false);
                // Played in order, the playlist must read as the collection from its first song,
                // wrapping once. Drop shuffled history, or a completed wrap when repeat all ends,
                // so playback can continue from the current song through the collection's end.
                if (!IsCollectionOrder(entries, position) || (repeat != "all" && current < entries[0]))
                {
                    DeleteQueueEntries((object)playlist, 0, position, cancellationToken);
                    entries.RemoveRange(0, position);
                    position = 0;
                }
                int start = entries[0];
                upcoming = current < start
                    ? [.. Enumerable.Range(current + 1, start - current - 1)]
                    : [.. Enumerable.Range(current + 1, active.Tracks.Length - current - 1),
                        .. (repeat == "all" ? Enumerable.Range(0, start) : [])];
            }

            // Keep the queued songs that already follow in the right order. A planned song absent
            // from the playlist was deleted from the library and is passed over, but only when a
            // queued song matches after it. Under shuffle every queued song stays and the others
            // are only added.
            int kept = shuffle ? entries.Count : position + 1;
            int planned = 0;
            while (kept < entries.Count)
            {
                int match = planned;
                while (match < upcoming.Count && !present.Contains(upcoming[match])) match++;
                if (match >= upcoming.Count || upcoming[match] != entries[kept]) break;
                kept++;
                planned = match + 1;
            }
            DeleteQueueEntries((object)playlist, kept, entries.Count, cancellationToken);
            entries.RemoveRange(kept, entries.Count - kept);

            int[] order = [.. entries, .. upcoming.Skip(planned)];
            Dictionary<int, int> indices = active.TrackIndices
                .Where(pair => entries.Contains(pair.Value))
                .ToDictionary(pair => pair.Key, pair => pair.Value);
            managedQueue = active with { Order = order, TrackIndices = indices };
            int next = entries.Count;
            // The next song must be queued before this call returns in case this one ends soon.
            AppendQueueTracks(appObject, (object)playlist, active.Tracks, order, ref next,
                FirstQueueChunk, indices, cancellationToken);
            managedQueue.Next = next;
            SaveManagedQueue();
            if (next < order.Length)
            {
                QueueBuild build = new(generation, (object)playlist, managedQueue, next,
                    Stopwatch.GetTimestamp());
                queueBuild = build;
                playlist = null;
                EnqueueInternal(() => ContinueQueueBuild(build));
            }
        }
        finally { ReleaseCom(playlist); }
    }

    /// <summary>
    /// Whether the songs up to and including <paramref name="position"/> follow collection order
    /// from the first, wrapping to the start of the collection at most once.
    /// </summary>
    internal static bool IsCollectionOrder(IReadOnlyList<int> entries, int position)
    {
        bool wrapped = false;
        for (int index = 1; index <= position; index++)
        {
            if (entries[index] > entries[index - 1] && (!wrapped || entries[index] < entries[0]))
                continue;
            if (wrapped || entries[index] >= entries[0]) return false;
            wrapped = true;
        }
        return true;
    }

    // Removes playlist entries [from, to) (zero-based), last first so earlier positions hold.
    // iTunes invalidates a playlist's track collection once one of its entries is deleted, so
    // the collection is read again for every entry.
    private static void DeleteQueueEntries(object playlistObject, int from, int to,
        CancellationToken cancellationToken)
    {
        dynamic playlist = playlistObject;
        for (int index = to; index > from; index--)
        {
            cancellationToken.ThrowIfCancellationRequested();
            dynamic? entries = null;
            dynamic? entry = null;
            try
            {
                entries = playlist.Tracks;
                entry = entries.Item(index);
                entry?.Delete();
            }
            finally
            {
                ReleaseCom(entry);
                ReleaseCom(entries);
            }
        }
    }

    private ManagedQueue? LoadManagedQueue()
    {
        try
        {
            using FileStream stream = File.OpenRead(queueStatePath);
            if (stream.Length is <= 0 or > 16 * 1024 * 1024) return null;
            ManagedQueue? value = JsonSerializer.Deserialize<ManagedQueue>(stream);
            if (value is null || value.PlaylistId <= 0 || value.Name is null
                || !value.Name.StartsWith(managedQueuePrefix, StringComparison.Ordinal)
                || value.Kind is not ("artists" or "albums" or "genres" or "playlists")
                || value.Filter is null || value.Filter.Length > 1024
                || value.Tracks is null || value.Tracks.Length is 0 or > 100_000
                || value.Order is null || value.Order.Length is 0 || value.Order.Length > value.Tracks.Length
                || value.TrackIndices is null || value.TrackIndices.Count is 0
                || value.TrackIndices.Count > value.Order.Length
                || value.Next < -1 || value.Next > value.Order.Length
                || value.Tracks.Any(track => track is null || track.Id is null
                    || !ItunesTrackId.TryDecode(track.Id, out _))
                || value.Order.Any(index => index < 0 || index >= value.Tracks.Length)
                || value.TrackIndices.Any(pair => pair.Key <= 0)
                || value.Order.Distinct().Count() != value.Order.Length
                || value.TrackIndices.Values.Distinct().Count() != value.TrackIndices.Count
                || !value.Order.ToHashSet().IsSupersetOf(value.TrackIndices.Values))
                return null;
            return value;
        }
        catch { return null; }
    }

    private void SaveManagedQueue()
    {
        try
        {
            if (managedQueue is null || managedQueue.Tracks.Length > 100_000) return;
            if (queueBuild is { } build && ReferenceEquals(build.Queue, managedQueue))
                managedQueue.Next = build.Next;
            string json = JsonSerializer.Serialize(managedQueue);
            if (System.Text.Encoding.UTF8.GetByteCount(json) <= 16 * 1024 * 1024)
                queuePersistence.WriteText(queueStatePath, json);
        }
        catch { /* Playback does not depend on optional restart recovery storage. */ }
    }

    private void ClearQueueState()
    {
        try { File.Delete(queueStatePath); } catch { }
    }
}
