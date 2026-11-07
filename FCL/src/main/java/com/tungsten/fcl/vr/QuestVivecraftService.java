package com.tungsten.fcl.vr;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipFile;

public final class QuestVivecraftService {
    private static final long MAX_ASSET_BYTES = 64L * 1024 * 1024;
    private static final int MAX_API_BYTES = 4 * 1024 * 1024;
    private static final Object INSTALL_LOCK = new Object();
    private static final Pattern MC_VERSION = Pattern.compile("(?<![0-9.])(?:1\\.(?:1[0-9]|[2-9][0-9])|[2-9][0-9]\\.[0-9]+)(?:\\.[0-9]+)?(?![0-9.])");
    private static final Pattern INVALID_FILE_CHARACTER = Pattern.compile("[\\\\/:*?\"<>|\\p{Cntrl}]");
    private final Transport transport;

    public enum Error { NETWORK, UNSUPPORTED, INVALID_ASSET, INCOMPATIBLE, CONFLICT, DESTINATION, INTEGRITY }
    public enum Phase { DOWNLOADING, VERIFYING, INSTALLING }

    public enum Source {
        QUESTCRAFT("QuestCraft", "QuestCraftPlusPlus/VivecraftMod"),
        CFF1028("cff1028", "cff1028/VivecraftMod");

        public final String displayName, releasesUrl;
        private final String apiPath, downloadPath;

        Source(String displayName, String repository) {
            this.displayName = displayName;
            releasesUrl = "https://github.com/" + repository + "/releases";
            apiPath = "/repos/" + repository + "/releases";
            downloadPath = "/" + repository + "/releases/download/";
        }

        @Override public String toString() {
            return displayName;
        }
    }

    public static final class ServiceException extends IOException {
        public final Error error;
        public ServiceException(Error error, String message) {
            super(message);
            this.error = error;
        }
    }

    public static final class Cancellation {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        public void cancel() {
            cancelled.set(true);
        }
        public void check() {
            if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new CancellationException();
        }
    }

    public interface Progress { void onProgress(Phase phase, long completed, long total); }

    public static final class ReleaseAsset {
        public final Source source;
        public final long id;
        public final String releaseName, tag, fileName, url, publishedAt, sha256;
        public final long size;
        public final boolean prerelease;
        private ReleaseAsset(Source source, long id, String releaseName, String tag, String fileName, String url,
                             String publishedAt, String sha256, long size, boolean prerelease) {
            this.source = source;
            this.id = id; this.releaseName = releaseName; this.tag = tag; this.fileName = fileName;
            this.url = url; this.publishedAt = publishedAt; this.sha256 = sha256;
            this.size = size; this.prerelease = prerelease;
        }
    }

    public static final class InstalledMod {
        public final File file;
        public final String version, sha256;
        public final boolean compatible;
        private InstalledMod(File file, String version, String sha256, boolean compatible) {
            this.file = file; this.version = version; this.sha256 = sha256; this.compatible = compatible;
        }
    }

    public static final class InstallResult {
        public final InstalledMod mod;
        public final boolean alreadyInstalled;
        private InstallResult(InstalledMod mod, boolean alreadyInstalled) {
            this.mod = mod; this.alreadyInstalled = alreadyInstalled;
        }
    }

    interface Transport { InputStream open(URL url, Cancellation cancellation) throws IOException; }
    public QuestVivecraftService() {
        this(QuestVivecraftService::openOfficial);
    }
    QuestVivecraftService(Transport transport) {
        this.transport = transport;
    }

    public List<ReleaseAsset> fetchReleases(QuestVivecraftTarget target, Cancellation cancellation) throws IOException {
        return fetchReleases(target, Source.QUESTCRAFT, cancellation);
    }

