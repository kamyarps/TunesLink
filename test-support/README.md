# Demo bridge verification

`TuneLinkDemoBridge` uses the real TLS, pairing, HTTP, and state-stream code with an in-memory music library. It never opens iTunes or modifies a user's library. Use a dedicated `--config-directory` for each test session.

Optional scenarios:

- `--large-library`: 720 songs, including a 120-track album and 601 albums/artists/genres. Scroll past the Android 480-item window and back to the beginning to verify forward and backward pagination. Expand **A Long Album** on a tablet to verify track paging inside the album detail.
- `--library-fault-file <path>`: while this file exists, library requests fail while playback state stays available. Open an uncached album to verify the error and retry UI. Remove the file and retry to recover. This option is confined to the demo harness.
- `--library-delay-ms <milliseconds>`: delay the first continuation request for songs/search to verify loading states and cancellation.
- `--legacy-state`: disable the state stream to exercise polling fallback.

For the Android emulator, connect manually to `10.0.2.2:<port>`. The default test pairing code is `123456`. Run the executable with `--discovery-port 0` to verify that recovery at a saved, reachable address works without UDP discovery.

Automated release regressions run with the existing Android unit tests and Windows `--self-test` command. They cover unavailable optional storage, revoked pairing recovery, lifecycle cancellation, bounded page windows, non-advancing pages, worker failure/recovery, large HTTP offsets, and absolute metadata/artwork cache expiry.

Issue #4 regressions additionally cover album pages within artists/genres, distinct album artists,
authenticated scoped browsing, queue order with shuffle/repeat, and phone navigation history.

## Live iTunes queue regression

`dotnet run --project test-support/TuneLinkItunesQueueTest --configuration Release -- --run-live`

Run with iTunes already open. This opt-in test temporarily changes playback, imports six silent WAV fixtures, and creates uniquely named QA playlists. It exercises the real bridge controller for Albums, Artists, Genres, and Playlists: starting at a middle track, choosing another track in the same queue, Next, Previous, natural completion, and shuffle preservation. It also verifies artist/genre album drill-down. The test restores the original track, position, playing/paused state, volume, mute, shuffle and repeat, and removes its library entries and playlists in `finally`. iTunes' pre-existing Up Next list is not exposed by COM and cannot be snapshotted or restored by this test. Temporary WAV files and the test's isolated cache remain under the printed/unique temporary directory for diagnosis.

The live test also checks Previous before the initially selected track, repeat-all wrapping,
repeat-off stopping at the collection end, preserving pause/position during mode changes, and
restoring queue identity and earlier tracks after the controller restarts. Recovery metadata is
stored alongside the bridge's optional library cache.
