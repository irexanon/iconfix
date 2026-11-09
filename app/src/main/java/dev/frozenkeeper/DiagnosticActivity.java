package dev.frozenkeeper;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/** On-device diagnostic screen for the LSPosed hook. */
public final class DiagnosticActivity extends Activity {
    private TextView logView;
    private TextView statusView;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        refreshLogs();
    }

    private void buildUi() {
        int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.rgb(18, 18, 18));

        TextView title = text("Frozen Icon Keeper — Diagnostics", 21, Color.WHITE);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        statusView = text("Reading Logcat…", 14, Color.rgb(150, 220, 150));
        statusView.setPadding(0, dp(8), 0, dp(8));
        root.addView(statusView, new LinearLayout.LayoutParams(-1, -2));

        TextView instructions = text(
                "Scope: com.android.launcher only\n"
                        + "1. Clear log below.  2. Restart Launcher.  3. Freeze one app in Ice Box.\n"
                        + "4. Unfreeze it.  5. Tap Refresh and copy the complete log.",
                14, Color.LTGRAY);
        root.addView(instructions, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        buttons.setPadding(0, dp(10), 0, dp(10));
        Button refresh = button("Refresh");
        Button clear = button("Clear Logcat");
        Button copy = button("Copy Logs");
        buttons.addView(refresh, weight());
        buttons.addView(clear, weight());
        buttons.addView(copy, weight());
        root.addView(buttons, new LinearLayout.LayoutParams(-1, -2));

        logView = text("", 12, Color.rgb(220, 220, 220));
        logView.setTextIsSelectable(true);
        logView.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        logView.setGravity(Gravity.TOP | Gravity.START);
        logView.setPadding(dp(8), dp(8), dp(8), dp(8));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(logView, new ScrollView.LayoutParams(-1, -1));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        refresh.setOnClickListener(v -> refreshLogs());
        clear.setOnClickListener(v -> clearLogs());
        copy.setOnClickListener(v -> copyLogs());
        setContentView(root);
    }

    private void refreshLogs() {
        new Thread(() -> {
            String output = readLogs();
            runOnUiThread(() -> {
                logView.setText(output);
                statusView.setText(output.contains("FrozenIconKeeper")
                        ? "Hook messages found. Check attached / skipped / saved / restored lines."
                        : "No FrozenIconKeeper messages found. Confirm LSPosed scope and restart Launcher.");
            });
        }, "FrozenIconKeeper-log-reader").start();
    }

    private String readLogs() {
        StringBuilder result = new StringBuilder();
        try {
            Process p = new ProcessBuilder("logcat", "-d", "-v", "time",
                    "-s", "FrozenIconKeeper:D", "XposedBridge:D", "*:S")
                    .redirectErrorStream(true).start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("FrozenIconKeeper") || line.contains("[FrozenIconKeeper]")) {
                        result.append(line).append('\n');
                    }
                }
            }
            p.waitFor();
        } catch (Throwable t) {
            result.append("Cannot read Logcat: ").append(t).append('\n')
                    .append("Use: adb logcat -d -v time -s FrozenIconKeeper:D XposedBridge:D\n");
        }
        if (result.length() == 0) {
            return "No matching messages.\n\nExpected after launcher restart:\n"
                    + "attached\n"
                    + "hooked LoaderCursor.markDeleted\n"
                    + "hooked OplusPackageUpdatedTask.execute\n"
                    + "saved from loader/saved\n"
                    + "restored\n"
                    + "forceReload called\n";
        }
        return result.toString();
    }

    private void clearLogs() {
        try {
            new ProcessBuilder("logcat", "-c").start().waitFor();
            logView.setText("Logcat cleared. Restart Launcher, run the freeze/unfreeze test, then tap Refresh.\n");
            statusView.setText("Logcat cleared.");
        } catch (Throwable t) {
            Toast.makeText(this, "Clear failed: " + t.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
        }
    }

    private void copyLogs() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("FrozenIconKeeper logs", logView.getText()));
        Toast.makeText(this, "Logs copied", Toast.LENGTH_SHORT).show();
    }

    private TextView text(String value, int size, int color) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(size);
        v.setTextColor(color);
        return v;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        return b;
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, -2, 1f);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