    public List<ReleaseAsset> fetchReleases(QuestVivecraftTarget target, Source source, Cancellation cancellation) throws IOException {
        requireTarget(target);
        Objects.requireNonNull(source);
        List<ReleaseAsset> result = new ArrayList<>();
        try {
            for (int page = 1; page <= 10; page++) {
                cancellation.check();
                String json;
                try (InputStream input = transport.open(new URL("https://api.github.com" + source.apiPath
                        + "?per_page=100&page=" + page), cancellation)) {
                    json = new String(readLimited(input, MAX_API_BYTES, cancellation), StandardCharsets.UTF_8);
                }
                JsonArray releases = JsonParser.parseString(json).getAsJsonArray();
                result.addAll(parseReleases(json, target, source));
                if (releases.size() < 100) break;
            }
        } catch (CancellationException e) {
            throw e;
        } catch (ServiceException e) {
            throw e;
        } catch (IOException | IllegalStateException | com.google.gson.JsonParseException e) {
            throw new ServiceException(Error.NETWORK, "Cannot read GitHub releases: " + e.getMessage());
        }
        result.sort(Comparator.comparing((ReleaseAsset asset) -> asset.publishedAt).reversed()
                .thenComparing(Comparator.comparingLong((ReleaseAsset asset) -> asset.id).reversed()));
        return Collections.unmodifiableList(result);
    }

    static List<ReleaseAsset> parseReleases(String json, QuestVivecraftTarget target) throws IOException {
        return parseReleases(json, target, Source.QUESTCRAFT);
    }

    static List<ReleaseAsset> parseReleases(String json, QuestVivecraftTarget target, Source source) throws IOException {
        requireTarget(target);
        Objects.requireNonNull(source);
        List<ReleaseAsset> result = new ArrayList<>();
        try {
            for (JsonElement element : JsonParser.parseString(json).getAsJsonArray()) {
                JsonObject release = element.getAsJsonObject();
                if (bool(release, "draft")) continue;
                String tag = text(release, "tag_name");
                String name = text(release, "name");
                String hints = name + " " + tag + " " + text(release, "target_commitish");
                String minecraftHints = name + " " + tag;
                if (!MC_VERSION.matcher(minecraftHints).find()) minecraftHints = text(release, "target_commitish");
                if (!hints.toLowerCase(Locale.ROOT).contains("openxr")) continue;
                JsonArray assets = release.getAsJsonArray("assets");
                if (assets == null) continue;
                for (JsonElement assetElement : assets) {
                    JsonObject asset = assetElement.getAsJsonObject();
                    String fileName = text(asset, "name");
                    if (!safeFileName(fileName) || !"uploaded".equals(text(asset, "state"))) continue;
                    String fileHints = fileName.toLowerCase(Locale.ROOT);
                    if (fileHints.contains("sources") || fileHints.contains("javadoc")
                            || !matchesMinecraft(fileName, minecraftHints, target.minecraftVersion)
                            || !matchesLoaderHint(fileHints, target.loader)) continue;
                    String url = text(asset, "browser_download_url");
                    if (!validAssetUrl(url, fileName, source)) continue;
                    long size = asset.get("size").getAsLong();
                    long id = asset.get("id").getAsLong();
                    if (size <= 0 || size > MAX_ASSET_BYTES || id <= 0) continue;
                    String digest = text(asset, "digest");
                    if (!digest.isEmpty() && !digest.matches("sha256:[a-fA-F0-9]{64}")) continue;
                    result.add(new ReleaseAsset(source, id, name.isEmpty() ? tag : name, tag, fileName, url,
                            text(release, "published_at"), digest.isEmpty() ? "" : digest.substring(7).toLowerCase(Locale.ROOT),
                            size, bool(release, "prerelease")));
                }
            }
        } catch (RuntimeException e) {
            throw new ServiceException(Error.NETWORK, "Invalid GitHub release metadata: " + e.getClass().getSimpleName());
        }
        return result;
    }

    public List<InstalledMod> scanInstalled(QuestVivecraftTarget target) throws IOException {
        return scanInstalled(target, new Cancellation());
    }

    public List<InstalledMod> scanInstalled(QuestVivecraftTarget target, Cancellation cancellation) throws IOException {
        Path mods;
        try {
            mods = modsDirectory(target, false);
        } catch (ServiceException e) {
            throw e;
        } catch (IOException e) {
            throw destination(e);
        }
        if (!Files.exists(mods)) return Collections.emptyList();
        File[] files = mods.toFile().listFiles();
        if (files == null) throw new ServiceException(Error.DESTINATION, "Cannot list " + mods);
        List<InstalledMod> result = new ArrayList<>();
        for (File file : files) {
            cancellation.check();
            if (!file.getName().toLowerCase(Locale.ROOT).endsWith(".jar")) continue;
            try (ZipFile jar = new ZipFile(file)) {
                if (!QuestVivecraftMetadata.containsVivecraft(jar)) continue;
                String version = "";
                boolean compatible = false;
                try {
                    version = QuestVivecraftMetadata.validate(jar, target).version;
                    compatible = true;
                } catch (ServiceException ignored) { }
                result.add(new InstalledMod(file, version, sha256(file.toPath(), cancellation), compatible));
            } catch (IOException | RuntimeException e) {
                if (e instanceof CancellationException) throw (CancellationException) e;
                throw new ServiceException(Error.CONFLICT, "Cannot inspect existing mod " + file.getName() + ": " + e.getMessage());
            }
        }
        return Collections.unmodifiableList(result);
    }

