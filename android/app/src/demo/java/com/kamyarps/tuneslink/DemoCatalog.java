package com.kamyarps.tuneslink;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.function.LongSupplier;

/**
 * The Google Play demo library: original sample music with no audio, plus a simulated player.
 * Pure Java so the unit tests can run it without Android; DemoBridge adapts it to the protocol.
 */
final class DemoCatalog {
    static final String REVISION = "tuneslink-demo-2";

    record Track(String id, String title, String artist, String album, String albumId,
                 String genre, int trackNumber, int duration) {
        String artworkId() {
            return albumId;
        }
    }

    record Collection(String id, String title, String subtitle, List<Track> tracks) {
        String artworkId() {
            return tracks.isEmpty() ? "" : tracks.get(0).artworkId();
        }
    }

    record Page<T>(List<T> items, int offset, int limit, int total) {
        boolean hasMore() {
            return offset + items.size() < total;
        }
    }

    record State(Track track, boolean playing, double position, int volume, boolean shuffle,
                 String repeatMode) {
    }

    private static final Comparator<String> TITLE_ORDER = (left, right) ->
            sortKey(left).compareTo(sortKey(right));

    final List<Track> tracks;
    private final Map<String, List<Collection>> collections = new LinkedHashMap<>();

    DemoCatalog() {
        List<Track> all = new ArrayList<>();
        album(all, "Afterglow Avenue", "Neon Palms", "Electronic", "afterglow-avenue",
                "Midnight Drive", 244, "Glass City", 218, "Low Tide Lights", 261,
                "Slow Fade", 232);
        album(all, "Paper Moons", "Ida Vance", "Indie Pop", "paper-moons",
                "Paper Moons", 201, "Northern Line", 227, "Sunday Letters", 242);
        album(all, "Wavelengths", "The Satellites", "Alternative", "wavelengths",
                "Golden Static", 198, "Orbit", 249, "Radio Silence", 224);
        album(all, "Tidewater", "Mira Solace", "Ambient", "tidewater",
                "First Light", 312, "Salt & Silver", 288, "Undertow", 363);
        album(all, "Wildwood", "Harbor & Pine", "Folk", "wildwood",
                "Cedar Smoke", 216, "River Road", 254, "Lanterns", 209);
        album(all, "Velvet Hours", "Kai Ardent", "Jazz", "velvet-hours",
                "Velvet Hours", 281, "Blue Room", 305, "Last Call", 238);
        album(all, "Static Bloom", "Echo Atlas", "Synthpop", "static-bloom",
                "Neon Garden", 223, "Afterimage", 247, "Bloom", 196);
        album(all, "Copper Sky", "Rowan Hale", "Americana", "copper-sky",
                "Copper Sky", 234, "Long Way Home", 262, "Dust & Daylight", 219);
        tracks = Collections.unmodifiableList(all);

        collections.put("albums", group("albums", all, Track::albumId, Track::album,
                Track::artist));
        collections.put("artists", group("artists", all, Track::artist, Track::artist,
                track -> ""));
        collections.put("genres", group("genres", all, Track::genre, Track::genre,
                track -> ""));
        List<Collection> playlists = new ArrayList<>();
        playlists.add(playlist("Favorites", "Golden Static", "Midnight Drive", "Paper Moons",
                "Lanterns", "Orbit", "Bloom", "Salt & Silver", "Copper Sky"));
        playlists.add(playlist("Late Night", "Midnight Drive", "Velvet Hours", "Afterimage",
                "Blue Room", "Undertow", "Neon Garden", "Glass City", "Long Way Home",
                "Last Call"));
        playlists.add(playlist("Morning Coffee", "First Light", "Paper Moons", "Cedar Smoke",
                "Dust & Daylight", "Sunday Letters", "River Road"));
        collections.put("playlists", Collections.unmodifiableList(playlists));
    }

    Page<Track> songs(String query, int offset, int limit) {
        List<Track> sorted = new ArrayList<>(tracks);
        sorted.sort((left, right) -> TITLE_ORDER.compare(left.title(), right.title()));
        return page(filterTracks(sorted, query), offset, limit);
    }

