package com.tungsten.fcl.vr;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import com.tungsten.fcl.activity.QuestFlatActivity;
import com.tungsten.fcl.activity.QuestVrActivity;
import com.tungsten.fcl.control.GameLaunchMetadata;

public final class QuestVrTransitions {
    private QuestVrTransitions() { }

    private static Intent destination(Context from, Class<? extends Activity> target, boolean animate,
                                      boolean newTask) {
        Intent intent = new Intent(from, target).setAction(Intent.ACTION_MAIN)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (newTask || !(from instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (target == QuestFlatActivity.class) intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (from instanceof Activity) GameLaunchMetadata.copy(((Activity) from).getIntent(), intent);
        if (!animate) intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        return intent;
    }

    private static void start(Context from, Intent intent, boolean animate) {
        Bundle options = animate ? null : ActivityOptions.makeCustomAnimation(from, 0, 0).toBundle();
        from.startActivity(intent, options);
        if (!animate && from instanceof Activity) ((Activity) from).overridePendingTransition(0, 0);
    }

    public static void immersive(Activity from, boolean animate) {
        start(from, destination(from, QuestVrActivity.class, animate, true), animate);
    }

    public static void flat(Context from, boolean returningFromVr, boolean animate) {
        Intent panel = destination(from, QuestFlatActivity.class, animate, returningFromVr);
        if (!returningFromVr) {
            start(from, panel, animate);
            return;
        }
        PendingIntent pending = PendingIntent.getActivity(from, 1505, panel,
                PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("extra_launch_in_home_pending_intent", pending);
        if (!animate) home.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        start(from, home, animate);
    }

    public static void fadeOutPanel(Activity activity, boolean enabled, Runnable afterFade) {
        QuestVrPanelFade.fadeOut(activity, enabled, afterFade);
    }

    public static void fadeInPanel(Activity activity, boolean enabled) {
        QuestVrPanelFade.fadeIn(activity, enabled);
    }
}
