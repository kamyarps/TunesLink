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
    internal const int SearchArtists = 2;
    internal const int SearchAlbums = 3;
    internal const int MaxArtworkSourceBytes = 8 * 1024 * 1024;
    internal const int MaxArtworkCacheBytes = 24 * 1024 * 1024;
    internal static readonly TimeSpan LibrarySnapshotLifetime = TimeSpan.FromMinutes(5);
    internal static readonly TimeSpan PersistedLibrarySnapshotLifetime = TimeSpan.FromHours(24);
    private const int MaxArtworkDimension = 4096;
    private const long MaxArtworkPixels = 16_000_000;
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

    private sealed record LibrarySnapshot(
        LibraryTrack[] Tracks,
        LibraryCollection[] Artists,
        LibraryCollection[] Albums,
        string Revision,
        string SourceSignature,
        DateTimeOffset CreatedAt,
        DateTimeOffset? ValidatedAt);

    private readonly BlockingCollection<WorkItem> queue = new();
    private readonly Thread staThread;
    private readonly Dictionary<string, ArtworkData> artworkCache = new(StringComparer.Ordinal);
    private readonly Queue<string> artworkCacheOrder = new();
    private long artworkCacheBytes;
    private LibrarySnapshot? librarySnapshot;
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
                string artist = ReadString(track, "Artist");
                string album = ReadString(track, "Album");
                double duration = Math.Max(0, ReadDouble(track, "Duration"));
                double position = Math.Clamp(Convert.ToDouble(app.PlayerPosition), 0, Math.Max(0, duration));
                string trackId = RegisterTrack(track);
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
        if (snapshot is not null && string.IsNullOrWhiteSpace(query))
            return PageSnapshotTracks(snapshot, query, offset, limit);
        dynamic app = GetITunes();
        if (string.IsNullOrWhiteSpace(query)
            && ValidatePersistedLibrarySnapshot((object)app, cancellationToken) is { } persisted)
            return PageSnapshotTracks(persisted, query, offset, limit);
        dynamic? playlist = null;
        dynamic? tracks = null;
        try
        {
            playlist = app.LibraryPlaylist;
            tracks = string.IsNullOrWhiteSpace(query)
                ? playlist.Tracks
                : playlist.Search(query.Trim(), SearchAllFields);
            return ReadTrackPage((object?)tracks, offset, limit, cancellationToken);
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
            "artists" => ReadGroupedCollections((object)app, kind, query, offset, limit,
                cancellationToken),
            "albums" => ReadGroupedCollections((object)app, kind, query, offset, limit,
                cancellationToken),
            "genres" => ReadGenreCollections((object)app, query, offset, limit,
                cancellationToken),
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
        if (snapshot is not null && string.IsNullOrWhiteSpace(query)
            && kind is "artists" or "albums"
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
                    if (!string.IsNullOrWhiteSpace(query))
                    {
                        tracks = playlist.Search(query.Trim(), SearchAllFields);
                    }
                    else if (kind == "artists" && filter != "Unknown Artist")
                    {
                        tracks = playlist.Search(filter, SearchArtists);
                    }
                    else if (kind == "albums"
                             && CollectionAlbumName(filter) is string albumName
                             && albumName != "Unknown Album")
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
                    tracks = playlist.Tracks;
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

    public Task PlayTrackAsync(string id, CancellationToken cancellationToken = default) =>
        Invoke<object?>(() =>
        {
            dynamic app = GetITunes();
            dynamic? track = ResolveTrack(app, id);
            if (track is null) throw new MediaNotFoundException("That song is no longer available");
            try { track.Play(); }
            finally { ReleaseCom(track); }
            return null;
        }, cancellationToken);

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
        dynamic app = GetITunes();
        dynamic? track = ResolveTrack(app, id);
        dynamic? artworks = null;
        dynamic? art = null;
        string? temporary = null;
        try
        {
            if (track is null) return null;
            artworks = track.Artwork;
            if (Convert.ToInt32(artworks.Count) < 1) return null;
            art = artworks.Item(1);
            temporary = Path.Combine(Path.GetTempPath(), "TunesLink-" + Guid.NewGuid().ToString("N") + ".art");
            art.SaveArtworkToFile(temporary);
            FileInfo sourceFile = new(temporary);
            if (!sourceFile.Exists || sourceFile.Length is <= 0 or > MaxArtworkSourceBytes)
                return null;
            byte[] source = File.ReadAllBytes(temporary);
            ArtworkData? normalized = NormalizeArtwork(id, source, safeSize);
            if (normalized is null) return null;
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

    private dynamic GetITunes()
    {
        if (itunes is not null) return itunes;
        Type? type = Type.GetTypeFromProgID("iTunes.Application", throwOnError: false);
        if (type is null) throw new InvalidOperationException("iTunes automation is not installed");
        itunes = Activator.CreateInstance(type)
                 ?? throw new InvalidOperationException("iTunes did not start");
        return itunes;
    }

    private void ReleaseITunes()
    {
        if (itunes is null) return;
        try { Marshal.FinalReleaseComObject(itunes); } catch { }
        itunes = null;
        artworkCache.Clear();
        artworkCacheOrder.Clear();
        artworkCacheBytes = 0;
        librarySnapshot = null;
    }

    private void RunSta()
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
        ReleaseITunes();
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
        new(available, playing, "Nothing playing",
            available ? "Choose a song in iTunes" : "Open iTunes on this computer",
            "", 0, 0, volume, "", "", false, "off");

    private LibraryPage ReadTrackPage(object? trackCollection, int offset, int limit,
        CancellationToken cancellationToken, string collectionKind = "", string collectionValue = "")
    {
        int safeLimit = Math.Clamp(limit, 1, 60);
        if (trackCollection is null) return new LibraryPage([], 0, safeLimit, 0, false);
        dynamic tracks = trackCollection;
        int available = Math.Max(0, Convert.ToInt32(tracks.Count));
        if (collectionKind.Length == 0)
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

        int requestedOffset = Math.Max(0, offset);
        int matching = 0;
        List<LibraryTrack> items = new(safeLimit);
        foreach (object trackObject in tracks)
        {
            cancellationToken.ThrowIfCancellationRequested();
            dynamic track = trackObject;
            try
            {
                if (!MatchesCollection(track, collectionKind, collectionValue)) continue;
                if (matching >= requestedOffset && items.Count < safeLimit)
                    items.Add(ReadLibraryTrack(track));
                matching++;
            }
            finally { ReleaseCom(trackObject); }
        }
        int safeFilteredOffset = Math.Clamp(requestedOffset, 0, matching);
        return new LibraryPage(items, safeFilteredOffset, safeLimit, matching,
            safeFilteredOffset + items.Count < matching);
    }

    private LibraryTrack ReadLibraryTrack(dynamic track)
    {
        string id = RegisterTrack(track);
        string title = ReadString(track, "Name");
        return new LibraryTrack(
            id,
            string.IsNullOrWhiteSpace(title) ? "Untitled" : title,
            ReadString(track, "Artist"),
            ReadString(track, "Album"),
            Math.Max(0, ReadDouble(track, "Duration")),
            Math.Max(0, ReadInt(track, "TrackNumber")),
            Math.Max(0, ReadInt(track, "DiscNumber")),
            id);
    }

    private LibraryCollectionPage ReadGroupedCollections(object appObject, string kind, string query,
        int offset, int limit, CancellationToken cancellationToken)
    {
        LibrarySnapshot snapshot = CurrentLibrarySnapshot()
            ?? ValidatePersistedLibrarySnapshot(appObject, cancellationToken)
            ?? BuildAndPersistLibrarySnapshot(appObject, cancellationToken);
        IEnumerable<LibraryCollection> filtered = kind == "artists"
            ? snapshot.Artists : snapshot.Albums;
        string term = query.Trim();
        if (term.Length > 0)
            filtered = filtered.Where(item => item.Title.Contains(term,
                    StringComparison.OrdinalIgnoreCase)
                || item.Subtitle.Contains(term, StringComparison.OrdinalIgnoreCase));
        return PageCollections(filtered.ToArray(), offset, limit, snapshot.Revision);
    }

    private LibraryCollectionPage ReadGenreCollections(object appObject, string query, int offset,
        int limit, CancellationToken cancellationToken)
    {
        dynamic app = appObject;
        dynamic? playlist = null;
        dynamic? tracks = null;
        try
        {
            playlist = app.LibraryPlaylist;
            tracks = playlist.Tracks;
            Dictionary<string, CollectionAccumulator> genres =
                new(StringComparer.OrdinalIgnoreCase);
            foreach (object trackObject in tracks)
            {
                cancellationToken.ThrowIfCancellationRequested();
                dynamic track = trackObject;
                try
                {
                    string genre = DisplayGenre(ReadString(track, "Genre"));
                    string artworkId = genres.ContainsKey(genre) ? "" : RegisterTrack(track);
                    AddCollection(genres, genre, genre, "", artworkId);
                }
                finally { ReleaseCom(trackObject); }
            }
            IEnumerable<LibraryCollection> filtered = MaterializeCollections("genres", genres);
            string term = query.Trim();
            if (term.Length > 0)
                filtered = filtered.Where(item => item.Title.Contains(term,
                    StringComparison.OrdinalIgnoreCase));
            return PageCollections(filtered.ToArray(), offset, limit);
        }
        finally
        {
            ReleaseCom(tracks);
            ReleaseCom(playlist);
        }
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
            List<LibraryTrack> libraryTracks = new(Math.Max(0, Convert.ToInt32(tracks.Count)));
            foreach (object trackObject in tracks)
            {
                cancellationToken.ThrowIfCancellationRequested();
                dynamic track = trackObject;
                try
                {
                    string artist = DisplayArtist(ReadString(track, "Artist"));
                    string album = DisplayAlbum(ReadString(track, "Album"));
                    string albumKey = artist + "\u001f" + album;
                    LibraryTrack libraryTrack = ReadLibraryTrack(track);
                    libraryTracks.Add(libraryTrack);
                    string artworkId = !artists.ContainsKey(artist) || !albums.ContainsKey(albumKey)
                        ? libraryTrack.ArtworkId : "";
                    AddCollection(artists, artist, artist, "", artworkId);
                    AddCollection(albums, albumKey, album, artist, artworkId);
                }
                finally { ReleaseCom(trackObject); }
            }
            LibraryTrack[] materializedTracks = libraryTracks.ToArray();
            string sourceSignature = ComputeLibrarySourceSignature(appObject, (object)playlist,
                (object)tracks);
            return new LibrarySnapshot(
                materializedTracks,
                MaterializeCollections("artists", artists),
                MaterializeCollections("albums", albums),
                ComputeLibraryRevision(materializedTracks),
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
                snapshot.Artists,
                snapshot.Albums,
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
            persisted.Tracks, persisted.Artists, persisted.Albums, persisted.Revision,
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
        IEnumerable<LibraryTrack> filtered = snapshot.Tracks;
        string term = query.Trim();
        if (term.Length > 0)
        {
            filtered = filtered.Where(track => track.Title.Contains(term,
                    StringComparison.OrdinalIgnoreCase)
                || track.Artist.Contains(term, StringComparison.OrdinalIgnoreCase)
                || track.Album.Contains(term, StringComparison.OrdinalIgnoreCase));
        }
        if (collectionKind.Length > 0)
        {
            filtered = filtered.Where(track => MatchesCollection(track, collectionKind,
                collectionValue));
        }
        LibraryTrack[] results = filtered.ToArray();
        int safeLimit = Math.Clamp(limit, 1, 60);
        int safeOffset = Math.Clamp(offset, 0, results.Length);
        LibraryTrack[] page = results.Skip(safeOffset).Take(safeLimit).ToArray();
        return new LibraryPage(page, safeOffset, safeLimit, results.Length,
            safeOffset + page.Length < results.Length, snapshot.Revision);
    }

    private static bool MatchesCollection(LibraryTrack track, string kind, string value)
    {
        string artist = DisplayArtist(track.Artist);
        if (kind == "artists") return string.Equals(artist, value,
            StringComparison.OrdinalIgnoreCase);
        if (kind == "genres") return false;
        if (kind != "albums") return true;
        string albumKey = artist + "\u001f" + DisplayAlbum(track.Album);
        return string.Equals(albumKey, value, StringComparison.OrdinalIgnoreCase);
    }

    private static string ComputeLibraryRevision(IEnumerable<LibraryTrack> tracks)
    {
        using IncrementalHash hash = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
        foreach (LibraryTrack track in tracks)
        {
            byte[] encoded = Encoding.UTF8.GetBytes(string.Join('\u001f',
                track.Id, track.Title, track.Artist, track.Album,
                track.Duration.ToString("R", CultureInfo.InvariantCulture),
                track.TrackNumber.ToString(CultureInfo.InvariantCulture),
                track.DiscNumber.ToString(CultureInfo.InvariantCulture)) + "\u001e");
            hash.AppendData(encoded);
        }
        return Convert.ToHexString(hash.GetHashAndReset()).ToLowerInvariant();
    }

    private static bool MatchesCollection(dynamic track, string kind, string value)
    {
        string artist = DisplayArtist(ReadString(track, "Artist"));
        if (kind == "artists") return string.Equals(artist, value,
            StringComparison.OrdinalIgnoreCase);
        if (kind == "genres") return string.Equals(
            DisplayGenre(ReadString(track, "Genre")), value,
            StringComparison.OrdinalIgnoreCase);
        if (kind != "albums") return true;
        string albumKey = artist + "\u001f" + DisplayAlbum(ReadString(track, "Album"));
        return string.Equals(albumKey, value, StringComparison.OrdinalIgnoreCase);
    }

    private static string DisplayArtist(string artist) =>
        string.IsNullOrWhiteSpace(artist) ? "Unknown Artist" : artist.Trim();

    private static string DisplayAlbum(string album) =>
        string.IsNullOrWhiteSpace(album) ? "Unknown Album" : album.Trim();

    private static string DisplayGenre(string genre) =>
        string.IsNullOrWhiteSpace(genre) ? "Unknown Genre" : genre.Trim();

    private static string? CollectionAlbumName(string value)
    {
        int separator = value.IndexOf('\u001f');
        return separator >= 0 && separator + 1 < value.Length
            ? value[(separator + 1)..] : null;
    }

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
                ?? throw new InvalidOperationException("Choose a song before changing playback mode");
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

    private static bool ReadBool(dynamic value, string property)
    {
        try
        {
            return property switch
            {
                "Shuffle" => Convert.ToBoolean(value.Shuffle),
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

    private static string ReadString(dynamic value, string property)
    {
        try
        {
            return property switch
            {
                "Name" => Convert.ToString(value.Name) ?? "",
                "Artist" => Convert.ToString(value.Artist) ?? "",
                "Album" => Convert.ToString(value.Album) ?? "",
                "PersistentID" => Convert.ToString(value.PersistentID) ?? "",
                _ => ""
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

    private static double ReadDouble(dynamic value, string property)
    {
        try
        {
            return property == "Duration" ? Convert.ToDouble(value.Duration) : 0;
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

    private static int ReadInt(dynamic value, string property)
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
                _ => 0
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
        artworkCache[key] = artwork;
        artworkCacheOrder.Enqueue(key);
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
