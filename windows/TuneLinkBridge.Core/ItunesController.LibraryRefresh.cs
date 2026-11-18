using System.Diagnostics;

namespace TunesLinkBridge;

internal sealed partial class ItunesController
{
    // Reading a large library takes tens of seconds of COM calls. Once the index has expired, the
    // previous one keeps answering browse requests while a replacement is read in small batches
    // queued behind other requests, so an expired index never blocks browsing.
    internal const int LibraryRefreshBatch = 40;
    // Up to four 60-row pages can be filtered directly before building a reusable index.
    private const int DirectScanLimit = 240;

    private void RequestLibraryIndex()
    {
        IndexPending = true;
        throw new LibraryIndexingException();
    }

    internal bool HostDrivesIndex { get; set; }
    internal bool IndexPending { get; private set; }

    internal Task<bool> AdvanceIndexAsync(CancellationToken token) => Invoke(() =>
    {
        if (!IndexPending) return true;
        try
        {
            LibraryRefresh refresh = libraryRefresh ??= new(++libraryRefreshGeneration,
                new LibrarySnapshotBuilder(this, GetITunes(), token));
            return ReadRefreshBatch(refresh, token);
        }
        catch
        {
            AbandonLibraryRefresh();
            throw;
        }
    }, token);

    private LibrarySnapshot? staleLibrarySnapshot;
    private LibraryRefresh? libraryRefresh;
    private int libraryRefreshGeneration;

    private sealed class LibraryRefresh(int generation, LibrarySnapshotBuilder builder)
    {
        public int Generation { get; } = generation;
        public LibrarySnapshotBuilder Builder { get; } = builder;
        public long StartedAt { get; } = Stopwatch.GetTimestamp();
        public bool Continuing { get; set; }
    }

    /// <summary>
    /// A validated index, or the expired one while a replacement is read in the background
    /// (<paramref name="refresh"/> starts that read). Null only when there has never been one.
    /// </summary>
    private LibrarySnapshot? UsableLibrarySnapshot(object appObject,
        CancellationToken cancellationToken, bool refresh = true)
    {
        LibrarySnapshot? snapshot = CurrentLibrarySnapshot()
            ?? ValidatePersistedLibrarySnapshot(appObject, cancellationToken);
        if (snapshot is not null) return snapshot;
        if (staleLibrarySnapshot is not { } stale) return null;
        if (refresh) StartLibraryRefresh(appObject);
        return stale;
    }

    private void StartLibraryRefresh(object appObject)
    {
        if (HostDrivesIndex)
        {
            IndexPending = true;
            return;
        }
        if (libraryRefresh is not null) return;
        LibraryRefresh started = new(++libraryRefreshGeneration,
            new LibrarySnapshotBuilder(this, appObject, CancellationToken.None));
        libraryRefresh = started;
        ContinueInBackground(started);
    }

    private void ContinueInBackground(LibraryRefresh refresh)
    {
        if (refresh.Continuing) return;
        refresh.Continuing = true;
        EnqueueInternal(() => ContinueLibraryRefresh(refresh));
    }

    /// <summary>
    /// Reads the first index while the request waits. A large library can take longer than one
    /// request may, so a request that runs out of time hands its progress to the background read
    /// instead of discarding it; the next request finds the read further along, or finished.
    /// </summary>
    private LibrarySnapshot ReadFirstLibrarySnapshot(object appObject,
        CancellationToken cancellationToken)
    {
        if (HostDrivesIndex)
        {
            RequestLibraryIndex();
        }
        LibraryRefresh refresh = libraryRefresh ??= new LibraryRefresh(++libraryRefreshGeneration,
            new LibrarySnapshotBuilder(this, appObject, cancellationToken));
        LibrarySnapshot snapshot;
        try
        {
            while (!refresh.Builder.Read(LibraryRefreshBatch, cancellationToken)) { }
            snapshot = refresh.Builder.Complete();
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            ContinueInBackground(refresh);
            throw;
        }
        catch
        {
            if (ReferenceEquals(libraryRefresh, refresh)) libraryRefresh = null;
            refresh.Builder.Dispose();
            throw;
        }
        // A queued background batch finds the refresh gone and only releases the builder.
        if (ReferenceEquals(libraryRefresh, refresh)) libraryRefresh = null;
        refresh.Builder.Dispose();
        PersistLibrarySnapshot(snapshot);
        staleLibrarySnapshot = null;
        BridgeDiagnostics.RecordDuration("library.index",
            (long)Stopwatch.GetElapsedTime(refresh.StartedAt).TotalMilliseconds);
        return snapshot;
    }

    private void ContinueLibraryRefresh(LibraryRefresh refresh)
    {
        if (!ReferenceEquals(libraryRefresh, refresh)
            || refresh.Generation != libraryRefreshGeneration)
        {
            refresh.Builder.Dispose();
            return;
        }
        try
        {
            if (!ReadRefreshBatch(refresh, CancellationToken.None))
            {
                EnqueueInternal(() => ContinueLibraryRefresh(refresh));
            }
        }
        catch (Exception exception)
        {
            // The expired index keeps answering; the next browse request tries again.
            BridgeDiagnostics.Record("library.refresh", exception);
            AbandonLibraryRefresh();
        }
    }

