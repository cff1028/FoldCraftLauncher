MobileGlues 2.0.0 — corresponding source and rebuild

Upstream: https://github.com/MobileGL-Dev/MobileGlues
Revision: fbc4e412e353302607ba36489b2dd573b5becb25
License: LGPL 2.1 only; see ../MobileGlues-LICENSE and the fetched upstream LICENSE.
Dependency source trees retain their own copyright and license files.

The patch modifies gl/shader.cpp, gl/shader.h, gl/getter.cpp and egl/context.h
under MobileGlues-cpp. It corrects shader-source pointer counts and byte-length
semantics, input validation, allocation failures and context-scoped submission
errors. It preserves other upstream GL error handling and translated-source
query behavior. The changes were made on 2026-10-31.

provenance.json records all source revisions, archive hashes, modified-file
hashes, the build configuration and the bundled library hash. The unused
perfetto submodule is omitted because PROFILING is disabled upstream.

Fetch with Python 3.8+ and Git, using a short directory outside the launcher:

  python fetch-source.py --work-dir D:\mg-source

The fetcher verifies five archive hashes, rejects unsafe ZIP entries, applies
mobileglues-shader-count.patch and verifies all four modified files. It creates
D:\mg-source\MobileGlues-fbc4e412e353302607ba36489b2dd573b5becb25.
It does not alter the launcher or replace its packaged native library.

For an offline fetch, add --archive-dir PATH. The directory must contain
MobileGlues.zip (source.zip is also accepted), glslang.zip, SPIRV-Cross.zip,
xxhash.zip and ska.zip, or NAME-REVISION.zip for each component. Hashes must
match provenance.json. Existing source output is never overwritten.

Rebuild on Windows with NDK 27.0.12077973 and CMake 3.22.1 in the Android SDK:

  powershell -ExecutionPolicy Bypass -File .\build-mobileglues.ps1 -SdkRoot D:\android-sdk -SourceRoot D:\mg-source\MobileGlues-fbc4e412e353302607ba36489b2dd573b5becb25\MobileGlues-cpp

The script writes build-arm64 and dist beside the fetched source directory.
It uses arm64-v8a, API 21, static libc++, Release and upstream ThinLTO. Git
version discovery is disabled; exact SPIRV-Cross revision is recorded in the
manifest. SOURCE_DATE_EPOCH=1786287886 and TZ=UTC use the upstream commit time.
Toolchain and source-path differences may change binary bytes.

To use a rebuilt renderer, replace only:
FCL/src/main/jniLibs/arm64-v8a/libmobileglues.so

Bundled library: 6401936 bytes
SHA-256: 2e3a42a22ff509520a709c9300af7c1efd2d10f91398b41d41d8088fc82c857b
