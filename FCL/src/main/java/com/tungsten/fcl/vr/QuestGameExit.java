package com.tungsten.fcl.vr;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ResultReceiver;
import android.os.Bundle;
import android.os.Process;

import com.tungsten.fcl.activity.QuestLauncherReturnActivity;
import com.tungsten.fclcore.util.Logging;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

public final class QuestGameExit {
    public static final String EXTRA_ACK = "com.tungsten.fcl.quest.RETURN_ACK";
    public static final String EXTRA_TASK = "com.tungsten.fcl.quest.LAUNCHER_TASK";
    public static final String EXTRA_CODE = "com.tungsten.fcl.quest.EXIT_CODE";
    public static final String EXTRA_LOG = "com.tungsten.fcl.quest.EXIT_LOG";
    private static final AtomicBoolean RETURNING = new AtomicBoolean();

    private QuestGameExit() { }

    public static void returnToLauncher(Context context, boolean fromVr, boolean animate,
                                        int exitCode, String logPath, Runnable closeGameActivities) {
        if (!RETURNING.compareAndSet(false, true)) return;
        QuestGameBootstrap.markReturning(context);
        Handler main = new Handler(Looper.getMainLooper());
        HandlerThread handoff = new HandlerThread("QuestGameExit");
        handoff.start();
        Handler background = new Handler(handoff.getLooper());
        Runnable killGame = () -> {
            if (QuestGameBootstrap.isGameProcess()) Process.killProcess(Process.myPid());
            else handoff.quitSafely();
        };
        ResultReceiver ack = new ResultReceiver(background) {
            @Override protected void onReceiveResult(int resultCode, Bundle data) {
                if (resultCode != Activity.RESULT_OK) return;
                background.removeCallbacksAndMessages(null);
                main.post(() -> {
                    try {
                        closeGameActivities.run();
                    } finally {
                        killGame.run();
                    }
                });
                background.postDelayed(killGame, 500);
            }
        };
        Intent destination = new Intent(context, QuestLauncherReturnActivity.class)
                .setAction(Intent.ACTION_MAIN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra(EXTRA_ACK, ack)
                .putExtra(EXTRA_TASK, QuestGameBootstrap.launcherTaskId())
                .putExtra(EXTRA_CODE, exitCode)
                .putExtra(EXTRA_LOG, logPath);
        Runnable open = () -> {
            try {
                Intent navigation = destination;
                if (fromVr) {
                    PendingIntent pending = PendingIntent.getActivity(context, 1507, destination,
                            PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                    navigation = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            .putExtra("extra_launch_in_home_pending_intent", pending);
                }
                if (!animate) navigation.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
                context.startActivity(navigation, animate ? null
                        : ActivityOptions.makeCustomAnimation(context, 0, 0).toBundle());
            } catch (RuntimeException failure) {
                Logging.LOG.log(Level.WARNING, "Cannot navigate from the game to the launcher", failure);
                try {
                    context.startActivity(destination);
                } catch (RuntimeException fallback) {
                    Logging.LOG.log(Level.WARNING, "Cannot open launcher return activity", fallback);
                }
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) open.run();
        else main.post(open);
        background.postDelayed(killGame, 15000);
    }
}
