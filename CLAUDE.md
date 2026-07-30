# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Read AGENTS.md first

`AGENTS.md` is the authoritative convention document for this repo (git safety, i18n, miuix UI rules, squircle usage, Lazy-list structure, wide-screen adaptation, Flow collection, verification and reporting requirements). Follow it. This file only covers what it does not: commands, big-picture architecture, fork/upstream workflow, and cross-file traps.

## Fork context

This is a fork of `NEORUAA/XHS_Downloader_Android`. `origin` is `we1005/XHS_Downloader_Android`; `upstream` points at NEORUAA. The fork carries exactly one local change on top of upstream: the ColorOS 16 / OnePlus 15 live-photo recognition fix in `LivePhotoCreator.java` (EXIF `oplus_8388608` UserComment + MPF App2 segment + cleaned XMP). Upstream has never touched that file, so syncs are historically conflict-free.

```bash
git fetch upstream && git rebase upstream/main     # keeps the fork's single commit on top, linear
```

Prefer rebase over the GitHub "Sync fork" button — the latter creates a merge commit once the fork is ahead.

Releases are cut from this fork with the naming convention `v<upstreamVersion>-coloros16` (tag) and `xhsdn-<upstreamVersion>-coloros16-fix.apk` (asset). **Release APKs are signed with the Android debug keystore** (`~/.android/debug.keystore`) because neither upstream nor this fork configures a release `signingConfig` — keep using the same keystore so users can upgrade in place, and keep saying so in the release notes.

## Build & test

Toolchain: Gradle wrapper 9.5.0, AGP 9.3.0, Kotlin 2.3.20, Java 11 bytecode, compileSdk/targetSdk 37, minSdk 24, version 1.3.4 (versionCode 22). Requires `ANDROID_HOME` or `local.properties`.

```bash
./gradlew assembleDebug            # debug APK -> app/build/outputs/apk/debug/
./gradlew installDebug             # build + adb install
./gradlew assembleRelease          # UNSIGNED apk -> app-release-unsigned.apk (see signing below)
./gradlew testDebugUnitTest        # JVM unit tests
./gradlew :app:compileDebugKotlin  # fastest check for Kotlin/Compose edits (AGENTS.md's minimum bar)
./gradlew lintDebug
```

Run a single test:

```bash
./gradlew testDebugUnitTest --tests "com.neoruaa.xhsdn.utils.UrlUtilsTest"
```

`assembleRelease` output is unsigned and **not installable as-is**. To produce a release APK, zipalign then sign with the debug keystore (`androiddebugkey` / `android` / `android`) using `$ANDROID_HOME/build-tools/<ver>/apksigner`.

If the wrapper stalls downloading Gradle 9.5.0, a Homebrew Gradle ≥ 9.5.0 works as a drop-in for verification (`/opt/homebrew/bin/gradle assembleDebug`) — AGP 9.3.0 builds fine on 9.5.1. Cached older wrappers (8.14.3, 9.3.1) are too old for AGP 9.3.0. Use `./gradlew` for anything whose output you ship.

`scripts/build.sh [debug|release|install|clean]` wraps the same tasks but hard-codes `JAVA_HOME` to the Android Studio JBR and `ANDROID_HOME` to `~/Library/Android/sdk`; call Gradle directly when your SDK/JDK live elsewhere.

Automated coverage is thin: `app/src/test` holds `ImageOrientationUtilsTest.java` and `utils/UrlUtilsTest.kt` (5 tests, pure JVM). There is no `app/src/androidTest` source set despite the Espresso dependencies, so `connectedAndroidTest` has nothing to run. Real verification is running the app against live note links — `docs/各类媒体资源笔记案例.md` and `docs/20260128-下载失败笔记案例.md` collect sample links per media type (video / Live Photo / image-only / known failures).

## Extraction pipeline

The core is Java and deliberately UI-independent:

```
share text → extractLinks (regex) → resolveShortUrl (xhslink.com|cn 302) → fetchPostDetails (OkHttp HTML)
  → parseInitialStateRootFromHtml (window.__INITIAL_STATE__) → findNoteObjects → extractMediaUrlsFromNote
  → transformXhsCdnUrl → FileDownloader → MediaStore (+ optional LivePhotoCreator merge)
```

`XHSDownloader.java` (~2.4k lines) owns everything up to dispatch. What matters when changing it:

