# Vector Manager Application

## Overview

The manager is the user-facing surface of the Vector framework: a single-activity Jetpack Compose
application that configures the root daemon over Binder. It is built as `:manager` and replaces the
legacy `:app` module.

Its architecture is dictated by one constraint. The manager normally runs *parasitically* — the
Zygisk layer transplants its DEX into a host process, usually `com.android.shell` — so its manifest
is never registered with the package manager. Nothing that depends on manifest registration exists
at runtime: no `ContentProvider`, therefore no `androidx.startup`, no `WorkManager`, no guarantee
that a declared `Application` subclass is ever instantiated, and no resource-backed theme applied by
the system before the first frame. The same APK is also installable as an ordinary application for
debugging, and must behave identically in both modes.

## Directory Structure

```text
src/main/kotlin/org/matrix/vector/manager/
├── data/
│   ├── github/           # Activity feed, contributor resolution, canary releases, device-flow auth
│   ├── log/              # Byte-offset log index and line parser
│   ├── model/            # Module detection, app and repository models
│   └── repository/       # Apps, modules, settings, backup, store catalogue, installer
├── di/ServiceLocator.kt  # Hand-rolled service location; no DI framework
├── ipc/                  # DaemonClient (suspending Binder wrapper), package broadcasts
├── net/                  # OkHttp factory and the DNS resolver
└── ui/
    ├── components/       # Shared surfaces: panel header, search field, snackbar, ambience
    ├── navigation/       # Navigation 3 route keys and back stack
    ├── screens/          # home, modules, logs, repo (store), canary, report, web, splash
    └── theme/            # Seeded colour scheme generation, typography
```

## Process Constraints

* *Service location, not injection.* `ServiceLocator.attach(context)` and `bind(service)` are
  idempotent and order-independent, because in parasitic mode there is no guaranteed initialisation
  point and the daemon binder may arrive before or after the first composition.
* *Theme from code.* The window theme is the platform default; all colour comes from
  `VectorTheme` at composition time, since a resource theme would require a registered manifest.
* *Process death is routine.* The host process is killed frequently, so every reading preference
  (word wrap, header surface, activity window, colour seed) is persisted rather than held in a
  `ViewModel`.
* *No `FileProvider`.* Exports go through the Storage Access Framework; the document belongs to
  DocumentsUI, which is what makes it shareable at all.

## Daemon IPC

`DaemonClient` wraps `ILSPManagerService`. Every call suspends on `Dispatchers.IO` and returns a
`Result`. The binder reference is read *once* per call rather than null-checked and then used,
which was a time-of-check/time-of-use race, and failures are caught as `Exception` rather than
`RemoteException` alone — a daemon built without a given method throws `NoSuchMethodError`, which is
the expected outcome when a newer manager meets an older framework.

Two calls were added to the AIDL for the log reader: `getLogParts(verbose)` lists the rotated parts
the daemon still holds, and `getLogPart(verbose, name)` opens one. The name arrives from an
unprivileged process and is used to build a path inside a root-only directory, so it is validated by
membership in the listing rather than by pattern-matching for traversal sequences.

## Log Reader

The daemon rotates its logcat capture at four megabytes and retains ten parts. A naive reader that
calls `readLines()` retains several megabytes of `String` per stream inside a process whose heap
belongs to the host application.

`data/log/LogFile.kt` therefore indexes rather than loads. One sequential byte scan records the
start offset of every line into a `LongArray`, allocating no `String`. The pane holds a window of at
most `WINDOW` lines around the viewport and pages outward as the viewport approaches either edge.
Rows are keyed by absolute line number, which is what allows the window to be extended upwards
without the viewport lurching: the list re-resolves its first visible item by key after rows are
inserted above it.

Filtering builds an `IntArray` of matching line numbers and pages through that instead, so a filter
over a 30,000-line file costs one scan and no re-parse.

## Colour Generation

Android exposes no public API that converts a colour into a Material scheme; `dynamicColorScheme`
reads the wallpaper and nothing else. `ui/theme/SeedScheme.kt` generates one in *CIE LCh* — the
same principle as Google's HCT — by holding the seed's hue and chroma and walking L\* across the
Material tone scale.

The non-obvious part is gamut mapping. Most (lightness, hue) pairs cannot hold the seed's full
chroma in sRGB, so each tone binary-searches the highest chroma that converts in range. Clamping the
channels instead shifts hue as tones darken, which is why naive generators drift blue toward purple
down the ramp. The error ramp is fixed at a red hue regardless of the seed, so destructive actions
do not change meaning with the theme.

`ui/components/ColorWheel.kt` renders the hue/chroma disc by evaluating every pixel through the same
conversion, once per tone, off the main thread and cached as an `ImageBitmap`.

## Remote Data

* *Store mirrors.* The full `modules.json` is served by exactly one host today; the public site
  answers it with 403 and two historical mirrors no longer resolve. Per-module detail *is* served by
  both, so the mirror lists are deliberately separate — merging them takes the catalogue offline.
* *Freshness is declared per request.* The OkHttp disk cache is the offline story: on total mirror
  failure the same request is replayed against the cache alone, so a cold start with no network
  renders the last known catalogue instead of an error.
* *DNS-over-HTTPS is a fallback, not a replacement.* `net/VectorDns.kt` attempts DoH, falls
  through to the system resolver on failure, latches that failure for the session, and disables
  itself entirely when a proxy is configured. The setting is read per lookup, because OkHttp cannot
  have its DNS swapped on a live client and rebuilding the shared client would orphan the cache.
* *Activity feed.* Commit history is fetched once per window and cached on disk with the total
  commit count and repository statistics, both of which come from response headers and a second
  request that a cached read does not make. `versionCode` equals `git rev-list --count`, so a
  commit's distance from HEAD is its version number, and the feed can name exactly which commits an
  update would bring without an additional endpoint.
* *Contributor resolution.* GitHub links commits to accounts by email and does not always succeed.
  A `@users.noreply.github.com` address encodes the account and needs only parsing. Otherwise the
  name is probed against `/users/{name}` *only if it is shaped like a handle* — containing a digit,
  hyphen or underscore — because `GET /users/Qing` returns a real and unrelated account, and
  crediting a contribution to a stranger is worse than leaving it uncredited.

## Canary Distribution

`actions/artifacts/<id>/zip` returns 401 to an anonymous caller; a release asset returns 206. CI
therefore attaches each master build to a `canary-<versionCode>` prerelease, and the canary screen
reads `/releases`. No account is required at any point, which matters for users who cannot reach
GitHub's sign-in at all.

Device-flow sign-in remains available and requests *no scopes*. It only raises the anonymous rate
limit; if `githubClientId` is not supplied as a Gradle property the app hides sign-in entirely rather
than presenting a control that cannot work.

## Build Notes

* Material 3 Expressive has not landed in a stable `material3` release, so `material3` is pinned
  above the Compose BOM rather than resolved from it.
* Kotlin is declared at the root with `apply false`. AGP 9 otherwise supplies its own, older
  version, against which Coil's metadata fails to load.
* `githubClientId` is read from `local.properties` or `~/.gradle/gradle.properties` and defaults to
  empty.