    /** Returns null when the collection does not exist. */
    Page<Track> collectionSongs(String kind, String id, String query, int offset, int limit) {
        Collection collection = find(kind, id);
        if (collection == null) return null;
        return page(filterTracks(collection.tracks(), query), offset, limit);
    }

    /** Returns null for an unknown kind. */
    Page<Collection> collections(String kind, String query, int offset, int limit) {
        List<Collection> source = collections.get(kind);
        if (source == null) return null;
        return page(filterCollections(source, query), offset, limit);
    }

    /** The albums of an artist or genre; null when the parent does not exist. */
    Page<Collection> collectionAlbums(String kind, String id, String query, int offset,
                                      int limit) {
        if (!"artists".equals(kind) && !"genres".equals(kind)) return null;
        Collection parent = find(kind, id);
        if (parent == null) return null;
        List<Collection> albums = new ArrayList<>();
        for (Collection album : collections.get("albums")) {
            for (Track track : parent.tracks()) {
                if (track.albumId().equals(album.id().substring("albums-".length()))) {
                    albums.add(album);
                    break;
                }
            }
        }
        return page(filterCollections(albums, query), offset, limit);
    }

    Track track(String id) {
        for (Track track : tracks) {
            if (track.id().equals(id)) return track;
        }
        return null;
    }

    boolean hasArtwork(String artworkId) {
        for (Track track : tracks) {
            if (track.artworkId().equals(artworkId)) return true;
        }
        return false;
    }

    private Collection find(String kind, String id) {
        List<Collection> source = collections.get(kind);
        if (source == null || id == null) return null;
        for (Collection collection : source) {
            if (collection.id().equals(id)) return collection;
        }
        return null;
    }

    private static void album(List<Track> into, String album, String artist, String genre,
                              String albumId, Object... songs) {
        for (int index = 0; index < songs.length; index += 2) {
            String title = (String) songs[index];
            into.add(new Track("demo-" + slug(title), title, artist, album, albumId, genre,
                    index / 2 + 1, (Integer) songs[index + 1]));
        }
    }

    private interface Key {
        String of(Track track);
    }

    private static List<Collection> group(String kind, List<Track> source, Key key, Key title,
                                          Key subtitle) {
        Map<String, List<Track>> groups = new LinkedHashMap<>();
        for (Track track : source) {
            groups.computeIfAbsent(key.of(track), ignored -> new ArrayList<>()).add(track);
        }
        List<Collection> result = new ArrayList<>();
        for (List<Track> members : groups.values()) {
            Track first = members.get(0);
            result.add(new Collection(kind + "-" + slug(key.of(first)), title.of(first),
                    subtitle.of(first), Collections.unmodifiableList(members)));
        }
        result.sort((left, right) -> TITLE_ORDER.compare(left.title(), right.title()));
        return Collections.unmodifiableList(result);
    }

    private Collection playlist(String name, String... titles) {
        List<Track> members = new ArrayList<>();
        for (String title : titles) members.add(track("demo-" + slug(title)));
        return new Collection("playlists-" + slug(name), name, "",
                Collections.unmodifiableList(members));
    }

    private static List<Track> filterTracks(List<Track> source, String query) {
        String term = normalize(query);
        if (term.isEmpty()) return source;
        List<Track> matches = new ArrayList<>();
        for (Track track : source) {
            if (normalize(track.title()).contains(term) || normalize(track.artist()).contains(term)
                    || normalize(track.album()).contains(term)) {
                matches.add(track);
            }
        }
        return matches;
    }

    private static List<Collection> filterCollections(List<Collection> source, String query) {
        String term = normalize(query);
        if (term.isEmpty()) return source;
        List<Collection> matches = new ArrayList<>();
        for (Collection collection : source) {
            if (normalize(collection.title()).contains(term)
                    || normalize(collection.subtitle()).contains(term)) {
                matches.add(collection);
            }
        }
        return matches;
    }

