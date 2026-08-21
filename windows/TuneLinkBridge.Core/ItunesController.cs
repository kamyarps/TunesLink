using System.Collections.Concurrent;
using System.Drawing.Imaging;
using System.Globalization;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;

namespace TunesLinkBridge;

internal sealed class ItunesController : IMediaController
{
    internal const int SearchAllFields = 0;
    internal const int SearchAlbums = 3;
    internal const int MaxArtworkSourceBytes = 8 * 1024 * 1024;
    internal const int MaxArtworkCacheBytes = 24 * 1024 * 1024;
    internal static readonly TimeSpan LibrarySnapshotLifetime = TimeSpan.FromMinutes(5);
    internal static readonly TimeSpan PersistedLibrarySnapshotLifetime = TimeSpan.FromHours(24);
    private const int MaxArtworkDimension = 4096;
    private const long MaxArtworkPixels = 16_000_000;
    private const string ManagedQueuePrefix =
        "TunesLink Playback Queue [managed-7f4d6b21]-";
    private sealed class WorkItem
    {
        public required Func<object?> Action { get; init; }
        public required TaskCompletionSource<object?> Completion { get; init; }
        public required CancellationToken CancellationToken { get; init; }
    }

    private sealed class CollectionAccumulator(string title, string subtitle, string artworkId)
    {
        public string Title { get; } = title;
        public string Subtitle { get; } = subtitle;
        public string ArtworkId { get; } = artworkId;
        public int TrackCount { get; set; }
    }

    // TrackGenres is parallel to Tracks. It stays out of LibraryTrack so it is never paid for on
    // the wire, and it lets genre filtering be a string compare instead of re-deriving each key
    // per request.
    private sealed record LibrarySnapshot(
        LibraryTrack[] Tracks,
        string[] TrackGenres,
        LibraryCollection[] Artists,
        LibraryCollection[] Albums,
        LibraryCollection[] Genres,
        string Revision,
        string SourceSignature,
        DateTimeOffset CreatedAt,
        DateTimeOffset? ValidatedAt);

    private sealed record QueueTrack(
        string Id,
        string Album,
        string AlbumArtist,
        int DiscNumber,
        int TrackNumber,
        int OriginalIndex);

    private sealed record ManagedQueue(int PlaylistId, string Kind, string Filter);

    internal static readonly TimeSpan MissingArtworkLifetime = TimeSpan.FromMinutes(5);
    private const int MaxMissingArtworkEntries = 512;

    private readonly BlockingCollection<WorkItem> queue = new();
    private readonly Thread staThread;
    private readonly Dictionary<string, ArtworkData> artworkCache = new(StringComparer.Ordinal);
    private readonly Queue<string> artworkCacheOrder = new();
    private readonly Dictionary<string, DateTimeOffset> missingArtwork =
        new(StringComparer.Ordinal);
    private long artworkCacheBytes;
    private LibrarySnapshot? librarySnapshot;
    private ManagedQueue? managedQueue;
    private readonly LibraryIndexStore libraryIndexStore;
    private dynamic? itunes;
    private bool disposed;

    public ItunesController(string? configDirectory = null,
                            IAtomicFilePersistence? persistence = null)
    {
        string directory = configDirectory ?? BrandPaths.UserConfigDirectory();
        libraryIndexStore = new LibraryIndexStore(directory, persistence);
        librarySnapshot = LoadPersistedLibrarySnapshot(libraryIndexStore);
        staThread = new Thread(RunSta)
        {
            IsBackground = true,
            Name = "iTunes automation"
        };
        staThread.SetApartmentState(ApartmentState.STA);
        staThread.Start();
    }

    public Task<PlaybackState> GetStateAsync(CancellationToken cancellationToken = default) => Invoke<PlaybackState>(() =>
    {
        try
        {
            dynamic app = GetITunes();
            bool playing = Convert.ToInt32(app.PlayerState) == 1;
            int soundVolume = Math.Clamp(Convert.ToInt32(app.SoundVolume), 0, 100);
            (bool shuffleEnabled, string repeatMode) = ReadPlaybackModes((object)app);
            dynamic? track = null;
            try
            {
                track = app.CurrentTrack;
                if (track is null)
                    return EmptyState(true, playing, soundVolume);
                string title = ReadString(track, "Name");
                string artist = DisplayArtist(ReadString(track, "Artist"));
                string album = DisplayAlbum(ReadString(track, "Album"));
                double duration = Math.Max(0, ReadDouble(track, "Duration"));
                double position = Math.Clamp(Convert.ToDouble(app.PlayerPosition), 0, Math.Max(0, duration));
                string trackId = RegisterPlaybackTrack((object)app, track);
                bool hasArtwork = false;
                dynamic? artworks = null;
                try
                {
                    artworks = track.Artwork;
                    hasArtwork = Convert.ToInt32(artworks.Count) > 0;
                }
                catch { }
                finally { ReleaseCom(artworks); }
                string artworkId = hasArtwork ? trackId : "";
                return new PlaybackState(true, playing, title, artist, album,
                    duration, position, soundVolume, artworkId,
                    trackId, shuffleEnabled, repeatMode);
            }
            finally
            {
                ReleaseCom(track);
            }
        }
        catch
        {
            ReleaseITunes();
            return EmptyState(false, false, 0);
        }
    }, cancellationToken);

    public Task<LibraryPage> GetLibraryAsync(string query, int offset, int limit,
        CancellationToken cancellationToken = default) => Invoke<LibraryPage>(() =>
    {
        LibrarySnapshot? snapshot = CurrentLibrarySnapshot();
        if (snapshot is not null) return PageSnapshotTracks(snapshot, query, offset, limit);
        dynamic app = GetITunes();
        if (ValidatePersistedLibrarySnapshot((object)app, cancellationToken) is { } persisted)
            return PageSnapshotTracks(persisted, query, offset, limit);
        dynamic? playlist = null;
        dynamic? tracks = null;
        try
        {
            playlist = app.LibraryPlaylist;
            if (string.IsNullOrWhiteSpace(query))
            {
                tracks = playlist.Tracks;
                return ReadTrackPage((object?)tracks, offset, limit, cancellationToken);
            }
            tracks = playlist.Search(query.Trim(), SearchAllFields);
            return ReadSearchPage((object?)tracks, query.Trim(), offset, limit,
                cancellationToken);
        }
        finally
        {
            ReleaseCom(tracks);
            ReleaseCom(playlist);
        }
    }, cancellationToken);

    public Task<LibraryCollectionPage> GetCollectionsAsync(string kind, string query, int offset,
        int limit, CancellationToken cancellationToken = default) => Invoke<LibraryCollectionPage>(() =>
    {
        dynamic app = GetITunes();
        return kind switch
        {
            "artists" or "albums" or "genres" => ReadGroupedCollections((object)app, kind, query,
                offset, limit, cancellationToken),
            "playlists" => ReadPlaylists((object)app, query, offset, limit, cancellationToken),
            _ => throw new ArgumentException("Unknown library collection"),
        };
    }, cancellationToken);

