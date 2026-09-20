using System.Runtime.InteropServices;
using System.Text;

namespace TunesLinkBridge;

internal static class Program
{
    [STAThread]
    private static int Main(string[] args)
    {
        if (!args.Contains("--run-live", StringComparer.Ordinal))
        {
            Console.Error.WriteLine("Pass --run-live to temporarily change playback and add/remove silent QA tracks in the open iTunes library.");
            return 2;
        }
        string prefix = "TunesLink QA " + Guid.NewGuid().ToString("N") + " ";
        string directory = Path.Combine(Path.GetTempPath(), prefix.Trim());
        Directory.CreateDirectory(directory);
        dynamic app = Activator.CreateInstance(Type.GetTypeFromProgID("iTunes.Application", true)!)!;
        dynamic? originalTrack = app.CurrentTrack;
        dynamic? originalPlaylist = app.CurrentPlaylist;
        int position = app.PlayerPosition;
        int volume = app.SoundVolume;
        bool muted = app.Mute;
        bool playing = Convert.ToInt32(app.PlayerState) == 1;
        bool shuffle = originalPlaylist?.Shuffle ?? false;
        int repeat = Convert.ToInt32(originalPlaylist?.SongRepeat ?? 0);
        try
        {
            Console.WriteLine("iTunes " + app.Version);
            Console.WriteLine("QA directory: " + directory);
            app.SoundVolume = 0;
            dynamic fixturePlaylist = app.CreatePlaylist(prefix + "Playlist");
            for (int index = 1; index <= 6; index++)
            {
                string path = Path.Combine(directory, $"track-{index}.wav");
                WriteSilence(path);
                dynamic operation = app.LibraryPlaylist.AddFile(path);
                while (operation.InProgress) Thread.Sleep(100);
                dynamic track = operation.Tracks.Item(1);
                track.Name = $"{prefix}Track {index}";
                track.Artist = prefix + "Artist";
                track.AlbumArtist = prefix + "Artist";
                track.Album = prefix + "Album";
                track.Genre = prefix + "Genre";
                track.DiscNumber = 1;
                track.TrackNumber = index;
                fixturePlaylist.AddTrack(track);
            }
            using ItunesController media = new(directory, managedQueuePrefix: prefix + "Queue ");
            LibraryTrack[] lastTracks = [];
            LibraryCollection? lastCollection = null;
            foreach (string kind in new[] { "albums", "artists", "genres", "playlists" })
            {
                LibraryCollection collection = media.GetCollectionsAsync(kind, prefix, 0, 60).GetAwaiter().GetResult().Items.Single();
                LibraryTrack[] tracks = media.GetCollectionTracksAsync(kind, collection.Id, "", 0, 60).GetAwaiter().GetResult().Items.ToArray();
                lastTracks = tracks;
                lastCollection = collection;
                Require(tracks.Length == 6, "all six fixture tracks are available");
                if (kind is "artists" or "genres")
                {
                    LibraryCollectionPage albums = media.GetCollectionAlbumsAsync(kind, collection.Id, "", 0, 60).GetAwaiter().GetResult();
                    Require(albums.Total == 1 && albums.Items[0].TrackCount == 6, kind + " album drill-down");
                }
                app.CurrentPlaylist.Shuffle = false;
                app.CurrentPlaylist.SongRepeat = 0;
                media.PlayTrackAsync(new(tracks[2].Id, kind, collection.Id)).GetAwaiter().GetResult();
                AssertTrack(media, tracks[2], kind + " starts at track 3");
                media.ExecuteAsync(new("previous", null)).GetAwaiter().GetResult();
                AssertTrack(media, tracks[1], kind + " previous reaches before the initial selection");
                media.PlayTrackAsync(new(tracks[2].Id, kind, collection.Id)).GetAwaiter().GetResult();
                media.ExecuteAsync(new("next", null)).GetAwaiter().GetResult();
                AssertTrack(media, tracks[3], kind + " next is track 4");
                media.PlayTrackAsync(new(tracks[4].Id, kind, collection.Id)).GetAwaiter().GetResult();
                media.ExecuteAsync(new("next", null)).GetAwaiter().GetResult();
                AssertTrack(media, tracks[5], kind + " reused queue next is track 6");
                media.PlayTrackAsync(new(tracks[2].Id, kind, collection.Id)).GetAwaiter().GetResult();
                media.ExecuteAsync(new("position", 7)).GetAwaiter().GetResult();
                Thread.Sleep(1800);
                AssertTrack(media, tracks[3], kind + " natural completion is track 4");
                media.ExecuteAsync(new("previous", null)).GetAwaiter().GetResult();
                AssertTrack(media, tracks[2], kind + " previous is track 3");
                media.ExecuteAsync(new("shuffle", 1)).GetAwaiter().GetResult();
                media.PlayTrackAsync(new(tracks[3].Id, kind, collection.Id)).GetAwaiter().GetResult();
                AssertTrack(media, tracks[3], kind + " selection with shuffle enabled");
                Require(media.GetStateAsync().GetAwaiter().GetResult().ShuffleEnabled, "shuffle setting preserved");
                Require(Convert.ToInt32(app.CurrentPlaylist.Tracks.Count) == 6, "shuffle includes the entire collection");
                media.ExecuteAsync(new("shuffle", 0)).GetAwaiter().GetResult();
                media.ExecuteAsync(new("repeat", 2)).GetAwaiter().GetResult();
                media.PlayTrackAsync(new(tracks[5].Id, kind, collection.Id)).GetAwaiter().GetResult();
                media.ExecuteAsync(new("next", null)).GetAwaiter().GetResult();
                AssertTrack(media, tracks[0], kind + " repeat all wraps to the original first track");
                media.ExecuteAsync(new("repeat", 0)).GetAwaiter().GetResult();
                media.PlayTrackAsync(new(tracks[5].Id, kind, collection.Id)).GetAwaiter().GetResult();
                media.ExecuteAsync(new("position", 7)).GetAwaiter().GetResult();
                Thread.Sleep(1800);
                Require(!media.GetStateAsync().GetAwaiter().GetResult().Playing, kind + " repeat off stops at the collection end");
                media.PlayTrackAsync(new(tracks[2].Id, kind, collection.Id)).GetAwaiter().GetResult();
                media.ExecuteAsync(new("playPause", null)).GetAwaiter().GetResult();
                media.ExecuteAsync(new("position", 3)).GetAwaiter().GetResult();
                media.ExecuteAsync(new("repeat", 2)).GetAwaiter().GetResult();
                PlaybackState paused = media.GetStateAsync().GetAwaiter().GetResult();
                Require(!paused.Playing && Math.Abs(paused.Position - 3) < 1, "mode changes preserve pause and position");
                media.ExecuteAsync(new("repeat", 0)).GetAwaiter().GetResult();
            }
            media.PlayTrackAsync(new(lastTracks[2].Id, "playlists", lastCollection!.Id)).GetAwaiter().GetResult();
            media.Dispose();
            using (ItunesController recovered = new(directory, managedQueuePrefix: prefix + "Queue "))
            {
                AssertTrack(recovered, lastTracks[2], "queue identity survives worker restart");
                recovered.ExecuteAsync(new("previous", null)).GetAwaiter().GetResult();
                AssertTrack(recovered, lastTracks[1], "previous can recover earlier tracks after worker restart");
            }
            Console.WriteLine("Live iTunes queue regressions passed.");
            return 0;
        }
        catch (Exception exception)
        {
            Console.Error.WriteLine(exception);
            return 1;
        }
        finally
        {
            try
            {
                if (originalTrack is not null)
                {
                    originalTrack.Play();
                    app.PlayerPosition = position;
                    if (!playing) app.Pause();
                }
                else app.Stop();
                if (originalPlaylist is not null)
                {
                    originalPlaylist.Shuffle = shuffle;
                    originalPlaylist.SongRepeat = repeat;
                }
            }
            finally
            {
                app.SoundVolume = volume;
                app.Mute = muted;
                dynamic playlists = app.LibrarySource.Playlists;
                for (int index = playlists.Count; index >= 1; index--)
                {
                    dynamic playlist = playlists.Item(index);
                    if (((string)playlist.Name).StartsWith(prefix, StringComparison.Ordinal)) playlist.Delete();
                }
                dynamic tracks = app.LibraryPlaylist.Tracks;
                for (int index = tracks.Count; index >= 1; index--)
                {
                    dynamic track = tracks.Item(index);
                    if (((string)track.Name).StartsWith(prefix, StringComparison.Ordinal)) track.Delete();
                }
                Marshal.FinalReleaseComObject(app);
                Console.WriteLine("Original playback settings restored; temporary library entries removed.");
            }
        }
    }

    private static void AssertTrack(ItunesController media, LibraryTrack expected, string label)
    {
        PlaybackState state = media.GetStateAsync().GetAwaiter().GetResult();
        Require(state.TrackId == expected.Id, label + $" (got '{state.Title}')");
    }

    private static void Require(bool condition, string label)
    {
        if (!condition) throw new InvalidOperationException(label);
        Console.WriteLine("PASS " + label);
    }

    private static void WriteSilence(string path)
    {
        const int length = 8000 * 2 * 8;
        using BinaryWriter writer = new(File.Create(path));
        writer.Write(Encoding.ASCII.GetBytes("RIFF")); writer.Write(36 + length);
        writer.Write(Encoding.ASCII.GetBytes("WAVEfmt ")); writer.Write(16);
        writer.Write((short)1); writer.Write((short)1); writer.Write(8000); writer.Write(16000);
        writer.Write((short)2); writer.Write((short)16);
        writer.Write(Encoding.ASCII.GetBytes("data")); writer.Write(length);
        writer.Write(new byte[length]);
    }
}
