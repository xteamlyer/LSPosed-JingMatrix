package org.matrix.hrmodule;

import android.os.Bundle;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
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

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        log("onModuleLoaded process=" + param.getProcessName()
                + " systemServer=" + param.isSystemServer()
                + " apiVersion=" + getApiVersion());
        if (!param.isSystemServer()) {
            new Thread(() -> probeInjectedService(param.getProcessName()), "hr-probe").start();
        }
    }

    /**
     * What can module code reach through the service binder that now lives inside an ordinary
     * hooked app process? On master only read-only preferences and file reads were available here.
     */
    private void probeInjectedService(String processName) {
        StringBuilder sb = new StringBuilder("injected-service probe in " + processName + ":");
        try {
            io.github.libxposed.service.XposedService svc = Svc.await();
            if (svc == null) {
                log(sb + " no service");
                return;
            }
            sb.append("\n  framework=").append(svc.getFrameworkName())
                    .append(" api=").append(svc.getApiVersion());
            try {
                sb.append("\n  getScope()=").append(svc.getScope());
            } catch (Throwable t) {
                sb.append("\n  getScope() -> ").append(t);
            }
            try {
                android.content.SharedPreferences p = svc.getRemotePreferences("probe");
                p.edit().putString("written_from", processName).apply();
                Thread.sleep(300);
                sb.append("\n  prefs write -> ").append(
                        svc.getRemotePreferences("probe").getString("written_from", "<absent>"));
            } catch (Throwable t) {
                sb.append("\n  prefs write -> ").append(t);
            }
            try {
                android.os.ParcelFileDescriptor pfd = svc.openRemoteFile("written_by_hooked_app.txt");
                java.io.FileOutputStream out =
                        new java.io.FileOutputStream(pfd.getFileDescriptor());
                out.write(("written by " + processName).getBytes());
                out.flush();
                pfd.close();
                sb.append("\n  openRemoteFile(write) -> OK, listRemoteFiles=")
                        .append(java.util.Arrays.toString(svc.listRemoteFiles()));
            } catch (Throwable t) {
                sb.append("\n  openRemoteFile(write) -> ").append(t);
            }
        } catch (Throwable t) {
            sb.append("\n  probe crashed: ").append(Log.getStackTraceString(t));
        }
        log(sb.toString());
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
        hook(value).setId("probe").intercept(new ValueHooker());
        hook(boom).setId("boom").intercept(new BoomHooker());
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
        List<XposedInterface.HookHandle> old = param.getOldHookHandles();
        log("onHotReloaded process=" + param.getProcessName()
                + " saved=" + saved
                + " oldHandles=" + old.size());
        carried = String.valueOf(saved);

        Bundle extras = param.getExtras();
        boolean bail = extras != null && extras.getBoolean("throwOnReloaded");

        for (XposedInterface.HookHandle handle : old) {
            String id = handle.getId();
            try {
                if ("probe".equals(id)) {
                    handle.replaceHook(new ValueHooker());
                    log("replaced hook id=probe");
                    if (bail) break;
                } else if ("boom".equals(id)) {
                    handle.replaceHook(new BoomHooker());
                    log("replaced hook id=boom");
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
}
