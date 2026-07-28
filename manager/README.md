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
    ├── screens/          # home, modules, logs, repo (store), canary, update, report, web, splash
    └── theme/            # Seeded colour scheme generation, typography, in-composition locale

src/debug/kotlin/org/matrix/vector/manager/demo/
                          # Scripted device states; compiled into debug builds only
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

## Localisation

The framework's language cannot come from the platform. Per-app language preferences are a manifest
feature, and this manifest is never registered, so `AppCompatDelegate.setApplicationLocales` has
nothing to attach to. The chosen language is instead applied inside the composition:
`ui/theme/AppLocale.kt` provides `LocalConfiguration`, `LocalContext` and `LocalLayoutDirection`
together, which is what makes a right-to-left language flip the whole app rather than only its text.

Two details are easy to get wrong and were:

* The overridden context must be a `ContextWrapper` around the activity, not the result of
  `createConfigurationContext` alone. The latter is detached from the activity, so anything reached
  through `LocalContext` — an activity result launcher, for one — fails to find its owner and the
  screen crashes on open rather than on language change.
* Every popup gets its own `AndroidComposeView`, which re-provides the Android composition locals
  from the base context. Sheets, dialogs and dropdown menus therefore render in the *system*
  language unless they re-apply the override themselves, which `LocalizedOverlay` exists to do.

Dates and month names are formatted at draw time, never in a model: a name formatted in a repository
is formatted with `Locale.getDefault()`, which in parasitic mode is the host application's.

## Framework Updates

The daemon gained an install path (`getRootImplementation`, `installFrameworkZip`) because the
manager cannot run a privileged flash itself. Root is detected by locating the implementation's own binary
and asking it — `magisk -V`, `ksud`, `apd` — rather than by looking for su, and the flash runs
through `ProcessBuilder` with an argument list, never a shell string, so a path can never become a
command. Progress arrives as log lines on an `IFrameworkInstallCallback`, delivered from a thread of the
daemon's own: a flash takes seconds to minutes, and holding a binder thread for it starves every
other call the manager is making meanwhile — including the log reads the install screen is doing to
show what is happening. If the manager goes away mid-flash the install continues, since stopping
would leave the module tree half-written, and the daemon's own log becomes the only record.

Two builds can share a version code — `git rev-list --count` is identical on a branch and on master
at the same depth — so a release's `target_commitish` is carried through and compared as well. When
the codes match and the hashes do not, the update screen says so instead of claiming the device is
up to date.

Every release publishes a release zip and a debug zip of roughly three times the size. Which one is
installed is the reader's choice, shown with its size, because the troubleshooting flow elsewhere in
this app asks people for a debug build.

## Module Updates

Installing a module APK goes through `PackageInstaller` with the download streamed straight into the
session — no temporary file, and no `FileProvider`, which parasitically does not exist.

The consent story differs sharply between the two modes, which is why the app has a confirmation
dialog of its own. Inside `com.android.shell` the manager inherits `INSTALL_PACKAGES`, so the commit
installs a third-party APK with no system prompt whatsoever; standalone, the platform asks as usual.
In the mode most people run, Vector's dialog is the only consent gate there is, so it names the
module, the file and its size *before* anything is downloaded.

Whether a module is out of date is one answer shared by three screens — `ServiceLocator.storeEntries`
joins the catalogue to the installed versions once, and the list's mark, the module's sheet and the
Store's count all read it. Muting is folded into that answer rather than applied at each reader,
because a mute only some of them honoured would be worse than none. The two screens that show a
module *by itself* deliberately ignore it: someone who opened one module's page is asking, not being
nagged.

Batch updates run one at a time on the application scope. Sequential because four concurrent sessions
contend for the same disk and, without `INSTALL_PACKAGES`, stack four system dialogs in an order
nobody chose; on the application scope because four modules take longer than anyone will hold a
bottom sheet open.

