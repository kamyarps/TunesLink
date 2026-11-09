using System.Globalization;

namespace TunesLinkBridge;

internal sealed partial class ItunesController
{
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
        catch (Exception exception) when (!IsItunesUnreachable(exception)) { return (false, "off"); }
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
        catch (Exception exception) when (!IsItunesUnreachable(exception))
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
        catch (Exception exception) when (!IsItunesUnreachable(exception))
        {
            try
            {
                return Convert.ToBoolean(value.GetType().InvokeMember(property,
                    System.Reflection.BindingFlags.GetProperty, null, value, null));
            }
            catch (Exception reflectionFailure) when (!IsItunesUnreachable(reflectionFailure))
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
        catch (Exception exception) when (!IsItunesUnreachable(exception))
        {
            try
            {
                return Convert.ToString(value.GetType().InvokeMember(property,
                    System.Reflection.BindingFlags.GetProperty, null, value, null)) ?? "";
            }
            catch (Exception reflectionFailure) when (!IsItunesUnreachable(reflectionFailure)) { return ""; }
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
        catch (Exception exception) when (!IsItunesUnreachable(exception))
        {
            try
            {
                return Convert.ToDouble(value.GetType().InvokeMember(property,
                    System.Reflection.BindingFlags.GetProperty, null, value, null));
            }
            catch (Exception reflectionFailure) when (!IsItunesUnreachable(reflectionFailure)) { return 0; }
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
        catch (Exception exception) when (!IsItunesUnreachable(exception))
        {
            try
            {
                return Convert.ToInt32(value.GetType().InvokeMember(property,
                    System.Reflection.BindingFlags.GetProperty, null, value, null));
            }
            catch (Exception reflectionFailure) when (!IsItunesUnreachable(reflectionFailure)) { return 0; }
        }
    }

}
