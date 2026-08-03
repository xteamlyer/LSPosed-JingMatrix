# API 102 conformance harness

A two-app harness for exercising the libxposed API 102 contract against a Vector build, from `adb`
only. Written while reviewing #757; kept here so the same checks can be re-run against any future
implementation.

It is deliberately small and inert: the module installs three hooks on one class in one throwaway
app and does nothing else, so it is safe to leave enabled on a real device.

## Layout

| Module   | Package                | Role |
| -------- | ---------------------- | ---- |
| `module` | `org.matrix.hrmodule`  | The Xposed module plus a UI and a broadcast receiver that drive `XposedService` |
| `target` | `org.matrix.hrtarget`  | The hooked app. `Probe.value()` returns `"ORIGINAL"`, `Probe.boom()` throws, `Probe.slow()` is hooked by a hooker that blocks. A receiver reports them plus the pid and process age |

The module's generation is baked in at build time via `-PhrGeneration=N`, which sets both
`versionCode` and a `BuildConfig.GENERATION` string. Every log line is prefixed `[V<N>]`, so the log
tells you unambiguously which generation of code is running.

`META-INF/xposed/` is **generated**, not checked in — `module.prop`'s `autoHotReload` and the number
of entries in `java_init.list` are themselves under test.

## Build and install

```sh
./gradlew :target:assembleDebug
./gradlew :module:assembleDebug -PhrGeneration=1
adb install -r target/build/outputs/apk/debug/target-debug.apk
adb install -r module/build/outputs/apk/debug/module-debug.apk
```

| Build property | Effect |
| -------------- | ------ |
| `-PhrGeneration=N` | The generation. Sets `versionCode` and the `[V<N>]` log prefix |
| `-PhrAutoReload=true` | `autoHotReload=true` in `module.prop` — installing the app should reload running targets by itself |
| `-PhrEntries=2` | Packages a second Java entry class. Hot reload requires exactly one, so this build must answer `UNSUPPORTED` |
| `-PhrEntries=0` | Declares no entry class at all. The API requires at least one; the daemon should refuse to load the module |

Enable and scope it (`system` is optional; include it to exercise system_server):

```sh
V=/data/adb/modules/zygisk_vector/cli
adb shell "su -c '$V modules enable org.matrix.hrmodule'"
adb shell "su -c '$V scope set org.matrix.hrmodule org.matrix.hrtarget/0 system/0'"
```

To produce a newer generation, rebuild and reinstall with a higher `-PhrGeneration`. Installing the
module app does not kill the *target* process, which is the whole point — the target keeps running
old code until it is hot reloaded.

## Driving it

Read the target:

```sh
adb shell am broadcast -a org.matrix.hrtarget.PROBE -n org.matrix.hrtarget/.ProbeReceiver
adb logcat -d | grep HRTarget
# HRTarget: PROBE pid=8883 aliveMs=64010 value=HOOKED-V2 carried=carried-from-V1-calls3 boom=THREW:...
```

`pid` and `aliveMs` are the proof that a reload did not restart the process.

Query and reload from the module app:

```sh
M="adb shell am broadcast -a org.matrix.hrmodule.CMD -n org.matrix.hrmodule/.CmdReceiver"
$M --es cmd targets
$M --es cmd reload --es filter hrtarget
$M --es cmd badTarget
adb logcat -d | grep HRModule
```

Launch the module app once after installing it. A freshly installed app is in the stopped state and
receives no broadcast until something starts it, which otherwise reads as a framework failure.

Both commands run off the main thread, because `getRunningTargets()` is a synchronous binder call.

### Flags

All are passed through to `HotReloadingParam.getExtras()` and interpreted by the *old* generation
(except `idReplace` and `staleHandle`, which the *new* one reads), so a flag only takes effect once
a generation containing it is the one loaded.

| Flag | Effect | Contract exercised |
| ---- | ------ | ------------------ |
| `--ez refuse true` | `onHotReloading` returns `false` | `FAILED` with a **null** message |
| `--ez throw true` | `onHotReloading` throws with a message | `FAILED` with a framework-provided message |
| `--ez throwNullMsg true` | `onHotReloading` throws `IllegalStateException()` | must still be distinguishable from a refusal |
| `--ez secEx true` | `onHotReloading` throws `SecurityException` | the AIDL reserves `SecurityException` for an invalid target id |
| `--ez leak true` | passes an old-classloader object to `setSavedInstanceState` | must be rejected with `IllegalArgumentException` |
| `--ez frozenHook true` | old code calls `hook(...).intercept(...)` during `onHotReloading` | old code is frozen before the handle list is captured |
| `--ez throwOnReloaded true` | new code throws from `onHotReloaded` after replacing one hook | no rollback: the new generation stays, and `FAILED` carries the new `loadedVersionCode` |
| `--ez idReplace true` | new code migrates by `setId` instead of by handle | the id form of atomic replacement |
| `--ez staleHandle true` | after replacing, uses the superseded handle | `replaceHook` must throw `IllegalStateException`, and `unhook()` must **not** cancel the replacement |
| `--el sleepMs 4000` | `onHotReloading` sleeps | the request enqueues rather than blocking |
| `--ei repeat N` | N sequential reloads | leak / GC pressure |
| `--ez concurrent true --ei repeat 3` | N simultaneous reloads | per-target serialisation, `IN_PROGRESS` |

### The in-flight snapshot

`Probe.slow()` is hooked by a hooker that blocks for six seconds, so a reload can land while a call
is still inside the chain:

```sh
adb shell am broadcast -a org.matrix.hrtarget.PROBE -n org.matrix.hrtarget/.ProbeReceiver --ez slow true
sleep 1
$M --es cmd reload --es filter hrtarget
adb logcat -d | grep 'HRTarget: SLOW'
# SLOW pid=8883 tookMs=6003 slow=SLOW-ANSWERED-BY-V1     <- the generation that started the call
```

`SLOW-ANSWERED-BY-V2` there would mean the replacement leaked into a call already running.

### detach and the legacy API

Both are probed automatically at load, in every process, and reported to the log:

```sh
adb logcat -d | grep -E 'legacy-api probe|detached from'
```

Every one of the five legacy names must be `refused`. The module declares `targetApiVersion=102`,
and the probe goes through `Class.forName` rather than direct linkage, because reflection is what a
module would reach for once linking stopped compiling. Note that the framework rewrites those
package names when dex obfuscation is on, so a build with obfuscation enabled is a materially
different test from one without — run both.

## Notes

- `META-INF/xposed/module.prop` carries `minApiVersion` and `targetApiVersion`, which
  `package-info.java` lists as the required properties. Vector's daemon reads `targetApiVersion`
  (it decides the legacy-API denial) and deliberately not `minApiVersion` — the API puts that check
  on the module, through `getApiVersion()`.
- Scope is declared both in `META-INF/xposed/scope.list` and as an `xposedscope` resource array
  (what LSPosed-derived managers read). The commands above set scope through the CLI, so neither
  path is load-bearing here.
- The signing config points at the standard `~/.android/debug.keystore`.
- The target app has no long-lived component, so between probes its process is cached and freezable.
  `pid` is the only trustworthy no-restart signal; `aliveMs` is measured from `ProbeReceiver` class
  init, so it reads ~0 whenever the broadcast itself started the process.
