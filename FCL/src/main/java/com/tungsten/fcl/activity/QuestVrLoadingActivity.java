package com.tungsten.fcl.activity;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.mio.util.ImageUtil;
import com.tungsten.fcl.R;
import com.tungsten.fcl.vr.QuestVrLogScrollView;
import com.tungsten.fcl.vr.QuestVrSession;
import com.tungsten.fcl.vr.QuestGameBootstrap;
import com.tungsten.fclauncher.bridge.FCLBridge;
import com.tungsten.fcllibrary.component.FCLActivity;
import com.tungsten.fcllibrary.component.dialog.FCLAlertDialog;
import com.tungsten.fcllibrary.component.theme.ThemeEngine;

public final class QuestVrLoadingActivity extends FCLActivity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private QuestVrSession session;
    private TextView phase;
    private TextView elapsed;
    private TextView log;
    private TextView logButton;
    private QuestVrLogScrollView logScroll;
    private View latestLogButton;
    private boolean confirmingStop;
    private String lastLog = "";
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (session == null || session.isExiting() || isDestroyed()) return;
            phase.setText(session.phase());
            elapsed.setText(getString(R.string.qv_start_elapsed, session.elapsedSeconds()));
            refreshStartupLog();
            main.postDelayed(this, 350);
        }
    };

    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FCLBridge bridge;
        try {
            bridge = QuestGameBootstrap.restore(this, getIntent());
        } catch (java.io.IOException | RuntimeException failure) {
            com.tungsten.fclcore.util.Logging.LOG.log(java.util.logging.Level.WARNING,
                    "Cannot restore the VR game launch", failure);
            Toast.makeText(this, getString(R.string.qv_start_restore_failed), Toast.LENGTH_LONG).show();
            finish();
            if (QuestGameBootstrap.isGameProcess()) android.os.Process.killProcess(android.os.Process.myPid());
            return;
        }
        if (bridge == null || !bridge.isVrEnabled()) {
            finish();
            if (QuestGameBootstrap.isGameProcess()) android.os.Process.killProcess(android.os.Process.myPid());
            return;
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_quest_vr_loading);
        View background = findViewById(R.id.qv_start_background);
        ImageUtil.loadInto(background, ThemeEngine.getInstance().getTheme().getBackground(this));
        phase = findViewById(R.id.qv_start_phase);
        elapsed = findViewById(R.id.qv_start_elapsed);
        log = findViewById(R.id.qv_start_log);
        logButton = findViewById(R.id.qv_start_show_log);
        logScroll = findViewById(R.id.qv_start_log_scroll);
        latestLogButton = findViewById(R.id.qv_start_latest_log);
        logScroll.setOnFollowTailChangedListener(following -> {
            latestLogButton.setVisibility(following ? View.GONE : View.VISIBLE);
            if (following) refreshStartupLog();
        });
        latestLogButton.setOnClickListener(v -> {
            logScroll.resumeFollowing();
            refreshStartupLog();
        });
        logButton.setOnClickListener(v -> {
            boolean show = logScroll.getVisibility() != View.VISIBLE;
            logScroll.setVisibility(show ? View.VISIBLE : View.GONE);
            logButton.setText(show ? R.string.qv_start_hide_log : R.string.qv_start_show_log);
            latestLogButton.setVisibility(View.GONE);
            if (show) {
                logScroll.resumeFollowing();
                refreshStartupLog();
            }
        });
        findViewById(R.id.qv_start_cancel).setOnClickListener(v -> confirmStop());
        try {
            session = QuestVrSession.prepare(this, bridge);
            ((TextView) findViewById(R.id.qv_start_instance)).setText(session.instanceName());
        } catch (RuntimeException | LinkageError failure) {
            QuestVrSession failed = QuestVrSession.get();
            if (failed != null) {
                failed.onLog("[FCL-QuestVR] Cannot prepare VR startup: " + failure + "\n");
                failed.onExit(1);
            }
            finish();
        }
    }

    private void refreshStartupLog() {
        if (session == null || logScroll.getVisibility() != View.VISIBLE
                || !logScroll.isFollowingTail()) return;
        String text = session.logPreview();
        if (!text.equals(lastLog)) {
            log.setText(text);
            lastLog = text;
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (session != null && !isFinishing()) {
            session.loadingResumed(true);
            main.post(refresh);
        }
    }

    @Override protected void onPause() {
        main.removeCallbacks(refresh);
        if (session != null) session.loadingResumed(false);
        super.onPause();
    }

    @Override protected void onDestroy() {
        main.removeCallbacks(refresh);
        View background = findViewById(R.id.qv_start_background);
        if (background != null) Glide.with(getApplicationContext()).clear(background);
        if (session != null) session.loadingDestroyed(this);
        super.onDestroy();
    }

    private void confirmStop() {
        if (session == null || session.isExiting() || confirmingStop) return;
        confirmingStop = true;
        FCLAlertDialog dialog = new FCLAlertDialog.Builder(this)
                .setTitle(getString(R.string.qv_start_cancel))
                .setMessage(getString(R.string.qv_start_cancel_message))
                .setPositiveButton(getString(R.string.qv_start_cancel), () -> session.cancel())
                .setNegativeButton(getString(R.string.button_cancel), null).create();
        dialog.setOnDismissListener(ignored -> confirmingStop = false);
        dialog.show();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP) confirmStop();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
}
