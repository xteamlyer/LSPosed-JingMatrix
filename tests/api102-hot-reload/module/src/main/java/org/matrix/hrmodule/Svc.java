package org.matrix.hrmodule;

import android.os.Bundle;
import android.util.Log;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.github.libxposed.service.HookedTarget;
import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/** Thin wrapper so both the activity and the adb-driven receiver can talk to the framework. */
public final class Svc {

    public static final String TAG = "HRModuleApp";

    private static final AtomicReference<XposedService> SERVICE = new AtomicReference<>();
    private static final CountDownLatch BOUND = new CountDownLatch(1);

    private Svc() {}

    public static void init() {
        if (SERVICE.get() != null) return;
        XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
            @Override
            public void onServiceBind(XposedService service) {
                Log.i(TAG, "service bound: " + service.getFrameworkName()
                        + " " + service.getFrameworkVersion()
                        + " api=" + service.getApiVersion());
                SERVICE.set(service);
                BOUND.countDown();
            }

            @Override
            public void onServiceDied(XposedService service) {
                Log.w(TAG, "service died");
                SERVICE.compareAndSet(service, null);
            }
        });
    }

    public static XposedService await() {
        init();
        try {
            BOUND.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        return SERVICE.get();
    }

    public static String describeTargets() {
        XposedService svc = await();
        if (svc == null) return "no service";
        StringBuilder sb = new StringBuilder();
        try {
            List<HookedTarget> targets = svc.getRunningTargets();
            sb.append("targets=").append(targets.size());
            for (int i = 0; i < targets.size(); i++) {
                HookedTarget t = targets.get(i);
                sb.append("\n  [").append(i).append("] ")
                        .append(t.getProcessName())
                        .append(" pid=").append(t.getPid())
                        .append(" uid=").append(t.getUid())
                        .append(" state=").append(t.getState())
                        .append(" loadedVersionCode=").append(t.getLoadedVersionCode());
            }
        } catch (Throwable t) {
            sb.append("FAILED: ").append(Log.getStackTraceString(t));
        }
        return sb.toString();
    }

    /** Fires {@code n} reload requests for the same target at once, from n threads. */
    public static String reloadConcurrently(String filter, Bundle data, int n) {
        XposedService svc = await();
        if (svc == null) return "no service";
        List<HookedTarget> targets;
        try {
            targets = svc.getRunningTargets();
        } catch (Throwable t) {
            return "getRunningTargets failed: " + t;
        }
        HookedTarget target = null;
        for (HookedTarget t : targets) {
            if (filter == null || t.getProcessName().contains(filter)) { target = t; break; }
        }
        if (target == null) return "no matching target";

        final HookedTarget chosen = target;
        Thread[] threads = new Thread[n];
        for (int i = 0; i < n; i++) {
            final int idx = i;
            threads[i] = new Thread(() -> {
                try {
                    svc.hotReloadModule(chosen, data, (tg, result) ->
                            Log.i(TAG, "RESULT[" + idx + "] " + tg.getProcessName()
                                    + " status=" + result.status()
                                    + " message=" + result.message()));
                } catch (Throwable t) {
                    Log.i(TAG, "RESULT[" + idx + "] threw " + t);
                }
            }, "hr-reload-" + i);
        }
        for (Thread t : threads) t.start();
        for (Thread t : threads) {
            try { t.join(60_000); } catch (InterruptedException ignored) {}
        }
        return "fired " + n + " concurrent reloads at " + chosen.getProcessName();
    }

    /** Reloads every target whose process name matches {@code filter}. */
    public static String reload(String filter, Bundle data) {
        XposedService svc = await();
        if (svc == null) return "no service";
        StringBuilder sb = new StringBuilder();
        try {
            List<HookedTarget> targets = svc.getRunningTargets();
            int hit = 0;
            for (HookedTarget t : targets) {
                if (filter != null && !t.getProcessName().contains(filter)) continue;
                hit++;
                sb.append("requesting reload of ").append(t.getProcessName())
                        .append(" (state=").append(t.getState()).append(")\n");
                long started = System.nanoTime();
                svc.hotReloadModule(t, data, (target, result) -> {
                    long ms = (System.nanoTime() - started) / 1_000_000;
                    Log.i(TAG, "RESULT " + target.getProcessName()
                            + " status=" + result.status()
                            + " message=" + result.message()
                            + " afterMs=" + ms);
                });
            }
            sb.append("dispatched ").append(hit).append(" request(s)");
        } catch (Throwable t) {
            sb.append("FAILED: ").append(Log.getStackTraceString(t));
        }
        return sb.toString();
    }
}
