namespace TunesLinkBridge;

internal sealed record PlaybackState(
    bool ITunesAvailable,
    bool Playing,
    string Title,
    string Artist,
    string Album,
    double Duration,
    double Position,
    int Volume,
    string ArtworkId,
    string TrackId,
    bool ShuffleEnabled,
    string RepeatMode);

internal sealed record ArtworkData(string Id, byte[] Bytes, string ContentType);

internal sealed record LibraryTrack(
    string Id,
    string Title,
    string Artist,
    string Album,
    double Duration,
    int TrackNumber,
    int DiscNumber,
    string ArtworkId);

internal sealed record LibraryPage(
    IReadOnlyList<LibraryTrack> Items,
    int Offset,
    int Limit,
    int Total,
    bool HasMore,
    string Revision = "");

internal sealed record LibraryCollection(
    string Id,
    string Title,
    string Subtitle,
    int TrackCount,
    string ArtworkId);

internal sealed record LibraryCollectionPage(
    IReadOnlyList<LibraryCollection> Items,
    int Offset,
    int Limit,
    int Total,
    bool HasMore,
    string Revision = "");

internal sealed record PlayerCommand(string Command, double? Value);

internal interface IMediaController : IDisposable
{
    Task<PlaybackState> GetStateAsync(CancellationToken cancellationToken = default);
    Task<LibraryPage> GetLibraryAsync(string query, int offset, int limit,
        CancellationToken cancellationToken = default);
    Task<LibraryCollectionPage> GetCollectionsAsync(string kind, string query, int offset,
        int limit, CancellationToken cancellationToken = default);
    Task<LibraryPage> GetCollectionTracksAsync(string kind, string id, string query, int offset,
        int limit, CancellationToken cancellationToken = default);
    Task PlayTrackAsync(string id, CancellationToken cancellationToken = default);
    Task ExecuteAsync(PlayerCommand command, CancellationToken cancellationToken = default);
    Task<ArtworkData?> GetArtworkAsync(string id, int maxSize,
        CancellationToken cancellationToken = default);
}

internal sealed record BridgeOptions(
    int Port = BridgeProtocol.DefaultPort,
    int DiscoveryPort = BridgeProtocol.DefaultDiscoveryPort,
    bool Headless = false,
    bool Demo = false,
    bool Background = false,
    string? ForcedPairCode = null,
    string? ConfigDirectory = null,
    bool LegacyState = false,
    string? ComputerName = null);
