package com.tungsten.fcl.vr;

import java.io.File;
import java.util.Objects;

public final class QuestVivecraftTarget {
    public enum Loader { FABRIC, QUILT, FORGE, NEOFORGE, NONE }

    public final String instanceId;
    public final String minecraftVersion;
    public final Loader loader;
    public final String loaderVersion;
    public final File gameDirectory;

    public QuestVivecraftTarget(String instanceId, String minecraftVersion, Loader loader,
                               String loaderVersion, File gameDirectory) {
        this.instanceId = Objects.requireNonNull(instanceId);
        this.minecraftVersion = minecraftVersion == null ? "" : minecraftVersion;
        this.loader = Objects.requireNonNull(loader);
        this.loaderVersion = loaderVersion == null ? "" : loaderVersion;
        this.gameDirectory = Objects.requireNonNull(gameDirectory).getAbsoluteFile();
    }
}
