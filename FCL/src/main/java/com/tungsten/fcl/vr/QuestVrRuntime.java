package com.tungsten.fcl.vr;

import android.content.Context;
import android.os.Build;

import com.mio.data.Renderer;
import com.tungsten.fclauncher.FCLConfig;
import com.tungsten.fclcore.util.platform.CommandBuilder;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public final class QuestVrRuntime {
    private static final String[] JARS = {"lwjgl-openxr.jar", "lwjgl-opengles.jar"};

    private QuestVrRuntime() {
    }

    public static synchronized void install(Context context) throws IOException {
        File directory = context.getDir("quest_vr", Context.MODE_PRIVATE);
        for (String name : JARS) {
            File destination = new File(directory, name);
            byte[] expected;
            try (InputStream source = context.getAssets().open("quest_vr/" + name)) {
                expected = digest(source);
            }
            if (destination.isFile()) {
                try (InputStream existing = Files.newInputStream(destination.toPath())) {
                    if (Arrays.equals(expected, digest(existing))) {
                        continue;
                    }
                }
            }
            File temporary = new File(directory, name + ".installing");
            try (InputStream source = context.getAssets().open("quest_vr/" + name)) {
                Files.copy(source, temporary.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static byte[] digest(InputStream stream) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[16384];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 is unavailable", impossible);
        }
    }

    public static List<String> getClasspath(Context context) {
        File directory = context.getDir("quest_vr", Context.MODE_PRIVATE);
        return Arrays.asList(new File(directory, JARS[0]).getAbsolutePath(),
                new File(directory, JARS[1]).getAbsolutePath());
    }

    public static void configureInstance(File gameDirectory, boolean enabled) throws IOException {
        configureInstance(gameDirectory, enabled, null, null);
    }

    public static void configureInstance(File gameDirectory, boolean enabled,
                                         Integer renderScalePercent, Boolean fsaaEnabled) throws IOException {
        QuestVrInstanceConfig.configure(gameDirectory.toPath(), enabled, renderScalePercent, fsaaEnabled);
    }

    public static void addJvmArguments(CommandBuilder command, Context context, Renderer renderer) {
        String nativeDirectory = context.getApplicationInfo().nativeLibraryDir;
        String lwjglDirectory = new File(context.getDir("runtime", Context.MODE_PRIVATE),
                "lwjgl/3.3.3/natives/arm64-v8a").getAbsolutePath();
        replaceProperty(command, "org.lwjgl.opengl.libname", nativeDirectory + "/" + renderer.getGlName());
        replaceProperty(command, "org.lwjgl.opengles.libname", "/system/lib64/libGLESv3.so");
        replaceProperty(command, "org.lwjgl.egl.libname", "/system/lib64/libEGL.so");
        replaceProperty(command, "org.lwjgl.openxr.libname", nativeDirectory + "/libquest_openxr.so");
        replaceProperty(command, "org.lwjgl.librarypath", nativeDirectory + ":" + lwjglDirectory);
        replaceProperty(command, "glfwstub.windowWidth", "1280");
        replaceProperty(command, "glfwstub.windowHeight", "720");
        replaceProperty(command, "glfwstub.initEgl", "false");
        replaceProperty(command, "fcl.quest.vr", "true");
        replaceProperty(command, "os.version", "Android-" + Build.VERSION.RELEASE);
        replaceProperty(command, "org.lwjgl.util.DebugLoader", "true");
    }

    private static void replaceProperty(CommandBuilder command, String key, String value) {
        String prefix = "-D" + key + "=";
        command.removeIf(argument -> argument.startsWith(prefix));
        command.add(prefix + value);
    }

    public static void applyEnvironment(FCLConfig config, Map<String, String> environment) {
        if (!config.isVrEnabled()) {
            environment.remove("FCL_VR");
            return;
        }
        String id = config.getRenderer().getId();
        if (!Renderer.ID_QUEST_MOBILEGLUES.equals(id)) {
            throw new IllegalStateException("Unsupported Quest VR renderer: " + id);
        }
        String nativeDirectory = config.getContext().getApplicationInfo().nativeLibraryDir;
        environment.put("FCL_VR", "1");
        environment.put("FCL_VR_TRANSITION_ANIMATION",
                QuestVrPreferences.isTransitionAnimationEnabled(config.getContext()) ? "1" : "0");
        environment.put("FCL_VR_RENDERER", id);
        environment.put("POJAV_RENDERER", id);
        environment.put("POJAVEXEC_EGL", nativeDirectory + "/libmobileglues.so");
        environment.remove("SFPEW_LAYER");
        environment.remove("SFPEW_EGL");
        environment.remove("SFPEW_RELAXED_CONTEXT");
        environment.put("LIBGL_ES", "3");
        environment.put("LIBGL_GLES", "libGLESv3.so");
        environment.put("FORCE_VSYNC", "false");
        environment.put("FCL_VR_NATIVE_DIR", nativeDirectory);
        environment.put("MG_DIR_PATH", new File(config.getContext().getFilesDir(), "quest-vr/mobileglues").getAbsolutePath());
        environment.remove("VULKAN_DRIVER_SYSTEM");
        environment.remove("VKSHIM_ENABLE");
        environment.remove("DRIVER_PATH");
        environment.remove("DRIVER_LIB");
        environment.remove("DRIVER_GPU");
    }
}
