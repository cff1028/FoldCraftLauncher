# Quest VR Java bindings

These assets run in the embedded Java VM. The launcher prepends them to its
LWJGL classpath and selects the packaged Android native libraries explicitly.
Their sizes and SHA-256 values are in `FCL/src/main/assets/quest_vr/checksums.json`.

| Asset | Source | License |
| --- | --- | --- |
| `lwjgl-openxr.jar` | <https://github.com/QuestCraftPlusPlus/lwjgl3/releases/download/3.3.3/lwjgl-openxr-3.3.3.jar> | BSD 3-Clause, `QuestCraft-LWJGL-LICENSE.md` |
| `lwjgl-opengles.jar` | <https://repo.maven.apache.org/maven2/org/lwjgl/lwjgl-opengles/3.3.3/lwjgl-opengles-3.3.3.jar> | BSD 3-Clause, `LWJGL-LICENSE.md` |

Minecraft 26.1 and newer (including their snapshots/pre-releases) use the
packaged LWJGL 3.4.1 core and core natives, plus the compatibility modules in
`quest_vr/3.4.1/`. Older VR instances keep the original 3.3.3 modules above.
Selection uses the resolved Minecraft version, not the instance name.

The compatibility modules retain the Android OpenXR API and original GLES JNI
signatures. They adapt callbacks, absolute memory access and struct-buffer
factories to the 3.4.1 core; they are not official 3.4.1 module builds.
Complete source, pinned provenance and the offline JDK 17 rebuild script are in
[`lwjgl341-compat`](lwjgl341-compat/README.md). Both modules retain BSD 3-Clause
licensing, also embedded inside the JARs.

Vivecraft must be installed separately with matching Minecraft and mod-loader
versions. Its desktop native libraries are not used for this Android runtime.
