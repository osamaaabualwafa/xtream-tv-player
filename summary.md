## Weekly QA — 2026-11-06 10:18 UTC

| Tests | Passed | Failed | Problems | Warnings | Crashes | Freezes (ANR) | Screenshots |
|---|---|---|---|---|---|---|---|
| 77 | 75 | 2 | 1 | 0 | 0 | 0 | 639 |

### Failed (not an app bug on record)
- **T02_LoginAndPlaylistsTest.logoutAndReopen** — java.lang.AssertionError: &quot;Log out / switch&quot; is not on the screen (HomeActivity)
- **T04_MoviesTest.browseAndWatchAMovie** — java.lang.AssertionError: Paused

### Problems found
- Movie position not saved after leaving the player (T10_BadNetworkTest.stateSurvivesRestart) Saved: 0 ms after ~15 s of watching

Random remote presses: 4000 events, no crash.

Download **qa-report** below for screenshots (open index.html).
