package com.multicall.audio;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import rikka.shizuku.Shizuku;

/**
 * MultiCall: per-app audio controls through Shizuku (shell-level app ops).
 *
 * Two separate app ops do two separate jobs:
 *   PLAY_AUDIO        -> "ignore" MUTES the app's own sound (e.g. Duolingo goes silent).
 *   TAKE_AUDIO_FOCUS  -> "ignore" stops the app grabbing audio focus, so it can no longer
 *                        pause or duck (lower) your music. The app itself still makes sound.
 *
 * The old build only ever set TAKE_AUDIO_FOCUS, which is why Duolingo kept playing.
 */
public class MainActivity extends Activity {

    static final String DUOLINGO = "com.duolingo";
    static final String[] MEETING_APPS = {
            "com.microsoft.teams",
            "us.zoom.videomeetings",
            "com.google.android.apps.meetings",
            "com.google.android.apps.tachyon"
    };
    static final String OP_MUTE = "PLAY_AUDIO";
    static final String OP_FOCUS = "TAKE_AUDIO_FOCUS";
    static final int SHIZUKU_REQUEST = 1;

    AudioManager audio;
    SharedPreferences prefs;
    LinearLayout root, panel;
    TextView status, selection, shizukuState;
    final Set<String> selected = new LinkedHashSet<>();

    // ---------- Shizuku wiring ----------

    final Shizuku.OnBinderReceivedListener binderReceived = () -> runOnUiThread(this::refreshShizukuState);
    final Shizuku.OnBinderDeadListener binderDead = () -> runOnUiThread(this::refreshShizukuState);
    final Shizuku.OnRequestPermissionResultListener permissionResult = (code, result) -> runOnUiThread(() -> {
        refreshShizukuState();
        if (code == SHIZUKU_REQUEST) {
            toastDialog(result == PackageManager.PERMISSION_GRANTED
                    ? "Shizuku permission granted. You're ready to go."
                    : "Shizuku permission was denied. Tap Connect Shizuku again and choose Allow.");
        }
    });

