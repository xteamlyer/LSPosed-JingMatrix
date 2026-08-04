Vector 2.2 is a hotfix for 2.1, and it brings libxposed API 102 — where a module can be swapped out without taking the process down with it.

> [!IMPORTANT]
> If you installed the manager as a separate app, uninstall it before updating: a 2.1 manager cannot talk to a 2.2 daemon. A parasitic manager needs nothing.

### 🩹 What 2.1 got wrong
*   🪝 **No hooks in release builds.** Modules loaded, and then nothing happened. R8 had merged `XResources` into a shared class that `XposedHelpers.findClass` touches on its way in, and `XResources` cannot resolve until the device has generated its super class. One failure is permanent, so every `findClass` in `system_server` failed for the rest of the boot.
*   🔗 **Canaries installed the wrong build.** Press install on a canary, get the newest release. The page is rebuilt around the builds themselves: each row a head commit, with its author, its pull request, and the issues closed since the build you are running.

### 🔁 libxposed API 102
Hot reload — a module's code replaced inside a process that is already running it. No more killing every process you are injected into to try a change, and no more reboot when the module hooks the system, taking with it the state you were trying to reproduce. Updates can reach processes still running the old code, if the module agrees to it. Around that: an entry class can step out of lifecycle callbacks while its siblings carry on, hookers swap atomically, and a module targeting 102 leaves the legacy API alone.

### 👥 One module, one configuration
A module is one package and one binary for the whole device, so its configuration belongs to the package; only its presence varies per user. Nothing enforced that, and a module installed in one user could run inside another user's applications. It now runs only in the users that installed it — `system_server` excepted, since it belongs to none of them.

### 🧹 Everything else
The IPC interfaces moved into Vector's own namespace, and the refactor shook out a run of unrelated bugs: JNI exceptions left pending across the native boundary, one of which left a process without Xposed while the log announced success; "Install as an app" going green for a copy that was a different build; an unbounded wait on a binder thread; a health flag latched before the work it reports. And the monochrome icon now carries the statue's own line work, so the themed launcher icon finally says what it is.

---

*The two fixes at the top exist because you reported them. Thank you — please keep telling us what breaks.*
