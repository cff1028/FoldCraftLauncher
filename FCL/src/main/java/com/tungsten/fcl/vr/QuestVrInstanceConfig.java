package com.tungsten.fcl.vr;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class QuestVrInstanceConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String[] OWNED_KEYS = {
            "stereoProviderPluginID", "vrEnabled", "rememberVr", "displayMirrorMode", "vrHotswitchingEnabled",
            "closeWithRuntime"
    };

    private QuestVrInstanceConfig() {
    }

    public static synchronized void configure(Path gameDirectory, boolean enabled) throws IOException {
        configure(gameDirectory, enabled, null, null);
    }

    public static synchronized void configure(Path gameDirectory, boolean enabled,
                                              Integer renderScalePercent, Boolean fsaaEnabled) throws IOException {
        renderScalePercent = QuestVrGraphicsSettings.normalizeRenderScalePercent(renderScalePercent);
        Path configuration = gameDirectory.resolve("config/vivecraft-client-config.json");
        Path stateFile = gameDirectory.resolve("config/.fcl-quest-vr-state.json");
        if (!enabled && !Files.exists(stateFile)) {
            if (VivecraftInspector.inspect(gameDirectory.resolve("mods").toFile()) == VivecraftInspector.Status.COMPATIBLE) {
                JsonObject config = readObject(configuration);
                config.addProperty("vrEnabled", "false");
                config.addProperty("vrHotswitchingEnabled", "false");
                writeObject(configuration, config);
            }
            return;
        }
        JsonObject config = readObject(configuration);
        if (enabled) {
            {
                JsonObject saved = readObject(stateFile);
                for (String key : OWNED_KEYS) {
                    if (saved.has(key)) continue;
                    JsonObject previous = new JsonObject();
                    previous.addProperty("present", config.has(key));
                    if (config.has(key)) {
                        previous.add("value", config.get(key).deepCopy());
                    }
                    saved.add(key, previous);
                }
                writeObject(stateFile, saved);
            }
            config.addProperty("stereoProviderPluginID", "OPENXR");
            if (!config.has("vrEnabled")) config.addProperty("vrEnabled", "true");
            config.addProperty("rememberVr", "true");
            config.addProperty("displayMirrorMode", "OFF");
            config.addProperty("vrHotswitchingEnabled", "false");
            config.addProperty("closeWithRuntime", "false");
            if (renderScalePercent != null) {
                config.addProperty("renderScaleFactor", Double.toString(renderScalePercent / 100.0));
            } else if (!config.has("renderScaleFactor")) {
                config.addProperty("renderScaleFactor", "0.8");
            }
            if (fsaaEnabled != null) config.addProperty("useFsaa", fsaaEnabled.toString());
        } else {
            JsonObject saved = readObject(stateFile);
            for (String key : OWNED_KEYS) {
                if (!saved.has(key) || !saved.get(key).isJsonObject()) {
                    if ("closeWithRuntime".equals(key) && !saved.has(key)) continue;
                    throw new IOException("Invalid Quest VR configuration backup: " + stateFile);
                }
                JsonObject previous = saved.getAsJsonObject(key);
                if (previous.get("present").getAsBoolean()) {
                    config.add(key, previous.get("value").deepCopy());
                } else {
                    config.remove(key);
                }
            }
            config.addProperty("vrEnabled", "false");
            config.addProperty("vrHotswitchingEnabled", "false");
        }
        writeObject(configuration, config);
        if (!enabled) {
            Files.deleteIfExists(stateFile);
        }
    }

    private static JsonObject readObject(Path file) throws IOException {
        return QuestVrGraphicsSettings.readObject(file);
    }

    private static void writeObject(Path file, JsonObject object) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(temporary, GSON.toJson(object).getBytes(StandardCharsets.UTF_8));
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
    }
}
