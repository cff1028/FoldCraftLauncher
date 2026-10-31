package com.tungsten.fcl.vr;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class QuestVrGraphicsSettings {
    public static final int MIN_RENDER_SCALE_PERCENT = 10;
    public static final int MAX_RENDER_SCALE_PERCENT = 900;
    public static final int DEFAULT_RENDER_SCALE_PERCENT = 80;
    public static final boolean DEFAULT_FSAA_ENABLED = false;

    private final int renderScalePercent;
    private final boolean fsaaEnabled;

    private QuestVrGraphicsSettings(int renderScalePercent, boolean fsaaEnabled) {
        this.renderScalePercent = renderScalePercent;
        this.fsaaEnabled = fsaaEnabled;
    }

    public int getRenderScalePercent() {
        return renderScalePercent;
    }

    public boolean isFsaaEnabled() {
        return fsaaEnabled;
    }

    public static QuestVrGraphicsSettings defaults() {
        return new QuestVrGraphicsSettings(DEFAULT_RENDER_SCALE_PERCENT, DEFAULT_FSAA_ENABLED);
    }

    public static QuestVrGraphicsSettings read(Path gameDirectory) throws IOException {
        Path configuration = gameDirectory.resolve("config/vivecraft-client-config.json");
        JsonObject config = readObject(configuration);
        int percent = DEFAULT_RENDER_SCALE_PERCENT;
        JsonElement scale = config.get("renderScaleFactor");
        if (isNumberOrString(scale)) {
            try {
                double factor = scale.getAsDouble();
                if (Double.isFinite(factor) && factor >= MIN_RENDER_SCALE_PERCENT / 100.0
                        && factor <= MAX_RENDER_SCALE_PERCENT / 100.0) {
                    percent = (int) Math.round(factor * 100.0);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        Boolean fsaa = parseFsaaEnabled(config.get("useFsaa"));
        return new QuestVrGraphicsSettings(percent, fsaa == null ? DEFAULT_FSAA_ENABLED : fsaa);
    }

    public static Integer normalizeRenderScalePercent(Integer value) {
        return value != null && value >= MIN_RENDER_SCALE_PERCENT && value <= MAX_RENDER_SCALE_PERCENT
                ? value : null;
    }

    public static Integer parseRenderScalePercent(JsonElement value) {
        if (!isNumberOrString(value)) return null;
        try {
            return normalizeRenderScalePercent(new BigDecimal(value.getAsString()).intValueExact());
        } catch (NumberFormatException | ArithmeticException ignored) {
            return null;
        }
    }

    public static Boolean parseFsaaEnabled(JsonElement value) {
        if (value == null || !value.isJsonPrimitive()) return null;
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (primitive.isBoolean()) return primitive.getAsBoolean();
        if (primitive.isString()) {
            String text = primitive.getAsString();
            if ("true".equalsIgnoreCase(text)) return true;
            if ("false".equalsIgnoreCase(text)) return false;
        }
        return null;
    }

    private static boolean isNumberOrString(JsonElement value) {
        return value != null && value.isJsonPrimitive()
                && (value.getAsJsonPrimitive().isNumber() || value.getAsJsonPrimitive().isString());
    }

    static JsonObject readObject(Path file) throws IOException {
        if (!Files.exists(file)) return new JsonObject();
        try {
            JsonElement parsed = JsonParser.parseString(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) throw new IllegalArgumentException("Expected a JSON object");
            return parsed.getAsJsonObject();
        } catch (RuntimeException failure) {
            throw new IOException("Cannot read Vivecraft configuration: " + file, failure);
        }
    }
}
