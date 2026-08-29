# Production-readiness without Android Studio

You can build, test, install, and debug Saarthi entirely from a terminal.
Android Studio is convenient but not required — and it costs ~10 GB of
disk space, which is a non-trivial ask when you're already shipping a
gigabyte-scale on-device LLM. This document is the contract for what
"production-shaped" means on this project, and the exact commands for
each step.

## Prerequisites (one-time)

Install only what's strictly needed:

| Tool                 | Purpose                                    | Size   |
|----------------------|--------------------------------------------|--------|
| JDK 17 (Temurin)     | Compiles Kotlin                            | ~300 MB|
| Android SDK platform-tools | `adb` for install + logcat            | ~10 MB |
| Android SDK platforms;android-35 | Build target                  | ~150 MB|
| Android SDK build-tools;35.0.0 | dexer / aapt2                   | ~75 MB |

That's it. ~550 MB total. No NDK needed (native bridge was removed in
v1.0.19), no emulator, no IDE. Install via `sdkmanager` or your distro's
package manager.

Set `ANDROID_HOME` to the SDK root and you're done.

## Daily commands

```bash
# Quick build of the debug APK (~3-5 min cold, ~30s warm)
./gradlew :app:assembleDebug

# Run all 55 unit tests across all modules
./gradlew testDebugUnitTest

# Static analysis on all modules — Android Lint catches things tests don't
# (unused resources, deprecated APIs, hard-coded strings, accessibility)
./gradlew lint

# Install the freshly-built debug APK on a connected phone
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Watch the live device log — filter to errors only
adb logcat *:E

# Pull the app's own structured debug log (gold mine for crash forensics)
adb pull /storage/emulated/0/Download/saarthi_debug.log
```

If you want a single command that mirrors what CI runs:

```bash
./gradlew testDebugUnitTest :app:assembleDebug lint
```

If everything is green locally, the SaarthiTest branch protection will
let your PR merge.

## The bug-finding system

Five layers, each catching what the layer above missed. Strongest at the
top — exhaust each before reaching for the next.

### 1. Compiler + KSP

Free. Catches type errors, unresolved imports, missing `@Inject`
constructor args, broken Hilt graphs.

```bash
./gradlew :app:assembleDebug
```

If this is red, nothing else matters yet.

### 2. Unit tests

Cover the pure-logic surfaces most likely to silently regress.
Counts change as tests are added — run `./gradlew testDebugUnitTest` rather
than treating any number in this table as current.

| Module              | What's locked |
|---------------------|---------------|
| `core-i18n`         | Language enum, personality, prompt instructions |
| `core-memory`       | Room helpers, memory/chat hygiene |
| `core-rag`          | BM25 retrieval, chunking |
| `core-inference`    | Download policy, crash recovery, engine lifecycle, catalog |
| `feature-assistant` | Chat mapping, pack chat |
| `feature-onboarding`| Onboarding download/init flow |
| `app`               | Manage-downloads, boot/wisdom wiring |

Run the whole suite or a single module:

```bash
./gradlew testDebugUnitTest
./gradlew :core:core-rag:testDebugUnitTest
```

Add a test alongside any non-trivial new logic. Test-classpath
(`junit + mockk + coroutines-test + turbine`) is wired into every
library module by `AndroidLibraryConventionPlugin`, so a new module
inherits it automatically.

### 3. Android Lint

Catches Android-specific bugs the JVM compiler can't see — unused
resources, deprecated API calls, missing `contentDescription`,
hard-coded strings, accessibility-touch-target sizes. Free.

```bash
./gradlew lint
# Open the HTML report:
open app/build/reports/lint-results-debug.html      # macOS
xdg-open app/build/reports/lint-results-debug.html  # Linux
```

Fix everything in `Errors`. Triage `Warnings` — many are noise,
some catch real regressions.

### 4. Saarthi DebugLogger (on-device)

Where the file lives depends on the build. Do not assume public Downloads
on a Play / production APK.

- **Beta** (`PUBLIC_DEBUG_LOG=true`): public Downloads, so a non-technical
  user can grab it with any file manager:

  ```bash
  adb pull /storage/emulated/0/Download/saarthi_debug.log -
  ```

- **Play production** (`PUBLIC_DEBUG_LOG=false`): app-private storage
  (`filesDir` or `externalFilesDir`). Use Support → Report a problem
  (FileProvider attach), or `adb` into the app's private dir. The path
  label is on the session-start line in the log itself.

