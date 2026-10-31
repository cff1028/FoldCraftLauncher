package com.tungsten.fcl.vr;

import android.app.Activity;
import android.app.Application;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Process;
import android.os.Handler;
import android.os.Looper;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.mio.data.Renderer;
import com.mio.manager.RendererManager;
import com.mio.plugin.DriverPlugin;
import com.tungsten.fcl.activity.JVMActivity;
import com.tungsten.fcl.activity.QuestVrLoadingActivity;
import com.tungsten.fcl.control.MenuType;
import com.tungsten.fclauncher.FCLConfig;
import com.tungsten.fclauncher.FCLauncher;
import com.tungsten.fclauncher.bridge.FCLBridge;
import com.tungsten.fclauncher.utils.FCLPath;

import org.lwjgl.glfw.CallbackBridge;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.List;
import java.util.function.Consumer;

public final class QuestGameBootstrap {
    private static final String EXTRA_TOKEN = "com.tungsten.fcl.quest.LAUNCH_TOKEN";
    private static final String DIRECTORY = "quest-game-launch";
    private static final Gson GSON = new Gson();
    private static int launcherTaskId = -1;
    private static volatile boolean gameProcess;

    private QuestGameBootstrap() { }

    public static boolean isGameProcess() {
        if (gameProcess) return true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            return Application.getProcessName().endsWith(":game");
        return false;
    }
    public static int launcherTaskId() {
        return launcherTaskId;
    }

    private static int runningGamePid(Context context) {
        ActivityManager manager = context.getSystemService(ActivityManager.class);
        List<ActivityManager.RunningAppProcessInfo> processes = manager == null ? null : manager.getRunningAppProcesses();
        if (processes != null) for (ActivityManager.RunningAppProcessInfo process : processes)
            if ((context.getPackageName() + ":game").equals(process.processName)) return process.pid;
        return -1;
    }

    private static boolean isReturning(Context context, int pid) {
        try {
            return pid != -1 && new String(Files.readAllBytes(new File(context.getCacheDir(), "quest-game-exiting").toPath()), StandardCharsets.UTF_8)
                    .equals(Integer.toString(pid));
        } catch (IOException ignored) {
            return false;
        }
    }

