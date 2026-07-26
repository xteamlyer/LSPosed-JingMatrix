package org.matrix.hrtarget;

import android.app.Activity;
import android.os.Bundle;
import android.os.Process;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {

    private TextView text;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 96, 48, 48);

        text = new TextView(this);
        text.setTextSize(16f);
        text.setGravity(Gravity.START);
        root.addView(text);

        Button refresh = new Button(this);
        refresh.setText("Refresh");
        refresh.setOnClickListener(v -> refresh());
        root.addView(refresh);

        setContentView(root);
        refresh();
    }

    private void refresh() {
        text.setText(ProbeReceiver.report());
    }
}
