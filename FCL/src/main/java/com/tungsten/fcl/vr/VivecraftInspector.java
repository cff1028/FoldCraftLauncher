package com.tungsten.fcl.vr;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Enumeration;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class VivecraftInspector {
    public static final String ANDROID_LOADER_CLASS = "org/vivecraft/util/VLoader.class";

    public enum Status {
        COMPATIBLE,
        MISSING,
        INCOMPATIBLE,
        CONFLICT,
        FAILED
    }

    private VivecraftInspector() {}

    public static Status inspect(File modsDirectory) {
        try {
            if (!modsDirectory.exists()) return Status.MISSING;
            if (!modsDirectory.isDirectory()) return Status.FAILED;
            File[] files = modsDirectory.listFiles();
            if (files == null) return Status.FAILED;
            return inspectFiles(Arrays.asList(files));
        } catch (SecurityException e) {
            return Status.FAILED;
        }
    }

    public static Status inspectFiles(Collection<File> files) {
        if (files == null) return Status.FAILED;
        int vivecraftJars = 0;
        boolean compatible = false;
        boolean unreadable = false;
        for (File file : files) {
            if (!file.getName().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                continue;
            }
            try (ZipFile jar = new ZipFile(file)) {
                boolean androidLoader = jar.getEntry(ANDROID_LOADER_CLASS) != null;
                boolean vivecraft = androidLoader;
                if (!vivecraft) {
                    Enumeration<? extends ZipEntry> entries = jar.entries();
                    while (entries.hasMoreElements()) {
                        String name = entries.nextElement().getName();
                        if (name.startsWith("org/vivecraft/") && name.endsWith(".class")) {
                            vivecraft = true;
                            break;
                        }
                    }
                }
                if (vivecraft) {
                    vivecraftJars++;
                    compatible |= androidLoader;
                }
            } catch (IOException | SecurityException e) {
                unreadable = true;
            }
        }
        if (vivecraftJars > 1) return Status.CONFLICT;
        if (compatible) return Status.COMPATIBLE;
        if (vivecraftJars == 1) return Status.INCOMPATIBLE;
        return unreadable ? Status.FAILED : Status.MISSING;
    }
}
