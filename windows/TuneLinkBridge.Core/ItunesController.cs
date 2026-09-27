using System.Buffers.Binary;
using System.Collections;
using System.Collections.Concurrent;
using System.Drawing.Imaging;
using System.Globalization;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;

namespace TunesLinkBridge;

internal sealed partial class ItunesController : IMediaController
{
    internal const int SearchAllFields = 0;
    internal const int SearchAlbums = 3;
    internal const int MaxArtworkSourceBytes = 8 * 1024 * 1024;
    internal const int MaxArtworkCacheBytes = 24 * 1024 * 1024;
    internal static readonly TimeSpan LibrarySnapshotLifetime = TimeSpan.FromMinutes(5);
    internal static readonly TimeSpan ArtworkLifetime = TimeSpan.FromMinutes(5);
    // Previous restarts a song that has played longer than this, as music players do.
    internal const double PreviousRestartSeconds = 3;
    private const int MaxArtworkDimension = 4096;
    private const long MaxArtworkPixels = 16_000_000;
    private const string DefaultManagedQueuePrefix =
        "TunesLink Playback Queue [managed-7f4d6b21]-";
    private readonly string managedQueuePrefix;

    // IITUserPlaylist.SpecialKind values. Only playlists a listener made (and Purchased) are
    // browsable; the media kinds below also hold the library's videos, podcasts, and audiobooks.
    private const int SpecialKindNone = 0;
    private const int SpecialKindPurchased = 1;
    private const int SpecialKindPodcasts = 3;
    private const int SpecialKindMovies = 7;
    private const int SpecialKindTvShows = 8;
    private const int SpecialKindAudiobooks = 9;

    // _IiTunesEvents, verified against iTunes 12.13 with IConnectionPointContainer.
    private static readonly Guid ItunesEventsId = new("5846EB78-317E-4B6F-B0C3-11EE8C8FEEF2");
    private const int QuittingEventId = 8;
    private const int AboutToPromptUserToQuitEventId = 9;
    // After iTunes announces it is quitting, the bridge waits for that process to exit (plus a
    // grace period) before connecting again, so a request cannot relaunch a closing iTunes. If
    // iTunes is still running long after, the quit was canceled and the bridge reconnects.
    private static readonly TimeSpan QuitGrace = TimeSpan.FromSeconds(2);
    private static readonly TimeSpan QuitWaitLimit = TimeSpan.FromSeconds(30);

    // A playlist's revision costs an enumeration of the playlist, so consecutive pages share it.
    private static readonly TimeSpan PlaylistRevisionLifetime = TimeSpan.FromSeconds(5);
    private const int MaxPlaylistRevisions = 64;

    internal readonly record struct ItunesProcess(int Id, DateTime StartedAt);

    private sealed record ExcludedMedia(string LibraryStamp, HashSet<int> DatabaseIds,
        int[]? LibraryIndices);

    private sealed record PlaylistRevision(int Count, string Revision, long ComputedAt);

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


    internal static readonly TimeSpan MissingArtworkLifetime = TimeSpan.FromMinutes(5);
    private const int MaxMissingArtworkEntries = 512;

    private readonly BlockingCollection<WorkItem> queue = new();
    private readonly Thread staThread;
    private readonly Dictionary<string, ArtworkData> artworkCache = new(StringComparer.Ordinal);
    private readonly Dictionary<string, DateTimeOffset> artworkFetchedAt = new(StringComparer.Ordinal);
    private readonly Queue<string> artworkCacheOrder = new();
    private readonly Dictionary<string, DateTimeOffset> missingArtwork =
        new(StringComparer.Ordinal);
    private long artworkCacheBytes;
    private LibrarySnapshot? librarySnapshot;
    private ManagedQueue? managedQueue;
    private readonly LibraryIndexStore libraryIndexStore;
    private LibraryIndexFileStamp? libraryIndexStamp;
    private readonly TimeProvider timeProvider;
    private readonly Func<ItunesProcess[]> listItunesProcesses;
    private readonly Dictionary<ItunesPlaylistLocator, PlaylistRevision> playlistRevisions = [];
    private ExcludedMedia? excludedMedia;
    private Action? quitHandler;
    private ItunesProcess[]? quittingProcesses;
    private long quitObservedAt;
    private long? quitExitedAt;
    private int workDepth;
    private dynamic? itunes;
    private bool disposed;

