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
            passed.Add("state");

            Progress("Reading the song library");
            LibraryPage library = await controller.GetLibraryAsync("", 0, 5)
                .ConfigureAwait(false);
            passed.Add($"library ({library.Total} tracks)");

            foreach (string kind in new[] { "artists", "albums", "genres", "playlists" })
            {
                Progress($"Browsing {kind}");
                LibraryCollectionPage collections = await controller.GetCollectionsAsync(
                    kind, "", 0, 5).ConfigureAwait(false);
                LibraryCollection? populated = collections.Items.FirstOrDefault(
                    collection => collection.TrackCount > 0);
                if (populated is null) continue;
                LibraryPage scoped = await controller.GetCollectionTracksAsync(kind,
                    populated.Id, "", 0, 5).ConfigureAwait(false);
                Require(scoped.Total > 0 && scoped.Items.Count > 0,
                    $"The first {kind} collection returned no tracks");
                passed.Add($"{kind} ({collections.Total})");
            }

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
