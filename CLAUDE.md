# Xtream TV Player — project brief

Android TV IPTV player that logs in with **Xtream Codes** (server URL + username + password).
Owner sideloads it on an **Android TV**. The repo is public; never commit credentials.

## Build environment
- The APK is built by **GitHub Actions**, not locally. Workflow: `.github/workflows/build.yml`.
  - Trigger on push to `main` and `workflow_dispatch`.
  - `actions/setup-java` (Temurin 17), `gradle/actions/setup-gradle`, run `./gradlew assembleRelease`.
  - Publish the APK to a GitHub Release (`softprops/action-gh-release`, tag `build-<run_number>`,
    `permissions: contents: write`) so the TV's Downloader app can fetch it from a direct link.
- Commit the Gradle wrapper (`gradlew`, `gradle/wrapper/*`).
- Sign release builds with a keystore committed at `app/release.keystore` (personal sideload app;
  a fixed key lets updates install over the old version). Set `minifyEnabled false`.
- `lint { checkReleaseBuilds = false; abortOnError = false }` so lint never blocks the APK.
- After pushing, check the Actions run and fix any compile errors until the APK builds.

## Stack (keep conservative — must compile first time on CI)
- Kotlin, classic Android Views (RecyclerView + findViewById), AppCompat `Theme.AppCompat.NoActionBar`, dark UI.
- AGP 8.7.x, Kotlin 2.0.x, compileSdk/targetSdk 35, **minSdk 21**.
- Media3 ExoPlayer (`media3-exoplayer`, `media3-exoplayer-hls`, `media3-ui`), Coil 2.x for images,
  coroutines + `lifecycleScope`, networking with `HttpURLConnection` + `org.json`.

## Manifest must-haves
- `android:usesCleartextTraffic="true"` — most Xtream servers are plain `http://`.
- `INTERNET` permission; `uses-feature android.software.leanback required=false`,
  `android.hardware.touchscreen required=false`.
- Launcher activity has both `LAUNCHER` and `LEANBACK_LAUNCHER` categories; provide `android:banner` (320x180 PNG) and an icon.

## Xtream Codes API
Base = `http(s)://host:port`. All calls: `{base}/player_api.php?username=U&password=P[&action=...]`
- no action → `user_info` (auth==1, status, exp_date unix ts or null, max_connections, active_cons, allowed_output_formats) + `server_info`
- `get_live_categories`, `get_live_streams[&category_id=]`
- `get_vod_categories`, `get_vod_streams[&category_id=]`, `get_vod_info&vod_id=`
- `get_series_categories`, `get_series[&category_id=]`, `get_series_info&series_id=`
  - `episodes` is usually an object keyed by season number (`{"1":[...]}`) but sometimes an array of arrays — handle both.
- `get_short_epg&stream_id=&limit=2` → `epg_listings[]`, `title`/`description` are **base64**.
Stream URLs (URL-encode user/pass path segments):
- Live: `{base}/live/U/P/{stream_id}.m3u8` (use `.ts` if `allowed_output_formats` lacks m3u8; on player error fall back m3u8 → ts once)
- Movie: `{base}/movie/U/P/{stream_id}.{container_extension}`
- Episode: `{base}/series/U/P/{episode_id}.{container_extension}`
JSON quirks: ids/numbers may arrive as strings or ints; fields may be null — parse defensively.
Login input: accept `host:port`, with/without scheme or trailing slash, and also a pasted
`get.php?username=..&password=..` M3U link (auto-extract username/password).

## Player
- `DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)` + a custom user agent.
- Live: hide controller; D-pad Up/Down and CHANNEL_UP/DOWN zap through the current category;
  OK shows an overlay with channel name, logo, Now/Next EPG. Show a clear error message on failure.
- VOD/episodes: standard controller; save and resume playback position per item.

## Screens
1. Login (saved in SharedPreferences; skip if already logged in and still valid).
2. Home: Live TV / Movies / Series tiles + account info (status, expiry, connections) + Logout.
3. Browse (per type): categories column on the left (incl. "All" and "★ Favorites" for live),
   channel list or poster grid on the right, search box filtering the current list.
   Long-press OK on a channel toggles favorite.
4. Details: movie (poster, plot, rating, Play/Resume) and series (seasons → episodes).
5. Player as above.
All items must be focusable with a visible focus highlight (D-pad only, no touch).
