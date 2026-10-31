package com.tungsten.fcl.vr;

import android.content.Context;
import android.os.Bundle;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ScrollView;

public final class QuestVrLogScrollView extends ScrollView {
    public interface OnFollowTailChangedListener {
        void onFollowTailChanged(boolean followingTail);
    }

    private boolean followingTail = true;
    private boolean handlingUserScroll;
    private boolean userFling;
    private OnFollowTailChangedListener followTailChangedListener;
    private final ViewTreeObserver.OnPreDrawListener followAfterLayout = () -> {
        if (followingTail && isShown() && !isLayoutRequested()) {
            scrollTo(getScrollX(), bottom());
        }
        return true;
    };

    public QuestVrLogScrollView(Context context) {
        this(context, null);
    }

    public QuestVrLogScrollView(Context context, AttributeSet attrs) {
        this(context, attrs, android.R.attr.scrollViewStyle);
    }

    public QuestVrLogScrollView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setSmoothScrollingEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
    }

    public boolean isFollowingTail() {
        return followingTail;
    }

    public void setOnFollowTailChangedListener(OnFollowTailChangedListener listener) {
        followTailChangedListener = listener;
    }

    public void resumeFollowing() {
        userFling = false;
        super.fling(0);
        setFollowingTail(true);
        invalidate();
    }

    private void setFollowingTail(boolean following) {
        if (followingTail == following) return;
        followingTail = following;
        if (following) userFling = false;
        if (followTailChangedListener != null) {
            boolean previous = handlingUserScroll;
            handlingUserScroll = false;
            try {
                followTailChangedListener.onFollowTailChanged(following);
            } finally {
                handlingUserScroll = previous;
            }
        }
    }

    private int bottom() {
        if (getChildCount() == 0) return 0;
        View content = getChildAt(0);
        return Math.max(0, content.getBottom() + getPaddingBottom() - getHeight());
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnPreDrawListener(followAfterLayout);
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        ViewTreeObserver observer = getViewTreeObserver();
        observer.removeOnPreDrawListener(followAfterLayout);
        observer.addOnPreDrawListener(followAfterLayout);
    }

    @Override protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnPreDrawListener(followAfterLayout);
        super.onDetachedFromWindow();
    }

    @Override protected void onScrollChanged(int x, int y, int oldX, int oldY) {
        super.onScrollChanged(x, y, oldX, oldY);
        if (!handlingUserScroll) return;
        if (y < oldY) setFollowingTail(false);
        else if (y > oldY && y >= bottom()) setFollowingTail(true);
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) userFling = false;
        return super.dispatchTouchEvent(event);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        boolean previous = handlingUserScroll;
        handlingUserScroll = event.getActionMasked() != MotionEvent.ACTION_DOWN;
        try {
            return super.onTouchEvent(event);
        } finally {
            handlingUserScroll = previous;
        }
    }

    @Override public boolean onGenericMotionEvent(MotionEvent event) {
        boolean previous = handlingUserScroll;
        handlingUserScroll = event.getActionMasked() == MotionEvent.ACTION_SCROLL;
        try {
            return super.onGenericMotionEvent(event);
        } finally {
            handlingUserScroll = previous;
        }
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        boolean previous = handlingUserScroll;
        int key = event.getKeyCode();
        handlingUserScroll = event.getAction() == KeyEvent.ACTION_DOWN
                && (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN
                || key == KeyEvent.KEYCODE_PAGE_UP || key == KeyEvent.KEYCODE_PAGE_DOWN
                || key == KeyEvent.KEYCODE_MOVE_HOME || key == KeyEvent.KEYCODE_MOVE_END
                || key == KeyEvent.KEYCODE_SPACE);
        try {
            return super.dispatchKeyEvent(event);
        } finally {
            handlingUserScroll = previous;
        }
    }

    @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
        boolean scroll = action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                || action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                || action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.getId()
                || action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.getId();
        if (scroll) userFling = true;
        boolean previous = handlingUserScroll;
        handlingUserScroll = scroll;
        try {
            boolean handled = super.performAccessibilityAction(action, arguments);
            if (scroll && !handled) userFling = false;
            return handled;
        } finally {
            handlingUserScroll = previous;
        }
    }

    @Override public void fling(int velocityY) {
        userFling = handlingUserScroll && (!followingTail || velocityY < 0);
        super.fling(velocityY);
    }

    @Override public void computeScroll() {
        boolean previous = handlingUserScroll;
        handlingUserScroll = userFling;
        try {
            super.computeScroll();
        } finally {
            handlingUserScroll = previous;
        }
    }
}
