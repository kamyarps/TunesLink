using System.Text.Json;

namespace TunesLinkBridge;

internal sealed partial class ItunesController
{
    private sealed record ManagedQueue(int PlaylistId, string Name, string Kind, string Filter,
        QueueTrack[] Tracks, int[] Order, Dictionary<int, int> TrackIndices);

    private readonly string queueStatePath;
    private readonly IAtomicFilePersistence queuePersistence;

    // A selected song must be the first entry passed to PlayFirstTrack: Track.Play leaves
    // Up Next at its previous cursor, and walking NextTrack is too slow for large collections.
    internal static int[] QueueOrder(int count, int selected, bool shuffle, string repeat) =>
        selected < 0 || selected >= count ? throw new ArgumentOutOfRangeException(nameof(selected))
        : [.. Enumerable.Range(selected, count - selected),
            .. (shuffle || repeat == "all" ? Enumerable.Range(0, selected) : [])];

    private void PlayManagedCollection(object appObject, string trackId, string kind,
        string collectionId, CancellationToken cancellationToken)
    {
        if (!ItunesCollectionId.TryDecodeText(collectionId, kind, out string filter))
            throw new MediaNotFoundException("That collection is no longer available");
        QueueTrack[] tracks = [.. LibraryGrouping.InCollectionOrder(
            SelectCollectionTracks(appObject, kind, filter, cancellationToken),
            item => item.Album, item => item.AlbumArtist,
            item => item.DiscNumber, item => item.TrackNumber, item => item.OriginalIndex)];
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
            foreach (object track in sourceTracks)
            {
                try
                {
                    cancellationToken.ThrowIfCancellationRequested();
                    tracks.Add(new QueueTrack(RegisterTrack(track), "", "", 0, 0, tracks.Count));
                }
                finally { ReleaseCom(track); }
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
        CancellationToken cancellationToken, bool preserveCurrentPlayback = false)
    {
        dynamic app = appObject;
        dynamic? playlist = null;
        bool activated = false;
        string name = managedQueuePrefix + Guid.NewGuid().ToString("N")[..8];
        int[] order = QueueOrder(tracks.Length, selected, shuffle, repeat);
        Dictionary<int, int> indices = [];
        try
        {
            playlist = app.CreatePlaylist(name);
            foreach (int index in order)
            {
                cancellationToken.ThrowIfCancellationRequested();
                dynamic? source = null;
                dynamic? added = null;
                try
                {
                    source = ResolveTrack(app, tracks[index].Id)
                        ?? throw new MediaNotFoundException("A song in that collection is no longer available");
                    added = playlist.AddTrack(source);
                    indices.Add(ReadInt((object)added, "TrackID"), index);
                }
                finally { ReleaseCom(added); ReleaseCom(source); }
            }
            SetProperty(playlist, "Shuffle", false);
            SetProperty(playlist, "SongRepeat", repeat == "one" ? 1 : repeat == "all" ? 2 : 0);
            cancellationToken.ThrowIfCancellationRequested();
            if (preserveCurrentPlayback)
            {
                if (!TryActiveQueue(appObject, out ManagedQueue current, out int currentIndex)
                    || current.Tracks[currentIndex].Id != tracks[selected].Id)
                    throw new MediaUnavailableException("Playback changed while updating the queue; try again");
                position = Convert.ToDouble(app.PlayerPosition);
                playing = Convert.ToInt32(app.PlayerState) == 1;
            }
            bool muted = Convert.ToBoolean(app.Mute);
            try
            {
                app.Mute = true;
                playlist.PlayFirstTrack();
                if (position > 0) app.PlayerPosition = position;
                if (!playing) app.Pause();
                SetProperty(playlist, "Shuffle", shuffle);
            }
            finally { app.Mute = muted; }
            activated = true;
            int playlistId = ReadInt((object)playlist, "PlaylistID");
            managedQueue = new(playlistId, name, kind, filter, tracks, order, indices);
            SaveManagedQueue();
            CleanupManagedQueues(appObject, playlistId);
        }
        finally
        {
            if (!activated && playlist is not null)
            {
                try { playlist.Delete(); } catch { }
            }
            ReleaseCom(playlist);
        }
    }

    private bool TryActiveQueue(object appObject, out ManagedQueue active, out int index)
    {
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
        dynamic app = appObject;
        ActivateManagedQueue(appObject, active.Tracks, index - 1, active.Kind, active.Filter,
            shuffle, repeat, 0, Convert.ToInt32(app.PlayerState) == 1, cancellationToken);
        return true;
    }

    private bool TryChangeManagedModes(object appObject, bool? requestedShuffle, int? requestedRepeat,
        CancellationToken cancellationToken)
    {
        if (!TryActiveQueue(appObject, out ManagedQueue active, out int index)) return false;
        (bool shuffle, string repeat) = ReadPlaybackModes(appObject);
        shuffle = requestedShuffle ?? shuffle;
        repeat = requestedRepeat is { } value ? value == 1 ? "one" : value == 2 ? "all" : "off" : repeat;
        dynamic app = appObject;
        ActivateManagedQueue(appObject, active.Tracks, index, active.Kind, active.Filter,
            shuffle, repeat, Convert.ToDouble(app.PlayerPosition), Convert.ToInt32(app.PlayerState) == 1,
            cancellationToken, preserveCurrentPlayback: true);
        return true;
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
                || value.TrackIndices is null || value.TrackIndices.Count != value.Order.Length
                || value.Tracks.Any(track => track is null || track.Id is null
                    || !ItunesTrackId.TryDecode(track.Id, out _))
                || value.Order.Any(index => index < 0 || index >= value.Tracks.Length)
                || value.TrackIndices.Any(pair => pair.Key <= 0)
                || value.Order.Distinct().Count() != value.Order.Length
                || !value.Order.ToHashSet().SetEquals(value.TrackIndices.Values))
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
