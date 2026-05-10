# Contributing to Saarthi

This is a quick reference for developers landing on the repo. The goal is
that anyone — human or LLM — can go from a fresh clone to a green build
in under 15 minutes, and that they understand what gates `main` so they
don't fight the CI.

## TL;DR

```bash
# Build the debug APK
./gradlew :app:assembleDebug

# Run all unit tests
./gradlew testDebugUnitTest

# Run tests for a single module
./gradlew :feature:feature-assistant:testDebugUnitTest
./gradlew :core:core-inference:testDebugUnitTest
```

That's it for the daily loop. If both commands are green locally, the CI
will be green too.

## Project layout

```
saarthi/                                # repo root (this dir IS a git repo)
├── app/                                # the actual Android app
├── build-logic/convention/             # convention plugins ("saarthi.android.*")
├── core/                               # cross-cutting libs
│   ├── core-common/                    # shared DI helpers
│   ├── core-i18n/                      # SupportedLanguage + locale helpers
│   ├── core-inference/                 # LiteRT engine, prompt provider, model catalog
│   ├── core-memory/                    # Room DB for chat + user memories
│   ├── core-rag/                       # embedding + vector store
│   └── core-ui/                        # design system (colors, glass cards)
├── feature/                            # user-facing screens
│   ├── feature-assistant/              # chat UI
│   └── feature-onboarding/             # first-run flow + model picker
├── gradle/libs.versions.toml           # version catalog (single source of truth)
└── .github/workflows/                  # CI (see "CI gates" below)
```

> Older revisions of this repo carried four pack feature modules
> (`feature-money`, `feature-kisan`, `feature-knowledge`, `feature-fieldexpert`)
> and a 148 MB embedded `llama.cpp` native bridge. v1.0.19 deleted all of
> them — the packs had no UI / consumer, the bridge had no Kotlin caller.
> Pack personas are now expressed via `PackType` + `SystemPromptProvider`,
> not as Gradle modules.

## How modules are wired

We use Gradle convention plugins instead of repeating `dependencies { … }`
in every module's `build.gradle.kts`. The plugins live in `build-logic/`:

| Plugin id                  | What it does                                                              |
|----------------------------|---------------------------------------------------------------------------|
| `saarthi.android.application` | Configures `:app` (release signing, packaging, FGS).                  |
| `saarthi.android.library`     | All `core-*` and `feature-*` modules. Sets `compileSdk = 35`, `minSdk = 28`, JDK 17, **and adds the standard test classpath**: `junit`, `mockk`, `coroutines-test`, `turbine`. |
| `saarthi.android.compose`     | Compose BOM, Compose plugin, ui-tooling.                              |
| `saarthi.android.feature`     | Applies `library` + `compose` + `hilt`, adds nav + viewmodel + core-* deps. |
| `saarthi.hilt`                | Hilt + KSP.                                                           |

**Adding a new feature module** means creating a `build.gradle.kts` with
just `plugins { id("saarthi.android.feature") }` plus a `dependencies` block
for module-specific extras. Test classpath, Compose, Hilt, language deps —
all free.

## CI gates

Two GitHub Actions workflows:

| Workflow             | When it runs                                | What it does                                                                                       |
|----------------------|---------------------------------------------|----------------------------------------------------------------------------------------------------|
| `ci.yml`             | Every push to `main`, every PR, dispatch    | **`Unit tests`** job — `testDebugUnitTest` across all modules. **`Assemble debug APK`** job — full debug build with NDK + Vulkan + ggml SVE patch. Uploads test reports + APK as artifacts. |
| `build_apk.yml`      | Push to `main`, dispatch                    | Builds the **signed release APK**. Pulls signing keys + HF token from secrets.                     |

CI runs but doesn't *gate* merges by itself. Branch protection on `main`
requires both `ci.yml` jobs to pass before merge. Configured under
**Settings → Branches → Branch protection rules**.

If you fork this repo and CI fails the first time, the most common causes
are:
- Missing repo secrets (`HF_APP_TOKEN`, `KEYSTORE_BASE64`, etc.) — only
  `build_apk.yml` needs these. `ci.yml` runs without secrets.
- A change to NDK / CMake versions in one workflow but not the other —
  keep them in sync.

## Writing tests

Every library module has the test classpath ready. Drop a test file in
`<module>/src/test/kotlin/<package>/`:

```kotlin
import org.junit.Assert.assertEquals
import org.junit.Test

class MyThingTest {
    @Test
    fun `does the thing`() {
        assertEquals(2, 1 + 1)
    }
}
```

Conventions we follow:

- **Test what's public.** No reflection-based tests of private methods —
  they break on every refactor and don't test the contract. The deleted
  `ChatRepositoryImplTest` was this anti-pattern.
- **Pure-logic tests live in `src/test/`** (no Robolectric). Anything
  needing a real Android runtime goes in `src/androidTest/`.
- **Mock at module boundaries** with MockK. Don't mock your own
  internals.
- **Coroutines** use `runTest` from `kotlinx-coroutines-test`. Flow
  assertions use Turbine.

## Versioning

`app/build.gradle.kts` carries `versionCode` and `versionName`. Every APK
change bumps both:

- `versionCode` is monotonic — increment by 1.
- `versionName` follows `MAJOR.MINOR.PATCH` semver, but on this project
  patch-level fixes increment the patch component (e.g. `1.0.17` →
  `1.0.18`).

CI/infrastructure-only changes (workflows, docs, build-logic) do **not**
bump the version — they don't change the APK.

## Don't ship gimmicks

This is the rule the project lives by. A few concrete consequences:

- If you remove a feature, remove the dead code paths too. Don't leave
  commented-out blocks or `_unused` parameters.
- If a test is broken, fix it or delete it. "Skip the test for now" is
  how `ChatRepositoryImplTest` rotted into a phantom.
- If a piece of infrastructure exists but no one calls it (e.g.
  `reattachActiveDownloads` for a year), document the call site or delete
  it.
- Comments explain *why*, not *what*. The diff already shows what
  changed.
