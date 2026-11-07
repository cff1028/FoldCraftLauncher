package com.tungsten.fcl.vr;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Surface;

import com.mio.service.GameService;
import com.tungsten.fcl.R;
import com.tungsten.fcl.activity.JVMActivity;
import com.tungsten.fcl.activity.QuestFlatActivity;
import com.tungsten.fcl.activity.QuestVrActivity;
import com.tungsten.fcl.activity.QuestVrLoadingActivity;
import com.tungsten.fcl.control.GameMenu;
import com.tungsten.fcl.control.GameLaunchMetadata;
import com.tungsten.fclauncher.bridge.FCLBridge;
import com.tungsten.fclauncher.bridge.FCLBridgeCallback;
import com.tungsten.fclauncher.keycodes.LwjglGlfwKeycode;
import com.tungsten.fclcore.util.Logging;
import com.tungsten.fclcore.util.io.FileUtils;
import org.lwjgl.glfw.CallbackBridge;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

public final class QuestVrSession implements FCLBridgeCallback {
    private static volatile QuestVrSession current;
    private final FCLBridge bridge;
    private final Context application;
    private final boolean transitionAnimation;
    private final GameLaunchMetadata launchMetadata;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final QuestVrLaunchGate gate = new QuestVrLaunchGate();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean exiting = new AtomicBoolean();
    private final StringBuilder recentLog = new StringBuilder();
    private final long startTime = SystemClock.elapsedRealtime();
    private WeakReference<QuestVrLoadingActivity> loading = new WeakReference<>(null);
    private WeakReference<QuestVrActivity> immersive = new WeakReference<>(null);
    private WeakReference<JVMActivity> flat = new WeakReference<>(null);
    private volatile WeakReference<GameMenu> flatMenu = new WeakReference<>(null);
    private boolean loadingResumed, flatResumed, immersiveResumed;
    private volatile boolean hasImmersiveActivity;
    private boolean handoffLaunched, flatLaunchPending, firstLog = true;
    private volatile int cursorMode = FCLBridge.CursorEnabled;
    private volatile int phase = R.string.qv_start_loading;

    private QuestVrSession(QuestVrLoadingActivity activity, FCLBridge bridge) {
        this.bridge = bridge;
        application = activity.getApplicationContext();
        transitionAnimation = QuestVrPreferences.isTransitionAnimationEnabled(application);
        launchMetadata = GameLaunchMetadata.capture(activity.getIntent(), bridge.getController());
        loading = new WeakReference<>(activity);
    }

    public static QuestVrSession get() {
        return current;
    }

    public static QuestVrSession prepare(QuestVrLoadingActivity activity, FCLBridge bridge) {
        QuestVrFeatures.requireEnabled(activity);
        if (current == null) {
            current = new QuestVrSession(activity, bridge);
            QuestVrBridge.initialize(activity, bridge.getVrRendererId());
        } else if (current.bridge != bridge || current.exiting.get()) {
            throw new IllegalStateException("A different VR game session is already running");
        }
        current.loading = new WeakReference<>(activity);
        return current;
    }

    public String instanceName() {
        return new File(bridge.getGameDir()).getName();
    }
    public long elapsedSeconds() {
        return (SystemClock.elapsedRealtime() - startTime) / 1000;
    }
    public int phase() {
        return phase;
    }
    public boolean isExiting() {
        return exiting.get();
    }
    public boolean isImmersiveReady() {
        return gate.state() == QuestVrLaunchGate.State.READY;
    }
    public boolean wantsImmersive() {
        return gate.wantsImmersive();
    }
    public boolean isFlat() {
        return gate.state() == QuestVrLaunchGate.State.FLAT;
    }

    public Class<? extends Activity> notificationActivity() {
        if (gate.wantsImmersive()) return QuestVrActivity.class;
        return isFlat() ? QuestFlatActivity.class : QuestVrLoadingActivity.class;
    }

