package com.tungsten.fcl.vr;

import android.app.Activity;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;

import com.tungsten.fcl.activity.QuestVrActivity;

final class QuestVrPanelFade {
    private static final String TAG = "com.questfcl.launcher.vr.transition";
    static final long FADE_OUT_MS = 180;
    static final long FADE_IN_MS = 220;

    private QuestVrPanelFade() { }

    static void fadeOut(Activity activity, boolean enabled, Runnable afterFade) {
        ViewGroup content = content(activity);
        if (!enabled || content == null || activity instanceof QuestVrActivity) {
            clear(content);
            afterFade.run();
            return;
        }
        clear(content);
        Veil veil = veil(content);
        veil.fade(0f, 1f, FADE_OUT_MS, () -> {
            if (veil.getParent() == content && !activity.isFinishing()
                    && !activity.isDestroyed()) afterFade.run();
        });
    }

    static void fadeIn(Activity activity, boolean enabled) {
        ViewGroup content = content(activity);
        if (content == null) return;
        clear(content);
        if (!enabled) return;
        Veil veil = veil(content);
        veil.fade(1f, 0f, FADE_IN_MS, () -> content.removeView(veil));
    }

    private static ViewGroup content(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return null;
        View content = activity.findViewById(android.R.id.content);
        return content instanceof ViewGroup ? (ViewGroup) content : null;
    }

    private static Veil veil(ViewGroup content) {
        Veil veil = new Veil(content.getContext());
        veil.setTag(TAG);
        veil.setBackgroundColor(Color.BLACK);
        veil.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        content.addView(veil, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return veil;
    }

    private static void clear(ViewGroup content) {
        if (content == null) return;
        View existing = content.findViewWithTag(TAG);
        if (existing != null) {
            if (existing instanceof Veil) ((Veil) existing).cancelFade();
            content.removeView(existing);
        }
    }

    private static final class Veil extends View {
        private ValueAnimator animator;
        private boolean cancelled;

        Veil(Context context) {
            super(context);
        }

        void fade(float from, float to, long duration, Runnable finished) {
            setAlpha(from);
            animator = ValueAnimator.ofFloat(from, to);
            animator.setDuration(duration);
            animator.setInterpolator(new AccelerateDecelerateInterpolator());
            animator.addUpdateListener(value -> setAlpha((Float) value.getAnimatedValue()));
            animator.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationCancel(Animator animation) {
                    cancelled = true;
                }
                @Override public void onAnimationEnd(Animator animation) {
                    if (!cancelled) finished.run();
                }
            });
            animator.start();
        }

        void cancelFade() {
            cancelled = true;
            if (animator != null) animator.cancel();
        }

        @Override protected void onDetachedFromWindow() {
            cancelFade();
            super.onDetachedFromWindow();
        }
    }
}
