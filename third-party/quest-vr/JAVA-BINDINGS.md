# Quest VR Java bindings

These assets run in the embedded Java VM. The launcher prepends them to its
LWJGL classpath and selects the packaged Android native libraries explicitly.
Their sizes and SHA-256 values are in `FCL/src/main/assets/quest_vr/checksums.json`.

| Asset | Source | License |
| --- | --- | --- |
| `lwjgl-openxr.jar` | <https://github.com/QuestCraftPlusPlus/lwjgl3/releases/download/3.3.3/lwjgl-openxr-3.3.3.jar> | BSD 3-Clause, `QuestCraft-LWJGL-LICENSE.md` |
| `lwjgl-opengles.jar` | <https://repo.maven.apache.org/maven2/org/lwjgl/lwjgl-opengles/3.3.3/lwjgl-opengles-3.3.3.jar> | BSD 3-Clause, `LWJGL-LICENSE.md` |

Vivecraft must be installed separately with matching Minecraft and mod-loader
versions. Its desktop native libraries are not used for this Android runtime.