    public Task<LibraryPage> GetCollectionTracksAsync(string kind, string id, string query,
        int offset, int limit, CancellationToken cancellationToken = default) => Invoke<LibraryPage>(() =>
    {
        dynamic app = GetITunes();
        LibrarySnapshot? snapshot = CurrentLibrarySnapshot();
        snapshot ??= ValidatePersistedLibrarySnapshot((object)app, cancellationToken);
        if (snapshot is not null
            && kind is "artists" or "albums" or "genres"
            && ItunesCollectionId.TryDecodeText(id, kind, out string snapshotFilter))
        {
            return PageSnapshotTracks(snapshot, query, offset, limit, kind, snapshotFilter);
        }
        dynamic? playlist = null;
        dynamic? tracks = null;
        try
        {
            string filter = "";
            switch (kind)
            {
                case "artists":
                case "albums":
                    if (!ItunesCollectionId.TryDecodeText(id, kind, out filter))
                        throw new MediaNotFoundException("That collection is no longer available");
                    playlist = app.LibraryPlaylist;
                    // A search here only narrows the candidates; the exact match still runs per
                    // track below. It is therefore only safe when every song in the collection is
                    // certain to be found. The album name qualifies. The artist name does not:
                    // artists are grouped by album artist, which a compilation's songs do not
                    // carry in the artist field iTunes searches.
                    if (!string.IsNullOrWhiteSpace(query))
                    {
                        tracks = playlist.Search(query.Trim(), SearchAllFields);
                    }
                    else if (kind == "albums"
                             && CollectionAlbumName(filter) is string albumName
                             && albumName != LibraryGrouping.UnknownAlbum)
                    {
                        tracks = playlist.Search(albumName, SearchAlbums);
                    }
                    else
                    {
                        tracks = playlist.Tracks;
                    }
                    break;
                case "genres":
                    if (!ItunesCollectionId.TryDecodeText(id, kind, out filter))
                        throw new MediaNotFoundException("That collection is no longer available");
                    playlist = app.LibraryPlaylist;
                    tracks = string.IsNullOrWhiteSpace(query)
                        ? playlist.Tracks
                        : playlist.Search(query.Trim(), SearchAllFields);
                    break;
                case "playlists":
                    if (!ItunesCollectionId.TryDecodePlaylist(id, out ItunesPlaylistLocator locator))
                        throw new MediaNotFoundException("That playlist is no longer available");
                    playlist = ResolvePlaylist((object)app, locator)
                        ?? throw new MediaNotFoundException("That playlist is no longer available");
                    tracks = string.IsNullOrWhiteSpace(query)
                        ? playlist.Tracks
                        : playlist.Search(query.Trim(), SearchAllFields);
                    break;
                default:
                    throw new ArgumentException("Unknown library collection");
            }
            return ReadTrackPage((object?)tracks, offset, limit, cancellationToken, kind, filter);
        }
        finally
        {
            ReleaseCom(tracks);
            ReleaseCom(playlist);
        }
    }, cancellationToken);

    public Task PlayTrackAsync(PlaybackSelection selection,
        CancellationToken cancellationToken = default) =>
        Invoke<object?>(() =>
        {
            dynamic app = GetITunes();
            string kind = selection.CollectionKind.Trim().ToLowerInvariant();
            string collectionId = selection.CollectionId.Trim();
            if (kind.Length == 0 && collectionId.Length == 0)
            {
                PlayLibraryTrack((object)app, selection.TrackId);
                managedQueue = null;
                CleanupManagedQueues((object)app);
            }
            else if (kind == "playlists")
            {
                PlayPlaylistTrack((object)app, selection.TrackId, collectionId);
                managedQueue = null;
                CleanupManagedQueues((object)app);
            }
            else if (kind is "artists" or "albums" or "genres")
            {
                PlayManagedCollection((object)app, selection.TrackId, kind, collectionId,
                    cancellationToken);
            }
            else
            {
                throw new ArgumentException("Invalid playback collection");
            }
            return null;
        }, cancellationToken);

    private static void PlayLibraryTrack(object appObject, string trackId)
    {
        dynamic app = appObject;
        dynamic? track = ResolveTrack(app, trackId);
        if (track is null) throw new MediaNotFoundException("That song is no longer available");
        try { track.Play(); }
        finally { ReleaseCom(track); }
    }

    private static void PlayPlaylistTrack(object appObject, string trackId, string collectionId)
    {
        if (!ItunesCollectionId.TryDecodePlaylist(collectionId,
                out ItunesPlaylistLocator playlistLocator)
            || !ItunesTrackId.TryDecode(trackId, out ItunesTrackLocator trackLocator))
        {
            throw new MediaNotFoundException("That playlist is no longer available");
        }

        dynamic app = appObject;
        dynamic? playlist = null;
        dynamic? tracks = null;
        dynamic? playable = null;
        try
        {
            playlist = ResolvePlaylist(appObject, playlistLocator)
                ?? throw new MediaNotFoundException("That playlist is no longer available");
            tracks = playlist.Tracks;
            playable = FindTrackByDatabaseId(tracks, trackLocator.DatabaseId);
            if (playable is null)
                throw new MediaNotFoundException("That song is no longer in this playlist");
            bool shuffleEnabled = ReadBool(playlist, "Shuffle");
            SetProperty(playlist, "Shuffle", false);
            try
            {
                playlist.PlayFirstTrack();
                playable.Play();
            }
            finally { SetProperty(playlist, "Shuffle", shuffleEnabled); }
        }
        finally
        {
            ReleaseCom(playable);
            ReleaseCom(tracks);
            ReleaseCom(playlist);
        }
    }

    private void PlayManagedCollection(object appObject, string trackId, string kind,
        string collectionId, CancellationToken cancellationToken)
    {
        if (!ItunesCollectionId.TryDecodeText(collectionId, kind, out string filter))
            throw new MediaNotFoundException("That collection is no longer available");

        dynamic app = appObject;
        if (TryPlayWithinActiveQueue(appObject, trackId, kind, filter)) return;

        // The queue order has to match the order the browse list showed, or a song started from
        // that list would carry on through a different running order than the one on screen.
        List<QueueTrack> selected = [.. LibraryGrouping.InCollectionOrder(
            SelectCollectionTracks(appObject, kind, filter, cancellationToken),
            item => item.Album, item => item.AlbumArtist,
            item => item.DiscNumber, item => item.TrackNumber, item => item.OriginalIndex)];
        int targetIndex = selected.FindIndex(item =>
            string.Equals(item.Id, trackId, StringComparison.Ordinal));
        if (targetIndex < 0)
            throw new MediaNotFoundException("That song is no longer in this collection");

        (bool shuffleEnabled, string repeatMode) = ReadPlaybackModes(appObject);
        dynamic? queuePlaylist = null;
        dynamic? queueTracks = null;
        dynamic? queueTrack = null;
        bool activated = false;
        try
        {
            queuePlaylist = app.CreatePlaylist(
                ManagedQueuePrefix + Guid.NewGuid().ToString("N")[..8]);
            for (int index = 0; index < selected.Count; index++)
            {
                cancellationToken.ThrowIfCancellationRequested();
                dynamic? sourceTrack = null;
                try
                {
                    sourceTrack = ResolveTrack(app, selected[index].Id)
                        ?? throw new MediaNotFoundException(
                            "A song in that collection is no longer available");
                    queuePlaylist.AddTrack(sourceTrack);
                }
                finally { ReleaseCom(sourceTrack); }
            }

            // IITTrack.Play alone can fall back to iTunes' Music library even when the
            // track object belongs to this playlist. PlayFirstTrack establishes the
            // playlist as the active queue before selecting the requested track.
            SetProperty(queuePlaylist, "Shuffle", false);
            SetProperty(queuePlaylist, "SongRepeat", repeatMode switch
            {
                "one" => 1,
                "all" => 2,
                _ => 0,
            });
            queueTracks = queuePlaylist.Tracks;
            queueTrack = queueTracks.Item(targetIndex + 1);
            queuePlaylist.PlayFirstTrack();
            if (targetIndex > 0) queueTrack.Play();
            SetProperty(queuePlaylist, "Shuffle", shuffleEnabled);
            activated = true;
            int queuePlaylistId = ReadInt(queuePlaylist, "PlaylistID");
            managedQueue = new ManagedQueue(queuePlaylistId, kind, filter);
            CleanupManagedQueues(appObject, queuePlaylistId);
        }
        finally
        {
            if (!activated && queuePlaylist is not null)
            {
                try { queuePlaylist.Delete(); }
                catch { }
            }
            ReleaseCom(queueTrack);
            ReleaseCom(queueTracks);
            ReleaseCom(queuePlaylist);
        }
    }