    private static <T> Page<T> page(List<T> source, int offset, int limit) {
        int safeLimit = Math.max(1, Math.min(60, limit));
        int start = Math.max(0, Math.min(offset, source.size()));
        int end = Math.min(source.size(), start + safeLimit);
        return new Page<>(new ArrayList<>(source.subList(start, end)), start, safeLimit,
                source.size());
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String sortKey(String title) {
        String key = normalize(title);
        return key.startsWith("the ") ? key.substring(4) : key;
    }

    static String slug(String value) {
        return value.toLowerCase(Locale.ROOT).replace("&", "and")
                .replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }

    /** A simulated iTunes player. Time advances with the injected clock; nothing is audible. */
    static final class Player {
        private final DemoCatalog catalog;
        private final LongSupplier clockMillis;
        private final Random random = new Random(7);
        private List<Track> queue;
        private int index;
        private boolean playing = true;
        private double anchorPosition = 72;
        private long anchorMillis;
        private int volume = 64;
        private boolean shuffle;
        private String repeatMode = "off";

        Player(DemoCatalog catalog, LongSupplier clockMillis) {
            this.catalog = catalog;
            this.clockMillis = clockMillis;
            this.queue = catalog.collections.get("albums").get(0).tracks();
            this.anchorMillis = clockMillis.getAsLong();
        }

        synchronized State state() {
            advance();
            return new State(queue.get(index), playing, currentPosition(), volume, shuffle,
                    repeatMode);
        }

        /** Plays a song from a collection (or from all songs); false when it is unknown. */
        synchronized boolean play(String trackId, String collectionKind, String collectionId) {
            List<Track> source = null;
            if (collectionKind != null && !collectionKind.isBlank()) {
                Collection collection = catalog.find(collectionKind, collectionId);
                if (collection != null) source = collection.tracks();
            }
            if (source == null) source = catalog.songs("", 0, 60).items();
            for (int position = 0; position < source.size(); position++) {
                if (source.get(position).id().equals(trackId)) {
                    queue = source;
                    index = position;
                    playing = true;
                    seek(0);
                    return true;
                }
            }
            return false;
        }

        /** Applies a playback command; false when the command or its value is invalid. */
        synchronized boolean command(String command, Double value) {
            advance();
            switch (command == null ? "" : command) {
                case "playPause" -> {
                    anchorPosition = currentPosition();
                    anchorMillis = clockMillis.getAsLong();
                    playing = !playing;
                }
                case "next" -> skip(1);
                case "previous" -> {
                    if (currentPosition() > 3) seek(0); else skip(-1);
                }
                case "shuffle" -> {
                    if (value == null) return false;
                    shuffle = value >= 0.5;
                }
                case "repeat" -> {
                    if (value == null) return false;
                    repeatMode = switch ((int) Math.round(value)) {
                        case 1 -> "one";
                        case 2 -> "all";
                        default -> "off";
                    };
                }
                case "volume" -> {
                    if (value == null) return false;
                    volume = (int) Math.max(0, Math.min(100, Math.round(value)));
                }
                case "position" -> {
                    if (value == null) return false;
                    seek(Math.max(0, Math.min(queue.get(index).duration(), value)));
                }
                default -> {
                    return false;
                }
            }
            return true;
        }

        private double currentPosition() {
            if (!playing) return anchorPosition;
            return anchorPosition + (clockMillis.getAsLong() - anchorMillis) / 1000.0;
        }

        private void seek(double seconds) {
            anchorPosition = seconds;
            anchorMillis = clockMillis.getAsLong();
        }

        private void skip(int direction) {
            if (shuffle && direction > 0 && queue.size() > 1) {
                int next = index;
                while (next == index) next = random.nextInt(queue.size());
                index = next;
            } else {
                index = Math.floorMod(index + direction, queue.size());
            }
            seek(0);
        }

        /** Moves past songs that finished while nobody was looking, like iTunes would. */
        private void advance() {
            int guard = 0;
            while (playing && currentPosition() >= queue.get(index).duration() && guard++ < 1000) {
                double overflow = currentPosition() - queue.get(index).duration();
                if ("one".equals(repeatMode)) {
                    // Same song again.
                } else if (index == queue.size() - 1 && !shuffle && !"all".equals(repeatMode)) {
                    index = 0;
                    playing = false;
                    seek(0);
                    return;
                } else {
                    skip(1);
                }
                seek(overflow);
            }
        }
    }
}
