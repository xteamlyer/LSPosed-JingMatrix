# API 102 hot reload conformance harness

A two-app harness for exercising the libxposed API 102 hot reload contract against a Vector build,
from `adb` only. Written while reviewing #757; kept here so the same checks can be re-run against
any future implementation.

It is deliberately small and inert: the module installs two hooks on one class in one throwaway app
and does nothing else, so it is safe to leave enabled on a real device.

## Layout

| Module   | Package                | Role |
| -------- | ---------------------- | ---- |
| `module` | `org.matrix.hrmodule`  | The Xposed module (one Java entry class, as hot reload requires) plus a UI and a broadcast receiver that drive `XposedService` |
| `target` | `org.matrix.hrtarget`  | The hooked app. `Probe.value()` returns `"ORIGINAL"`; `Probe.boom()` throws. A receiver reports both plus the pid and process age |

The module's generation is baked in at build time via `-PhrGeneration=N`, which sets both
`versionCode` and a `BuildConfig.GENERATION` string. Every log line is prefixed `[V<N>]`, so the
log tells you unambiguously which generation of code is running.

## Build and install

```sh
./gradlew :target:assembleDebug
./gradlew :module:assembleDebug -PhrGeneration=1
adb install -r target/build/outputs/apk/debug/target-debug.apk
adb install -r module/build/outputs/apk/debug/module-debug.apk
```

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
adb logcat -d | grep HRModule
```

Both commands run off the main thread, because `XposedService.hotReloadModule()` is documented to
enqueue but currently blocks for the whole reload.

### Flags

All are passed through to `HotReloadingParam.getExtras()` and interpreted by the *old* generation,
so a flag only takes effect once a generation containing it is the one loaded in the target.

| Flag | Effect | Contract exercised |
| ---- | ------ | ------------------ |
| `--ez refuse true` | `onHotReloading` returns `false` | `FAILED` with a **null** message |
| `--ez throw true` | `onHotReloading` throws with a message | `FAILED` with a framework-provided message |
| `--ez throwNullMsg true` | `onHotReloading` throws `IllegalStateException()` | must still be distinguishable from a refusal |
| `--ez secEx true` | `onHotReloading` throws `SecurityException` | the AIDL reserves `SecurityException` for an invalid target id |
| `--ez leak true` | passes an old-classloader object to `setSavedInstanceState` | must be rejected with `IllegalArgumentException` |
| `--ez frozenHook true` | old code calls `hook(...).intercept(...)` during `onHotReloading` | old code is supposed to be frozen |
| `--ez throwOnReloaded true` | new code throws from `onHotReloaded` after replacing one hook | partial-migration handling |
| `--el sleepMs 4000` | `onHotReloading` sleeps | shows whether the caller blocks |
| `--ei repeat N` | N sequential reloads | leak / GC pressure |
| `--ez concurrent true --ei repeat 3` | N simultaneous reloads | per-target serialisation, `IN_PROGRESS` |

The module also probes, from inside the hooked process, what the injected service exposes —
`getScope()`, a preferences write, and an `openRemoteFile` write. On a spec-conformant build the
file write should not succeed, since `XposedInterface#openRemoteFile` is documented read-only.

## What it found

Measured on a Pixel 6, Android 17, against #757 merged onto master:

- Reload works: `STALE` detected, generation swapped in 70–110 ms, same pid, hooks replaced,
  `savedInstanceState` carried across.
- No classloader leak. 20 reloads grew Dalvik Other 16 MB → 64 MB PSS; a forced GC returned it to
  13.9 MB, below the starting baseline.
- **system_server never becomes a hot reload target.** The framework log shows the module loading
  and instantiating its entry in pid 1653, but `getRunningTargets()` never lists it.
  `registerHotReloadTarget` does a bare `state.modules[...]` lookup before the daemon cache is
  populated, throws `Unknown module`, and the client swallows it.
- **A frozen target is reported as a module refusal.** Freeze the target's cgroup
  (`/sys/fs/cgroup/apps/uid_<uid>/pid_<pid>/cgroup.freeze`) and a reload returns
  `FAILED message=null` in ~1 ms without ever reaching the module — identical to a genuine refusal.
  Unfreeze and it works again, same pid.
- A module exception carrying no message also yields `FAILED message=null`, which the spec reserves
  for a refusal.
- `onHotReloaded` throwing leaves the process running the new generation while the service reports
  `FAILED` and the old `loadedVersionCode`.
- Old code can still register hooks during `onHotReloading`; the stray hook then appears in
  `getOldHookHandles()`.

## Notes

- `META-INF/xposed/module.prop` carries `minApiVersion` and `targetApiVersion`, which
  `package-info.java` lists as the required properties. Vector's daemon currently reads only
  `targetApiVersion`.
- Scope is declared both in `META-INF/xposed/scope.list` (the mechanism `package-info.java`
  documents) and as an `xposedscope` resource array (what LSPosed-derived managers read). The tests
  above set scope through the CLI, so neither path is load-bearing here.
- The signing config points at the standard `~/.android/debug.keystore`.