    private bool TryPlayWithinActiveQueue(object appObject, string trackId, string kind,
        string filter)
    {
        ManagedQueue? active = managedQueue;
        if (active is null
            || !string.Equals(active.Kind, kind, StringComparison.Ordinal)
            || !string.Equals(active.Filter, filter, StringComparison.OrdinalIgnoreCase)
            || !ItunesTrackId.TryDecode(trackId, out ItunesTrackLocator locator)
            || locator.DatabaseId == 0)
        {
            return false;
        }
        dynamic app = appObject;
        dynamic? current = null;
        dynamic? tracks = null;
        dynamic? target = null;
        try
        {
            current = app.CurrentPlaylist;
            if (current is null
                || ReadInt((object)current, "PlaylistID") != active.PlaylistId
                || !ReadString((object)current, "Name").StartsWith(
                    ManagedQueuePrefix, StringComparison.Ordinal))
            {
                return false;
            }
            tracks = current.Tracks;
            target = FindTrackByDatabaseId(tracks, locator.DatabaseId);
            if (target is null) return false;
            target.Play();
            return true;
        }
        catch
        {
            return false;
        }
        finally
        {
            ReleaseCom(target);
            ReleaseCom(tracks);
            ReleaseCom(current);
        }
    }

    private List<QueueTrack> SelectCollectionTracks(object appObject, string kind, string filter,
        CancellationToken cancellationToken)
    {
        LibrarySnapshot? snapshot = CurrentLibrarySnapshot()
            ?? ValidatePersistedLibrarySnapshot(appObject, cancellationToken);
        List<QueueTrack> selected = [];
        if (snapshot is not null)
        {
            for (int index = 0; index < snapshot.Tracks.Length; index++)
            {
                cancellationToken.ThrowIfCancellationRequested();
                LibraryTrack track = snapshot.Tracks[index];
                if (!MatchesCollection(track, snapshot.TrackGenres[index], kind, filter))
                    continue;
                selected.Add(new QueueTrack(track.Id, track.Album, track.AlbumArtist,
                    Math.Max(0, track.DiscNumber), Math.Max(0, track.TrackNumber), index));
            }
            return selected;
        }

        dynamic app = appObject;
        dynamic? library = null;
        dynamic? tracks = null;
        try
        {
            library = app.LibraryPlaylist;
            tracks = library.Tracks;
            int originalIndex = 0;
            foreach (object trackObject in tracks)
            {
                cancellationToken.ThrowIfCancellationRequested();
                originalIndex++;
                dynamic track = trackObject;
                try
                {
                    if (!MatchesCollection(track, kind, filter)) continue;
                    selected.Add(new QueueTrack(
                        RegisterTrack(track),
                        LibraryGrouping.DisplayAlbum(ReadString(track, "Album")),
                        LibraryGrouping.AlbumArtist(ReadString(track, "Artist"),
                            ReadString(track, "AlbumArtist"), ReadBool(track, "Compilation")),
                        Math.Max(0, ReadInt(track, "DiscNumber")),
                        Math.Max(0, ReadInt(track, "TrackNumber")),
                        originalIndex));
                }
                finally { ReleaseCom(trackObject); }
            }
            return selected;
        }
        finally
        {
            ReleaseCom(tracks);
            ReleaseCom(library);
        }
    }

    private static dynamic? FindTrackByDatabaseId(dynamic tracks, int databaseId)
    {
        if (tracks is null || databaseId == 0) return null;
        foreach (object trackObject in tracks)
        {
            dynamic track = trackObject;
            if (ReadInt(track, "TrackDatabaseID") == databaseId) return track;
            ReleaseCom(trackObject);
        }
        return null;
    }

    private static void CleanupManagedQueues(object appObject, int keepPlaylistId = 0)
    {
        dynamic app = appObject;
        dynamic? source = null;
        dynamic? playlists = null;
        try
        {
            source = app.LibrarySource;
            playlists = source.Playlists;
            int count = Math.Max(0, Convert.ToInt32(playlists.Count));
            for (int index = count; index >= 1; index--)
            {
                dynamic? playlist = null;
                try
                {
                    playlist = playlists.Item(index);
                    object? playlistObject = playlist;
                    if (playlistObject is null
                        || ReadInt(playlistObject, "PlaylistID") == keepPlaylistId
                        || !ReadString(playlistObject, "Name").StartsWith(
                            ManagedQueuePrefix, StringComparison.Ordinal))
                    {
                        continue;
                    }
                    playlistObject.GetType().InvokeMember("Delete",
                        System.Reflection.BindingFlags.InvokeMethod, null, playlistObject, null,
                        null, CultureInfo.InvariantCulture, null);
                }
                catch { }
                finally { ReleaseCom(playlist); }
            }
        }
        finally
        {
            ReleaseCom(playlists);
            ReleaseCom(source);
        }
    }

    public Task ExecuteAsync(PlayerCommand command, CancellationToken cancellationToken = default) => Invoke<object?>(() =>
    {
        dynamic app = GetITunes();
        switch (command.Command)
        {
            case "playPause": app.PlayPause(); break;
            case "next": app.NextTrack(); break;
            case "previous": app.PreviousTrack(); break;
            case "shuffle":
                if (command.Value is null) throw new ArgumentException("Shuffle requires a value");
                SetCurrentPlaylistProperty((object)app, "Shuffle", command.Value.Value >= 0.5);
                break;
            case "repeat":
                if (command.Value is null) throw new ArgumentException("Repeat requires a value");
                SetCurrentPlaylistProperty((object)app, "SongRepeat",
                    Math.Clamp((int)Math.Round(command.Value.Value), 0, 2));
                break;
            case "volume":
                if (command.Value is null) throw new ArgumentException("Volume requires a value");
                app.SoundVolume = Math.Clamp((int)Math.Round(command.Value.Value), 0, 100);
                break;
            case "position":
                if (command.Value is null) throw new ArgumentException("Position requires a value");
                dynamic? track = null;
                try
                {
                    track = app.CurrentTrack;
                    double duration = track is null ? double.MaxValue : Math.Max(0, ReadDouble(track, "Duration"));
                    app.PlayerPosition = Math.Clamp(command.Value.Value, 0, duration);
                }
                finally { ReleaseCom(track); }
                break;
            default: throw new ArgumentException("Unknown command");
        }
        return null;
    }, cancellationToken);

