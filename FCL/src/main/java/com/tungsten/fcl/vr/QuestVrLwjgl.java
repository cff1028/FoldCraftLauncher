package com.tungsten.fcl.vr;

import com.tungsten.fclcore.util.versioning.GameVersionNumber;

public final class QuestVrLwjgl {
    private QuestVrLwjgl() {}

    public static String defaultVersion(String minecraft) {
        if (minecraft == null || !GameVersionNumber.isKnown(minecraft)) return "3.3.3";
        GameVersionNumber version = GameVersionNumber.asGameVersion(minecraft);
        GameVersionNumber release = GameVersionNumber.getReleaseOfSnapshot(version);
        if (release != null) version = release;
        return version.compareTo("26.1") >= 0 ? "3.4.1" : "3.3.3";
    }
}