Tags you'll see in there:

| Tag        | What it tracks                                 |
|------------|------------------------------------------------|
| `APP`      | Process start sessions                         |
| `LITERT`   | Engine init / inference timing / crashes       |
| `PROFILER` | Device-tier classification + GPU/NPU policy   |
| `CHAT`     | Stream start / done / token rate              |
| `PROMPT`   | Tier, recap state, language line              |
| `MEMORY`   | Memory facts injected per turn                 |
| `WISDOM`   | Daily wisdom alarm fired vs. notification suppressed |
| `DOWNLOAD` | Model download progress + reattach            |
| `CRASH`    | Uncaught exception + 30 frames of stack       |

Every new feature should add 1-3 log lines at its critical decision
points. The cost is ~1 µs per log; the benefit is that a user can
hand you a single file and you can reconstruct what happened in
their session.

### 5. Live logcat

For when the bug isn't reproducing in the recorded log — usually
because the app crashed before it could flush.

```bash
# Filter to fatal errors only
adb logcat AndroidRuntime:E *:S

# Filter to anything Saarthi prints
adb logcat | grep -i saarthi

# Capture native crashes (for the litertlm runtime — we don't
# symbolicate these but Google does upstream)
adb logcat -b crash
```

### 6. CI as a necessary gate (not the ship bar)

Every pull request triggers `.github/workflows/ci.yml` (job `test-and-lint`:
`testDebugUnitTest` + `lintDebug`). That is JVM unit tests and Android Lint —
not an APK, not Firebase Test Lab, and not LiteRT inference on a phone.

`.github/workflows/build_apk.yml` runs on push to `main`/`master` and
uploads a **debug** APK. The Play-signed `.aab` is `.github/workflows/release_aab.yml`
on a `v*` tag only.

A green CI check is required and not sufficient for Play. Physical-phone
smoke in `docs/RELEASE_CHECKLIST.md` is still the ship bar. Emulator and
cloud-agent runs are not GPU/SIGKILL proof.

`.github/dependabot.yml` opens grouped weekly dependency-update PRs
(Compose, Lifecycle, Hilt, Room, etc.). Each goes through the same
`ci.yml` job, so a regressing upgrade can't merge silently to `main`.

## Adding a new feature without breaking production

The order matters — front-load the cheap checks:

1. **Add the test first** if the change has any logic surface.
   Failing test now, passing test after the change.
2. **Add a `DebugLogger.log("YOURTAG", "…")` line** at every decision
   point in your code. Five log lines per feature is not too many.
3. **Build locally** with `./gradlew :app:assembleDebug` — wins free
   compiler + KSP + Hilt-graph validation.
4. **Run tests** with `./gradlew testDebugUnitTest`.
5. **Run lint** with `./gradlew lint` and open the HTML report.
6. **Install on device** and exercise the feature end-to-end.
7. **Pull the debug log** after the test session and confirm your
   new log lines tell the story you'd expect.
8. Open the PR — `ci.yml` runs unit tests + lint (not assemble, not device inference).
9. Wait for `test-and-lint` to go green. Merge only after the human smoke list
   in `docs/RELEASE_CHECKLIST.md` if this is a store candidate.

This is the production loop. There is nothing missing from it that
Android Studio would add — it gives you a friendlier UI for steps 3
and 6, but the steps themselves are identical.

## APK size budget

Track APK size as a first-class metric. After the v1.0.19 cleanup the
arm64-v8a-only debug APK is **~100 MB** (most of which is the
litertlm-android native runtime, not our code). Release with R8 +
resource shrinking is meaningfully smaller.

```bash
# Quick check
ls -lh app/build/outputs/apk/debug/*.apk
ls -lh app/build/outputs/apk/release/*.apk

# Per-section breakdown
unzip -l app/build/outputs/apk/release/app-release.apk | sort -nr | head -20
```

If a feature adds more than ~500 KB of compiled bytecode or pulls in
a heavy dep, that's a design choice that needs a justification in the
PR — same way an O(n²) loop would.

## When something is on fire in production

1. Get the user to share `/storage/emulated/0/Download/saarthi_debug.log`.
2. Search for `[CRASH]` first, then the most recent `[LITERT]` line.
3. The line just before the gap is what caused the kill.
4. Reproduce locally if possible. If not, write a test that would
   have caught it, then fix.
5. Bump `versionCode` + `versionName` in `app/build.gradle.kts`,
   commit with a message that explains the *why*, push, let CI sign
   the release APK.
