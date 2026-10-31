package com.tungsten.fcl.vr;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.tomlj.Toml;
import org.tomlj.TomlParseResult;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class QuestVivecraftMetadata {
    static final String OPENXR_CLASS = "org/vivecraft/client_vr/provider/openxr/MCOpenXR.class";
    final String version;

    private QuestVivecraftMetadata(String version) {
        this.version = version;
    }

    static QuestVivecraftMetadata validate(ZipFile jar, QuestVivecraftTarget target) throws IOException {
        if (jar.getEntry(VivecraftInspector.ANDROID_LOADER_CLASS) == null || jar.getEntry(OPENXR_CLASS) == null)
            throw incompatible("The JAR does not contain the Android VLoader and OpenXR provider.");
        try {
            JsonObject metadata;
            switch (target.loader) {
                case FABRIC -> {
                    metadata = json(jar, "fabric.mod.json");
                    requireVivecraft(metadata, "id");
                    JsonObject depends = metadata.getAsJsonObject("depends");
                    if (depends == null) throw incompatible("Missing Fabric dependencies.");
                    requireVersion("minecraft", target.minecraftVersion, depends.get("minecraft"));
                    requireVersion("fabricloader", target.loaderVersion, depends.get("fabricloader"));
                }
                case QUILT -> {
                    metadata = json(jar, "quilt.mod.json").getAsJsonObject("quilt_loader");
                    if (metadata == null) throw incompatible("Missing Quilt loader descriptor.");
                    requireVivecraft(metadata, "id");
                    JsonArray depends = metadata.getAsJsonArray("depends");
                    requireVersion("minecraft", target.minecraftVersion, dependency(depends, "minecraft", "id", "versions"));
                    requireVersion("quilt_loader", target.loaderVersion, dependency(depends, "quilt_loader", "id", "versions"));
                }
                case FORGE, NEOFORGE -> {
                    String entry = target.loader == QuestVivecraftTarget.Loader.NEOFORGE
                            && jar.getEntry("META-INF/neoforge.mods.toml") != null
                            ? "META-INF/neoforge.mods.toml" : "META-INF/mods.toml";
                    TomlParseResult parsed = Toml.parse(read(jar, entry));
                    if (parsed.hasErrors()) throw incompatible("Malformed Forge/NeoForge descriptor.");
                    JsonObject toml = JsonParser.parseString(parsed.toJson()).getAsJsonObject();
                    metadata = null;
                    JsonArray mods = toml.getAsJsonArray("mods");
                    if (mods != null) for (JsonElement element : mods) {
                        JsonObject mod = element.getAsJsonObject();
                        if ("vivecraft".equals(string(mod, "modId"))) metadata = mod;
                    }
                    if (metadata == null) throw incompatible("The mod ID is not vivecraft.");
                    JsonObject allDepends = toml.getAsJsonObject("dependencies");
                    JsonArray depends = allDepends == null ? null : allDepends.getAsJsonArray("vivecraft");
                    requireVersion("minecraft", target.minecraftVersion,
                            dependency(depends, "minecraft", "modId", "versionRange"));
                    String loader = target.loader == QuestVivecraftTarget.Loader.NEOFORGE ? "neoforge" : "forge";
                    requireVersion(loader, target.loaderVersion, dependency(depends, loader, "modId", "versionRange"));
                }
                default -> throw incompatible("This instance has no supported mod loader.");
            }
            String version = string(metadata, "version");
            if (version.contains("${file.jarVersion}")) {
                String manifest = read(jar, "META-INF/MANIFEST.MF");
                String implementation = new Manifest(new java.io.ByteArrayInputStream(
                        manifest.getBytes(StandardCharsets.UTF_8))).getMainAttributes().getValue("Implementation-Version");
                if (implementation == null) throw incompatible("Missing mod version in manifest.");
                version = version.replace("${file.jarVersion}", implementation);
            }
            if (version.isEmpty() || version.length() > 200) throw incompatible("Missing or invalid mod version.");
            return new QuestVivecraftMetadata(version);
        } catch (QuestVivecraftService.ServiceException e) {
            throw e;
        } catch (RuntimeException e) {
            throw incompatible("Unreadable mod dependencies: " + e.getClass().getSimpleName());
        }
    }

    static boolean containsVivecraft(ZipFile jar) {
        Enumeration<? extends ZipEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            if (name.startsWith("org/vivecraft/") && name.endsWith(".class")) return true;
        }
        try {
            if (jar.getEntry("fabric.mod.json") != null && "vivecraft".equals(string(json(jar, "fabric.mod.json"), "id"))) return true;
            if (jar.getEntry("quilt.mod.json") != null) {
                JsonObject quilt = json(jar, "quilt.mod.json").getAsJsonObject("quilt_loader");
                if (quilt != null && "vivecraft".equals(string(quilt, "id"))) return true;
            }
            for (String entry : new String[]{"META-INF/neoforge.mods.toml", "META-INF/mods.toml"}) {
                if (jar.getEntry(entry) == null) continue;
                TomlParseResult parsed = Toml.parse(read(jar, entry));
                if (parsed.hasErrors()) throw new IllegalArgumentException("Malformed mod metadata");
                JsonArray mods = JsonParser.parseString(parsed.toJson()).getAsJsonObject().getAsJsonArray("mods");
                if (mods != null) for (JsonElement element : mods) {
                    if ("vivecraft".equals(string(element.getAsJsonObject(), "modId"))) return true;
                }
            }
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("Cannot inspect mod descriptor", e);
        }
        return false;
    }

    private static JsonElement dependency(JsonArray dependencies, String id, String idKey, String versionKey) {
        if (dependencies != null) for (JsonElement element : dependencies) {
            if (element.isJsonObject() && id.equals(string(element.getAsJsonObject(), idKey)))
                return element.getAsJsonObject().get(versionKey);
        }
        return null;
    }

    private static void requireVersion(String dependency, String version, JsonElement rules) throws IOException {
        if (!QuestModVersionRules.matches(version, rules))
            throw incompatible(dependency + " " + version + " does not satisfy " + (rules == null ? "missing dependency" : rules.toString()));
    }

    private static void requireVivecraft(JsonObject object, String key) throws IOException {
        if (!"vivecraft".equals(string(object, key))) throw incompatible("The mod ID is not vivecraft.");
    }

    private static JsonObject json(ZipFile jar, String entry) throws IOException {
        return JsonParser.parseString(read(jar, entry)).getAsJsonObject();
    }

    static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    private static String read(ZipFile jar, String name) throws IOException {
        ZipEntry entry = jar.getEntry(name);
        if (entry == null) throw incompatible("Missing " + name);
        if (entry.getSize() > 512 * 1024) throw incompatible("Mod descriptor too large: " + name);
        try (InputStream input = jar.getInputStream(entry); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > 512 * 1024) throw incompatible("Mod descriptor too large: " + name);
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static QuestVivecraftService.ServiceException incompatible(String message) {
        return new QuestVivecraftService.ServiceException(QuestVivecraftService.Error.INCOMPATIBLE, message);
    }
}
