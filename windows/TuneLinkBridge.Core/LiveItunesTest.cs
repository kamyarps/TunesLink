namespace TunesLinkBridge;

internal static class LiveItunesTest
{
    public static async Task<int> RunAsync()
    {
        using IsolatedItunesController controller = new(diagnostics: false);
        PlaybackState? original = null;
        List<string> passed = [];
        string artworkResult = "skipped (current track has no artwork)";
        try
        {
            Progress("Reading playback state");
            original = await controller.GetStateAsync().ConfigureAwait(false);
            Require(original.ITunesAvailable, "iTunes Legacy is not available");
            Require(original.TrackId.Length >= 8, "Choose a current track in iTunes first");
            passed.Add("state");

            Progress("Reading the song library");
            LibraryPage library = await controller.GetLibraryAsync("", 0, 5)
                .ConfigureAwait(false);
            Require(library.Total > 0 && library.Items.Count > 0,
                "The iTunes library returned no tracks");
            passed.Add($"library ({library.Total} tracks)");

            foreach (string kind in new[] { "artists", "albums", "genres", "playlists" })
            {
                Progress($"Browsing {kind}");
                LibraryCollectionPage collections = await controller.GetCollectionsAsync(
                    kind, "", 0, 5).ConfigureAwait(false);
                Require(collections.Total > 0 && collections.Items.Count > 0,
                    $"The iTunes library returned no {kind}");
                LibraryCollection? populated = collections.Items.FirstOrDefault(
                    collection => collection.TrackCount > 0);
                Require(populated is not null,
                    $"The first page of {kind} contained no populated collection");
                LibraryPage scoped = await controller.GetCollectionTracksAsync(kind,
                    populated!.Id, "", 0, 5).ConfigureAwait(false);
                Require(scoped.Total > 0 && scoped.Items.Count > 0,
                    $"The first {kind} collection returned no tracks");
                passed.Add($"{kind} ({collections.Total})");
            }

            await controller.ExecuteAsync(new PlayerCommand("volume", 0))
                .ConfigureAwait(false);
            await RequireStateAsync(controller, state => state.Volume == 0,
                "volume mute").ConfigureAwait(false);
            passed.Add("volume");

            bool testedShuffle = !original.ShuffleEnabled;
            await controller.ExecuteAsync(new PlayerCommand("shuffle", testedShuffle ? 1 : 0))
                .ConfigureAwait(false);
            await RequireStateAsync(controller, state => state.ShuffleEnabled == testedShuffle,
                "shuffle toggle").ConfigureAwait(false);
            passed.Add("shuffle");

            int testedRepeatValue = original.RepeatMode == "all" ? 1 : 2;
            string testedRepeat = testedRepeatValue == 1 ? "one" : "all";
            await controller.ExecuteAsync(new PlayerCommand("repeat", testedRepeatValue))
                .ConfigureAwait(false);
            await RequireStateAsync(controller, state => state.RepeatMode == testedRepeat,
                "repeat mode").ConfigureAwait(false);
            passed.Add("repeat one/all");

            await controller.PlayTrackAsync(original.TrackId).ConfigureAwait(false);
            await RequireStateAsync(controller,
                state => state.Playing && state.TrackId == original.TrackId,
                "current-track playback").ConfigureAwait(false);
            passed.Add("play track");

            await controller.ExecuteAsync(new PlayerCommand("playPause", null))
                .ConfigureAwait(false);
            await RequireStateAsync(controller, state => !state.Playing,
                "play/pause").ConfigureAwait(false);
            passed.Add("play/pause");

            double testedPosition = Math.Min(original.Duration,
                Math.Max(0, original.Position + 2));
            await controller.ExecuteAsync(new PlayerCommand("position", testedPosition))
                .ConfigureAwait(false);
            await RequireStateAsync(controller,
                state => Math.Abs(state.Position - testedPosition) < 1,
                "seek").ConfigureAwait(false);
            passed.Add("seek");

            if (original.ArtworkId.Length > 0)
            {
                ArtworkData? artwork = await controller.GetArtworkAsync(original.ArtworkId, 180)
                    .ConfigureAwait(false);
                Require(artwork is { Bytes.Length: > 100 },
                    "Current-track artwork could not be decoded");
                artworkResult = $"passed ({artwork!.Bytes.Length} bytes)";
            }

            Console.WriteLine("Live iTunes test passed:");
            foreach (string item in passed) Console.WriteLine("  - " + item);
            Console.WriteLine("  - artwork: " + artworkResult);
            return 0;
        }
        catch (Exception exception)
        {
            Console.Error.WriteLine("Live iTunes test failed: " + exception.Message);
            return 1;
        }
        finally
        {
            if (original is not null)
                await RestoreAsync(controller, original).ConfigureAwait(false);
        }
    }

    private static async Task<PlaybackState> RequireStateAsync(
        IsolatedItunesController controller, Func<PlaybackState, bool> predicate, string label)
    {
        for (int attempt = 0; attempt < 20; attempt++)
        {
            PlaybackState state = await controller.GetStateAsync().ConfigureAwait(false);
            if (predicate(state)) return state;
            await Task.Delay(150).ConfigureAwait(false);
        }
        throw new InvalidOperationException("Timed out waiting for " + label);
    }

    private static async Task RestoreAsync(
        IsolatedItunesController controller, PlaybackState original)
    {
        List<Exception> failures = [];
        await TryRestoreAsync(() => controller.ExecuteAsync(new PlayerCommand(
            "shuffle", original.ShuffleEnabled ? 1 : 0)), failures).ConfigureAwait(false);
        double repeat = original.RepeatMode switch
        {
            "one" => 1,
            "all" => 2,
            _ => 0,
        };
        await TryRestoreAsync(() => controller.ExecuteAsync(new PlayerCommand("repeat", repeat)),
            failures).ConfigureAwait(false);
        await TryRestoreAsync(() => controller.ExecuteAsync(new PlayerCommand(
            "position", original.Position)), failures).ConfigureAwait(false);
        await TryRestoreAsync(async () =>
        {
            PlaybackState current = await controller.GetStateAsync().ConfigureAwait(false);
            if (current.Playing != original.Playing)
                await controller.ExecuteAsync(new PlayerCommand("playPause", null))
                    .ConfigureAwait(false);
        }, failures).ConfigureAwait(false);
        await TryRestoreAsync(() => controller.ExecuteAsync(new PlayerCommand(
            "volume", original.Volume)), failures).ConfigureAwait(false);

        if (failures.Count > 0)
            Console.Error.WriteLine("Warning: one or more iTunes settings could not be restored: "
                + string.Join("; ", failures.Select(failure => failure.Message)));
    }

    private static async Task TryRestoreAsync(Func<Task> action, List<Exception> failures)
    {
        try { await action().ConfigureAwait(false); }
        catch (Exception exception) { failures.Add(exception); }
    }

    private static void Require(bool condition, string message)
    {
        if (!condition) throw new InvalidOperationException(message);
    }

    private static void Progress(string message)
    {
        Console.WriteLine("Testing: " + message + "...");
        Console.Out.Flush();
    }
}
