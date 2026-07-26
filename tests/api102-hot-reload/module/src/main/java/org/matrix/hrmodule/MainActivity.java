package org.matrix.hrmodule;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {

    private TextView text;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Svc.init();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 96, 48, 48);

        text = new TextView(this);
        text.setTextSize(14f);
        root.addView(text);

        Button targets = new Button(this);
        targets.setText("List targets");
        targets.setOnClickListener(v -> run(() -> Svc.describeTargets()));
        root.addView(targets);

        Button reload = new Button(this);
        reload.setText("Hot reload hrtarget");
        reload.setOnClickListener(v -> run(() -> Svc.reload("hrtarget", new Bundle())));
        root.addView(reload);

        setContentView(root);
        text.setText("module app generation " + ModuleMain.GEN);
    }

    private void run(java.util.concurrent.Callable<String> work) {
        new Thread(() -> {
            String out;
            try {
                out = work.call();
            } catch (Exception e) {
                out = "error: " + e;
            }
            String finalOut = out;
            runOnUiThread(() -> text.setText(finalOut));
        }).start();
    }
}
