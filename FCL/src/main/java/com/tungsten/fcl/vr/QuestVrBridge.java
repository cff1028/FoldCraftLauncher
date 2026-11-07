package com.tungsten.fcl.vr;

import android.app.Activity;
import android.os.Build;
import android.system.Os;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.Keep;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public final class QuestVrBridge {
    private static final String TAG = "FCL-QuestVR";
    private static String activeRenderer;

    static {
        System.loadLibrary("vloader");
    }

    private QuestVrBridge() {}

    public static synchronized void initialize(Activity activity, String rendererId) {
        QuestVrFeatures.requireEnabled(activity);
        if (!"quest_mobileglues".equals(rendererId)) {
            throw new IllegalArgumentException("Unsupported Quest VR renderer: " + rendererId);
        }
        if (activeRenderer != null && !activeRenderer.equals(rendererId)) {
            throw new IllegalStateException("Changing a VR renderer requires a new game process");
        }
        try {
            Os.setenv("FCL_VR", "1", true);
            Os.setenv("FCL_VR_RENDERER", rendererId, true);
            Os.setenv("POJAV_RENDERER", rendererId, true);
            Os.setenv("LIBGL_ES", "3", true);
            Os.setenv("FCL_VR_TRANSITION_ANIMATION",
                    QuestVrPreferences.isTransitionAnimationEnabled(activity) ? "1" : "0", true);
            Os.setenv("VR_MODEL", Build.MODEL, true);
            Os.unsetenv("SFPEW_LAYER");
            File directory = new File(activity.getFilesDir(), "quest-vr/mobileglues");
            if (!directory.isDirectory() && !directory.mkdirs()) {
                throw new IllegalStateException("Cannot create the VR renderer configuration directory");
            }
            String config = "{\"enableANGLE\":2,\"enableNoError\":1," +
                    "\"fsr1Setting\":0,\"enableExtDirectStateAccess\":1," +
                    "\"enableExtTimerQuery\":1,\"enableExtComputeShader\":0," +
                    "\"customGLVersion\":0,\"maxGlslCacheSize\":32}";
            Files.write(new File(directory, "config.json").toPath(), config.getBytes(StandardCharsets.UTF_8));
            Os.setenv("MG_DIR_PATH", directory.getAbsolutePath(), true);
            Os.setenv("POJAVEXEC_EGL", "libmobileglues.so", true);
            nativeInitialize(activity);
            activeRenderer = rendererId;
            Log.i(TAG, "Android bridge ready: " + rendererId + " on " + Build.MODEL);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot initialize Quest VR: " + e.getMessage(), e);
        }
    }

    public static void setResumed(boolean resumed) {
        Log.i(TAG, resumed ? "VR Activity resumed" : "VR Activity paused");
    }

    public static void release(Activity activity) {
        Log.i(TAG, "Activity destroyed; reference retained until OpenXR releases its lease");
    }

    public static synchronized void bindImmersiveActivity(Activity activity) {
        if (activeRenderer == null) throw new IllegalStateException("VR startup has not been prepared");
        nativeInitialize(activity);
        Log.i(TAG, "Immersive Activity bound to the running game");
    }

    @Keep
    public static boolean awaitImmersiveActivity() {
        QuestVrSession session = QuestVrSession.get();
        return session != null && session.awaitImmersiveActivity();
    }

    @Keep
    public static void onGameFrame() {
        QuestVrSession session = QuestVrSession.get();
        if (session != null) session.onGameFrame();
    }

    @Keep
    public static void onFirstFlatFrame() {
        QuestVrSession session = QuestVrSession.get();
        if (session != null) session.onFirstFlatFrame();
    }

    @Keep
    public static void onOpenXrStopped() {
        QuestVrSession session = QuestVrSession.get();
        if (session != null) session.onOpenXrStopped();
    }

    public static native void nativeSetFlatSurface(Surface surface);
    public static native void nativeSetImmersive(boolean immersive);

    private static native void nativeInitialize(Activity activity);
}
