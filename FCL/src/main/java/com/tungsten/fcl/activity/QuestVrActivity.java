package com.tungsten.fcl.activity;

import android.os.Bundle;
import android.view.KeyEvent;
import android.view.WindowManager;
import androidx.annotation.Nullable;
import com.tungsten.fcl.vr.QuestVrSession;
import com.tungsten.fclauncher.keycodes.LwjglGlfwKeycode;
import com.tungsten.fcllibrary.component.FCLActivity;
import org.lwjgl.glfw.CallbackBridge;

public final class QuestVrActivity extends FCLActivity {
    private QuestVrSession session;

    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        session = QuestVrSession.get();
        if (session == null || !session.attachImmersive(this)) {
            finish();
            return;
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override protected void onResume() {
        super.onResume();
        if (session != null && !isFinishing()) session.immersiveResumed(this);
    }

    @Override protected void onPause() {
        if (session != null) session.immersivePaused(this);
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (session != null) session.immersiveDestroyed(this);
        super.onDestroy();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && session != null && !session.isExiting()) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                CallbackBridge.sendKeycode(LwjglGlfwKeycode.KEY_ESCAPE, (char) 0, 0, 0, true);
                CallbackBridge.sendKeycode(LwjglGlfwKeycode.KEY_ESCAPE, (char) 0, 0, 0, false);
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
}
