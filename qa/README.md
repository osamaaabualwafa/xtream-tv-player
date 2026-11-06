# Weekly QA

Every **Wednesday at 02:00 Riyadh time** GitHub runs `.github/workflows/weekly-qa.yml`:

1. Builds the app and the UI tests (`app/src/androidTest/.../qa`).
2. Starts a fake Xtream server (`qa/server/fake_xtream.py`): made-up channels, movies, series, guide
   and test videos. No real provider or login is used.
3. Starts six Android TV emulators in parallel (1080p, Android 11; groups in `qa/shards.py`) and
   runs every test with remote-control keys.
4. Presses 4,000 random remote keys ("monkey") to look for crashes and freezes.
5. Builds the report (`qa/make_report.py`): screenshots, automatic screen checks, problems,
   crashes, measurements. Uploaded as the **qa-report** artifact, pushed to the **qa-report** branch,
   and the screen recording as **qa-video**.

The report has an **APP BUG** section: real app problems confirmed by review, listed in
`qa/known_app_bugs.json` with the screen, the steps and screenshots from the run.

At about 09:00 a scheduled Claude task reads the report and sends a summary with screenshots.
It does not change any code without approval.

## What is tested

| Test | Covers |
| --- | --- |
| T01 Data | Server addresses, login answers, lists, details, both episode formats, base64 guide, broken and huge servers |
| T02 Login and playlists | First start, every login error, M3U link, expiry warning, add / switch / edit / delete playlists, avatar, open at start, log out |
| T03 Live TV | Categories, play, channel info, zapping (incl. 20 fast presses), number keys, last channel, pause, broken and slow channels, in-player list, options, guide, catch-up, favorites, list filter, TS-only account |
| T04 Movies | Grid, categories, details, play, controls, seek, pause, resume later, Continue watching, end of movie, missing file, favorite, screensaver |
| T05 Series | Seasons, episodes, next-episode countdown and auto-play, episode list in player, both episode formats, 12-episode season, empty series |
| T06 Search and Home | Search (English, Arabic, no result, filters, open results), Home rows, counts, featured banner, refresh, guide from list |
| T07 Settings | Every option in every section, every value, then restored; layouts; connection check |
| T08 Themes | Default and Glass × every screen × 5 Home layouts × 4 Movies/Series layouts; Glass: 9 backgrounds, 3 glass styles, 4 highlight styles (dark and Daylight), moving background; old Coastal/Coconut/Cinema themes move to Default; 8 colours; palettes; backgrounds; text and interface sizes; effects |
| T09 Stress | 10,000 channels / 50,000 movies / 5,000 series: open times, fast scrolling, memory, search; 800 random keys |
| T11 New in the Glass version | Dock Home (every tile), quick drawer on the Menu key (every Home layout, its buttons, the "does nothing" setting), Cards player menu (live and movie, Default and Glass) |
| T12 New in 2.0.0 | Sort button in Movies (Up to Search, Left to Sort, the themed list, A to Z), Arrange Home rows (hide, show, move), the themed message pill, Ratings Get started (no key), the HD banner and adaptive icon |
| T10 Bad network | Slow server, broken server, internet cut while watching live / a movie / browsing / starting, state kept after reopening |

Automatic screen checks on every screenshot: nothing highlighted, highlight off screen, text cut
with "…", text taller than its box, text outside the screen, low contrast, overlapping text.

## After every push to main

A short list runs on its own (`PUSH_SHARDS` in `qa/shards.py`): data, Live TV, what's new, and past
crashes. Its report is on the **qa-report-push** branch, so the weekly **qa-report** is never replaced.
It only warns: the release workflow publishes the APK whatever this finds.

## Run it now

GitHub → Actions → **Weekly QA (Android TV)** → *Run workflow*. To run only some tests, enter
class names, e.g. `com.osama.xtreamtv.qa.T03_LiveTvTest`.

## Fake server accounts (username = password)

`demo`, `second`, `expiring`, `expired`, `banned`, `slow`, `broken`, `tsonly`, `huge`.
