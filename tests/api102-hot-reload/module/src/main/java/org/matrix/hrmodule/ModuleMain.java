package org.matrix.hrmodule;

import android.os.Bundle;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Test module for libxposed API 102 hot reload.
 *
 * <p>Generation is baked in at build time so the log tells us which code is running.
 */
public class ModuleMain extends XposedModule {

    static final String TAG = "HRModule";
    static final String GEN = BuildConfig.GENERATION;

    /** Target of the hooks. */
    static final String TARGET_PKG = "org.matrix.hrtarget";

    /** Carried across generations through setSavedInstanceState. */
    static volatile String carried = "none";

    /** Counts calls so we can see whether state survived. */
    static volatile int calls = 0;

    /** How long the hooker on {@code Probe.slow()} blocks, so a reload can race it. */
    static final long SLOW_MS = 6000;

    static void log(String msg) {
        Log.i(TAG, "[" + GEN + "] " + msg);
    }

    public static class ValueHooker implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            calls++;
            return "HOOKED-" + GEN + " carried=" + carried + " calls=" + calls;
        }
    }

    /** Proceeds so the original exception must reach the caller unchanged. */
    public static class BoomHooker implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            return chain.proceed();
        }
    }

    /**
     * Blocks, then answers with the generation that was on the chain when the call started. A
     * reload driven while this is running must not change the answer.
     */
    public static class SlowHooker implements XposedInterface.Hooker {
        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            Thread.sleep(SLOW_MS);
            return "SLOW-ANSWERED-BY-" + GEN;
        }
    }

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        log("onModuleLoaded process=" + param.getProcessName()
                + " systemServer=" + param.isSystemServer()
                + " apiVersion=" + getApiVersion());

        probeLegacyApi(param.getProcessName());

        // detach(): a module whose work is confined to one process should stop hearing about the
        // others. The framework is required to keep every XposedInterface API working afterwards,
        // which is what the second half of this checks.
        if (!param.isSystemServer() && !TARGET_PKG.equals(param.getProcessName())) {
            detach();
            detach(); // idempotent by contract
            log("detached from " + param.getProcessName()
                    + "; APIs still work: framework=" + getFrameworkName()
                    + " api=" + getApiVersion());
        }
    }

    /**
     * API 102: "Libxposed modules can not call legacy de.robv APIs." This module declares
     * targetApiVersion=102, so every one of these must be refused - including through reflection,
     * which is the form a real module would reach for once direct linkage stopped compiling.
     */
    private void probeLegacyApi(String processName) {
        String[] names = {
            "de.robv.android.xposed.XposedBridge",
            "de.robv.android.xposed.XposedHelpers",
            "android.app.AndroidAppHelper",
            "android.content.res.XResources",
            "android.content.res.XModuleResources",
        };
        StringBuilder sb = new StringBuilder("legacy-api probe in " + processName + ":");
        for (String name : names) {
            try {
                Class<?> c = Class.forName(name, false, getClass().getClassLoader());
                sb.append("\n  BUG: resolved ").append(name).append(" -> ").append(c);
            } catch (ClassNotFoundException e) {
                sb.append("\n  refused ").append(name);
            } catch (Throwable t) {
                sb.append("\n  ").append(name).append(" -> ").append(t);
            }
        }

        // The one that means anything on an obfuscated build. The names above are string literals,
        // which the framework's dex obfuscator does not rewrite, so with obfuscation on they name
        // nothing whether or not the rule is enforced. This is a *type reference*, which is
        // rewritten along with the framework's own, so the loader is asked for the name a real
        // module would really end up asking for.
        try {
            LegacyLink.touch();
            sb.append("\n  BUG: resolved the legacy API by direct linkage");
        } catch (NoClassDefFoundError | ClassNotFoundException e) {
            sb.append("\n  refused direct linkage (")
                    .append(e.getClass().getSimpleName())
                    .append(")");
        } catch (Throwable t) {
            sb.append("\n  direct linkage -> ").append(t);
        }
        log(sb.toString());
    }

    /**
     * Isolated so the verifier only has to resolve {@code XposedBridge} when {@link #touch()} is
     * actually called, rather than when {@link ModuleMain} is loaded.
     */
    static final class LegacyLink {
        static void touch() throws ClassNotFoundException {
            de.robv.android.xposed.XposedBridge.log("hrmodule probing the legacy API");
        }
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        if (!TARGET_PKG.equals(param.getPackageName())) return;
        log("onPackageLoaded " + param.getPackageName() + " first=" + param.isFirstPackage());
        try {
            installHooks(param.getDefaultClassLoader());
        } catch (Throwable t) {
            log("installHooks failed: " + Log.getStackTraceString(t));
        }
    }

    private void installHooks(ClassLoader cl) throws Throwable {
        Class<?> probe = cl.loadClass(TARGET_PKG + ".Probe");
        Method value = probe.getDeclaredMethod("value");
        Method boom = probe.getDeclaredMethod("boom");
        Method slow = probe.getDeclaredMethod("slow");
        hook(value).setId("probe").intercept(new ValueHooker());
        hook(boom).setId("boom").intercept(new BoomHooker());
        hook(slow).setId("slow").intercept(new SlowHooker());
        log("hooks installed on " + probe);
    }

    @Override
    public boolean onHotReloading(XposedModuleInterface.HotReloadingParam param) {
        Bundle extras = param.getExtras();
        log("onHotReloading extras=" + (extras == null ? "null" : extras.keySet()));

        long sleepMs = extras == null ? 0 : extras.getLong("sleepMs", 0);
        if (sleepMs > 0) {
            log("onHotReloading sleeping " + sleepMs + "ms");
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            log("onHotReloading woke up");
        }

        if (extras != null && extras.getBoolean("frozenHook")) {
            // The framework is supposed to freeze old code before capturing hook handles,
            // so registering a new hook from here must fail.
            try {
                Method m = String.class.getDeclaredMethod("isEmpty");
                hook(m).intercept(new BoomHooker());
                log("BUG: old code installed a hook while frozen");
            } catch (Throwable t) {
                log("frozen as expected: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }

        if (extras != null && extras.getBoolean("refuse")) {
            log("refusing hot reload on request");
            return false;
        }
        if (extras != null && extras.getBoolean("throw")) {
            throw new IllegalStateException("onHotReloading threw on request");
        }
        if (extras != null && extras.getBoolean("secEx")) {
            // A SecurityException from module code. The AIDL reserves SecurityException on
            // hotReloadModule for an invalid target id, so this must arrive as FAILED instead.
            throw new SecurityException("module code threw SecurityException");
        }
        if (extras != null && extras.getBoolean("throwNullMsg")) {
            // An exception carrying no message. The spec says a thrown reload must be reported
            // with a framework-provided diagnostic message, and reserves the null message for a
            // module that REFUSED the reload. If this arrives as FAILED/null it is ambiguous.
            throw new IllegalStateException();
        }
        if (extras != null && extras.getBoolean("leak")) {
            // Deliberately hand over an object created by the OLD module classloader.
            // The framework is supposed to reject this with IllegalArgumentException.
            List<Object> leaky = new ArrayList<>();
            leaky.add(new ValueHooker());
            try {
                param.setSavedInstanceState(leaky);
                log("BUG: framework accepted an old-classloader object");
            } catch (IllegalArgumentException e) {
                log("framework correctly rejected old-classloader state: " + e.getMessage());
            }
        }

        // A plain String is classloader-neutral, so this must be accepted.
        param.setSavedInstanceState("carried-from-" + GEN + "-calls" + calls);
        return true;
    }

    @Override
    public void onHotReloaded(XposedModuleInterface.HotReloadedParam param) {
        Object saved = param.getSavedInstanceState();
        List<XposedInterface.HookHandle> old = new ArrayList<>(param.getOldHookHandles());
        // The interface does not specify an ordering, and the framework builds this list from a
        // concurrent set. Sorting makes the throwOnReloaded case below deterministic instead of
        // migrating a different subset on every run.
        old.sort(Comparator.comparing(h -> String.valueOf(h.getId())));

        log("onHotReloaded process=" + param.getProcessName()
                + " saved=" + saved
                + " oldHandles=" + old.size());
        carried = String.valueOf(saved);

        Bundle extras = param.getExtras();
        boolean bail = extras != null && extras.getBoolean("throwOnReloaded");
        boolean byId = extras != null && extras.getBoolean("idReplace");
        boolean checkStale = extras != null && extras.getBoolean("staleHandle");

        for (XposedInterface.HookHandle handle : old) {
            String id = handle.getId();
            try {
                if ("probe".equals(id)) {
                    migrateProbe(handle, byId, checkStale);
                    if (bail) break;
                } else if ("boom".equals(id)) {
                    handle.replaceHook(new BoomHooker());
                    log("replaced hook id=boom");
                } else if ("slow".equals(id)) {
                    handle.replaceHook(new SlowHooker());
                    log("replaced hook id=slow");
                } else {
                    handle.unhook();
                    log("unhooked id=" + id + " on " + handle.getExecutable());
                }
            } catch (Throwable t) {
                log("handle " + id + " failed: " + Log.getStackTraceString(t));
            }
        }
        if (bail) {
            log("throwing from onHotReloaded after replacing only one hook");
            throw new IllegalStateException("onHotReloaded threw on request");
        }
        log("onHotReloaded done");
    }

    /**
     * Migrates the {@code probe} hook, by handle or by id, and optionally checks that the handle
     * left behind is really dead.
     */
    private void migrateProbe(XposedInterface.HookHandle handle, boolean byId, boolean checkStale) {
        if (byId) {
            // The other half of the id contract: a new hook with the same id on the same
            // executable in the same module replaces the old one atomically. Nothing here holds
            // the old handle, which is how new code would normally reach for it.
            hook(handle.getExecutable()).setId("probe").intercept(new ValueHooker());
            log("replaced hook id=probe by id");
        } else {
            handle.replaceHook(new ValueHooker());
            log("replaced hook id=probe by handle");
        }

        if (!checkStale) return;

        // "After a successful replacement, this handle is no longer valid." Not merely unable to
        // replace again - unable to act on the record at all, so unhook() through it must not
        // cancel the hook that replaced it.
        try {
            handle.replaceHook(new BoomHooker());
            log("BUG: a superseded handle replaced the hook again");
        } catch (IllegalStateException e) {
            log("superseded handle correctly refused replaceHook: " + e.getMessage());
        }
        handle.unhook();
        log("called unhook() on the superseded handle; the probe hook must still be installed");
    }
}