- **UA matters.** `fetchPostDetails` sends a mobile UA with the `xiaohongshu` suffix; the HTML embeds `window.__INITIAL_STATE__`. `parseInitialStateRootFromHtml` uses a hand-rolled brace/quote scanner (`extractFirstJsObjectLiteral`) plus `replaceJsUndefinedWithNull` because the payload is a JS literal, not valid JSON. `findNoteObjects` walks the tree heuristically (`isLikelyNoteObject`) instead of following a fixed path, so XHS schema churn usually breaks only the heuristics.
- **Media extraction.** Video: `video.consumer.originVideoKey` → `https://sns-video-bd.xhscdn.com/<key>`, falling back to `video.media.stream.h265[].url/masterUrl`. Images: `imageList[].urlDefault` (then `url`, `traceId`, `infoList`).
- **Watermark removal** is `transformXhsCdnUrl`: takes the CDN path token and rewrites to `https://ci.xiaohongshu.com/<token>`. Images only. `urlMapping` keeps transformed→original so `buildDownloadCandidateUrls` can retry the original if the rewritten URL fails.
- **Live Photo detection is presence-based:** an `imageList[i]` entry carrying `stream.h264[0].masterUrl` becomes a `MediaPair(isLivePhoto=true)`, later a `LivePhotoPair`. This is why a plain video note's cover+video can be mis-merged; `docs/小红书去水印工具迭代计划.md` proposes resolution matching instead (not implemented).
- **File naming** is template-driven. `NamingFormat.java` defines the tokens (`{title}`, `{username}`, `{userId}`, `{postId}`, `{publishTime}`, `{index}`, `{index_padded}`, `{downloadTimestamp}`); `buildFileBaseName`/`applyCustomTemplate` resolve them against `NoteMetadata` captured during parsing.
- **Cancellation is cooperative and two-sided.** `stopDownload()` sets a volatile flag polled by `checkForStop()`/`shouldStop()` between items, and `DownloadCallback.isCancelled()` (a default method) lets the Kotlin side veto mid-flight. Coroutine cancellation alone will not stop in-flight work — see `MainViewModel.cancelCurrentDownload`.

`FileDownloader.java` writes output: all saves go through MediaStore with `IS_PENDING` into `Pictures/xhsdn`, `Movies/xhsdn`, or `Downloads/xhsdn`, with filesystem fallbacks for older Android / MediaStore failures. Its `SHARED_HTTP_CLIENT` (`getSharedHttpClient()`) is the single OkHttp client for the whole app. `downloadFileToInternalStorage` serves the Live Photo path — temp files land in `getExternalFilesDir(null)` as `xhs_<timestamp>_<name>`.

`LivePhotoCreator.java` produces motion photos: normalize to JPEG (WebP conversion + EXIF orientation via `ImageOrientationUtils`), inject EXIF App1 / XMP App1 / MPF App2 segments, then append the raw MP4. Segment order is load-bearing — `SOI → EXIF → XMP → MPF → JFIF → ICC → DQT/SOF/DHT/SOS → image → EOI → MP4 trailer` — and the EXIF `UserComment = "oplus_8388608"` stamp is what makes ColorOS recognize the result. It validates the output and returns false on failure so `XHSDownloader.createLivePhotos` can fall back to saving image and video separately. Its only call site is `XHSDownloader.java` → `createLivePhoto(File, File, File, Context)`.

## State and the download entry points

`TaskManager` (object in `data/DownloadTask.kt`) is the only persistence layer — a `MutableStateFlow<List<DownloadTask>>` serialized as JSON into the `task_history` SharedPreferences file. No Room/DAO. `TaskManager.init(context)` must run before use (`MainActivity.onCreate`, plus defensively in `BackgroundDownloadManager`).

There are **three** ways a download starts, and the first two duplicate task/progress/notification bookkeeping:

1. `viewmodels/MainViewModel.kt` — foreground. Owns `downloadJob` + `currentDownloader`, drives `MainUiState`, and has variants `startDownload`, `retryTask`, `onWebCrawlResult`.
2. `data/BackgroundDownloadManager.kt` — app-scoped `CoroutineScope(Dispatchers.IO)`, `activeUrls` dedupe, `activeJobs` for `stopTask`, notifications via `NotificationHelper`.
3. **Selective download** (pref `selective_download`) — `MainViewModel.startSelectiveDownload` calls `XHSDownloader.downloadContentToCache(url, cacheDir)`, which returns a `SelectiveDownloadResult` of `CachedMediaFile`s staged in app cache. The user picks items in `ui/SelectableMediaWaterfall.kt` (`toggleSelectiveItem`), then `saveSelectedMedia` promotes only the chosen files into MediaStore; `cancelSelectiveDownload` discards the cache. State lives in `SelectiveDownloadUiState` / `SelectiveDownloadPhase`.

