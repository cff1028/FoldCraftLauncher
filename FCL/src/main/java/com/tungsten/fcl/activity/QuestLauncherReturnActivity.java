package com.tungsten.fcl.activity;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Application;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;

import com.tungsten.fcl.vr.QuestGameExit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class QuestLauncherReturnActivity extends Activity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ResultReceiver ack = getIntent().getParcelableExtra(QuestGameExit.EXTRA_ACK);
        int taskId = getIntent().getIntExtra(QuestGameExit.EXTRA_TASK, -1);
        int exitCode = getIntent().getIntExtra(QuestGameExit.EXTRA_CODE, 0);
        String logPath = getIntent().getStringExtra(QuestGameExit.EXTRA_LOG);
        Application application = getApplication();
        Handler handler = new Handler(Looper.getMainLooper());
        AtomicBoolean acknowledged = new AtomicBoolean();
        Application.ActivityLifecycleCallbacks callback = new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(Activity activity) {
                if (!(activity instanceof MainActivity) || !acknowledged.compareAndSet(false, true)) return;
                application.unregisterActivityLifecycleCallbacks(this);
                handler.removeCallbacksAndMessages(null);
                activity.getWindow().getDecorView().post(() -> {
                    if (ack != null) ack.send(RESULT_OK, null);
                    if (exitCode != 0) {
                        Intent crash = new Intent(activity, JVMCrashActivity.class)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                                .putExtra("isGame", true).putExtra("exitCode", exitCode)
                                .putExtra("logPath", logPath)
                                .putExtra(QuestGameExit.EXTRA_TASK, activity.getTaskId());
                        activity.startActivity(crash);
                    }
                });
            }
            @Override public void onActivityCreated(Activity a, Bundle b) { }
            @Override public void onActivityStarted(Activity a) { }
            @Override public void onActivityPaused(Activity a) { }
            @Override public void onActivityStopped(Activity a) { }
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
            @Override public void onActivityDestroyed(Activity a) { }
        };
        application.registerActivityLifecycleCallbacks(callback);
        handler.postDelayed(() -> application.unregisterActivityLifecycleCallbacks(callback), 15000);
        handler.post(new Runnable() {
            private int remaining = 200;
            @Override public void run() {
                if (acknowledged.get() || remaining-- <= 0) return;
                try {
                    MainActivity main = MainActivity.getInstance();
                    if (!main.isFinishing() && !main.isDestroyed() && main.hasWindowFocus()) {
                        callback.onActivityResumed(main);
                        return;
                    }
                } catch (RuntimeException ignored) {
                }
                handler.postDelayed(this, 50);
            }
        });
        Intent launcher = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        boolean restored = false;
        ActivityManager manager = getSystemService(ActivityManager.class);
        if (manager != null && taskId != -1) {
            for (ActivityManager.AppTask task : manager.getAppTasks()) {
                if (task.getTaskInfo().taskId != taskId) continue;
                task.startActivity(this, launcher, null);
                restored = true;
                break;
            }
        }
        if (!restored) startActivity(new Intent(this, SplashActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        finish();
        overridePendingTransition(0, 0);
    }
}