    public void loadingResumed(boolean resumed) {
        loadingResumed = resumed;
        if (!resumed && gate.state() == QuestVrLaunchGate.State.SWITCHING) handoffLaunched = false;
        if (!resumed || exiting.get()) return;
        if (started.compareAndSet(false, true)) {
            GameService.start(loading.get());
            CallbackBridge.windowWidth = FCLBridge.DEFAULT_WIDTH;
            CallbackBridge.windowHeight = FCLBridge.DEFAULT_HEIGHT;
            windowFocus(true);
            bridge.setSurfaceDestroyed(false);
            bridge.execute(null, this);
            bridge.pushEventWindow(FCLBridge.DEFAULT_WIDTH, FCLBridge.DEFAULT_HEIGHT);
        }
        if (isImmersiveReady()) {
            QuestVrTransitions.immersive(loading.get(), transitionAnimation);
            finishLoading();
            return;
        }
        launchImmersiveIfRequested();
        if (isFlat()) showFlat(false);
    }

    public boolean awaitImmersiveActivity() {
        if (Looper.myLooper() == Looper.getMainLooper())
            throw new IllegalStateException("OpenXR cannot wait on the Android UI thread");
        try {
            boolean ready = gate.awaitReady(() -> {
                QuestVrBridge.nativeSetImmersive(true);
                phase = R.string.qv_start_switching;
                onLog("[FCL-QuestVR] Entering VR; waiting for the immersive Activity to resume.\n");
                main.post(() -> {
                    handoffLaunched = false;
                    flatLaunchPending = false;
                    launchImmersiveIfRequested();
                });
            }, 30000);
            if (!ready && !exiting.get() && gate.state() == QuestVrLaunchGate.State.FAILED)
                fail("Timed out entering the immersive VR Activity", null);
            return ready && !exiting.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            fail("Interrupted while entering immersive VR", interrupted);
            return false;
        }
    }

    private void launchImmersiveIfRequested() {
        if (exiting.get() || handoffLaunched || gate.state() != QuestVrLaunchGate.State.SWITCHING) return;
        Activity activity = immersiveResumed ? immersive.get() : flatResumed ? flat.get()
                : loadingResumed ? loading.get() : null;
        if (!usable(activity)) return;
        handoffLaunched = true;
        QuestVrTransitions.fadeOutPanel(activity, transitionAnimation, () -> {
            if (exiting.get() || !gate.wantsImmersive()) return;
            boolean sourceResumed = activity == immersive.get() ? immersiveResumed
                    : activity == flat.get() ? flatResumed : activity == loading.get() && loadingResumed;
            if (!sourceResumed) {
                handoffLaunched = false;
                return;
            }
            try {
                QuestVrTransitions.immersive(activity, transitionAnimation);
            } catch (RuntimeException failure) {
                fail("Cannot open the immersive VR Activity", failure);
            }
        });
    }

    public boolean attachImmersive(QuestVrActivity activity) {
        if (exiting.get() || !gate.wantsImmersive()) return false;
        try {
            restoreLaunchMetadata(activity);
            QuestVrBridge.bindImmersiveActivity(activity);
            immersive = new WeakReference<>(activity);
            hasImmersiveActivity = true;
            return true;
        } catch (RuntimeException | LinkageError failure) {
            fail("Cannot bind the immersive VR Activity", failure);
            return false;
        }
    }

    public void immersiveResumed(QuestVrActivity activity) {
        if (exiting.get() || immersive.get() != activity) return;
        immersiveResumed = true;
        if (!gate.wantsImmersive()) {
            showFlat(true);
            return;
        }
        QuestVrBridge.setResumed(true);
        windowFocus(true);
        if (gate.ready()) {
            GameService.start(activity);
            finishLoading();
        }
    }

    public void immersivePaused(QuestVrActivity activity) {
        if (immersive.get() != activity) return;
        immersiveResumed = false;
        gate.paused();
        if (!exiting.get() && gate.wantsImmersive()) {
            QuestVrBridge.setResumed(false);
            windowFocus(false);
        }
    }