    boolean shizukuReady() {
        try {
            return Shizuku.pingBinder() && !Shizuku.isPreV11()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    void refreshShizukuState() {
        if (shizukuState == null) return;
        String s;
        boolean ok = false;
        try {
            if (!Shizuku.pingBinder()) s = "Shizuku: not running. Open the Shizuku app and start it.";
            else if (Shizuku.isPreV11()) s = "Shizuku: too old. Update the Shizuku app.";
            else if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) s = "Shizuku: running, tap Connect Shizuku to allow MultiCall.";
            else { s = "Shizuku: connected"; ok = true; }
        } catch (Throwable t) {
            s = "Shizuku: error " + t.getMessage();
        }
        shizukuState.setText(s);
        shizukuState.setTextColor(c(ok ? "#72F2CE" : "#FFB86B"));
    }

    void connectShizuku() {
        try {
            if (!Shizuku.pingBinder()) {
                new AlertDialog.Builder(this)
                        .setMessage("Shizuku isn't running.\n\nOpen the Shizuku app, start it (wireless debugging), then come back here.")
                        .setPositiveButton("Open Shizuku", (d, w) -> launch("moe.shizuku.privileged.api"))
                        .setNegativeButton("OK", null).show();
                return;
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(SHIZUKU_REQUEST);
            } else {
                toastDialog("Shizuku is already connected.");
            }
        } catch (Throwable t) {
            status.setText("Shizuku error: " + t);
        }
        refreshShizukuState();
    }

    /** Runs one shell command as the Shizuku (shell) user. Returns "exitCode\noutput". */
    static String[] runShell(String... cmd) throws Exception {
        Method m = Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
        m.setAccessible(true);
        Process p = (Process) m.invoke(null, cmd, null, null);
        String out = readAll(p.getInputStream());
        String err = readAll(p.getErrorStream());
        int code = p.waitFor();
        return new String[]{String.valueOf(code), (out + err).trim()};
    }

    // InputStream.readAllBytes() only exists on Android 13+, so read manually.
    static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) bo.write(buf, 0, n);
        in.close();
        return bo.toString("UTF-8");
    }

    /** Sets an op at both package and uid level so it sticks on Samsung firmware. */
    static String setOp(String pkg, String op, String mode) throws Exception {
        String[] a = runShell("cmd", "appops", "set", pkg, op, mode);
        String[] b = runShell("cmd", "appops", "set", "--uid", pkg, op, mode);
        StringBuilder sb = new StringBuilder();
        if (!a[0].equals("0")) sb.append("package-level failed: ").append(a[1]).append('\n');
        if (!b[0].equals("0")) sb.append("uid-level failed: ").append(b[1]).append('\n');
        return sb.toString();
    }

    /** Human readable state of an op: "blocked" / "normal". */
    static String readOp(String pkg, String op) throws Exception {
        String a = runShell("cmd", "appops", "get", pkg, op)[1];
        String b = runShell("cmd", "appops", "get", "--uid", pkg, op)[1];
        String all = (a + " " + b).toLowerCase();
        return (all.contains("ignore") || all.contains("deny") || all.contains("errored")) ? "blocked" : "normal";
    }

    // ---------- Actions ----------

    interface Job { String run() throws Exception; }

    void runJob(String title, Job job) {
        if (!shizukuReady()) {
            refreshShizukuState();
            new AlertDialog.Builder(this).setTitle(title)
                    .setMessage("Shizuku isn't connected yet.\n\n1. Start the Shizuku app\n2. Tap Connect Shizuku here and choose Allow")
                    .setPositiveButton("Connect Shizuku", (d, w) -> connectShizuku())
                    .setNegativeButton("Cancel", null).show();
            return;
        }
        ProgressDialog working = ProgressDialog.show(this, title, "Working…", true, false);
        new Thread(() -> {
            String result;
            try {
                result = job.run();
            } catch (Throwable t) {
                result = "Failed: " + t;
            }
            final String r = result;
            runOnUiThread(() -> {
                working.dismiss();
                status.setText(title + " — done");
                new AlertDialog.Builder(this).setTitle(title).setMessage(r).setPositiveButton("OK", null).show();
            });
        }).start();
    }

    String applyToApps(List<String> pkgs, String op, String mode, boolean restartApps) throws Exception {
        if (pkgs.isEmpty()) return "None of those apps are installed.";
        StringBuilder sb = new StringBuilder();
        for (String pkg : pkgs) {
            String err = setOp(pkg, op, mode);
            rememberTouched(pkg);
            if (restartApps) runShell("am", "force-stop", pkg);
            String now = readOp(pkg, op);
            sb.append(label(pkg)).append(": ")
              .append(op.equals(OP_MUTE) ? (now.equals("blocked") ? "MUTED" : "sound ON")
                                         : (now.equals("blocked") ? "can't pause/duck music" : "normal focus"))
              .append('\n');
            if (!err.isEmpty()) sb.append("   ").append(err);
        }
        return sb.toString().trim();
    }

    String restoreApps(List<String> pkgs) throws Exception {
        if (pkgs.isEmpty()) return "Nothing to restore.";
        StringBuilder sb = new StringBuilder();
        for (String pkg : pkgs) {
            String e1 = setOp(pkg, OP_MUTE, "default");
            String e2 = setOp(pkg, OP_FOCUS, "default");
            sb.append(label(pkg)).append(": back to normal\n");
            if (!(e1 + e2).isEmpty()) sb.append("   ").append(e1).append(e2);
        }
        return sb.toString().trim();
    }

    String statusOf(List<String> pkgs) throws Exception {
        if (pkgs.isEmpty()) return "Select at least one app first.";
        StringBuilder sb = new StringBuilder();
        for (String pkg : pkgs) {
            sb.append(label(pkg)).append('\n')
              .append("   Sound: ").append(readOp(pkg, OP_MUTE).equals("blocked") ? "MUTED" : "on").append('\n')
              .append("   Can pause/duck other audio: ").append(readOp(pkg, OP_FOCUS).equals("blocked") ? "no" : "yes").append('\n');
        }
        return sb.toString().trim();
    }

    void muteDuolingo(boolean mute) {
        if (!installed(DUOLINGO)) { toastDialog("Duolingo isn't installed."); return; }
        List<String> one = new ArrayList<>();
        one.add(DUOLINGO);
        runJob(mute ? "Mute Duolingo" : "Unmute Duolingo", () -> {
            String r = applyToApps(one, OP_MUTE, mute ? "ignore" : "default", true);
            if (mute) {
                // Also stop it pausing your music when a lesson starts.
                setOp(DUOLINGO, OP_FOCUS, "ignore");
                r += "\nDuolingo also can't pause or duck your music now.";
            } else {
                setOp(DUOLINGO, OP_FOCUS, "default");
            }
            return r + "\n\nDuolingo was closed so the change applies cleanly. Open it again and test a lesson.";
        });
    }

    void meetingNoDuck(boolean on) {
        List<String> apps = new ArrayList<>();
        for (String p : MEETING_APPS) if (installed(p)) apps.add(p);
        runJob(on ? "Stop meeting apps ducking music" : "Undo meeting app fix", () -> {
            String r = applyToApps(apps, OP_FOCUS, on ? "ignore" : "default", false);
            if (on) r += "\n\nTip: apply this BEFORE joining the call (or rejoin). If media is still quieter "
                    + "during a live call, that's Android's call-mode volume, not ducking: enable Multi Sound / "
                    + "Separate app sound in Sound Assistant and raise Media volume below.";
            return r;
        });
    }

    List<String> selectedList() { return new ArrayList<>(selected); }

    // ---------- UI ----------

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        prefs = getSharedPreferences("multicall", MODE_PRIVATE);
        loadSelection();

        ScrollView scroll = new ScrollView(this);
        getWindow().setStatusBarColor(c("#10182A"));
        getWindow().setNavigationBarColor(c("#10182A"));
        scroll.setBackgroundColor(c("#10182A"));
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(28));
        scroll.addView(root);
        setContentView(scroll);
        panel = root;

        root.addView(text("MULTICALL  /  AUDIO STUDIO", 12, "#72F2CE"));
        TextView hero = text("Your audio.\nYour rules.", 36, "#F1F6FF");
        hero.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(hero);
        root.addView(text("Galaxy edition • Meeting & media control", 14, "#AABBD3"));

        section("Connection", "Shizuku gives MultiCall the shell access it needs.");
        shizukuState = text("Shizuku: checking…", 14, "#FFB86B");
        panel.addView(shizukuState);
        status = text("Ready", 13, "#AABBD3");
        panel.addView(status);
        button("Connect Shizuku", this::connectShizuku);

        section("01  Quick fixes", "One tap. Each one can be undone.");
        button("Mute Duolingo", () -> muteDuolingo(true));
        button("Unmute Duolingo", () -> muteDuolingo(false));
        button("Stop Teams / Zoom / Meet ducking my music", () -> meetingNoDuck(true));
        button("Undo meeting app fix", () -> meetingNoDuck(false));

        section("02  Any app", "Pick one or more apps, then choose what to do.");
        selection = text("", 15, "#72F2CE");
        panel.addView(selection);
        updateSelectionText();
        button("Choose apps", this::chooseApps);
        button("Mute selected apps", () -> runJob("Mute apps", () -> applyToApps(selectedList(), OP_MUTE, "ignore", false)
                + "\n\nIf an app is still audible, tap Restart selected apps."));
        button("Unmute selected apps", () -> runJob("Unmute apps", () -> applyToApps(selectedList(), OP_MUTE, "default", false)));
        button("Stop selected apps pausing / ducking my music", () -> runJob("Block audio focus",
                () -> applyToApps(selectedList(), OP_FOCUS, "ignore", false)
                        + "\n\nThese apps still make their own sound; they just can't pause or lower other audio."));
        button("Restore selected apps to normal", () -> runJob("Restore apps", () -> restoreApps(selectedList())));
        button("Check selected apps", () -> runJob("App audio status", () -> statusOf(selectedList())));
        button("Restart selected apps", () -> runJob("Restart apps", () -> {
            StringBuilder sb = new StringBuilder();
            for (String p : selected) { runShell("am", "force-stop", p); sb.append(label(p)).append(": restarted\n"); }
            return sb.length() == 0 ? "Select at least one app first." : sb.toString().trim();
        }));

        section("03  Meeting mode", "Volume and Samsung Multi Sound.");
        volume("Media volume", AudioManager.STREAM_MUSIC);
        volume("Call volume", AudioManager.STREAM_VOICE_CALL);
        button("Open Sound Assistant (Multi Sound)", () -> launch("com.samsung.android.soundassistant"));
        button("Open Teams", () -> launch("com.microsoft.teams"));
        button("Open Zoom", () -> launch("us.zoom.videomeetings"));

        section("04  Reset & diagnostics", "Undo everything MultiCall has changed.");
        button("Reset every app MultiCall touched", () -> runJob("Reset all",
                () -> restoreApps(new ArrayList<>(prefs.getStringSet("touched", new HashSet<>())))));
        button("Check audio mode", () -> {
            int mode = audio.getMode();
            String name = mode == AudioManager.MODE_IN_COMMUNICATION ? "in a VoIP call (Teams/Zoom)"
                    : mode == AudioManager.MODE_IN_CALL ? "in a phone call"
                    : mode == AudioManager.MODE_RINGTONE ? "ringing" : "normal";
            toastDialog("Audio mode: " + name + "\nMusic active: " + audio.isMusicActive());
        });
        button("Check for updates", this::checkUpdate);

        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceived);
            Shizuku.addBinderDeadListener(binderDead);
            Shizuku.addRequestPermissionResultListener(permissionResult);
        } catch (Throwable ignored) { }
        refreshShizukuState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshShizukuState();
    }

    @Override
    protected void onDestroy() {
        try {
            Shizuku.removeBinderReceivedListener(binderReceived);
            Shizuku.removeBinderDeadListener(binderDead);
            Shizuku.removeRequestPermissionResultListener(permissionResult);
        } catch (Throwable ignored) { }
        super.onDestroy();
    }

    void loadSelection() {
        selected.clear();
        selected.addAll(prefs.getStringSet("selectedApps", new HashSet<>()));
        // migrate single selection from older builds
        String old = getPreferences(0).getString("selected", null);
        if (old != null && selected.isEmpty()) selected.add(old);
    }

    void saveSelection() {
        prefs.edit().putStringSet("selectedApps", new HashSet<>(selected)).apply();
    }

    void rememberTouched(String pkg) {
        Set<String> t = new HashSet<>(prefs.getStringSet("touched", new HashSet<>()));
        t.add(pkg);
        prefs.edit().putStringSet("touched", t).apply();
    }

    void updateSelectionText() {
        if (selected.isEmpty()) { selection.setText("No apps selected"); return; }
        StringBuilder sb = new StringBuilder();
        for (String p : selected) sb.append("• ").append(label(p)).append('\n');
        selection.setText(sb.toString().trim());
    }

    void chooseApps() {
        PackageManager pm = getPackageManager();
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> raw = pm.queryIntentActivities(intent, 0);
        List<String> pkgs = new ArrayList<>();
        List<String> names = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        raw.sort((a, z) -> a.loadLabel(pm).toString().compareToIgnoreCase(z.loadLabel(pm).toString()));
        for (ResolveInfo r : raw) {
            String p = r.activityInfo.packageName;
            if (p.equals(getPackageName()) || !seen.add(p)) continue;
            pkgs.add(p);
            names.add(r.loadLabel(pm) + "  •  " + p);
        }
        boolean[] checked = new boolean[pkgs.size()];
        for (int i = 0; i < pkgs.size(); i++) checked[i] = selected.contains(pkgs.get(i));
        new AlertDialog.Builder(this).setTitle("Select apps")
                .setMultiChoiceItems(names.toArray(new String[0]), checked, (d, i, isChecked) -> checked[i] = isChecked)
                .setPositiveButton("Done", (d, w) -> {
                    selected.clear();
                    for (int i = 0; i < pkgs.size(); i++) if (checked[i]) selected.add(pkgs.get(i));
                    saveSelection();
                    updateSelectionText();
                })
                .setNegativeButton("Cancel", null).show();
    }

    boolean installed(String pkg) {
        try { getPackageManager().getPackageInfo(pkg, 0); return true; }
        catch (Exception e) { return false; }
    }

    String label(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    void launch(String p) {
        Intent i = getPackageManager().getLaunchIntentForPackage(p);
        if (i != null) startActivity(i);
        else status.setText("App not installed: " + p);
    }

    void toastDialog(String msg) {
        new AlertDialog.Builder(this).setMessage(msg).setPositiveButton("OK", null).show();
    }

    int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }

    int c(String hex) { return Color.parseColor(hex); }

    GradientDrawable bg(String hex, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(c(hex));
        d.setCornerRadius(dp(radius));
        return d;
    }

    TextView text(String value, int size, String color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(c(color));
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    void section(String title, String desc) {
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(16), dp(18), dp(16));
        panel.setBackground(bg("#182338", 22));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(14);
        root.addView(panel, p);
        TextView t = text(title, 23, "#F1F6FF");
        t.setTypeface(Typeface.DEFAULT_BOLD);
        panel.addView(t);
        panel.addView(text(desc, 13, "#AABBD3"));
    }

    void button(String s, Runnable r) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTextColor(c("#D9FDF3"));
        b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        b.setPadding(dp(16), dp(8), dp(16), dp(8));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(c("#426B79")), bg("#263B50", 14), null));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(8);
        b.setMinHeight(dp(52));
        panel.addView(b, p);
        b.setOnClickListener(v -> r.run());
    }

    void volume(String label, int stream) {
        panel.addView(text(label, 15, "#E4ECFA"));
        SeekBar s = new SeekBar(this);
        s.setProgressTintList(ColorStateList.valueOf(c("#72F2CE")));
        s.setThumbTintList(ColorStateList.valueOf(c("#72F2CE")));
        s.setMax(audio.getStreamMaxVolume(stream));
        s.setProgress(audio.getStreamVolume(stream));
        panel.addView(s);
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onStartTrackingTouch(SeekBar b) { }
            public void onStopTrackingTouch(SeekBar b) { }
            public void onProgressChanged(SeekBar b, int p, boolean user) {
                if (user) try { audio.setStreamVolume(stream, p, 0); } catch (Exception e) { status.setText(e.toString()); }
            }
        });
    }

    // ---------- Updates ----------

    static final String RELEASE = "https://github.com/rickbesada-netizen/MultiCall/releases/download/android-latest/";

    void checkUpdate() {
        status.setText("Checking for updates…");
        new Thread(() -> {
            try {
                java.net.URLConnection conn = new java.net.URL(RELEASE + "version.txt").openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                int latest;
                try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(conn.getInputStream()))) {
                    latest = Integer.parseInt(r.readLine().trim());
                }
                int current = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
                runOnUiThread(() -> {
                    if (latest > current) {
                        status.setText("Update available");
                        new AlertDialog.Builder(this).setTitle("MultiCall update available")
                                .setMessage("Install build " + latest + " over your current build " + current + "?")
                                .setPositiveButton("Download & install", (d, w) -> downloadUpdate())
                                .setNegativeButton("Later", null).show();
                    } else {
                        status.setText("You have the latest version");
                        toastDialog("You have the latest version (build " + current + ").");
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> toastDialog("Update check failed: " + e.getMessage()));
            }
        }).start();
    }

    void downloadUpdate() {
        if (!getPackageManager().canRequestPackageInstalls()) {
            startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            toastDialog("Allow installation from MultiCall, then check for updates again.");
            return;
        }
        ProgressDialog progress = ProgressDialog.show(this, "MultiCall", "Downloading update…", true, false);
        new Thread(() -> {
            try {
                java.io.File dir = new java.io.File(getCacheDir(), "updates");
                dir.mkdirs();
                java.io.File file = new java.io.File(dir, "MultiCall.apk");
                java.net.URLConnection conn = new java.net.URL(RELEASE + "MultiCall.apk").openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                try (InputStream in = conn.getInputStream(); java.io.FileOutputStream out = new java.io.FileOutputStream(file)) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                }
                android.content.pm.PackageInfo info = getPackageManager().getPackageArchiveInfo(file.getAbsolutePath(), 0);
                if (info == null || !getPackageName().equals(info.packageName)) throw new Exception("Downloaded file isn't a MultiCall APK");
                runOnUiThread(() -> {
                    progress.dismiss();
                    Intent i = new Intent(Intent.ACTION_VIEW);
                    i.setDataAndType(androidx.core.content.FileProvider.getUriForFile(this, "com.multicall.audio.updates", file),
                            "application/vnd.android.package-archive");
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(i);
                });
            } catch (Exception e) {
                runOnUiThread(() -> { progress.dismiss(); toastDialog("Download failed: " + e.getMessage()); });
            }
        }).start();
    }
}
