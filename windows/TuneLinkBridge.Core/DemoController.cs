using System.Drawing.Drawing2D;
using System.Drawing.Imaging;

namespace TunesLinkBridge;

internal sealed class DemoController : IMediaController
{
    private readonly object gate = new();
    private readonly DemoTrack[] tracks = DemoLibrary.CreateTracks(includeArchive: false);
    private int index;
    private bool playing = true;
    private int volume = 64;
    private double position = 72;
    private bool shuffleEnabled;
    private string repeatMode = "off";
    private DateTimeOffset lastTick = DateTimeOffset.UtcNow;

    public Task<PlaybackState> GetStateAsync(CancellationToken cancellationToken = default)
    {
        cancellationToken.ThrowIfCancellationRequested();
        lock (gate)
        {
            Tick();
            DemoTrack track = tracks[index];
            return Task.FromResult(new PlaybackState(true, playing, track.Title, track.Artist,
                track.Album, track.Duration, position, volume, DemoLibrary.TrackId(track),
                DemoLibrary.TrackId(track), shuffleEnabled, repeatMode));
        }
    }

    public Task<LibraryPage> GetLibraryAsync(string query, int offset, int limit,
        CancellationToken cancellationToken = default)
    {
        cancellationToken.ThrowIfCancellationRequested();
        lock (gate)
        {
            return Task.FromResult(DemoLibrary.GetTracks(tracks, query, offset, limit));
        }
    }

    public Task<LibraryCollectionPage> GetCollectionsAsync(string kind, string query, int offset,
        int limit, CancellationToken cancellationToken = default)
    {
        cancellationToken.ThrowIfCancellationRequested();
        lock (gate)
        {
            return Task.FromResult(DemoLibrary.GetCollections(tracks, kind, query, offset, limit));
        }
    }

    public Task<LibraryPage> GetCollectionTracksAsync(string kind, string id, string query,
        int offset, int limit, CancellationToken cancellationToken = default)
    {
        cancellationToken.ThrowIfCancellationRequested();
        lock (gate)
        {
            if (!DemoLibrary.TryGetCollectionTracks(tracks, kind, id, query, offset, limit,
                    out LibraryPage page))
            {
                throw new MediaNotFoundException("That collection is no longer available");
            }
            return Task.FromResult(page);
        }
    }

    public Task PlayTrackAsync(string id, CancellationToken cancellationToken = default)
    {
        cancellationToken.ThrowIfCancellationRequested();
        lock (gate)
        {
            int selected = Array.FindIndex(tracks, track => DemoLibrary.TrackId(track) == id);
            if (selected < 0) throw new MediaNotFoundException("That song is no longer available");
            Tick();
            index = selected;
            position = 0;
            playing = true;
        }
        return Task.CompletedTask;
    }

    public Task ExecuteAsync(PlayerCommand command, CancellationToken cancellationToken = default)
    {
        cancellationToken.ThrowIfCancellationRequested();
        lock (gate)
        {
            Tick();
            switch (command.Command)
            {
                case "playPause": playing = !playing; break;
                case "next": index = (index + 1) % tracks.Length; position = 0; break;
                case "previous": index = (index + tracks.Length - 1) % tracks.Length; position = 0; break;
                case "shuffle":
                    if (command.Value is null) throw new ArgumentException("Shuffle requires a value");
                    shuffleEnabled = command.Value.Value >= 0.5;
                    break;
                case "repeat":
                    if (command.Value is null) throw new ArgumentException("Repeat requires a value");
                    repeatMode = Math.Clamp((int)Math.Round(command.Value.Value), 0, 2) switch
                    {
                        1 => "one",
                        2 => "all",
                        _ => "off",
                    };
                    break;
                case "volume":
                    if (command.Value is null) throw new ArgumentException("Volume requires a value");
                    volume = Math.Clamp((int)Math.Round(command.Value.Value), 0, 100);
                    break;
                case "position":
                    if (command.Value is null) throw new ArgumentException("Position requires a value");
                    position = Math.Clamp(command.Value.Value, 0, tracks[index].Duration);
                    break;
                default: throw new ArgumentException("Unknown command");
            }
        }
        return Task.CompletedTask;
    }

    public Task<ArtworkData?> GetArtworkAsync(string id, int maxSize,
        CancellationToken cancellationToken = default)
    {
        cancellationToken.ThrowIfCancellationRequested();
        lock (gate)
        {
            DemoTrack? track = tracks.FirstOrDefault(item => DemoLibrary.TrackId(item) == id);
            if (track is null) return Task.FromResult<ArtworkData?>(null);
            int size = Math.Clamp(maxSize, 64, 1000);
            using Bitmap bitmap = new(size, size);
            using Graphics graphics = Graphics.FromImage(bitmap);
            graphics.SmoothingMode = SmoothingMode.AntiAlias;
            (Color first, Color second) = track.Title == "Golden Static"
                ? (Color.FromArgb(18, 86, 92), Color.FromArgb(233, 184, 80))
                : (Color.FromArgb(48, 35, 112), Color.FromArgb(230, 89, 124));
            using LinearGradientBrush background = new(new Rectangle(0, 0, size, size),
                first, second, 35f);
            graphics.FillRectangle(background, 0, 0, size, size);
            float scale = size / 900f;
            using SolidBrush haze = new(Color.FromArgb(55, Color.White));
            graphics.FillEllipse(haze, -180 * scale, 280 * scale, 780 * scale, 780 * scale);
            graphics.FillEllipse(haze, 480 * scale, -190 * scale, 570 * scale, 570 * scale);
            using Pen orbit = new(Color.FromArgb(115, Color.White), Math.Max(1, 7 * scale));
            for (int ring = 0; ring < 5; ring++)
                graphics.DrawEllipse(orbit, (155 + ring * 26) * scale, (155 + ring * 26) * scale,
                    (590 - ring * 52) * scale, (590 - ring * 52) * scale);
            using SolidBrush center = new(Color.FromArgb(235, 255, 55, 95));
            graphics.FillEllipse(center, 330 * scale, 330 * scale, 240 * scale, 240 * scale);
            using Font titleFont = new("Segoe UI", Math.Max(8, 42 * scale), FontStyle.Bold, GraphicsUnit.Pixel);
            using Font albumFont = new("Segoe UI", Math.Max(7, 22 * scale), FontStyle.Bold, GraphicsUnit.Pixel);
            using SolidBrush centerType = new(Color.FromArgb(245, Color.White));
            StringFormat format = new() { Alignment = StringAlignment.Center };
            graphics.DrawString("TunesLink", albumFont, centerType,
                new RectangleF(330 * scale, 405 * scale, 240 * scale, 40 * scale), format);
            using SolidBrush white = new(Color.FromArgb(240, Color.White));
            graphics.DrawString(track.Album.ToUpperInvariant(), titleFont, white,
                new RectangleF(80 * scale, 770 * scale, 740 * scale, 80 * scale), format);
            using MemoryStream output = new();
            bitmap.Save(output, ImageFormat.Jpeg);
            return Task.FromResult<ArtworkData?>(new ArtworkData(id, output.ToArray(), "image/jpeg"));
        }
    }

    private void Tick()
    {
        DateTimeOffset now = DateTimeOffset.UtcNow;
        if (playing)
        {
            position += (now - lastTick).TotalSeconds;
            if (position >= tracks[index].Duration)
            {
                index = (index + 1) % tracks.Length;
                position = 0;
            }
        }
        lastTick = now;
    }

    public void Dispose() { }
}