    public void onGameFrame() {
        if (exiting.get() || !gate.firstGameFrame()) return;
        onLog("[FCL-QuestVR] Game rendering started without an OpenXR request; opening the flat game panel.\n");
        main.post(() -> showFlat(false));
    }

    public void onOpenXrStopped() {
        if (exiting.get()) return;
        boolean changed = gate.flat();
        QuestVrBridge.nativeSetImmersive(false);
        if (changed) onLog("[FCL-QuestVR] OpenXR stopped; returning the running game to Quest Home.\n");
        main.post(() -> {
            if (!isFlat()) return;
            handoffLaunched = false;
            showFlat(hasImmersiveActivity);
        });
    }

    private void showFlat(boolean fromVr) {
        if (exiting.get() || !isFlat() || flatLaunchPending
                || (flatResumed && (!fromVr || !immersiveResumed))) return;
        Activity activity = fromVr ? immersive.get() : loading.get();
        if (!usable(activity)) activity = flat.get();
        Context context = usable(activity) ? activity : application;
        flatLaunchPending = true;
        try {
            QuestVrTransitions.flat(context, fromVr, transitionAnimation);
        } catch (RuntimeException failure) {
            flatLaunchPending = false;
            fail("Cannot return the game to a Quest Home panel", failure);
        }
    }

    public boolean attachFlat(JVMActivity activity, GameMenu menu) {
        if (exiting.get()) return false;
        flat = new WeakReference<>(activity);
        flatMenu = new WeakReference<>(menu);
        menu.appendSessionLog(logPreview());
        menu.onCursorModeChange(cursorMode);
        return true;
    }

    public void restoreLaunchMetadata(Activity activity) {
        Intent intent = activity.getIntent();
        if (intent == null) {
            intent = new Intent();
            activity.setIntent(intent);
        }
        launchMetadata.applyTo(intent);
    }

    public void flatResumed(JVMActivity activity) {
        if (exiting.get() || flat.get() != activity) return;
        flatResumed = true;
        flatLaunchPending = false;
        if (isFlat()) {
            QuestVrTransitions.fadeInPanel(activity, transitionAnimation);
            windowFocus(true);
            GameService.start(activity);
            finishLoading();
        } else if (isImmersiveReady()) {
            QuestVrTransitions.immersive(activity, transitionAnimation);
        } else launchImmersiveIfRequested();
    }

    public void flatPaused(JVMActivity activity) {
        if (flat.get() != activity) return;
        flatResumed = false;
        if (gate.state() == QuestVrLaunchGate.State.SWITCHING) handoffLaunched = false;
        if (isFlat()) windowFocus(false);
    }

    public void flatSurface(JVMActivity activity, Surface surface) {
        if (flat.get() == activity && !exiting.get()) QuestVrBridge.nativeSetFlatSurface(surface);
    }

    public void onFirstFlatFrame() {
        main.post(() -> {
            GameMenu menu = flatMenu.get();
            if (!exiting.get() && menu != null) menu.onGraphicOutput();
        });
    }

    public void flatDestroyed(JVMActivity activity) {
        if (flat.get() != activity) return;
        QuestVrBridge.nativeSetFlatSurface(null);
        flat.clear();
        flatMenu.clear();
        flatResumed = false;
        flatLaunchPending = false;
    }

    private void finishLoading() {
        QuestVrLoadingActivity panel = loading.get();
        if (usable(panel)) {
            panel.finish();
            if (!transitionAnimation) panel.overridePendingTransition(0, 0);
        }
    }

    public void loadingDestroyed(QuestVrLoadingActivity activity) {
        if (loading.get() == activity) {
            loading.clear();
            if (gate.state() == QuestVrLaunchGate.State.LOADING && !exiting.get())
                fail("VR loading window was closed", null);
        }
    }

