namespace TunesLinkBridge;

internal static partial class BridgeSelfTest
{
    private static void TestIssue4Regressions()
    {
        Ensure(ItunesController.QueueOrder(6, 2, false, "off").SequenceEqual([2, 3, 4, 5]),
            "starting in the middle ends at the collection end with repeat off");
        Ensure(ItunesController.QueueOrder(6, 4, false, "off").SequenceEqual([4, 5]),
            "reselection starts a new native queue at the selected track");
        Ensure(ItunesController.QueueOrder(6, 2, false, "all").SequenceEqual([2, 3, 4, 5, 0, 1]),
            "repeat all includes the entire collection in its cyclic order");
        Ensure(ItunesController.QueueOrder(6, 2, true, "off").Order().SequenceEqual([0, 1, 2, 3, 4, 5]),
            "shuffle can reach every track including tracks before the selection");
        Ensure(ItunesController.QueueOrder(6, 2, false, "one").SequenceEqual([2, 3, 4, 5]),
            "repeat one retains normal forward order for manual Next");

        LibraryTrack[] tracks = Enumerable.Range(0, 121).Select(index => new LibraryTrack(
            index.ToString(System.Globalization.CultureInfo.InvariantCulture), "Song", "Performer",
            "Album " + (index / 2).ToString("D3", System.Globalization.CultureInfo.InvariantCulture),
            8, 1, 1, "", "Artist")).ToArray();
        LibraryCollectionPage first = CollectionAlbums.Page(tracks, "", 0, 60, "r1");
        LibraryCollectionPage last = CollectionAlbums.Page(tracks, "", 60, 60, "r1");
        Ensure(first.Total == 61 && first.Items.Count == 60 && first.HasMore
            && last.Items.Count == 1 && !last.HasMore, "albums paginate as albums, not track-page fragments");
        LibraryTrack[] sameTitles = [tracks[0], tracks[0] with { Id = "other", AlbumArtist = "Other artist" }];
        Ensure(CollectionAlbums.Page(sameTitles, "", 0, 60, "r1").Total == 2,
            "same-title albums by different album artists stay separate");
        Ensure(CollectionAlbums.Page(tracks, "Album 060", 0, 60, "r1").Total == 1,
            "scoped album search");
        Ensure(CollectionAlbums.Page(tracks, "", int.MaxValue, 60, "r1").Items.Count == 0,
            "scoped album offsets beyond the end are safe");
    }

}
