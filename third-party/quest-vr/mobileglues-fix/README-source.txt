MobileGlues 2.0.1 Dev1 — corresponding source and rebuild

Upstream: https://github.com/MobileGL-Dev/MobileGlues
Revision: fcdf91456c1b3b5e77e5264cffe682595c13113f
License: LGPL 2.1 only; see ../MobileGlues-LICENSE and the fetched upstream LICENSE.
Dependency trees retain their copyright and license files.

This revision includes the official Minecraft 26.3 uniform parsing/default-value
fixes from 7bb2325 and the renderer/context changes through fcdf914. The supplied
reference plugin exhibits these fixes; its exact build revision was not verified.

Local modifications by cff1028, 2026-10-31 to 2026-11-06, preserve shader-source
pointer-count/byte-length semantics, validation, allocation guards, context-local
submission errors and renderer-scoped procedure lookup. Uniform defaults are
share-group scoped, follow successful compilation and deferred deletion, and are
applied only after successful links without changing the current program binding.
All modified/new files and their hashes are listed in provenance.json.
Unrelated upstream GL error and translated-source query behavior is preserved.

Quest uses GLES 3.2. If another device lacks the GLES 3.1 program-uniform functions
required by nonzero defaults, this build rejects that link with GL_INVALID_OPERATION
before replacing the linked executable. It does not temporarily unbind a program
marked for deletion. Backend allocation failures remain subject to upstream behavior.

provenance.json records source/dependency revisions, archive and patched-file
hashes, build settings and the bundled library hash. PROFILING is disabled, so the
unused perfetto submodule is omitted.

Fetch with Python 3.9+ and Git into a short path outside the launcher:

  python fetch-source.py --work-dir D:\mg-source

The fetcher validates archive paths/hashes, applies mobileglues-quest-compat.patch
and validates every patched file. It never overwrites an existing source output.
For offline use add --archive-dir PATH containing NAME-REVISION.zip for each
component (or MobileGlues.zip, glslang.zip, SPIRV-Cross.zip, xxhash.zip and ska.zip).

Rebuild on Windows with NDK 27.0.12077973 and CMake 3.22.1 in the Android SDK:

  powershell -ExecutionPolicy Bypass -File .\build-mobileglues.ps1 -SdkRoot D:\android-sdk -SourceRoot D:\mg-source\MobileGlues-fcdf91456c1b3b5e77e5264cffe682595c13113f\MobileGlues-cpp

The script writes build-arm64 and dist beside the fetched source directory.
It uses arm64-v8a, API 21, static libc++, Release and upstream ThinLTO, with Git
version discovery disabled. SOURCE_DATE_EPOCH=1788669897 and TZ=UTC use the pinned
commit time. Toolchain and source-path differences may change binary bytes.

To relink, replace FCL/src/main/jniLibs/arm64-v8a/libmobileglues.so and rebuild FCL
as described in BUILDING.txt. The source ZIP includes these instructions in rebuild/.

Bundled library: 6468856 bytes
SHA-256: fc768f95c8d6018072e2676071f400fc30fbf86948e5bc7706e801b9fb79c3b3