    public void immersiveDestroyed(QuestVrActivity activity) {
        QuestVrBridge.release(activity);
        if (immersive.get() != activity) return;
        immersive.clear();
        immersiveResumed = false;
        if (!exiting.get() && gate.wantsImmersive()) {
            fail("The active immersive Activity was destroyed before OpenXR stopped", null);
        } else if (!exiting.get()) {
            onLog("[FCL-QuestVR] Inactive immersive Activity released; flat game continues.\n");
        }
    }

    private static boolean usable(Activity activity) {
        return activity != null && !activity.isFinishing() && !activity.isDestroyed();
    }

    private static void windowFocus(boolean focused) {
        CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_FOCUSED, focused ? 1 : 0);
        CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_HOVERED, focused ? 1 : 0);
        if (focused) CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_VISIBLE, 1);
    }

    public void cancel() {
        onLog("[FCL-QuestVR] Session stopped by the user.\n");
        onExit(0);
    }

    private void fail(String message, Throwable failure) {
        onLog("[FCL-QuestVR] " + message + (failure == null ? "" : ": " + failure) + "\n");
        onExit(1);
    }

    @Override public void onCursorModeChange(int mode) {
        cursorMode = mode;
        main.post(() -> {
            GameMenu menu = flatMenu.get();
            if (menu != null) menu.onCursorModeChange(mode);
        });
    }

    @Override public synchronized void onLog(String line) {
        if (gate.wantsImmersive() && (line.contains("Vivecraft: OpenXR init failed")
                || line.contains("Vivecraft: Failed to initialize VR"))) onOpenXrStopped();
        recentLog.append(line);
        if (recentLog.length() > 12000) recentLog.delete(0, recentLog.length() - 12000);
        if (gate.state() == QuestVrLaunchGate.State.LOADING) {
            if (line.contains("Reloading ResourceManager") || line.contains("resource packs"))
                phase = R.string.qv_start_resources;
            else if (line.contains("Loading Minecraft") || line.contains("Fabric Loader"))
                phase = R.string.qv_start_mods;
        }
        if (flatMenu.get() != null)
            main.post(() -> {
                GameMenu menu = flatMenu.get();
                if (menu != null) menu.appendSessionLog(line);
            });
        try {
            File file = new File(bridge.getLogPath());
            if (firstLog) {
                FileUtils.writeText(file, line);
                firstLog = false;
            } else FileUtils.writeTextWithAppendMode(file, line);
        } catch (IOException failure) {
            Logging.LOG.log(Level.WARNING, "Cannot write Quest VR game log", failure);
        }
    }

    public synchronized String logPreview() {
        return recentLog.toString().replaceAll("(?i)(--(?:accessToken|clientId|xuid|uuid|username)[ =]+)\\S+", "$1[hidden]");
    }

    @Override public void onExit(int code) {
        if (!exiting.compareAndSet(false, true)) return;
        boolean fromVr = gate.wantsImmersive();
        gate.fail();
        Runnable shutdown = () -> {
            Activity activity = flatResumed ? flat.get() : immersive.get();
            if (!usable(activity)) activity = loading.get();
            if (!usable(activity)) activity = flat.get();
            Context context = usable(activity) ? activity : application;
            GameService.stop(context);
            QuestGameExit.returnToLauncher(context, fromVr || immersiveResumed, transitionAnimation, code, bridge.getLogPath(), () -> {
                QuestVrLoadingActivity loadingActivity = loading.get();
                JVMActivity flatActivity = flat.get();
                QuestVrActivity vrActivity = immersive.get();
                if (usable(loadingActivity)) loadingActivity.finish();
                if (usable(flatActivity)) flatActivity.finish();
                if (usable(vrActivity)) vrActivity.finishAndRemoveTask();
            });
        };
        if (Looper.myLooper() == Looper.getMainLooper()) shutdown.run();
        else main.post(shutdown);
    }
}