    public InstallResult install(QuestVivecraftTarget target, ReleaseAsset asset, File privateCacheDirectory,
                                 Cancellation cancellation, Progress progress) throws IOException {
        requireTarget(target);
        cancellation.check();
        if (!validAssetUrl(asset.url, asset.fileName, asset.source) || asset.size <= 0 || asset.size > MAX_ASSET_BYTES)
            throw new ServiceException(Error.INVALID_ASSET, "Invalid source release asset.");
        List<InstalledMod> existing = scanInstalled(target, cancellation);
        if (existing.size() == 1 && existing.get(0).compatible && !asset.sha256.isEmpty()
                && asset.sha256.equals(existing.get(0).sha256)) return new InstallResult(existing.get(0), true);
        if (!existing.isEmpty() && (!asset.sha256.isEmpty() || existing.size() > 1)) throw conflict(existing);
        Path temporary;
        try {
            Files.createDirectories(privateCacheDirectory.toPath());
            temporary = Files.createTempFile(privateCacheDirectory.toPath(), "quest-vivecraft-", ".download");
        } catch (IOException e) {
            throw destination(e);
        }
        try {
            notify(progress, Phase.DOWNLOADING, 0, asset.size);
            long completed = 0;
            try (InputStream input = openDownload(new URL(asset.url), cancellation);
                 OutputStream output = Files.newOutputStream(temporary)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    cancellation.check();
                    completed += count;
                    if (completed > asset.size || completed > MAX_ASSET_BYTES)
                        throw new ServiceException(Error.INTEGRITY, "Asset exceeds its published size.");
                    output.write(buffer, 0, count);
                    notify(progress, Phase.DOWNLOADING, completed, asset.size);
                }
            }
            if (completed != asset.size) throw new ServiceException(Error.INTEGRITY, "Incomplete release asset.");
            cancellation.check();
            notify(progress, Phase.VERIFYING, 0, 0);
            String hash = sha256(temporary, cancellation);
            if (!asset.sha256.isEmpty() && !asset.sha256.equals(hash))
                throw new ServiceException(Error.INTEGRITY, "GitHub SHA-256 does not match the downloaded asset.");
            QuestVivecraftMetadata metadata;
            try (ZipFile jar = new ZipFile(temporary.toFile())) {
                metadata = QuestVivecraftMetadata.validate(jar, target);
            } catch (ServiceException e) {
                throw e;
            } catch (IOException e) {
                throw new ServiceException(Error.INVALID_ASSET, "Invalid JAR archive: " + e.getMessage());
            }
            synchronized (INSTALL_LOCK) {
                cancellation.check();
                existing = scanInstalled(target, cancellation);
                if (existing.size() == 1 && existing.get(0).compatible && hash.equals(existing.get(0).sha256))
                    return new InstallResult(existing.get(0), true);
                if (!existing.isEmpty()) throw conflict(existing);
                Path mods = modsDirectory(target, true);
                Path destination = mods.resolve(asset.fileName);
                if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS))
                    throw new ServiceException(Error.CONFLICT, "A file already exists: " + asset.fileName);
                notify(progress, Phase.INSTALLING, 0, 0);
                Path staged = Files.createTempFile(mods, ".quest-vivecraft-", ".part");
                try {
                    try (InputStream input = Files.newInputStream(temporary); OutputStream output = Files.newOutputStream(staged)) {
                        byte[] buffer = new byte[64 * 1024];
                        int count;
                        while ((count = input.read(buffer)) != -1) {
                            cancellation.check();
                            output.write(buffer, 0, count);
                        }
                    }
                    cancellation.check();
                    try {
                        Files.move(staged, destination);
                    } catch (java.nio.file.FileAlreadyExistsException e) {
                        throw new ServiceException(Error.CONFLICT, "A file already exists: " + asset.fileName);
                    }
                    return new InstallResult(new InstalledMod(destination.toFile(), metadata.version, hash, true), false);
                } finally {
                    deleteTemporary(staged);
                }
            }
        } catch (ServiceException e) {
            throw e;
        } catch (IOException e) {
            throw destination(e);
        } finally {
            deleteTemporary(temporary);
        }
    }

    private InputStream openDownload(URL url, Cancellation cancellation) throws IOException {
        try {
            return new FilterInputStream(transport.open(url, cancellation)) {
                @Override public int read() throws IOException {
                    try {
                        return super.read();
                    } catch (IOException e) {
                        throw network(e);
                    }
                }
                @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                    try {
                        return super.read(bytes, offset, length);
                    } catch (IOException e) {
                        throw network(e);
                    }
                }
                @Override public void close() throws IOException {
                    try {
                        super.close();
                    } catch (IOException e) {
                        throw network(e);
                    }
                }
            };
        } catch (IOException e) {
            throw network(e);
        }
    }

    private static ServiceException network(IOException e) {
        return e instanceof ServiceException ? (ServiceException) e
                : new ServiceException(Error.NETWORK, "Release download failed: " + e.getMessage());
    }

    private static ServiceException destination(IOException e) {
        return new ServiceException(Error.DESTINATION, "Cannot write/read the local download or instance directory: " + e.getMessage());
    }

    private static void deleteTemporary(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            Logger.getLogger(QuestVivecraftService.class.getName()).log(Level.WARNING,
                    "Cannot remove Quest Vivecraft temporary file " + path, e);
        }
    }

    private static ServiceException conflict(List<InstalledMod> existing) {
        StringBuilder names = new StringBuilder();
        for (InstalledMod mod : existing) {
            if (names.length() > 0) names.append(", ");
            names.append(mod.file.getName());
        }
        return new ServiceException(Error.CONFLICT, "Existing Vivecraft mod(s): " + names);
    }

    private static Path modsDirectory(QuestVivecraftTarget target, boolean create) throws IOException {
        Path game = target.gameDirectory.toPath().toAbsolutePath().normalize();
        if (!Files.isDirectory(game)) throw new ServiceException(Error.DESTINATION, "Instance directory no longer exists: " + game);
        Path realGame = game.toRealPath();
        Path mods = realGame.resolve("mods");
        if (Files.isSymbolicLink(mods)) throw new ServiceException(Error.DESTINATION, "The mods directory is a symbolic link.");
        if (create && !Files.exists(mods)) Files.createDirectory(mods);
        if (Files.exists(mods) && (!Files.isDirectory(mods) || !mods.toRealPath().getParent().equals(realGame)))
            throw new ServiceException(Error.DESTINATION, "Invalid instance mods directory.");
        return mods;
    }

    private static void requireTarget(QuestVivecraftTarget target) throws IOException {
        if (target.loader == QuestVivecraftTarget.Loader.NONE || target.loaderVersion.isEmpty()
                || !target.minecraftVersion.matches("[0-9]+(?:\\.[0-9]+)*"))
            throw new ServiceException(Error.UNSUPPORTED, "Cannot identify this instance's Minecraft and mod loader versions.");
    }

    private static boolean matchesMinecraft(String fileName, String releaseHints, String version) {
        Matcher fileVersions = MC_VERSION.matcher(fileName);
        boolean hasFileVersion = false;
        while (fileVersions.find()) {
            hasFileVersion = true;
            if (QuestModVersionRules.matches(version, fileVersions.group())) return true;
        }
        if (hasFileVersion) return false;
        Matcher releaseVersions = MC_VERSION.matcher(releaseHints);
        while (releaseVersions.find()) if (QuestModVersionRules.matches(version, releaseVersions.group())) return true;
        return false;
    }

    private static boolean matchesLoaderHint(String name, QuestVivecraftTarget.Loader loader) {
        if (name.contains("neoforge")) return loader == QuestVivecraftTarget.Loader.NEOFORGE;
        if (name.contains("forge")) return loader == QuestVivecraftTarget.Loader.FORGE;
        if (name.contains("fabric")) return loader == QuestVivecraftTarget.Loader.FABRIC;
        if (name.contains("quilt")) return loader == QuestVivecraftTarget.Loader.QUILT;
        return true;
    }

    static boolean validAssetUrl(String value, String fileName) {
        return validAssetUrl(value, fileName, Source.QUESTCRAFT);
    }

    static boolean validAssetUrl(String value, String fileName, Source source) {
        if (source == null || !safeFileName(fileName)) return false;
        try {
            URI uri = URI.create(value);
            if (!"https".equals(uri.getScheme()) || !"github.com".equals(uri.getHost())
                    || uri.getPort() != -1 || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
                return false;
            String path = uri.getPath();
            if (!path.startsWith(source.downloadPath)) return false;
            String rest = path.substring(source.downloadPath.length());
            String[] parts = rest.split("/", -1);
            return parts.length == 2 && !parts[0].isEmpty() && !parts[0].equals(".") && !parts[0].equals("..")
                    && parts[1].equals(fileName) && !rest.contains("\\");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean safeFileName(String name) {
        return name != null && name.length() <= 180 && name.length() > 4
                && name.toLowerCase(Locale.ROOT).endsWith(".jar")
                && !name.startsWith(".") && !INVALID_FILE_CHARACTER.matcher(name).find();
    }

    private static InputStream openOfficial(URL initial, Cancellation cancellation) throws IOException {
        Source source = null;
        for (Source candidate : Source.values()) {
            if (initial.getHost().equals("api.github.com") && initial.getPath().equals(candidate.apiPath)
                    || initial.getHost().equals("github.com") && initial.getPath().startsWith(candidate.downloadPath)) {
                source = candidate;
                break;
            }
        }
        if (source == null) throw new ServiceException(Error.INVALID_ASSET, "Unknown release source.");
        URL url = initial;
        for (int redirects = 0; redirects < 6; redirects++) {
            cancellation.check();
            if (!allowedNetworkUrl(url, source)) throw new ServiceException(Error.INVALID_ASSET, "Unexpected download host.");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setRequestProperty("User-Agent", "FCL-Quest-VR");
            connection.setRequestProperty("Accept", url.getHost().equals("api.github.com")
                    ? "application/vnd.github+json" : "application/octet-stream");
            connection.setRequestProperty("Accept-Encoding", "identity");
            try {
                int code = connection.getResponseCode();
                if (code >= 300 && code < 400) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new ServiceException(Error.NETWORK, "GitHub returned an empty redirect.");
                    url = new URL(url, location);
                    connection.disconnect();
                    continue;
                }
                if (code != 200) throw new ServiceException(Error.NETWORK, "GitHub HTTP " + code
                        + (code == 403 || code == 429 ? " (rate limit or access denied; retry later)" : ""));
                InputStream stream = connection.getInputStream();
                return new FilterInputStream(stream) {
                    @Override public void close() throws IOException {
                        try {
                            super.close();
                        } finally {
                            connection.disconnect();
                        }
                    }
                };
            } catch (IOException | RuntimeException e) {
                connection.disconnect();
                throw e;
            }
        }
        throw new ServiceException(Error.NETWORK, "Too many GitHub redirects.");
    }

    private static boolean allowedNetworkUrl(URL url, Source source) {
        if (!url.getProtocol().equals("https") || url.getPort() != -1 || url.getUserInfo() != null
                || url.getRef() != null) return false;
        return switch (url.getHost()) {
            case "api.github.com" -> url.getPath().equals(source.apiPath);
            case "github.com" -> url.getPath().startsWith(source.downloadPath);
            case "release-assets.githubusercontent.com", "objects.githubusercontent.com", "github-releases.githubusercontent.com" -> true;
            default -> false;
        };
    }

    private static byte[] readLimited(InputStream input, int maximum, Cancellation cancellation) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                cancellation.check();
                if (output.size() + count > maximum) throw new ServiceException(Error.NETWORK, "GitHub response is too large.");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static String sha256(Path path, Cancellation cancellation) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                cancellation.check();
                digest.update(buffer, 0, count);
            }
        }
        StringBuilder hash = new StringBuilder();
        for (byte value : digest.digest()) hash.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return hash.toString();
    }

    private static void notify(Progress progress, Phase phase, long completed, long total) {
        if (progress != null) progress.onProgress(phase, completed, total);
    }

    private static String text(JsonObject object, String key) {
        return QuestVivecraftMetadata.string(object, key);
    }
    private static boolean bool(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() && object.get(key).getAsBoolean();
    }
}
