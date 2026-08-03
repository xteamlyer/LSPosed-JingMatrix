package org.matrix.hrtarget;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

/** Lets the harness poll the hooked values over adb without touching the screen. */
public class ProbeReceiver extends BroadcastReceiver {

    public static final String TAG = "HRTarget";

    /** Set once per process; lets us prove the process was never restarted. */
    private static final long PROCESS_BORN_AT = SystemClock.elapsedRealtime();

    public static String report() {
        String value;
        try {
            value = String.valueOf(Probe.value());
        } catch (Throwable t) {
            value = "THREW:" + t.getClass().getSimpleName() + ":" + t.getMessage();
        }
        String boom;
        try {
            boom = "RETURNED:" + Probe.boom();
        } catch (Throwable t) {
            boom = "THREW:" + t.getClass().getSimpleName() + ":" + t.getMessage();
        }
        return "pid=" + Process.myPid()
                + " aliveMs=" + (SystemClock.elapsedRealtime() - PROCESS_BORN_AT)
                + " value=" + value
                + " boom=" + boom;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent.getBooleanExtra("slow", false)) {
            // Started and deliberately left running: the point is to have a call sitting inside the
            // hook chain while the module is reloaded underneath it. Whichever generation answers
            // is logged with the elapsed time, so the two outcomes are told apart rather than
            // guessed at.
            long started = SystemClock.elapsedRealtime();
            new Thread(() -> {
                String slow;
                try {
                    slow = String.valueOf(Probe.slow());
                } catch (Throwable t) {
                    slow = "THREW:" + t.getClass().getSimpleName() + ":" + t.getMessage();
                }
                Log.i(TAG, "SLOW pid=" + Process.myPid()
                        + " tookMs=" + (SystemClock.elapsedRealtime() - started)
                        + " slow=" + slow);
            }, "hr-slow").start();
            Log.i(TAG, "SLOW dispatched pid=" + Process.myPid());
            return;
        }
        Log.i(TAG, "PROBE " + report());
    }
}