    public Task<ArtworkData?> GetArtworkAsync(string id, int maxSize,
        CancellationToken cancellationToken = default) => Invoke<ArtworkData?>(() =>
    {
        if (string.IsNullOrWhiteSpace(id)) return null;
        int safeSize = Math.Clamp(maxSize, 64, 1000);
        string cacheKey = id + ":" + safeSize;
        if (artworkCache.TryGetValue(cacheKey, out ArtworkData? cached)) return cached;
        if (missingArtwork.TryGetValue(id, out DateTimeOffset missedAt))
        {
            if (DateTimeOffset.UtcNow - missedAt < MissingArtworkLifetime) return null;
            missingArtwork.Remove(id);
        }
        dynamic app = GetITunes();
        dynamic? track = ResolveTrack(app, id);
        dynamic? artworks = null;
        dynamic? art = null;
        string? temporary = null;
        try
        {
            if (track is null) return RecordMissingArtwork(id);
            artworks = track.Artwork;
            if (Convert.ToInt32(artworks.Count) < 1) return RecordMissingArtwork(id);
            art = artworks.Item(1);
            temporary = Path.Combine(Path.GetTempPath(), "TunesLink-" + Guid.NewGuid().ToString("N") + ".art");
            art.SaveArtworkToFile(temporary);
            FileInfo sourceFile = new(temporary);
            if (!sourceFile.Exists || sourceFile.Length is <= 0 or > MaxArtworkSourceBytes)
                return RecordMissingArtwork(id);
            byte[] source = File.ReadAllBytes(temporary);
            ArtworkData? normalized = NormalizeArtwork(id, source, safeSize);
            if (normalized is null) return RecordMissingArtwork(id);
            missingArtwork.Remove(id);
            CacheArtwork(cacheKey, normalized);
            return normalized;
        }
        finally
        {
            if (temporary is not null) try { File.Delete(temporary); } catch { }
            ReleaseCom(art);
            ReleaseCom(artworks);
            ReleaseCom(track);
        }
    }, cancellationToken);

    private ArtworkData? RecordMissingArtwork(string id)
    {
        if (missingArtwork.Count >= MaxMissingArtworkEntries)
        {
            DateTimeOffset now = DateTimeOffset.UtcNow;
            foreach (string expired in missingArtwork
                         .Where(entry => now - entry.Value >= MissingArtworkLifetime)
                         .Select(entry => entry.Key).ToList())
                missingArtwork.Remove(expired);
            if (missingArtwork.Count >= MaxMissingArtworkEntries) missingArtwork.Clear();
        }
        missingArtwork[id] = DateTimeOffset.UtcNow;
        return null;
    }

    private dynamic GetITunes()
    {
        if (itunes is not null) return itunes;
        Type? type = Type.GetTypeFromProgID("iTunes.Application", throwOnError: false);
        if (type is null)
            throw new MediaUnavailableException("iTunes Legacy is not installed on this computer");
        if (!ItunesProcessRunning())
            throw new MediaUnavailableException("Open iTunes on this computer to continue");
        itunes = Activator.CreateInstance(type)
                 ?? throw new MediaUnavailableException("iTunes did not respond");
        return itunes;
    }

    private static bool ItunesProcessRunning()
    {
        System.Diagnostics.Process[] processes =
            System.Diagnostics.Process.GetProcessesByName("iTunes");
        try
        {
            return processes.Length > 0;
        }
        finally
        {
            foreach (System.Diagnostics.Process process in processes) process.Dispose();
        }
    }

    private void ReleaseITunes()
    {
        if (itunes is null) return;
        try { Marshal.FinalReleaseComObject(itunes); } catch { }
        itunes = null;
        artworkCache.Clear();
        artworkCacheOrder.Clear();
        artworkCacheBytes = 0;
        missingArtwork.Clear();
        managedQueue = null;
        librarySnapshot = librarySnapshot is { } snapshot
            ? snapshot with { ValidatedAt = null }
            : null;
    }

    private void RunSta()
    {
        IOleMessageFilter? previousFilter = null;
        bool filterRegistered = false;
        try
        {
            filterRegistered =
                CoRegisterMessageFilter(new RetryRejectedCallFilter(), out previousFilter) >= 0;
        }
        catch { }
        try
        {
            foreach (WorkItem item in queue.GetConsumingEnumerable())
            {
                if (item.CancellationToken.IsCancellationRequested)
                {
                    item.Completion.TrySetCanceled(item.CancellationToken);
                    continue;
                }
                try { item.Completion.TrySetResult(item.Action()); }
                catch (Exception exception) { item.Completion.TrySetException(exception); }
            }
        }
        finally
        {
            if (filterRegistered)
            {
                try { _ = CoRegisterMessageFilter(previousFilter, out _); } catch { }
            }
            ReleaseITunes();
        }
    }

