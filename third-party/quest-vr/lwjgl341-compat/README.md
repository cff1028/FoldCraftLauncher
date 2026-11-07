# LWJGL 3.4.1 VR compatibility bindings

Complete corresponding Java sources are in `sources.zip`, including upstream copyright notices and BSD licenses. The source revisions, modifications and hashes are recorded in `provenance.json`.

Rebuild from the repository root with Python 3.9+ and JDK 17:

```text
python third-party/quest-vr/lwjgl341-compat/build.py --javac /path/to/jdk-17/bin/javac
```

The script uses the checked-in runtime dependencies and writes the two JARs to `FCL/src/main/assets/quest_vr/3.4.1`. Use `--output DIR` to build elsewhere. It requires no downloads. JAR entry order and timestamps are fixed; the packaged artifacts use the compiler version recorded in `provenance.json`.

These are compatibility modules based on QuestCraft OpenXR / LWJGL GLES 3.3.3, rebuilt for the 3.4.1 core. They are not the upstream 3.4.1 OpenXR/GLES module releases. Android XR types, struct layout, public APIs, GLES capability ordering and native JNI signatures remain intact. The launcher selects the actual 3.4.1 core and Android core natives for Minecraft 26.1 and newer; older VR instances retain the unchanged 3.3.3 modules.
