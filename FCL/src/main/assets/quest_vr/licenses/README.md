# Quest VR components

This fork adds Quest VR support to Fold Craft Launcher. Changes by cff1028,
2026-10-31: per-instance VR settings, Android/OpenXR lifecycle and presentation,
Vivecraft installation checks, and MobileGlues shader-source compatibility.
FCL retains its GPL-3.0 license and upstream copyright notices.

| Component | Source | License |
| --- | --- | --- |
| MobileGlues 2.0.0 + shader-source fixes | [fbc4e412](https://github.com/MobileGL-Dev/MobileGlues/tree/fbc4e412e353302607ba36489b2dd573b5becb25); patch/build scripts in `mobileglues-fix` | LGPL-2.1-only |
| SimpleFPEWrapper (ordinary mode) | [ca8c6af26ca2](https://github.com/MobileGL-Dev/SimpleFPEWrapper/tree/ca8c6af26ca2996c3d07be90cde0e72ebf815935); submodules pinned in `SimpleFPEWrapper-source.json` | LGPL-3.0 |
| OpenXR loader/headers | [Pojlib 207b765b](https://github.com/QuestCraftPlusPlus/Pojlib/tree/207b765b31d112367e5639b0f1d96902fac8ccc0), Khronos OpenXR | Apache-2.0 |
| LWJGL OpenGLES/OpenXR bindings | See `JAVA-BINDINGS.md`; native OpenGLES from the same Pojlib revision | BSD-3-Clause |
| Pojlib JNI interface reference | Same Pojlib revision | LGPL-3.0 |

The launcher adapters are built from `FCL/src/main/jni/questvr`.
QuestCraft and QCXR informed the integration; their Unity runtime is not bundled.
Vivecraft is downloaded separately. LTW is excluded from this public build.

License texts and dependency notices in this directory are also included in
the APK under `assets/quest_vr/licenses`. glslang, SPIRV-Cross, xxhash, ska,
GLM and the NDK runtime retain their respective notices and licenses.
These notices supplement, and do not replace, the upstream credits in README.

Corresponding source, including the modified MobileGlues source and both
renderers' pinned dependencies, is provided alongside each APK on the
[release page](https://github.com/cff1028/FoldCraftLauncher/releases).
The launcher source is the release tag's Source code archive. See
`BUILDING.txt` at the repository root for rebuilding/relinking and installation.
You may modify/relink the LGPL libraries and debug those modifications under
their licenses; no launcher agreement restricts those rights.

Bundled native SHA-256:

- libmobileglues.so: `2e3a42a22ff509520a709c9300af7c1efd2d10f91398b41d41d8088fc82c857b`
- libopenxr_loader.so: `547bc81d961b6830c6e2e6d19fb417cf583ebcba0819bb26f84d9fbabf8acb97`
- liblwjgl_opengles.so: `437f171d392ba1d8153ba4a53d4e81cc9e5c8f89f4a70e9b26d97472cf3bd398`