The panel is told when an install lands rather than waiting to overhear it. A replaced package does
broadcast and the manager does listen, but delivery is the system's business and this process is a
guest in someone else's; the one install path the app performs itself has no reason to learn about it
second-hand.

## Demo Mode

Several states worth designing against cannot be produced on a working phone: SELinux policy not
loaded, the system server not injected, a framework below the API level installed modules need, no
root implementation at all, every installed module a version behind. `src/debug` contains a scenario list and an `ILSPManagerService` stub
that scripts the answers it has an opinion about and delegates the rest to the real daemon.

It is a source set rather than a flag. A demo mode that could be switched on in a release build
would be a way to make the manager report a healthy framework when it is not, which is the one lie
this app must never be able to tell; a reviewer can confirm by finding no `manager/demo` classes in
a release APK.

The most useful ones lie about a *version*, because that is what every update decision is made
against: the framework's own version code comes from the daemon, and so do the installed modules', so
reporting an old one turns a real release into a real update with nothing else faked — the catalogue,
the release list, the APK and the install are all genuine. The module scenario stops lying about a
package the moment that package actually changes, which is what makes it a test of the refresh rather
than a picture of one.

The scenario host renders `VectorApp()` itself rather than launching the manager activity. Launching
it lets `ParasiticManagerHooker` hand over the real binder a moment later, which silently undid every
scenario — including "no daemon at all", which came up reporting a healthy framework.

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
* *Activity feed.* `versionCode` equals `git rev-list --count`, so a commit's distance from HEAD is
  its version number, and the feed can name exactly which commits an update would bring without an
  additional endpoint. The total count comes from the `Link: rel="last"` header and the repository
  statistics from a second request, so both are cached in files of their own — they are answers a
  cached read cannot reproduce, and writing a failed fetch straight through erased them.
* *Commit archive.* `/commits` returns at most a hundred per request, so a full history has to be
  walked backwards and kept. `data/github/CommitArchive.kt` is append-only NDJSON keyed by SHA:
  everything below the tip is immutable, so a chunk costs its own length rather than a rewrite, and
  the mutable head window is simply appended again with later lines winning on read.

  The walk is cursored on *date*, not page number — page numbers are relative to the tip and shift
  under any new commit — and on the **commit** date rather than the author date, because that is
  what `until` filters on and the two differ on 39 of the newest hundred commits here.

  A date cursor has one failure mode and this repository has it: 100+ commits share
  `2023-02-26T08:48:49Z`, and asking for commits at or before that second returns the same hundred
  forever. Inside such a plateau the walk pages by number, which is safe in exactly that position
  because the window is anchored by an `until` in the past. Completion is an *empty* page and
  nothing weaker; "nothing new" is what the plateau produces on every request.

  Three pages are fetched per visit and the cursor is left on disk. Sixty requests an hour is the
  anonymous budget, and a history that assembles over a few sessions is preferable to one that
  spends all of it on arrival.
* *Contributor resolution.* GitHub links commits to accounts by email and does not always succeed.
  A `@users.noreply.github.com` address encodes the account and needs only parsing. Otherwise the
  name is probed against `/users/{name}` *only if it is shaped like a handle* — containing a digit,
  hyphen or underscore — because `GET /users/Qing` returns a real and unrelated account, and
  crediting a contribution to a stranger is worse than leaving it uncredited.
* *Co-authors.* A `Co-authored-by:` trailer carries an address and no account, and no endpoint turns
  one into the other — the users search API refuses to index email, and answers `total_count: 0` for
  a noreply address however it is phrased. But every commit GitHub *has* attributed is a verified
  email-to-login pair, and the archive is full of them, so trailers are resolved against history
  already in hand at no request cost. Names are indexed too, one tier weaker and first-wins.
* *Module detection.* Deciding whether an installed package is a module means opening its APK and
  its splits as zips: roughly 550 opens on a 363-package device, once paid on every visit to the
  panel. `data/model/ModuleDetectionCache.kt` keys the answer by package, version code and install
  time, which is the exact set of things whose change can change the answer.

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
