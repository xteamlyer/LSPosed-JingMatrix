package org.matrix.hrmodule;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/**
 * adb entrypoint:
 *   am broadcast -a org.matrix.hrmodule.CMD --es cmd targets
 *   am broadcast -a org.matrix.hrmodule.CMD --es cmd reload [--es filter hrtarget]
 *                [--ez refuse true] [--ez throw true] [--ez leak true]
 */
public class CmdReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String cmd = intent.getStringExtra("cmd");
        String filter = intent.getStringExtra("filter");
        Bundle data = new Bundle();
        for (String flag : new String[] {"refuse", "throw", "leak", "frozenHook", "throwOnReloaded", "throwNullMsg", "secEx"}) {
            if (intent.getBooleanExtra(flag, false)) data.putBoolean(flag, true);
        }
        long sleepMs = intent.getLongExtra("sleepMs", 0);
        if (sleepMs > 0) data.putLong("sleepMs", sleepMs);
        final int repeat = intent.getIntExtra("repeat", 1);
        final boolean concurrent = intent.getBooleanExtra("concurrent", false);
        data.putString("origin", "adb");

        // getRunningTargets()/hotReloadModule() are synchronous binder calls, so keep them
        // off the main thread.
        final PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                String out;
                if ("targets".equals(cmd)) {
                    out = Svc.describeTargets();
                } else if ("reload".equals(cmd)) {
                    if (concurrent) {
                        out = Svc.reloadConcurrently(filter, data, repeat);
                    } else {
                        StringBuilder acc = new StringBuilder();
                        for (int i = 0; i < repeat; i++) {
                            acc.append("#").append(i).append(" ")
                                    .append(Svc.reload(filter, data)).append("\n");
                        }
                        out = acc.toString();
                    }
                } else {
                    out = "unknown cmd: " + cmd;
                }
                Log.i(Svc.TAG, "CMD " + cmd + " ->\n" + out);
            } catch (Throwable t) {
                Log.e(Svc.TAG, "CMD " + cmd + " crashed", t);
            } finally {
                pending.finish();
            }
        }, "hr-cmd").start();
    }
}
