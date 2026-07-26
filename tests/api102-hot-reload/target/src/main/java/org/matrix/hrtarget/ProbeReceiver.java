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
        Log.i(TAG, "PROBE " + report());
    }
}
