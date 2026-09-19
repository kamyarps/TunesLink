# Demo bridge verification

`TuneLinkDemoBridge` uses the real TLS, pairing, HTTP, and state-stream code with an in-memory music library. It never opens iTunes or modifies a user's library. Use a dedicated `--config-directory` for each test session.

Optional scenarios:

- `--large-library`: 720 songs, including a 120-track album and 601 albums/artists/genres. Scroll past the Android 480-item window and back to the beginning to verify forward and backward pagination. Expand **A Long Album** on a tablet to verify track paging inside the album detail.
- `--library-fault-file <path>`: while this file exists, library requests fail while playback state stays available. Open an uncached album to verify the error and retry UI. Remove the file and retry to recover. This option is confined to the demo harness.
- `--library-delay-ms <milliseconds>`: delay the first continuation request for songs/search to verify loading states and cancellation.
- `--legacy-state`: disable the state stream to exercise polling fallback.

For the Android emulator, connect manually to `10.0.2.2:<port>`. The default test pairing code is `123456`. Run the executable with `--discovery-port 0` to verify that recovery at a saved, reachable address works without UDP discovery.

Automated release regressions run with the existing Android unit tests and Windows `--self-test` command. They cover unavailable optional storage, revoked pairing recovery, lifecycle cancellation, bounded page windows, non-advancing pages, worker failure/recovery, large HTTP offsets, and absolute metadata/artwork cache expiry.
