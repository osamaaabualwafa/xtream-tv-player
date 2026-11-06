# Tests

Two places run tests. Both write a report in the same layout: a table of what passed and failed,
then the failures, then measurements and pictures.

## On GitHub (by itself)

| When | What | Report |
|---|---|---|
| After every push to `main` | Short list: data, Live TV, new features (T11, T12), Settings, old themes moving to Default, bad network, biggest sizes (`qa/shards.py`, `PUSH_SHARDS`) | branch `qa-report-push` |
| Wednesdays 02:00 Riyadh | Full suite T01–T12 (`app/src/androidTest/.../qa`) | branch `qa-report` |
| `[full qa]` in a push's commit message | The full suite right after the short list | branch `qa-report` |

It only warns: the APK is published whatever the tests find. Every push to `main` also publishes a
new APK, even when only tests changed.

## On this computer (the emulator), one command

```
python scripts/test.py <level>
```

| Level | When | Checks it runs | Time |
|---|---|---|---|
| `quick <screens…>` | While working on one change | screens-few, contrast, ratings | 3–4 min |
| `push` | Before a push | screens (with contrast and sizes), focus, home-rows | about 15 min |
| `player` | Player changes | player | about 8 min |
| `look` | Colours, themes, backgrounds, scrolling | contrast, colours, themes, backgrounds, scrolling, episodes | about 15 min |

`python scripts/test.py list` shows every check with its last result. `python scripts/test.py stop`
stops a run and puts the emulator back. Reports: `.uitest/reports/<date>-<level>.md` (never committed).

When the owner says "push", they choose: no tests, `quick`, `push`, `player` or `look`.

## Every check

| Check | What it checks | Review items | Script (`scripts/uitest/`) |
|---|---|---|---|
| screens | Every screen in Default and Glass: highlight cut off, crashes, changes from the saved pictures | 1.1C–1.1Z | `run.py push` |
| screens-few | Only the named screens, Glass with Lift | | `run.py quick` |
| sizes | Every screen at the biggest interface and text sizes: cut text | size rule | `size_check.py` |
| focus | Where the highlight lands on Home, Settings, Movies, the avatar picker | 1.1C, 1.1S–1.1Z | `focus_checks.py` |
| home-rows | Recently added switches in H1–H4, also after a restart | 1.1T(a) | `recent_rows.py` |
| ratings | Settings › Ratings before and after a key, the key window and phone page, a made-up key refused and not saved, scores on posters, no provider ★. The owner's key is put aside and brought back | 1.1E | `ratings_checks.py` |
| contrast | Text against what it sits on, every theme, at least 4.5:1 (no emulator) | 1.1R | `check_contrast.py` |
| colours | Colour table with contrast ratios, every theme (no emulator) | 1.1R | `colour_table.py` |
| player | Seeking and the bubble, OK, resume, screensaver, tracks, picture, login keyboard | 1.1I, 1.1J, 1.1M, 1.1P, 1.2A, 1.2B | `player_checks.py` |
| themes | Pictures: playlists, Home, Settings, player in 6 themes | 1.1R | `theme_matrix.py` |
| backgrounds | Pictures: Home with every Default background and colour | 1.1Y | `bg_grid.py` |
| scrolling | Holding Down through Movies and Series: frames, stutters, picture times | 1.1D | `scroll_perf.py` |
| episodes | Holding Right along a 300-episode row: frames, stutters, how far it got | 1.1F | `episode_perf.py` |

During the build: `scripts/check_focus.py` (focus growth only in FocusFx.kt) and the report-cleaning
unit test (`app/src/test`, no addresses or logins in diagnostics reports).

## Rules for the checks

- They use the fake server (`mock_server.py`) and a fake playlist. The owner's real playlists are hidden
  while a check runs and put back after, so no server name or login ever appears in a picture or report.
- A new feature's check goes into the list above and into a level, not into a separate one-off script.
- When the app changes on purpose, the check changes in the same commit (an out-of-date check is a bug).
