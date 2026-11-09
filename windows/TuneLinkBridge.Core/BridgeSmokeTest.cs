namespace TunesLinkBridge;

/// <summary>Checks the packaged executable's isolated worker without touching iTunes or saved data.</summary>
internal static class BridgeSmokeTest
{
    internal static async Task<int> RunAsync()
    {
        using IsolatedItunesController controller = new("--itunes-worker-demo", diagnostics: false);
        PlaybackState state = await controller.GetStateAsync().ConfigureAwait(false);
        LibraryPage page = await controller.GetLibraryAsync("", 0, 1).ConfigureAwait(false);
        return state.ITunesAvailable && page.Items.Count == 1 ? 0 : 1;
    }
}