**When changing progress accounting, failure counting, or task status semantics, change paths 1 and 2 together.** In particular `isTerminalDownloadError(status: String)` is duplicated verbatim in `MainViewModel.kt` and `BackgroundDownloadManager.kt`, and matches substrings of **English** error strings emitted by the Java layer (`"failed to download after"`, `"non-media response received"`, …). Editing one of those messages in `XHSDownloader`/`FileDownloader` silently breaks failed-file counting in both callers — and per AGENTS.md those log/error strings stay English, so don't "fix" them by localizing.

`DownloadCallback` (`onFileDownloaded`, `onDownloadProgress`, `onDownloadProgressUpdate`, `onDownloadError`, `onVideoDetected`, `isCancelled`) is the only seam between the Java core and Kotlin state. `XHSDownloader`'s constructor wraps the caller's callback to count successes.

Two subtleties: `TaskManager.updateProgress` deliberately refuses progress regressions, and `getMediaCount` (used to size a task before downloading) performs an extra fetch+parse round trip and subtracts `livePhotoPairs.size()` when Live Photo merging is on, since each pair yields one file. When two parses of the same note disagree on media count, the app surfaces `InconsistentRetryDialogState` and can dump diagnostics via `saveInconsistentRetryLogs`.

## UI layout

Compose + **miuix** (`top.yukonga.miuix.kmp`) — see AGENTS.md for the full UI ruleset. Activities: `MainActivity` (download entry, history list, `TaskCell`, clipboard bubble), `SettingsActivity` (own `SettingsViewModel` over SharedPreferences), `WebViewActivity`, `DetailActivity`. Shared composables live in `ui/`: `CustomTabRow.kt`, `PopupPositionProviders.kt`, `AdaptiveUi.kt` (wide-screen/inset helpers), `GroupedCardItems.kt` (the split-Lazy-item card helper AGENTS.md mandates), `SelectableMediaWaterfall.kt`.

`WebViewActivity` is the fallback for notes whose HTML yields no media: it loads the note, runs `assets/xhs_extractor.js` via `evaluateJavascript` (scrapes `.note-image-box img` / `.media-container video`, un-blobs video srcs from `window.__INITIAL_STATE__`), and returns `image_urls` + `content_text` + optional `task_id` through `setResult` → `MainViewModel.onWebCrawlResult`.

**Dead legacy code:** `MediaAdapter.java` and the XML layouts (`res/layout/*.xml`) are unreferenced RecyclerView-era leftovers — no `R.layout` usage remains anywhere. Upstream still occasionally edits them (e.g. sweeping hardcoded strings in `activity_main.xml`), which makes them look alive; don't extend them, the app is fully Compose.

## Settings and permissions

Settings are read straight from `getSharedPreferences("XHSDownloaderPrefs", MODE_PRIVATE)` wherever needed — including inside `XHSDownloader` and `NotificationHelper` — with no repository layer. Keys: `create_live_photos`, `selective_download`, `use_custom_naming_format`, `custom_naming_template`, `use_metadata_file_names`, `auto_read_clipboard`, `show_clipboard_bubble`, `manual_input_links`, `debug_notification_enabled`, `keep_screen_on`. `MainActivity` registers an `OnSharedPreferenceChangeListener` so `keep_screen_on` / `manual_input_links` apply live.

The app requests `MANAGE_EXTERNAL_STORAGE` plus `requestLegacyExternalStorage`; gates use `Environment.isExternalStorageManager()` on R+ and legacy read/write below (`BackgroundDownloadManager.hasStoragePermission`, `MainActivity.ensureStoragePermission`). Diagnostic notifications are opt-in behind `debug_notification_enabled` and use fixed IDs (`MONITOR_STATUS_ID`, `DEBUG_STATUS_ID`) so they overwrite rather than stack.

Clipboard: `MainActivity` registers a `ClipboardManager.OnPrimaryClipChangedListener` on `ON_RESUME` with a delay, because Android 10+ only allows clipboard reads once the window has focus.
