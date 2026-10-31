package com.tungsten.fcl.vr;

import android.content.Context;

public final class QuestVrPreferences {
    private static final String TRANSITION_ANIMATION_KEY = "questVrTransitionAnimation";

    private QuestVrPreferences() {}

    public static boolean isTransitionAnimationEnabled(Context context) {
        return context.getSharedPreferences("launcher", Context.MODE_PRIVATE)
                .getBoolean(TRANSITION_ANIMATION_KEY, true);
    }

    public static void setTransitionAnimationEnabled(Context context, boolean enabled) {
        context.getSharedPreferences("launcher", Context.MODE_PRIVATE).edit()
                .putBoolean(TRANSITION_ANIMATION_KEY, enabled).apply();
    }
}