    private bool ReadRefreshBatch(LibraryRefresh refresh, CancellationToken token)
    {
        if (!refresh.Builder.Read(LibraryRefreshBatch, token)) return false;
        PersistLibrarySnapshot(refresh.Builder.Complete());
        staleLibrarySnapshot = null;
        BridgeDiagnostics.RecordDuration("library.refresh",
            (long)Stopwatch.GetElapsedTime(refresh.StartedAt).TotalMilliseconds);
        libraryRefresh = null;
        refresh.Builder.Dispose();
        IndexPending = false;
        return true;
    }

    private void AbandonLibraryRefresh()
    {
        IndexPending = false;
        libraryRefreshGeneration++;
        if (libraryRefresh is not { } refresh) return;
        libraryRefresh = null;
        refresh.Builder.Dispose();
    }

    /// <summary>Reads the library into an index, a batch of songs at a time.</summary>
    private sealed class LibrarySnapshotBuilder : IDisposable
    {
        private readonly ItunesController owner;
        private readonly object appObject;
        private readonly object playlist;
        private readonly object tracks;
        private readonly IEnumerator<object> items;
        private readonly HashSet<int> excluded = [];
        private IEnumerator<bool>? exclusionScan;
        private readonly Dictionary<string, CollectionAccumulator> artists =
            new(StringComparer.OrdinalIgnoreCase);
        private readonly Dictionary<string, CollectionAccumulator> albums =
            new(StringComparer.OrdinalIgnoreCase);
        private readonly Dictionary<string, CollectionAccumulator> genres =
            new(StringComparer.OrdinalIgnoreCase);
        private readonly List<LibraryTrack> libraryTracks;
        private readonly List<string> libraryGenres;
        private bool disposed;

        public LibrarySnapshotBuilder(ItunesController owner, object appObject,
            CancellationToken cancellationToken)
        {
            this.owner = owner;
            this.appObject = appObject;
            dynamic app = appObject;
            playlist = app.LibraryPlaylist;
            try
            {
                tracks = ((dynamic)playlist).Tracks;
                // Songs come from the library playlist so their IDs stay stable; the library's
                // videos, podcasts, and audiobooks are left out of Songs, Artists, Albums, and
                // Genres.
                cancellationToken.ThrowIfCancellationRequested();
                exclusionScan = ScanExcludedMedia(appObject, tracks, excluded, null).GetEnumerator();
                int expected = Math.Max(0, Convert.ToInt32(((dynamic)tracks).Count));
                libraryTracks = new(expected);
                libraryGenres = new(expected);
                items = ComItems(tracks).GetEnumerator();
            }
            catch
            {
                ReleaseCom(tracks);
                ReleaseCom(playlist);
                throw;
            }
        }

        /// <summary>Reads up to <paramref name="count"/> songs; true once every song is read.</summary>
        public bool Read(int count, CancellationToken cancellationToken)
        {
            long started = Stopwatch.GetTimestamp();
            for (int read = 0; read < count; read++)
            {
                cancellationToken.ThrowIfCancellationRequested();
                if (read > 0 && Stopwatch.GetElapsedTime(started).TotalMilliseconds >= 100) return false;
                if (exclusionScan is not null)
                {
                    try
                    {
                        if (exclusionScan.MoveNext()) continue;
                    }
                    catch (Exception exception)
                    {
                        BridgeDiagnostics.Record("library.media", exception);
                        excluded.Clear();
                    }
                    exclusionScan.Dispose();
                    exclusionScan = null;
                }
                if (!items.MoveNext()) return true;
                object trackObject = items.Current;
                dynamic track = trackObject;
                try
                {
                    ItunesTrackLocator locator = ReadLocator(trackObject);
                    if (excluded.Contains(locator.DatabaseId)) continue;
                    string artist = LibraryGrouping.DisplayArtist(ReadString(track, "Artist"));
                    string album = LibraryGrouping.DisplayAlbum(ReadString(track, "Album"));
                    string albumArtist = LibraryGrouping.AlbumArtist(artist,
                        ReadString(track, "AlbumArtist"), ReadBool(track, "Compilation"));
                    string albumKey = LibraryGrouping.AlbumKey(albumArtist, album);
                    string genre = LibraryGrouping.DisplayGenre(ReadString(track, "Genre"));
                    LibraryTrack libraryTrack = owner.ReadLibraryTrack(track, artist, album,
                        albumArtist, ItunesTrackId.Encode(locator));
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
            return false;
        }

        public LibrarySnapshot Complete()
        {
            LibraryTrack[] materializedTracks = libraryTracks.ToArray();
            string[] materializedGenres = libraryGenres.ToArray();
            DateTimeOffset now = owner.timeProvider.GetUtcNow();
            return new LibrarySnapshot(
                materializedTracks,
                materializedGenres,
                MaterializeCollections("artists", artists),
                MaterializeCollections("albums", albums),
                MaterializeCollections("genres", genres),
                ComputeLibraryRevision(materializedTracks, materializedGenres),
                ComputeLibrarySourceSignature(appObject, playlist, tracks),
                now,
                now);
        }

        public void Dispose()
        {
            if (disposed) return;
            disposed = true;
            exclusionScan?.Dispose();
            items.Dispose();
            ReleaseCom(tracks);
            ReleaseCom(playlist);
        }
    }
}