    public ItunesController(string? configDirectory = null,
                            IAtomicFilePersistence? persistence = null, TimeProvider? timeProvider = null,
                            string managedQueuePrefix = DefaultManagedQueuePrefix,
                            Func<ItunesProcess[]>? itunesProcesses = null)
    {
        this.managedQueuePrefix = managedQueuePrefix;
        this.timeProvider = timeProvider ?? TimeProvider.System;
        listItunesProcesses = itunesProcesses ?? RunningItunesProcesses;
        string directory = configDirectory ?? BrandPaths.UserConfigDirectory();
        queueStatePath = Path.Combine(directory, "Cache", "managed-queue-v1.json");
        queuePersistence = persistence ?? AtomicFilePersistence.Instance;
        managedQueue = LoadManagedQueue();
        libraryIndexStore = new LibraryIndexStore(directory, persistence);
        LibraryIndexFileStamp? stampBeforeLoad = libraryIndexStore.Stamp();
        librarySnapshot = LoadPersistedLibrarySnapshot(libraryIndexStore);
        LibraryIndexFileStamp? stampAfterLoad = libraryIndexStore.Stamp();
        libraryIndexStamp = stampBeforeLoad == stampAfterLoad ? stampAfterLoad : null;
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
        catch (COMException exception) when (ItunesWorkerProtocol.ClassifyComFailure(
            exception.HResult) == ItunesWorkerFailureCategory.ItunesBusy)
        {
            // A dialog in iTunes, not a closed iTunes: keep the connection and report busy.
            throw;
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
        if (UsableLibrarySnapshot((object)app, cancellationToken) is { } persisted)
            return PageSnapshotTracks(persisted, query, offset, limit);
        dynamic? playlist = null;
        dynamic? tracks = null;
        try
        {
            playlist = app.LibraryPlaylist;
            bool browsing = string.IsNullOrWhiteSpace(query);
            ExcludedMedia excluded = ExcludedMediaFor((object)app, (object)playlist,
                withLibraryIndices: browsing, cancellationToken);
            if (browsing)
            {
                tracks = playlist.Tracks;
                return ReadTrackPage((object?)tracks, offset, limit, cancellationToken,
                    excluded: excluded);
            }
            tracks = playlist.Search(query.Trim(), SearchAllFields);
            return ReadSearchPage((object?)tracks, query.Trim(), offset, limit,
                excluded.DatabaseIds, cancellationToken);
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

    public Task<LibraryCollectionPage> GetCollectionAlbumsAsync(string kind, string id, string query,
        int offset, int limit, CancellationToken cancellationToken = default) => Invoke<LibraryCollectionPage>(() =>
    {
        CollectionAlbums.Validate(kind, id);
        string filter = id;
        object app = GetITunes();
        LibrarySnapshot snapshot = UsableLibrarySnapshot(app, cancellationToken)
            ?? BuildAndPersistLibrarySnapshot(app, cancellationToken);
        HashSet<string> albumKeys = new(StringComparer.OrdinalIgnoreCase);
        for (int index = 0; index < snapshot.Tracks.Length; index++)
        {
            cancellationToken.ThrowIfCancellationRequested();
            LibraryTrack track = snapshot.Tracks[index];
            if (MatchesCollection(track, snapshot.TrackGenres[index], kind, filter))
                albumKeys.Add(LibraryGrouping.AlbumKey(track.AlbumArtist, track.Album));
        }
        return CollectionAlbums.Page(snapshot.Tracks.Where(track => albumKeys.Contains(
            LibraryGrouping.AlbumKey(track.AlbumArtist, track.Album))), query, offset, limit, snapshot.Revision);
    }, cancellationToken);

    public Task<LibraryPage> GetCollectionTracksAsync(string kind, string id, string query,
        int offset, int limit, CancellationToken cancellationToken = default) => Invoke<LibraryPage>(() =>
    {
        dynamic app = GetITunes();
        LibrarySnapshot? snapshot = UsableLibrarySnapshot((object)app, cancellationToken);
        if (snapshot is not null
            && kind is "artists" or "albums" or "genres"
            && ItunesCollectionId.IsValidText(id, kind))
        {
            return PageSnapshotTracks(snapshot, query, offset, limit, kind, id);
        }
        dynamic? playlist = null;
        dynamic? tracks = null;
        try
        {
            string filter = id;
            string revision = "";
            switch (kind)
            {
                case "artists":
                case "albums":
                    if (!ItunesCollectionId.IsValidText(id, kind))
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
                    if (!ItunesCollectionId.IsValidText(id, kind))
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
                    revision = ReadPlaylistRevision((object)playlist, locator, cancellationToken);
                    tracks = string.IsNullOrWhiteSpace(query)
                        ? playlist.Tracks
                        : playlist.Search(query.Trim(), SearchAllFields);
                    break;
                default:
                    throw new ArgumentException("Unknown library collection");
            }
            ExcludedMedia? excluded = RequiresTrackFilter(kind)
                ? ExcludedMediaFor((object)app, (object)playlist, withLibraryIndices: false,
                    cancellationToken)
                : null;
            return ReadTrackPage((object?)tracks, offset, limit, cancellationToken, kind, filter,
                excluded, revision);
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
                PlayLibraryTrack((object)app, selection.TrackId, cancellationToken);
                AbandonQueueBuild();
                managedQueue = null;
                ClearQueueState();
                CleanupManagedQueues((object)app);
            }
            else if (kind == "playlists")
            {
                PlayManagedPlaylist((object)app, selection.TrackId, collectionId, cancellationToken);
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

    private static void PlayLibraryTrack(object appObject, string trackId, CancellationToken cancellationToken)
    {
        dynamic app = appObject;
        dynamic? track = ResolveTrack(app, trackId);
        if (track is null) throw new MediaNotFoundException("That song is no longer available");
        try
        {
            cancellationToken.ThrowIfCancellationRequested();
            track.Play();
        }
        finally { ReleaseCom(track); }
    }

    private List<QueueTrack> SelectCollectionTracks(object appObject, string kind, string filter,
        CancellationToken cancellationToken)
    {
        // Playback uses an expired index as it is; browsing starts its replacement.
        LibrarySnapshot? snapshot = UsableLibrarySnapshot(appObject, cancellationToken,
            refresh: false);
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
            HashSet<int> excluded = ExcludedMediaFor(appObject, (object)library,
                withLibraryIndices: false, cancellationToken).DatabaseIds;
            // The album search has the same exact-match filter below as browsing. It avoids
            // walking the entire library when the index has not been built yet.
            tracks = kind == "albums"
                && CollectionAlbumName(filter) is string albumName
                && albumName != LibraryGrouping.UnknownAlbum
                    ? library.Search(albumName, SearchAlbums)
                    : library.Tracks;
            if (tracks is null) return selected;
            int originalIndex = 0;
            foreach (object trackObject in ComItems((object?)tracks))
            {
                cancellationToken.ThrowIfCancellationRequested();
                originalIndex++;
                dynamic track = trackObject;
                try
                {
                    if (IsExcluded(trackObject, excluded)
                        || !MatchesCollection(track, kind, filter)) continue;
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

    private void CleanupManagedQueues(object appObject, int keepPlaylistId = 0)
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
                            managedQueuePrefix, StringComparison.Ordinal))
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
            case "previous":
                // A song a few seconds in restarts; phones reconcile Previous that way.
                if (Convert.ToDouble(app.PlayerPosition) > PreviousRestartSeconds)
                    app.PlayerPosition = 0;
                else if (!TryManagedPrevious((object)app, cancellationToken))
                    app.PreviousTrack();
                break;
            case "shuffle":
                if (command.Value is null) throw new ArgumentException("Shuffle requires a value");
                if (!TryChangeManagedModes((object)app, command.Value.Value >= 0.5, null, cancellationToken))
                    SetCurrentPlaylistProperty((object)app, "Shuffle", command.Value.Value >= 0.5);
                break;
            case "repeat":
                if (command.Value is null) throw new ArgumentException("Repeat requires a value");
                int repeat = Math.Clamp((int)Math.Round(command.Value.Value), 0, 2);
                if (!TryChangeManagedModes((object)app, null, repeat, cancellationToken))
                    SetCurrentPlaylistProperty((object)app, "SongRepeat", repeat);
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
        if (FreshArtwork(cacheKey) is { } cached) return cached;
        if (missingArtwork.TryGetValue(id, out DateTimeOffset missedAt))
        {
            if (timeProvider.GetUtcNow() - missedAt < MissingArtworkLifetime) return null;
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
        catch (Exception exception) when (IsTrackArtworkFailure(exception))
        {
            return RecordMissingArtwork(id);
        }
        finally
        {
            if (temporary is not null) try { File.Delete(temporary); } catch { }
            ReleaseCom(art);
            ReleaseCom(artworks);
            ReleaseCom(track);
        }
    }, cancellationToken);

    // One track's artwork failing to save or read is that track's problem. A busy or closed
    // iTunes is not, and must not hide every requested cover for the missing-artwork lifetime.
    private static bool IsTrackArtworkFailure(Exception exception) => exception switch
    {
        COMException com => ItunesWorkerProtocol.ClassifyComFailure(com.HResult)
            == ItunesWorkerFailureCategory.ComFailure,
        IOException or UnauthorizedAccessException or InvalidCastException
            or Microsoft.CSharp.RuntimeBinder.RuntimeBinderException => true,
        _ => false,
    };

    private ArtworkData? RecordMissingArtwork(string id)
    {
        if (missingArtwork.Count >= MaxMissingArtworkEntries)
        {
            DateTimeOffset now = timeProvider.GetUtcNow();
            foreach (string expired in missingArtwork
                         .Where(entry => now - entry.Value >= MissingArtworkLifetime)
                         .Select(entry => entry.Key).ToList())
                missingArtwork.Remove(expired);
            if (missingArtwork.Count >= MaxMissingArtworkEntries) missingArtwork.Clear();
        }
        missingArtwork[id] = timeProvider.GetUtcNow();
        return null;
    }

    private dynamic GetITunes()
    {
        if (ItunesQuitting())
            throw new MediaUnavailableException("Open iTunes on this computer to continue");
        if (itunes is not null) return itunes;
        Type? type = Type.GetTypeFromProgID("iTunes.Application", throwOnError: false);
        if (type is null)
            throw new MediaUnavailableException("iTunes Legacy is not installed on this computer");
        if (listItunesProcesses().Length == 0)
            throw new MediaUnavailableException("Open iTunes on this computer to continue");
        itunes = Activator.CreateInstance(type)
                 ?? throw new MediaUnavailableException("iTunes did not respond");
        AdviseQuitEvents((object)itunes);
        return itunes;
    }

    private static ItunesProcess[] RunningItunesProcesses()
    {
        System.Diagnostics.Process[] processes =
            System.Diagnostics.Process.GetProcessesByName("iTunes");
        try
        {
            return [.. processes.Select(process =>
            {
                // The start time tells a relaunched iTunes apart from one reusing a process ID.
                try { return new ItunesProcess(process.Id, process.StartTime); }
                catch (Exception exception) when (exception is InvalidOperationException
                                                      or System.ComponentModel.Win32Exception)
                {
                    return new ItunesProcess(process.Id, default);
                }
            })];
        }
        finally
        {
            foreach (System.Diagnostics.Process process in processes) process.Dispose();
        }
    }

    // Without these events, quitting iTunes while a worker holds it shows iTunes' "applications
    // are using the scripting interface" prompt. Events are delivered on this STA thread.
    private void AdviseQuitEvents(object app)
    {
        if (!Marshal.IsComObject(app)) return;
        Action handler = OnItunesQuitting;
        quitHandler = handler;
        try
        {
            ComEventsHelper.Combine(app, ItunesEventsId, QuittingEventId, handler);
            ComEventsHelper.Combine(app, ItunesEventsId, AboutToPromptUserToQuitEventId, handler);
        }
        catch (Exception exception)
        {
            // Playback still works without the events; iTunes may just ask before quitting.
            BridgeDiagnostics.Record("itunes.events.advise", exception);
        }
    }

    private void UnadviseQuitEvents(object app)
    {
        if (quitHandler is not { } handler) return;
        quitHandler = null;
        try
        {
            ComEventsHelper.Remove(app, ItunesEventsId, QuittingEventId, handler);
            ComEventsHelper.Remove(app, ItunesEventsId, AboutToPromptUserToQuitEventId, handler);
        }
        catch (Exception exception)
        {
            BridgeDiagnostics.Record("itunes.events.unadvise", exception);
        }
    }

    internal void OnItunesQuitting()
    {
        if (Thread.CurrentThread != staThread)
        {
            EnqueueInternal(ReleaseForQuit);
            return;
        }
        NoteItunesQuitting();
        // Inside a running request its COM objects are still in use; release once it returns.
        if (workDepth > 0) EnqueueInternal(ReleaseITunes);
        else ReleaseITunes();
    }

    private void ReleaseForQuit()
    {
        NoteItunesQuitting();
        ReleaseITunes();
    }

    private void NoteItunesQuitting()
    {
        if (quittingProcesses is not null) return;
        quittingProcesses = listItunesProcesses();
        quitObservedAt = timeProvider.GetTimestamp();
        quitExitedAt = null;
    }

    private bool ItunesQuitting()
    {
        if (quittingProcesses is not { } quitting) return false;
        ItunesProcess[] running = listItunesProcesses();
        if (quitting.Any(running.Contains))
        {
            if (timeProvider.GetElapsedTime(quitObservedAt) < QuitWaitLimit) return true;
        }
        else
        {
            quitExitedAt ??= timeProvider.GetTimestamp();
            if (timeProvider.GetElapsedTime(quitExitedAt.Value) < QuitGrace) return true;
        }
        quittingProcesses = null;
        quitExitedAt = null;
        return false;
    }

    private void ReleaseITunes()
    {
        AbandonQueueBuild();
        AbandonLibraryRefresh();
        playlistRevisions.Clear();
        excludedMedia = null;
        if (itunes is null) return;
        UnadviseQuitEvents((object)itunes);
        try { Marshal.FinalReleaseComObject(itunes); } catch { }
        itunes = null;
        artworkCache.Clear();
        artworkFetchedAt.Clear();
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
                workDepth++;
                try { item.Completion.TrySetResult(item.Action()); }
                catch (Exception exception) { item.Completion.TrySetException(exception); }
                finally { workDepth--; }
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
        // Under the 6 s state timeout: a dialog open in iTunes then fails the call as busy,
        // which keeps the worker, instead of timing out, which recycles it.
        private const int MaxRetryMilliseconds = 4_000;

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

    /// <summary>Queues the controller's own follow-up work behind requests already waiting.</summary>
    private void EnqueueInternal(Action action)
    {
        try
        {
            queue.Add(new WorkItem
            {
                Action = () =>
                {
                    action();
                    return null;
                },
                Completion = new TaskCompletionSource<object?>(
                    TaskCreationOptions.RunContinuationsAsynchronously),
                CancellationToken = CancellationToken.None
            });
        }
        // Disposing (including ObjectDisposedException): nothing more will run.
        catch (InvalidOperationException) { }
    }

    /// <summary>
    /// Enumerates a COM collection and releases its enumerator as soon as the walk ends, instead
    /// of leaving the reference to the finalizer.
    /// </summary>
    private static IEnumerable<object> ComItems(object? collection)
    {
        if (collection is null) yield break;
        IEnumerator enumerator = ((IEnumerable)collection).GetEnumerator();
        try
        {
            while (enumerator.MoveNext())
                if (enumerator.Current is { } item) yield return item;
        }
        finally
        {
            if (enumerator is ICustomAdapter adapter) ReleaseCom(adapter.GetUnderlyingObject());
            (enumerator as IDisposable)?.Dispose();
        }
    }

    private static async Task<T> AwaitTyped<T>(Task<object?> task) => (T)(await task.ConfigureAwait(false))!;

    private static PlaybackState EmptyState(bool available, bool playing, int volume) =>
        new(available, playing, "", "", "", 0, 0, volume, "", "", false, "off");

    private LibraryPage ReadTrackPage(object? trackCollection, int offset, int limit,
        CancellationToken cancellationToken, string collectionKind = "", string collectionValue = "",
        ExcludedMedia? excluded = null, string revision = "")
    {
        int safeLimit = Math.Clamp(limit, 1, 60);
        if (trackCollection is null) return new LibraryPage([], 0, safeLimit, 0, false, revision);
        dynamic tracks = trackCollection;
        int available = Math.Max(0, Convert.ToInt32(tracks.Count));
        if (!RequiresTrackFilter(collectionKind) && excluded is { DatabaseIds.Count: > 0 })
            return ReadLibraryPageWithout((object)tracks, available, excluded.LibraryIndices ?? [],
                excluded.DatabaseIds, offset, safeLimit, cancellationToken);
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
            return new LibraryPage(page, safeOffset, safeLimit, available, end < available,
                revision);
        }

        // A collection has to be ordered as a whole before it can be paged, so this fallback
        // materializes every match. It only runs when no library snapshot is available.
        List<LibraryTrack> matches = [];
        foreach (object trackObject in ComItems((object)tracks))
        {
            cancellationToken.ThrowIfCancellationRequested();
            dynamic track = trackObject;
            try
            {
                if (!IsExcluded(trackObject, excluded?.DatabaseIds)
                    && MatchesCollection(track, collectionKind, collectionValue))
                    matches.Add(ReadLibraryTrack(track));
            }
            finally { ReleaseCom(trackObject); }
        }
        LibraryTrack[] ordered = InCollectionOrder(matches);
        int safeFilteredOffset = Math.Clamp(Math.Max(0, offset), 0, ordered.Length);
        LibraryTrack[] items = ordered.Skip(safeFilteredOffset).Take(safeLimit).ToArray();
        return new LibraryPage(items, safeFilteredOffset, safeLimit, ordered.Length,
            safeFilteredOffset + items.Length < ordered.Length, revision);
    }

    /// <summary>
    /// Pages the library by position while leaving out its videos, podcasts, and audiobooks.
    /// Their library positions are known, so each page still reads only its own songs.
    /// </summary>
    private LibraryPage ReadLibraryPageWithout(object trackCollection, int available,
        int[] hiddenPositions, HashSet<int> hiddenIds, int offset, int limit,
        CancellationToken cancellationToken)
    {
        dynamic tracks = trackCollection;
        int[] hidden = [.. hiddenPositions.Where(position => position <= available).Distinct().Order()];
        HashSet<int> skipped = [.. hidden];
        int total = available - hidden.Length;
        int safeOffset = Math.Clamp(offset, 0, total);
        // Every hidden position at or before the requested song moves it one further along.
        int position = safeOffset + 1;
        foreach (int hiddenPosition in hidden)
        {
            if (hiddenPosition > position) break;
            position++;
        }
        List<LibraryTrack> page = new(limit);
        for (; position <= available && page.Count < limit; position++)
        {
            if (skipped.Contains(position)) continue;
            cancellationToken.ThrowIfCancellationRequested();
            dynamic? track = null;
            try
            {
                track = tracks.Item(position);
                // Guards against positions that moved since they were read: a hidden item is
                // never shown, at worst a page comes back short.
                if (track is not null && !IsExcluded((object)track, hiddenIds))
                    page.Add(ReadLibraryTrack(track));
            }
            finally { ReleaseCom(track); }
        }
        return new LibraryPage(page, safeOffset, limit, total, safeOffset + page.Count < total);
    }

    private LibraryPage ReadSearchPage(object? trackCollection, string term, int offset,
        int limit, HashSet<int> excluded, CancellationToken cancellationToken)
    {
        int safeLimit = Math.Clamp(limit, 1, 60);
        if (trackCollection is null) return new LibraryPage([], 0, safeLimit, 0, false);
        List<LibraryTrack> matches = [];
        foreach (object trackObject in ComItems(trackCollection))
        {
            cancellationToken.ThrowIfCancellationRequested();
            dynamic track = trackObject;
            try
            {
                if (IsExcluded(trackObject, excluded)) continue;
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
        LibrarySnapshot snapshot = UsableLibrarySnapshot(appObject, cancellationToken)
            ?? BuildAndPersistLibrarySnapshot(appObject, cancellationToken);
        IEnumerable<LibraryCollection> filtered = kind switch
        {
            "artists" => snapshot.Artists,
            "genres" => snapshot.Genres,
            _ => snapshot.Albums,
        };
        string term = query.Trim();
        if (term.Length > 0)
            filtered = filtered.Where(item => LibraryGrouping.Matches(item.Title, term)
                || LibraryGrouping.Matches(item.Subtitle, term));
        return PageCollections(filtered.ToArray(), offset, limit, snapshot.Revision);
    }

    private LibrarySnapshot BuildLibrarySnapshot(object appObject,
        CancellationToken cancellationToken)
    {
        using LibrarySnapshotBuilder builder = new(this, appObject, cancellationToken);
        while (!builder.Read(int.MaxValue, cancellationToken)) { }
        return builder.Complete();
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
        .OrderBy(item => item.Value.Title, LibraryGrouping.TitleOrder)
        .ThenBy(item => item.Value.Subtitle, LibraryGrouping.TitleOrder)
        .ThenBy(item => item.Key, StringComparer.Ordinal)
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
                    // iTunes' own lists (Music, Movies, Podcasts, Genius, folders, ...) repeat the
                    // library views or hold no songs.
                    if (ReadInt(playlistObject, "SpecialKind")
                        is not (SpecialKindNone or SpecialKindPurchased)) continue;
                    string title = ReadString(playlistObject, "Name").Trim();
                    if (title.StartsWith(managedQueuePrefix, StringComparison.Ordinal)) continue;
                    if (title.Length == 0 || !LibraryGrouping.Matches(title, query.Trim())) continue;
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
            LibraryCollection[] ordered = [.. items
                .OrderBy(item => item.Title, LibraryGrouping.NameOrder)
                .ThenBy(item => item.Id, StringComparer.Ordinal)];
            return PageCollections(ordered, offset, limit, CollectionRevision(ordered));
        }
        finally
        {
            ReleaseCom(playlists);
            ReleaseCom(source);
        }
    }

    /// <summary>
    /// Identifies one version of a list of collections, so a phone replaces its pages when the
    /// list changes instead of merging pages from two versions.
    /// </summary>
    private static string CollectionRevision(IEnumerable<LibraryCollection> collections)
    {
        using IncrementalHash hash = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
        foreach (LibraryCollection collection in collections)
            hash.AppendData(Encoding.UTF8.GetBytes(string.Join('\u001f', collection.Id,
                collection.Title, collection.TrackCount.ToString(CultureInfo.InvariantCulture))
                + "\u001e"));
        return Convert.ToHexString(hash.GetHashAndReset(), 0, 8).ToLowerInvariant();
    }

    /// <summary>
    /// A playlist's revision: a short hash of its songs in order. Smart playlists such as Recently
    /// Played reorder as songs finish, and without a revision a phone would merge pages from
    /// before and after the change and show the same song twice.
    /// </summary>
    private string ReadPlaylistRevision(object playlistObject, ItunesPlaylistLocator locator,
        CancellationToken cancellationToken)
    {
        dynamic playlist = playlistObject;
        dynamic? tracks = null;
        try
        {
            tracks = playlist.Tracks;
            if (tracks is null) return "";
            int count = Math.Max(0, Convert.ToInt32(tracks.Count));
            if (playlistRevisions.TryGetValue(locator, out PlaylistRevision? cached)
                && cached.Count == count
                && timeProvider.GetElapsedTime(cached.ComputedAt) < PlaylistRevisionLifetime)
                return cached.Revision;
            using IncrementalHash hash = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
            byte[] entry = new byte[8];
            foreach (object track in ComItems((object)tracks))
            {
                try
                {
                    cancellationToken.ThrowIfCancellationRequested();
                    BinaryPrimitives.WriteInt32LittleEndian(entry, ReadInt(track, "TrackID"));
                    BinaryPrimitives.WriteInt32LittleEndian(entry.AsSpan(4),
                        ReadInt(track, "TrackDatabaseID"));
                    hash.AppendData(entry);
                }
                finally { ReleaseCom(track); }
            }
            string revision = Convert.ToHexString(hash.GetHashAndReset(), 0, 8).ToLowerInvariant();
            if (playlistRevisions.Count >= MaxPlaylistRevisions) playlistRevisions.Clear();
            playlistRevisions[locator] = new PlaylistRevision(count, revision,
                timeProvider.GetTimestamp());
            return revision;
        }
        finally { ReleaseCom(tracks); }
    }

    /// <summary>
    /// The library's videos, podcasts, and audiobooks, which live in iTunes' media playlists.
    /// Those playlists are usually small, and the set is reused until the library changes.
    /// </summary>
    private ExcludedMedia ExcludedMediaFor(object appObject, object libraryObject,
        bool withLibraryIndices, CancellationToken cancellationToken)
    {
        dynamic app = appObject;
        dynamic library = libraryObject;
        dynamic? libraryTracks = null;
        dynamic? source = null;
        dynamic? playlists = null;
        try
        {
            libraryTracks = library.Tracks;
            string stamp = string.Join(':',
                Math.Max(0, Convert.ToInt32(libraryTracks.Count)).ToString(CultureInfo.InvariantCulture),
                ReadDouble(libraryObject, "Duration").ToString("R", CultureInfo.InvariantCulture),
                ReadDouble(libraryObject, "Size").ToString("R", CultureInfo.InvariantCulture));
            if (excludedMedia is { } cached && cached.LibraryStamp == stamp
                && (!withLibraryIndices || cached.LibraryIndices is not null))
                return cached;
            HashSet<int> ids = [];
            List<int> positions = [];
            try
            {
                source = app.LibrarySource;
                playlists = source.Playlists;
                int count = Math.Max(0, Convert.ToInt32(playlists.Count));
                for (int index = 1; index <= count; index++)
                {
                    cancellationToken.ThrowIfCancellationRequested();
                    dynamic? playlist = null;
                    dynamic? tracks = null;
                    try
                    {
                        playlist = playlists.Item(index);
                        object? playlistObject = playlist;
                        if (playlistObject is null || ReadInt(playlistObject, "Kind") != 2
                            || ReadInt(playlistObject, "SpecialKind") is not (SpecialKindPodcasts
                                or SpecialKindMovies or SpecialKindTvShows or SpecialKindAudiobooks))
                            continue;
                        tracks = playlist.Tracks;
                        foreach (object track in ComItems((object?)tracks))
                        {
                            try
                            {
                                cancellationToken.ThrowIfCancellationRequested();
                                int databaseId = ReadInt(track, "TrackDatabaseID");
                                if (databaseId == 0 || !ids.Add(databaseId) || !withLibraryIndices)
                                    continue;
                                int position = LibraryPosition(appObject, (object)libraryTracks, track);
                                if (position > 0) positions.Add(position);
                            }
                            finally { ReleaseCom(track); }
                        }
                    }
                    finally
                    {
                        ReleaseCom(tracks);
                        ReleaseCom(playlist);
                    }
                }
            }
            catch (Exception exception) when (exception is not OperationCanceledException)
            {
                // Unreadable media playlists leave the library shown whole rather than not at
                // all. The failure is not cached, so the next request tries again.
                BridgeDiagnostics.Record("library.media", exception);
                return new ExcludedMedia(stamp, [], withLibraryIndices ? [] : null);
            }
            excludedMedia = new ExcludedMedia(stamp, ids,
                withLibraryIndices ? [.. positions.Order()] : null);
            return excludedMedia;
        }
        finally
        {
            ReleaseCom(playlists);
            ReleaseCom(source);
            ReleaseCom(libraryTracks);
        }
    }

    /// <summary>Where a track from another playlist sits in the library playlist, or zero.</summary>
    private static int LibraryPosition(object appObject, object libraryTracksObject, object track)
    {
        dynamic libraryTracks = libraryTracksObject;
        dynamic? copy = null;
        try
        {
            copy = libraryTracks.ItemByPersistentID(
                ReadParameterizedInt(appObject, "ITObjectPersistentIDHigh", track),
                ReadParameterizedInt(appObject, "ITObjectPersistentIDLow", track));
            return copy is null ? 0 : ReadInt((object)copy, "Index");
        }
        catch { return 0; }
        finally { ReleaseCom(copy); }
    }

    private static bool IsExcluded(object track, HashSet<int>? excluded) =>
        excluded is { Count: > 0 } && excluded.Contains(ReadInt(track, "TrackDatabaseID"));

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
        && IsFresh(snapshot.CreatedAt, timeProvider.GetUtcNow(), LibrarySnapshotLifetime)
        && libraryIndexStore.Stamp() == libraryIndexStamp
            ? snapshot
            : null;

    internal static bool IsFresh(DateTimeOffset fetchedAt, DateTimeOffset now, TimeSpan lifetime) =>
        now >= fetchedAt && now - fetchedAt < lifetime;

    private LibrarySnapshot BuildAndPersistLibrarySnapshot(object appObject,
        CancellationToken cancellationToken)
    {
        LibrarySnapshot snapshot = BuildLibrarySnapshot(appObject, cancellationToken);
        PersistLibrarySnapshot(snapshot);
        return snapshot;
    }

    private void PersistLibrarySnapshot(LibrarySnapshot snapshot)
    {
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
            libraryIndexStamp = libraryIndexStore.Stamp();
        }
        catch (Exception exception)
        {
            BridgeDiagnostics.Record("library.cache.write", exception);
        }
    }

    private LibrarySnapshot? ValidatePersistedLibrarySnapshot(object appObject,
        CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        LibrarySnapshot? snapshot = librarySnapshot;
        DateTimeOffset now = timeProvider.GetUtcNow();
        // Browsing runs in a separate worker and may replace the index while this worker's
        // snapshot is still fresh. A file stamp check avoids repeatedly parsing an old index.
        LibraryIndexFileStamp? currentStamp = libraryIndexStore.Stamp();
        if (currentStamp != libraryIndexStamp)
        {
            snapshot = LoadPersistedLibrarySnapshot(libraryIndexStore);
            librarySnapshot = snapshot;
            libraryIndexStamp = currentStamp;
        }
        if (snapshot is null) return null;
        // Aggregate counts cannot detect tag-only edits. Never renew the original fetch time.
        if (!IsFresh(snapshot.CreatedAt, now, LibrarySnapshotLifetime))
        {
            staleLibrarySnapshot = snapshot;
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
                staleLibrarySnapshot = snapshot;
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
        LibraryGrouping.MatchesTrack(track.Title, track.Artist, track.Album, track.AlbumArtist,
            term);

    private static bool MatchesCollection(LibraryTrack track, string genre,
        string kind, string value)
    {
        if (kind == "genres") return ItunesCollectionId.MatchesText(value, kind, genre);
        if (kind == "artists") return ItunesCollectionId.MatchesText(value, kind, track.AlbumArtist);
        if (kind != "albums") return true;
        return ItunesCollectionId.MatchesText(value, kind,
            LibraryGrouping.AlbumKey(track.AlbumArtist, track.Album));
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
        if (kind == "genres") return ItunesCollectionId.MatchesText(value, kind,
            LibraryGrouping.DisplayGenre(ReadString(track, "Genre")));
        string albumArtist = LibraryGrouping.AlbumArtist(ReadString(track, "Artist"),
            ReadString(track, "AlbumArtist"), ReadBool(track, "Compilation"));
        if (kind == "artists") return ItunesCollectionId.MatchesText(value, kind, albumArtist);
        if (kind != "albums") return true;
        return ItunesCollectionId.MatchesText(value, kind,
            LibraryGrouping.AlbumKey(albumArtist, ReadString(track, "Album")));
    }

    internal static string DisplayArtist(string artist) =>
        LibraryGrouping.DisplayArtist(artist);

    internal static string DisplayAlbum(string album) => LibraryGrouping.DisplayAlbum(album);

    internal static string DisplayGenre(string genre) => LibraryGrouping.DisplayGenre(genre);

    private static string? CollectionAlbumName(string value) =>
        ItunesCollectionId.TryDecodeText(value, "albums", out string key)
            ? LibraryGrouping.AlbumNameFromKey(key) : null;

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
                "SpecialKind" => Convert.ToInt32(value.SpecialKind),
                "Index" => Convert.ToInt32(value.Index),
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
                    managedQueuePrefix, StringComparison.Ordinal))
            {
                return RegisterTrack(track);
            }

            managedQueue ??= LoadManagedQueue();
            if (managedQueue is { } active && ReadInt((object)playlist!, "PlaylistID") == active.PlaylistId
                && ReadString((object)playlist!, "Name") == active.Name
                && active.TrackIndices.TryGetValue(ReadInt((object)track, "TrackID"), out int index))
                return active.Tracks[index].Id;

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

    private ArtworkData? FreshArtwork(string key) =>
        artworkCache.TryGetValue(key, out ArtworkData? cached)
        && artworkFetchedAt.TryGetValue(key, out DateTimeOffset fetchedAt)
        && IsFresh(fetchedAt, timeProvider.GetUtcNow(), ArtworkLifetime) ? cached : null;

    private void CacheArtwork(string key, ArtworkData artwork)
    {
        if (artworkCache.TryGetValue(key, out ArtworkData? replaced))
            artworkCacheBytes -= replaced.Bytes.Length;
        else
            artworkCacheOrder.Enqueue(key);
        artworkCache[key] = artwork;
        artworkFetchedAt[key] = timeProvider.GetUtcNow();
        artworkCacheBytes += artwork.Bytes.Length;
        while (artworkCacheOrder.Count > 48 || artworkCacheBytes > MaxArtworkCacheBytes)
        {
            string oldest = artworkCacheOrder.Dequeue();
            artworkFetchedAt.Remove(oldest);
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