    [ComImport]
    [Guid("00000016-0000-0000-C000-000000000046")]
    [InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IOleMessageFilter
    {
        [PreserveSig]
        int HandleInComingCall(int callType, IntPtr taskCaller, int tickCount,
            IntPtr interfaceInfo);

        [PreserveSig]
        int RetryRejectedCall(IntPtr taskCallee, int tickCount, int rejectType);

        [PreserveSig]
        int MessagePending(IntPtr taskCallee, int tickCount, int pendingType);
    }

    [DllImport("ole32.dll")]
    private static extern int CoRegisterMessageFilter(IOleMessageFilter? filter,
        out IOleMessageFilter? previous);

    private sealed class RetryRejectedCallFilter : IOleMessageFilter
    {
        private const int ServerCallRetryLater = 2;
        private const int RetryDelayMilliseconds = 150;
        private const int MaxRetryMilliseconds = 20_000;

        public int HandleInComingCall(int callType, IntPtr taskCaller, int tickCount,
            IntPtr interfaceInfo) => 0;

        public int RetryRejectedCall(IntPtr taskCallee, int tickCount, int rejectType) =>
            rejectType == ServerCallRetryLater && tickCount < MaxRetryMilliseconds
                ? RetryDelayMilliseconds
                : -1;

        public int MessagePending(IntPtr taskCallee, int tickCount, int pendingType) => 2;
    }

    private Task<T> Invoke<T>(Func<T> action, CancellationToken cancellationToken)
    {
        if (disposed) return Task.FromException<T>(new ObjectDisposedException(nameof(ItunesController)));
        if (cancellationToken.IsCancellationRequested) return Task.FromCanceled<T>(cancellationToken);
        TaskCompletionSource<object?> completion = new(TaskCreationOptions.RunContinuationsAsynchronously);
        try
        {
            queue.Add(new WorkItem
            {
                Action = () => action(),
                Completion = completion,
                CancellationToken = cancellationToken
            }, cancellationToken);
        }
        catch (OperationCanceledException)
        {
            completion.TrySetCanceled(cancellationToken);
        }
        catch (InvalidOperationException)
        {
            completion.TrySetException(new ObjectDisposedException(nameof(ItunesController)));
        }
        return AwaitTyped<T>(completion.Task);
    }

    private static async Task<T> AwaitTyped<T>(Task<object?> task) => (T)(await task.ConfigureAwait(false))!;

    private static PlaybackState EmptyState(bool available, bool playing, int volume) =>
        new(available, playing, "", "", "", 0, 0, volume, "", "", false, "off");

    private LibraryPage ReadTrackPage(object? trackCollection, int offset, int limit,
        CancellationToken cancellationToken, string collectionKind = "", string collectionValue = "")
    {
        int safeLimit = Math.Clamp(limit, 1, 60);
        if (trackCollection is null) return new LibraryPage([], 0, safeLimit, 0, false);
        dynamic tracks = trackCollection;
        int available = Math.Max(0, Convert.ToInt32(tracks.Count));
        if (!RequiresTrackFilter(collectionKind))
        {
            int safeOffset = Math.Clamp(offset, 0, available);
            int end = Math.Min(available, safeOffset + safeLimit);
            List<LibraryTrack> page = new(end - safeOffset);
            for (int index = safeOffset + 1; index <= end; index++)
            {
                cancellationToken.ThrowIfCancellationRequested();
                dynamic? track = null;
                try
                {
                    track = tracks.Item(index);
                    if (track is not null) page.Add(ReadLibraryTrack(track));
                }
                finally { ReleaseCom(track); }
            }
            return new LibraryPage(page, safeOffset, safeLimit, available, end < available);
        }

        // A collection has to be ordered as a whole before it can be paged, so this fallback
        // materializes every match. It only runs when no library snapshot is available.
        List<LibraryTrack> matches = [];
        foreach (object trackObject in tracks)
        {
            cancellationToken.ThrowIfCancellationRequested();
            dynamic track = trackObject;
            try
            {
                if (MatchesCollection(track, collectionKind, collectionValue))
                    matches.Add(ReadLibraryTrack(track));
            }
            finally { ReleaseCom(trackObject); }
        }
        LibraryTrack[] ordered = InCollectionOrder(matches);
        int safeFilteredOffset = Math.Clamp(Math.Max(0, offset), 0, ordered.Length);
        LibraryTrack[] items = ordered.Skip(safeFilteredOffset).Take(safeLimit).ToArray();
        return new LibraryPage(items, safeFilteredOffset, safeLimit, ordered.Length,
            safeFilteredOffset + items.Length < ordered.Length);
    }

    private LibraryPage ReadSearchPage(object? trackCollection, string term, int offset,
        int limit, CancellationToken cancellationToken)
    {
        int safeLimit = Math.Clamp(limit, 1, 60);
        if (trackCollection is null) return new LibraryPage([], 0, safeLimit, 0, false);
        dynamic tracks = trackCollection;
        List<LibraryTrack> matches = [];
        foreach (object trackObject in tracks)
        {
            cancellationToken.ThrowIfCancellationRequested();
            dynamic track = trackObject;
            try
            {
                LibraryTrack candidate = ReadLibraryTrack(track);
                if (MatchesTerm(candidate, term)) matches.Add(candidate);
            }
            finally { ReleaseCom(trackObject); }
        }
        int safeOffset = Math.Clamp(offset, 0, matches.Count);
        LibraryTrack[] items = matches.Skip(safeOffset).Take(safeLimit).ToArray();
        return new LibraryPage(items, safeOffset, safeLimit, matches.Count,
            safeOffset + items.Length < matches.Count);
    }

    private static bool RequiresTrackFilter(string collectionKind) =>
        collectionKind is "artists" or "albums" or "genres";

    private LibraryTrack ReadLibraryTrack(dynamic track)
    {
        string artist = LibraryGrouping.DisplayArtist(ReadString(track, "Artist"));
        return ReadLibraryTrack(track, artist,
            LibraryGrouping.DisplayAlbum(ReadString(track, "Album")),
            LibraryGrouping.AlbumArtist(artist, ReadString(track, "AlbumArtist"),
                ReadBool(track, "Compilation")));
    }

    // The snapshot build has already read the artist, album, and album artist to derive its
    // grouping keys, so it hands them over instead of paying for the same COM reads a second
    // time per track.
    private LibraryTrack ReadLibraryTrack(dynamic track, string artist, string album,
        string albumArtist)
    {
        string id = RegisterTrack(track);
        string title = ReadString(track, "Name");
        return new LibraryTrack(
            id,
            string.IsNullOrWhiteSpace(title) ? "Untitled" : title,
            artist,
            album,
            Math.Max(0, ReadDouble(track, "Duration")),
            Math.Max(0, ReadInt(track, "TrackNumber")),
            Math.Max(0, ReadInt(track, "DiscNumber")),
            id,
            albumArtist);
    }

    /// <summary>Album by album, then disc and track order, with library order breaking ties.</summary>
    private static LibraryTrack[] InCollectionOrder(IReadOnlyList<LibraryTrack> tracks) =>
        [.. LibraryGrouping.InCollectionOrder(
            tracks.Select((track, index) => (Track: track, Index: index)),
            item => item.Track.Album,
            item => item.Track.AlbumArtist,
            item => item.Track.DiscNumber,
            item => item.Track.TrackNumber,
            item => item.Index).Select(item => item.Track)];

    private LibraryCollectionPage ReadGroupedCollections(object appObject, string kind, string query,
        int offset, int limit, CancellationToken cancellationToken)
    {
        LibrarySnapshot snapshot = CurrentLibrarySnapshot()
            ?? ValidatePersistedLibrarySnapshot(appObject, cancellationToken)
            ?? BuildAndPersistLibrarySnapshot(appObject, cancellationToken);
        IEnumerable<LibraryCollection> filtered = kind switch
        {
            "artists" => snapshot.Artists,
            "genres" => snapshot.Genres,
            _ => snapshot.Albums,
        };
        string term = query.Trim();
        if (term.Length > 0)
            filtered = filtered.Where(item => item.Title.Contains(term,
                    StringComparison.OrdinalIgnoreCase)
                || item.Subtitle.Contains(term, StringComparison.OrdinalIgnoreCase));
        return PageCollections(filtered.ToArray(), offset, limit, snapshot.Revision);
    }

    private LibrarySnapshot BuildLibrarySnapshot(object appObject,
        CancellationToken cancellationToken)
    {
        dynamic app = appObject;
        dynamic? playlist = null;
        dynamic? tracks = null;
        try
        {
            playlist = app.LibraryPlaylist;
            tracks = playlist.Tracks;
            Dictionary<string, CollectionAccumulator> artists =
                new(StringComparer.OrdinalIgnoreCase);
            Dictionary<string, CollectionAccumulator> albums =
                new(StringComparer.OrdinalIgnoreCase);
            Dictionary<string, CollectionAccumulator> genres =
                new(StringComparer.OrdinalIgnoreCase);
            int expectedTracks = Math.Max(0, Convert.ToInt32(tracks.Count));
            List<LibraryTrack> libraryTracks = new(expectedTracks);
            List<string> libraryGenres = new(expectedTracks);
            foreach (object trackObject in tracks)
            {
                cancellationToken.ThrowIfCancellationRequested();
                dynamic track = trackObject;
                try
                {
                    string artist = LibraryGrouping.DisplayArtist(ReadString(track, "Artist"));
                    string album = LibraryGrouping.DisplayAlbum(ReadString(track, "Album"));
                    string albumArtist = LibraryGrouping.AlbumArtist(artist,
                        ReadString(track, "AlbumArtist"), ReadBool(track, "Compilation"));
                    string albumKey = LibraryGrouping.AlbumKey(albumArtist, album);
                    string genre = LibraryGrouping.DisplayGenre(ReadString(track, "Genre"));
                    LibraryTrack libraryTrack = ReadLibraryTrack(track, artist, album,
                        albumArtist);
                    libraryTracks.Add(libraryTrack);
                    libraryGenres.Add(genre);
                    string artworkId =
                        !artists.ContainsKey(albumArtist) || !albums.ContainsKey(albumKey)
                            ? libraryTrack.ArtworkId : "";
                    AddCollection(artists, albumArtist, albumArtist, "", artworkId);
                    AddCollection(albums, albumKey, album, albumArtist, artworkId);
                    AddCollection(genres, genre, genre, "", libraryTrack.ArtworkId);
                }
                finally { ReleaseCom(trackObject); }
            }
            LibraryTrack[] materializedTracks = libraryTracks.ToArray();
            string[] materializedGenres = libraryGenres.ToArray();
            string sourceSignature = ComputeLibrarySourceSignature(appObject, (object)playlist,
                (object)tracks);
            return new LibrarySnapshot(
                materializedTracks,
                materializedGenres,
                MaterializeCollections("artists", artists),
                MaterializeCollections("albums", albums),
                MaterializeCollections("genres", genres),
                ComputeLibraryRevision(materializedTracks, materializedGenres),
                sourceSignature,
                DateTimeOffset.UtcNow,
                DateTimeOffset.UtcNow);
        }
        finally
        {
            ReleaseCom(tracks);
            ReleaseCom(playlist);
        }
    }

    private static void AddCollection(Dictionary<string, CollectionAccumulator> groups,
        string key, string title, string subtitle, string artworkId)
    {
        if (!groups.TryGetValue(key, out CollectionAccumulator? group))
        {
            group = new CollectionAccumulator(title, subtitle, artworkId);
            groups.Add(key, group);
        }
        group.TrackCount++;
    }

    private static LibraryCollection[] MaterializeCollections(string kind,
        Dictionary<string, CollectionAccumulator> groups) => groups
        .OrderBy(item => item.Value.Title, StringComparer.OrdinalIgnoreCase)
        .ThenBy(item => item.Value.Subtitle, StringComparer.OrdinalIgnoreCase)
        .Select(item => new LibraryCollection(
            ItunesCollectionId.EncodeText(kind, item.Key),
            item.Value.Title,
            item.Value.Subtitle,
            item.Value.TrackCount,
            item.Value.ArtworkId))
        .ToArray();

    private LibraryCollectionPage ReadPlaylists(object appObject, string query, int offset, int limit,
        CancellationToken cancellationToken)
    {
        dynamic app = appObject;
        dynamic? source = null;
        dynamic? playlists = null;
        try
        {
            source = app.LibrarySource;
            playlists = source.Playlists;
            int count = Math.Max(0, Convert.ToInt32(playlists.Count));
            List<LibraryCollection> items = new(count);
            for (int index = 1; index <= count; index++)
            {
                cancellationToken.ThrowIfCancellationRequested();
                dynamic? playlist = null;
                dynamic? tracks = null;
                dynamic? firstTrack = null;
                try
                {
                    playlist = playlists.Item(index);
                    object? playlistObject = playlist;
                    if (playlistObject is null || ReadInt(playlistObject, "Kind") != 2) continue;
                    string title = ReadString(playlistObject, "Name").Trim();
                    if (title.StartsWith(ManagedQueuePrefix, StringComparison.Ordinal)) continue;
                    if (title.Length == 0 || !title.Contains(query.Trim(),
                            StringComparison.OrdinalIgnoreCase)) continue;
                    int sourceId = ReadInt(playlistObject, "SourceID");
                    int playlistId = ReadInt(playlistObject, "PlaylistID");
                    if (sourceId == 0 || playlistId == 0) continue;
                    dynamic availablePlaylist = playlistObject;
                    tracks = availablePlaylist.Tracks;
                    if (tracks is null) continue;
                    int trackCount = Math.Max(0, Convert.ToInt32(tracks.Count));
                    string artworkId = "";
                    if (trackCount > 0)
                    {
                        firstTrack = tracks.Item(1);
                        if (firstTrack is not null) artworkId = RegisterTrack(firstTrack);
                    }
                    items.Add(new LibraryCollection(
                        ItunesCollectionId.EncodePlaylist(new(sourceId, playlistId)),
                        title,
                        "",
                        trackCount,
                        artworkId));
                }
                finally
                {
                    ReleaseCom(firstTrack);
                    ReleaseCom(tracks);
                    ReleaseCom(playlist);
                }
            }
            return PageCollections(items.OrderBy(item => item.Title,
                StringComparer.OrdinalIgnoreCase).ToArray(), offset, limit);
        }
        finally
        {
            ReleaseCom(playlists);
            ReleaseCom(source);
        }
    }

    private static LibraryCollectionPage PageCollections(
        LibraryCollection[] collections, int offset, int limit, string revision = "")
    {
        int safeLimit = Math.Clamp(limit, 1, 60);
        int safeOffset = Math.Clamp(offset, 0, collections.Length);
        LibraryCollection[] page = collections.Skip(safeOffset).Take(safeLimit).ToArray();
        return new LibraryCollectionPage(page, safeOffset, safeLimit, collections.Length,
            safeOffset + page.Length < collections.Length, revision);
    }

    private LibrarySnapshot? CurrentLibrarySnapshot() => librarySnapshot is { ValidatedAt: { } } snapshot
        && DateTimeOffset.UtcNow - snapshot.ValidatedAt.Value < LibrarySnapshotLifetime
            ? snapshot
            : null;

    private LibrarySnapshot BuildAndPersistLibrarySnapshot(object appObject,
        CancellationToken cancellationToken)
    {
        LibrarySnapshot snapshot = BuildLibrarySnapshot(appObject, cancellationToken);
        librarySnapshot = snapshot;
        try
        {
            LibraryIndexData persisted = new(
                snapshot.Tracks,
                snapshot.TrackGenres,
                snapshot.Artists,
                snapshot.Albums,
                snapshot.Genres,
                snapshot.Revision,
                snapshot.SourceSignature,
                snapshot.CreatedAt);
            libraryIndexStore.Save(persisted);
        }
        catch (Exception exception)
        {
            BridgeDiagnostics.Record("library.cache.write", exception);
        }
        return snapshot;
    }

    private LibrarySnapshot? ValidatePersistedLibrarySnapshot(object appObject,
        CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        LibrarySnapshot? snapshot = librarySnapshot;
        if (snapshot is null) return null;
        DateTimeOffset now = DateTimeOffset.UtcNow;
        if (now - snapshot.CreatedAt > PersistedLibrarySnapshotLifetime
            || snapshot.CreatedAt > now.AddMinutes(5))
        {
            librarySnapshot = null;
            return null;
        }

        dynamic app = appObject;
        dynamic? playlist = null;
        dynamic? tracks = null;
        try
        {
            playlist = app.LibraryPlaylist;
            tracks = playlist.Tracks;
            string signature = ComputeLibrarySourceSignature(appObject, (object)playlist,
                (object)tracks);
            if (!CryptographicOperations.FixedTimeEquals(
                    Encoding.UTF8.GetBytes(signature),
                    Encoding.UTF8.GetBytes(snapshot.SourceSignature)))
            {
                librarySnapshot = null;
                return null;
            }
            librarySnapshot = snapshot with { ValidatedAt = now };
            return librarySnapshot;
        }
        finally
        {
            ReleaseCom(tracks);
            ReleaseCom(playlist);
        }
    }

    private static LibrarySnapshot? LoadPersistedLibrarySnapshot(LibraryIndexStore store)
    {
        LibraryIndexData? persisted = store.Load();
        return persisted is null ? null : new LibrarySnapshot(
            persisted.Tracks, persisted.TrackGenres,
            persisted.Artists, persisted.Albums, persisted.Genres, persisted.Revision,
            persisted.SourceSignature, persisted.CreatedAt, null);
    }

    private static string ComputeLibrarySourceSignature(object appObject, object playlistObject,
        object tracksObject)
    {
        dynamic app = appObject;
        dynamic playlist = playlistObject;
        dynamic tracks = tracksObject;
        string material = string.Join('\u001f',
            ReadInt(playlist, "SourceID").ToString(CultureInfo.InvariantCulture),
            ReadInt(playlist, "PlaylistID").ToString(CultureInfo.InvariantCulture),
            Math.Max(0, Convert.ToInt32(tracks.Count)).ToString(CultureInfo.InvariantCulture),
            ReadDouble(playlist, "Duration").ToString("R", CultureInfo.InvariantCulture),
            ReadDouble(playlist, "Size").ToString("R", CultureInfo.InvariantCulture),
            ReadString(playlist, "DateModified"),
            ReadLibraryXmlStamp(app));
        return Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(material)))
            .ToLowerInvariant();
    }

    private static string ReadLibraryXmlStamp(dynamic app)
    {
        try
        {
            string path = ReadString(app, "LibraryXMLPath");
            if (string.IsNullOrWhiteSpace(path) || path.Length > 32_768) return "";
            FileInfo file = new(path);
            return file.Exists ? string.Join(':',
                file.Length.ToString(CultureInfo.InvariantCulture),
                file.LastWriteTimeUtc.Ticks.ToString(CultureInfo.InvariantCulture)) : "";
        }
        catch
        {
            return "";
        }
    }

    private static LibraryPage PageSnapshotTracks(LibrarySnapshot snapshot, string query,
        int offset, int limit, string collectionKind = "", string collectionValue = "")
    {
        string term = query.Trim();
        List<LibraryTrack> results = [];
        for (int index = 0; index < snapshot.Tracks.Length; index++)
        {
            LibraryTrack track = snapshot.Tracks[index];
            if (term.Length > 0 && !MatchesTerm(track, term)) continue;
            if (collectionKind.Length > 0 && !MatchesCollection(track,
                    snapshot.TrackGenres[index],
                    collectionKind, collectionValue)) continue;
            results.Add(track);
        }
        IReadOnlyList<LibraryTrack> ordered = RequiresTrackFilter(collectionKind)
            ? InCollectionOrder(results) : results;
        int safeLimit = Math.Clamp(limit, 1, 60);
        int safeOffset = Math.Clamp(offset, 0, ordered.Count);
        LibraryTrack[] page = ordered.Skip(safeOffset).Take(safeLimit).ToArray();
        return new LibraryPage(page, safeOffset, safeLimit, ordered.Count,
            safeOffset + page.Length < ordered.Count, snapshot.Revision);
    }

    private static bool MatchesTerm(LibraryTrack track, string term) =>
        track.Title.Contains(term, StringComparison.OrdinalIgnoreCase)
        || track.Artist.Contains(term, StringComparison.OrdinalIgnoreCase)
        || track.Album.Contains(term, StringComparison.OrdinalIgnoreCase);

    private static bool MatchesCollection(LibraryTrack track, string genre,
        string kind, string value)
    {
        if (kind == "genres") return string.Equals(genre, value,
            StringComparison.OrdinalIgnoreCase);
        if (kind == "artists") return string.Equals(track.AlbumArtist, value,
            StringComparison.OrdinalIgnoreCase);
        if (kind != "albums") return true;
        return string.Equals(LibraryGrouping.AlbumKey(track.AlbumArtist, track.Album), value,
            StringComparison.OrdinalIgnoreCase);
    }

    private static string ComputeLibraryRevision(LibraryTrack[] tracks, string[] genres)
    {
        using IncrementalHash hash = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
        for (int index = 0; index < tracks.Length; index++)
        {
            LibraryTrack track = tracks[index];
            byte[] encoded = Encoding.UTF8.GetBytes(string.Join('\u001f',
                track.Id, track.Title, track.Artist, track.Album, genres[index],
                track.AlbumArtist,
                track.Duration.ToString("R", CultureInfo.InvariantCulture),
                track.TrackNumber.ToString(CultureInfo.InvariantCulture),
                track.DiscNumber.ToString(CultureInfo.InvariantCulture)) + "\u001e");
            hash.AppendData(encoded);
        }
        return Convert.ToHexString(hash.GetHashAndReset()).ToLowerInvariant();
    }

    private static bool MatchesCollection(dynamic track, string kind, string value)
    {
        // Genre is settled before the album artist so filtering by genre never pays for the
        // album artist and compilation reads.
        if (kind == "genres") return string.Equals(
            LibraryGrouping.DisplayGenre(ReadString(track, "Genre")), value,
            StringComparison.OrdinalIgnoreCase);
        string albumArtist = LibraryGrouping.AlbumArtist(ReadString(track, "Artist"),
            ReadString(track, "AlbumArtist"), ReadBool(track, "Compilation"));
        if (kind == "artists") return string.Equals(albumArtist, value,
            StringComparison.OrdinalIgnoreCase);
        if (kind != "albums") return true;
        return string.Equals(
            LibraryGrouping.AlbumKey(albumArtist, ReadString(track, "Album")), value,
            StringComparison.OrdinalIgnoreCase);
    }

    internal static string DisplayArtist(string artist) =>
        LibraryGrouping.DisplayArtist(artist);

    internal static string DisplayAlbum(string album) => LibraryGrouping.DisplayAlbum(album);

    internal static string DisplayGenre(string genre) => LibraryGrouping.DisplayGenre(genre);

    private static string? CollectionAlbumName(string value) =>
        LibraryGrouping.AlbumNameFromKey(value);

    private static dynamic? ResolvePlaylist(object appObject, ItunesPlaylistLocator locator)
    {
        dynamic app = appObject;
        try { return app.GetITObjectByID(locator.SourceId, locator.PlaylistId, 0, 0); }
        catch { return null; }
    }

    private static (bool ShuffleEnabled, string RepeatMode) ReadPlaybackModes(object appObject)
    {
        dynamic app = appObject;
        dynamic? playlist = null;
        try
        {
            playlist = app.CurrentPlaylist;
            if (playlist is null) return (false, "off");
            int repeat = Math.Clamp(ReadInt(playlist, "SongRepeat"), 0, 2);
            return (ReadBool(playlist, "Shuffle"), repeat switch
            {
                1 => "one",
                2 => "all",
                _ => "off",
            });
        }
        catch { return (false, "off"); }
        finally { ReleaseCom(playlist); }
    }

    private static void SetCurrentPlaylistProperty(object appObject, string property, object value)
    {
        dynamic app = appObject;
        dynamic? playlist = null;
        try
        {
            playlist = app.CurrentPlaylist
                ?? throw new ArgumentException("Choose a song before changing playback mode");
            SetProperty(playlist, property, value);
        }
        finally { ReleaseCom(playlist); }
    }

    private static void SetProperty(dynamic value, string property, object propertyValue)
    {
        try
        {
            value.GetType().InvokeMember(property,
                System.Reflection.BindingFlags.SetProperty, null, value,
                new object?[] { propertyValue });
        }
        catch
        {
            switch (property)
            {
                case "Shuffle":
                    value.Shuffle = Convert.ToBoolean(propertyValue, CultureInfo.InvariantCulture);
                    break;
                case "SongRepeat":
                    value.SongRepeat = Convert.ToInt32(propertyValue, CultureInfo.InvariantCulture);
                    break;
                default: throw;
            }
        }
    }

    internal static bool ReadBool(dynamic value, string property)
    {
        try
        {
            return property switch
            {
                "Shuffle" => Convert.ToBoolean(value.Shuffle),
                "Compilation" => Convert.ToBoolean(value.Compilation),
                _ => throw new ArgumentException("Unknown Boolean property"),
            };
        }
        catch
        {
            try
            {
                return Convert.ToBoolean(value.GetType().InvokeMember(property,
                    System.Reflection.BindingFlags.GetProperty, null, value, null));
            }
            catch
            {
                return false;
            }
        }
    }

    // Every default arm below throws so that an unlisted property falls through to the reflection
    // path instead of silently reading as an empty string, a zero, or a false.
    internal static string ReadString(dynamic value, string property)
    {
        try
        {
            return property switch
            {
                "Name" => Convert.ToString(value.Name) ?? "",
                "Artist" => Convert.ToString(value.Artist) ?? "",
                "Album" => Convert.ToString(value.Album) ?? "",
                // AlbumArtist belongs to IITFileOrCDTrack, so other track kinds fail both the
                // dynamic and the reflection read and are treated as having no album artist.
                "AlbumArtist" => Convert.ToString(value.AlbumArtist) ?? "",
                "Genre" => Convert.ToString(value.Genre) ?? "",
                "PersistentID" => Convert.ToString(value.PersistentID) ?? "",
                _ => throw new ArgumentException("Unknown text property"),
            };
        }
        catch
        {
            try
            {
                return Convert.ToString(value.GetType().InvokeMember(property,
                    System.Reflection.BindingFlags.GetProperty, null, value, null)) ?? "";
            }
            catch { return ""; }
        }
    }

    internal static double ReadDouble(dynamic value, string property)
    {
        try
        {
            return property switch
            {
                "Duration" => Convert.ToDouble(value.Duration),
                _ => throw new ArgumentException("Unknown numeric property"),
            };
        }
        catch
        {
            try
            {
                return Convert.ToDouble(value.GetType().InvokeMember(property,
                    System.Reflection.BindingFlags.GetProperty, null, value, null));
            }
            catch { return 0; }
        }
    }

    internal static int ReadInt(dynamic value, string property)
    {
        try
        {
            return property switch
            {
                "SourceID" => Convert.ToInt32(value.SourceID),
                "PlaylistID" => Convert.ToInt32(value.PlaylistID),
                "TrackID" => Convert.ToInt32(value.TrackID),
                "TrackDatabaseID" => Convert.ToInt32(value.TrackDatabaseID),
                "TrackNumber" => Convert.ToInt32(value.TrackNumber),
                "DiscNumber" => Convert.ToInt32(value.DiscNumber),
                "Kind" => Convert.ToInt32(value.Kind),
                "SongRepeat" => Convert.ToInt32(value.SongRepeat),
                _ => throw new ArgumentException("Unknown integer property"),
            };
        }
        catch
        {
            try
            {
                return Convert.ToInt32(value.GetType().InvokeMember(property,
                    System.Reflection.BindingFlags.GetProperty, null, value, null));
            }
            catch { return 0; }
        }
    }

    private string RegisterTrack(dynamic track)
    {
        ItunesTrackLocator locator = new(
            ReadInt(track, "SourceID"),
            ReadInt(track, "PlaylistID"),
            ReadInt(track, "TrackID"),
            ReadInt(track, "TrackDatabaseID"));
        return ItunesTrackId.Encode(locator);
    }

    private string RegisterPlaybackTrack(object appObject, dynamic track)
    {
        dynamic? playlist = null;
        dynamic? library = null;
        dynamic? libraryTracks = null;
        dynamic? canonical = null;
        try
        {
            playlist = track.Playlist;
            if (playlist is null
                || !ReadString(playlist, "Name").StartsWith(
                    ManagedQueuePrefix, StringComparison.Ordinal))
            {
                return RegisterTrack(track);
            }

            dynamic app = appObject;
            int high = ReadParameterizedInt(appObject, "ITObjectPersistentIDHigh", track);
            int low = ReadParameterizedInt(appObject, "ITObjectPersistentIDLow", track);
            library = app.LibraryPlaylist;
            libraryTracks = library.Tracks;
            canonical = libraryTracks.ItemByPersistentID(high, low);
            return canonical is null ? RegisterTrack(track) : RegisterTrack(canonical);
        }
        catch
        {
            return RegisterTrack(track);
        }
        finally
        {
            ReleaseCom(canonical);
            ReleaseCom(libraryTracks);
            ReleaseCom(library);
            ReleaseCom(playlist);
        }
    }

    private static int ReadParameterizedInt(object value, string property, object argument)
    {
        try
        {
            return Convert.ToInt32(value.GetType().InvokeMember(property,
                System.Reflection.BindingFlags.GetProperty, null, value,
                new[] { argument }, null, CultureInfo.InvariantCulture, null),
                CultureInfo.InvariantCulture);
        }
        catch { return 0; }
    }

    private static dynamic? ResolveTrack(dynamic app, string id)
    {
        if (!ItunesTrackId.TryDecode(id, out ItunesTrackLocator locator)) return null;
        try
        {
            return app.GetITObjectByID(locator.SourceId, locator.PlaylistId,
                locator.TrackId, locator.DatabaseId);
        }
        catch { return null; }
    }

    private void CacheArtwork(string key, ArtworkData artwork)
    {
        if (artworkCache.TryGetValue(key, out ArtworkData? replaced))
            artworkCacheBytes -= replaced.Bytes.Length;
        else
            artworkCacheOrder.Enqueue(key);
        artworkCache[key] = artwork;
        artworkCacheBytes += artwork.Bytes.Length;
        while (artworkCacheOrder.Count > 48 || artworkCacheBytes > MaxArtworkCacheBytes)
        {
            string oldest = artworkCacheOrder.Dequeue();
            if (artworkCache.Remove(oldest, out ArtworkData? removed))
                artworkCacheBytes -= removed.Bytes.Length;
        }
    }

    internal static ArtworkData? NormalizeArtwork(string id, byte[] source, int max)
    {
        if (source.Length is 0 or > MaxArtworkSourceBytes) return null;
        try
        {
            using MemoryStream input = new(source);
            using Image image = Image.FromStream(input, useEmbeddedColorManagement: false,
                validateImageData: true);
            if (image.Width is <= 0 or > MaxArtworkDimension
                || image.Height is <= 0 or > MaxArtworkDimension
                || (long)image.Width * image.Height > MaxArtworkPixels)
                return null;
            double scale = Math.Min(1, Math.Min(max / (double)image.Width, max / (double)image.Height));
            int width = Math.Max(1, (int)Math.Round(image.Width * scale));
            int height = Math.Max(1, (int)Math.Round(image.Height * scale));
            using Bitmap resized = new(width, height, System.Drawing.Imaging.PixelFormat.Format24bppRgb);
            using (Graphics graphics = Graphics.FromImage(resized))
            {
                graphics.Clear(Color.FromArgb(18, 18, 20));
                graphics.InterpolationMode = System.Drawing.Drawing2D.InterpolationMode.HighQualityBicubic;
                graphics.DrawImage(image, 0, 0, width, height);
            }
            using MemoryStream output = new();
            ImageCodecInfo? codec = ImageCodecInfo.GetImageEncoders()
                .FirstOrDefault(item => item.FormatID == ImageFormat.Jpeg.Guid);
            if (codec is null) resized.Save(output, ImageFormat.Jpeg);
            else
            {
                using EncoderParameters parameters = new(1);
                parameters.Param[0] = new EncoderParameter(System.Drawing.Imaging.Encoder.Quality, 88L);
                resized.Save(output, codec, parameters);
            }
            byte[] bytes = output.ToArray();
            return bytes.Length is > 0 and <= 2 * 1024 * 1024
                ? new ArtworkData(id, bytes, "image/jpeg")
                : null;
        }
        catch
        {
            return null;
        }
    }

    private static void ReleaseCom(object? value)
    {
        if (value is null || !Marshal.IsComObject(value)) return;
        try { Marshal.FinalReleaseComObject(value); } catch { }
    }

    public void Dispose()
    {
        if (disposed) return;
        disposed = true;
        queue.CompleteAdding();
        staThread.Join(TimeSpan.FromSeconds(3));
        queue.Dispose();
    }
}
