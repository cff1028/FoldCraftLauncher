package com.tungsten.fcl.vr;

import com.tungsten.fcl.game.FCLGameRepository;
import com.tungsten.fclcore.download.LibraryAnalyzer;
import com.tungsten.fclcore.game.Version;

public final class QuestVivecraftTargets {
    private QuestVivecraftTargets() {}

    public static QuestVivecraftTarget capture(FCLGameRepository repository, String versionId) {
        String minecraft = "";
        String loaderVersion = "";
        QuestVivecraftTarget.Loader loader = QuestVivecraftTarget.Loader.NONE;
        try {
            Version version = repository.getResolvedVersion(versionId);
            minecraft = repository.getGameVersion(version).orElse("");
            LibraryAnalyzer analyzer = LibraryAnalyzer.analyze(version, minecraft);
            LibraryAnalyzer.LibraryType[] types = { LibraryAnalyzer.LibraryType.NEO_FORGE,
                    LibraryAnalyzer.LibraryType.FORGE, LibraryAnalyzer.LibraryType.QUILT,
                    LibraryAnalyzer.LibraryType.FABRIC };
            QuestVivecraftTarget.Loader[] loaders = { QuestVivecraftTarget.Loader.NEOFORGE,
                    QuestVivecraftTarget.Loader.FORGE, QuestVivecraftTarget.Loader.QUILT,
                    QuestVivecraftTarget.Loader.FABRIC };
            int found = 0;
            for (int i = 0; i < types.length; i++) {
                if (analyzer.has(types[i])) {
                    loader = loaders[i];
                    loaderVersion = analyzer.getVersion(types[i]).orElse("");
                    found++;
                }
            }
            if (found != 1) loader = QuestVivecraftTarget.Loader.NONE;
        } catch (RuntimeException ignored) {
            loader = QuestVivecraftTarget.Loader.NONE;
        }
        return new QuestVivecraftTarget(versionId, minecraft, loader, loaderVersion,
                repository.getRunDirectory(versionId));
    }
}