    static void markReturning(Context context) {
        try {
            Files.write(new File(context.getCacheDir(), "quest-game-exiting").toPath(),
                    Integer.toString(Process.myPid()).getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) { }
    }

    public static boolean resumeRunningGame(Context context) {
        int pid = runningGamePid(context);
        if (pid == -1 || isReturning(context, pid)) return false;
        context.startActivity(new Intent(context, QuestVrLoadingActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        return true;
    }

    public static void startLaunch(Context context, Intent intent, Consumer<Exception> failure) {
        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(new Runnable() {
            private int remaining = 160;
            @Override public void run() {
                int pid = runningGamePid(context);
                if (pid != -1 && isReturning(context, pid) && remaining-- > 0) {
                    handler.postDelayed(this, 100);
                    return;
                }
                try {
                    if (pid != -1 && isReturning(context, pid))
                        throw new IOException("The previous game process is still stopping");
                    if (resumeRunningGame(context)) discardLaunch(context, intent);
                    else context.startActivity(intent);
                } catch (Exception error) {
                    discardLaunch(context, intent);
                    failure.accept(error);
                }
            }
        });
    }

    public static void attachLaunch(Context context, Intent intent, FCLBridge bridge,
                                    boolean useInputStack) throws IOException {
        FCLConfig config = bridge.getLaunchConfig();
        if (config == null || !config.isVrEnabled()) throw new IOException("Missing VR launch configuration");
        Launch launch = new Launch();
        launch.logDir = config.getLogDir();
        launch.javaPath = config.getJavaPath();
        launch.workingDir = config.getWorkingDir();
        launch.renderer = config.getRenderer();
        launch.args = config.getArgs();
        launch.useVKDriverSystem = config.getUseVKDriverSystem();
        launch.installedModLoaders = config.getInstalledModLoaders();
        launch.lwjglVersion = config.getLwjglVersion();
        launch.driver = DriverPlugin.getSelected();
        launch.scaleFactor = bridge.getScaleFactor();
        launch.controller = bridge.getController();
        launch.javaVersion = bridge.getJava();
        launch.modSummary = bridge.getModSummary();
        launch.useInputStack = useInputStack;
        launch.forceResolution = FCLBridge.FORCE_RESOLUTION;
        launch.forceScale = FCLBridge.FORCE_RESOLUTION_SCALE;
        launch.forceWidth = FCLBridge.FORCE_RESOLUTION_WIDTH;
        launch.forceHeight = FCLBridge.FORCE_RESOLUTION_HEIGHT;
        launch.forceStartSize = FCLBridge.FORCE_RESOLUTION_START_SIZE;
        launch.launcherTaskId = context instanceof Activity ? ((Activity) context).getTaskId() : -1;
        String token = UUID.randomUUID().toString();
        File file = launchFile(context, token);
        try (Writer writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
            GSON.toJson(launch, writer);
        } catch (IOException | RuntimeException failure) {
            Files.deleteIfExists(file.toPath());
            throw failure;
        }
        intent.putExtra(EXTRA_TOKEN, token);
    }

    public static void discardLaunch(Context context, Intent intent) {
        String token = intent.getStringExtra(EXTRA_TOKEN);
        if (token == null) return;
        try {
            Files.deleteIfExists(launchFile(context, token).toPath());
        } catch (IOException ignored) { }
    }

    public static FCLBridge restore(Activity activity, Intent intent) throws IOException {
        if (!isGameProcess()) throw new IOException("VR must run in its isolated game process");
        FCLBridge existing = JVMActivity.getPreparedBridge();
        if (existing != null && gameProcess) {
            discardLaunch(activity, intent);
            intent.removeExtra(EXTRA_TOKEN);
            return existing;
        }
        String token = intent.getStringExtra(EXTRA_TOKEN);
        if (token == null) throw new IOException("The game session is no longer available");
        File file = launchFile(activity, token);
        Launch launch;
        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            launch = GSON.fromJson(reader, Launch.class);
        } catch (JsonParseException failure) {
            throw new IOException("Invalid VR launch configuration", failure);
        } finally {
            Files.deleteIfExists(file.toPath());
            intent.removeExtra(EXTRA_TOKEN);
        }
        if (launch == null || launch.renderer == null || launch.args == null
                || launch.javaPath == null || launch.workingDir == null || launch.logDir == null)
            throw new IOException("Incomplete VR launch configuration");
        FCLPath.loadPaths(activity);
        RendererManager.INSTANCE.init(activity);
        if (launch.driver != null) DriverPlugin.setSelected(launch.driver);
        FCLBridge.FORCE_RESOLUTION = launch.forceResolution;
        FCLBridge.FORCE_RESOLUTION_SCALE = launch.forceScale;
        FCLBridge.FORCE_RESOLUTION_WIDTH = launch.forceWidth;
        FCLBridge.FORCE_RESOLUTION_HEIGHT = launch.forceHeight;
        FCLBridge.FORCE_RESOLUTION_START_SIZE = launch.forceStartSize;
        CallbackBridge.nativeSetUseInputStackQueue(launch.useInputStack);
        FCLConfig config = new FCLConfig(activity.getApplicationContext(), launch.logDir,
                launch.javaPath, launch.workingDir, launch.renderer, launch.args);
        config.setVrEnabled(true);
        config.setUseVKDriverSystem(launch.useVKDriverSystem);
        config.setInstalledModLoaders(launch.installedModLoaders);
        config.setLwjglVersion(launch.lwjglVersion);
        FCLBridge bridge = FCLauncher.launchMinecraft(config);
        bridge.setScaleFactor(launch.scaleFactor);
        bridge.setController(launch.controller);
        bridge.setGameDir(launch.workingDir);
        bridge.setJava(launch.javaVersion);
        bridge.setRenderer(launch.renderer.getName());
        bridge.setModSummary(launch.modSummary);
        launcherTaskId = launch.launcherTaskId;
        gameProcess = true;
        Files.deleteIfExists(new File(activity.getCacheDir(), "quest-game-exiting").toPath());
        JVMActivity.setFCLBridge(bridge, MenuType.GAME);
        return bridge;
    }

    private static File launchFile(Context context, String token) throws IOException {
        if (!token.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw new IOException("Invalid VR launch token");
        File directory = new File(context.getCacheDir(), DIRECTORY);
        if (!directory.isDirectory() && !directory.mkdirs())
            throw new IOException("Cannot create private launch directory");
        return new File(directory, token + ".json");
    }

    private static final class Launch {
        String logDir, javaPath, workingDir, lwjglVersion;
        Renderer renderer;
        String[] args;
        boolean useVKDriverSystem, useInputStack, forceResolution;
        FCLConfig.InstalledModLoaders installedModLoaders;
        DriverPlugin.Driver driver;
        double scaleFactor;
        String controller, javaVersion, modSummary;
        float forceScale;
        int forceWidth, forceHeight, forceStartSize, launcherTaskId;
    }
}
