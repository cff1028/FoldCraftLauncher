package com.tungsten.fcl.vr;

import android.content.Context;

public final class QuestVrPreferences {
    private static final String TRANSITION_ANIMATION_KEY = "questVrTransitionAnimation";
    private static final String HIGH_GAME_THREAD_PRIORITY_KEY = "highGameThreadPriority";

    private QuestVrPreferences() {}

    public static boolean isTransitionAnimationEnabled(Context context) {
        return QuestVrFeatures.isEnabled(context) && context.getSharedPreferences("launcher", Context.MODE_PRIVATE)
                .getBoolean(TRANSITION_ANIMATION_KEY, true);
    }

    public static void setTransitionAnimationEnabled(Context context, boolean enabled) {
        context.getSharedPreferences("launcher", Context.MODE_PRIVATE).edit()
                .putBoolean(TRANSITION_ANIMATION_KEY, enabled).apply();
    }

    public static boolean isHighGameThreadPriorityEnabled(Context context) {
        return context.getSharedPreferences("launcher", Context.MODE_PRIVATE)
                .getBoolean(HIGH_GAME_THREAD_PRIORITY_KEY, true);
    }

    public static void setHighGameThreadPriorityEnabled(Context context, boolean enabled) {
        context.getSharedPreferences("launcher", Context.MODE_PRIVATE).edit()
                .putBoolean(HIGH_GAME_THREAD_PRIORITY_KEY, enabled).apply();
    }

}
