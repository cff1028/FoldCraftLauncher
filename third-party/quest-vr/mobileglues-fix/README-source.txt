MobileGlues 2.0.1 Dev1 — corresponding source and rebuild

Upstream: https://github.com/MobileGL-Dev/MobileGlues
Revision: fcdf91456c1b3b5e77e5264cffe682595c13113f
License: LGPL 2.1 only; see ../MobileGlues-LICENSE and the fetched upstream LICENSE.
Dependency trees retain their copyright and license files.

This revision includes the official Minecraft 26.3 uniform parsing/default-value
fixes from 7bb2325 and the renderer/context changes through fcdf914. The supplied
reference plugin exhibits these fixes; its exact build revision was not verified.

Local modifications by cff1028, 2026-10-31 to 2026-11-13, preserve shader-source
pointer-count/byte-length semantics, validation, allocation guards, context-local
submission errors and renderer-scoped procedure lookup. Uniform defaults are
share-group scoped, follow successful compilation and deferred deletion, and are
applied only after successful links without changing the current program binding.
All modified/new files and their hashes are listed in provenance.json.
Default upstream GL error and translated-source query behavior is preserved.

Local build vrm-mipmap-20261113-2 retains desktop glDepthRange via GLES. Double
depth-range/clear values are clamped before float conversion; reversed and equal
depth ranges are retained. State remains owned by the current GLES context.
Unsized depth allocations are resolved to sized storage for depth blits. Depth
uploads convert the actual client/PBO data when GLES requires another scalar
type, including later subimage updates, while restoring pixel-unpack state.

Optional diagnostics: set MG_BACKEND_DIAGNOSTICS=1 before game launch. Default is
off. Depth/stencil blits have separate success/error budgets and include actual
attachment formats, dimensions and copy rectangles, independent of normal draw
logging. [MG-BACKEND] separates prior, operation and probe errors and identifies
the current context. This mode consumes backend errors and may affect internal
error checks/timing; use only for diagnosis. It does not touch the separate shader
error channel and restores any temporary bindings used to inspect attachments.

Framebuffer routing preserves desktop fragment-output semantics, including
single glDrawBuffer attachment selection, logical draw/read-buffer queries and
attachment queries after remapping. Default back-left attachment queries map to
the GLES back buffer. Fragment and combined sampler limits report actual GLES
capability bounded by the implementation's tracked texture units.
Attachment records commit only after the backend accepts the operation. The
bounded before/after error checks preserve errors in strict diagnostic mode;
default mode retains the legacy error-discarding policy.

GL_SRGB8 mipmap compatibility uses a temporary SRGB8_ALPHA8 texture for GPU
reduction and copies the generated levels back into the original SRGB8 texture.
The original object, base image, opaque RGB alpha, sRGB encoding and user sampler
settings are retained. Supported targets are 2D, cube, 2D-array and 3D, including
immutable storage, base/max-level ranges and the named-texture entry point.
Other formats use native generation. The fallback requires GLES 3.1 level queries;
cube-map arrays retain the previous native behavior. Temporary GL bindings and
draw state are restored. Active occlusion queries or a current program pending
deletion are rejected conservatively, avoiding damaged state or query results.
Failure can leave only part of the requested lower mip chain generated; the base
image is not overwritten. This is not a promise of atomic texture replacement.

MG_VRM_DIAGNOSTICS=1 records indexed draws using the VRM material uniforms:
logical/driver output targets, material/UV uniforms, texture mip levels and
samplers, vertex attributes and draw state. Sampling is bounded per model proxy
and material-feature state, with a process-wide sample/log-size limit. New models
can therefore be sampled after the initial model; name-reuse detection uses an
idle-time heuristic, not complete object-lifetime tracking. The [MG-VRM] records
restore temporary bindings. This mode performs no error reads, image readbacks
or shader changes. It is off by default and adds query/logging overhead while
active. Synthetic coverage does not prove an actual Quest rendering fault.
Budgets are 24 states per context/program/VAO and material-feature setting,
512 samples and 4 MiB of formatted stdout records per process. Idle reuse is
reconsidered after 30 seconds. A budget notice indicates capture suppression.

MG_STRICT_ERRORS=1 is a separate raw-error diagnostic mode, off by default. It
preserves context-local frontend, shader and backend error flags for glGetError,
even when a shader error would normally be muted. It also exposes real backend
errors from internal fallback attempts, including successful fallback attempts;
this is not a promise of complete desktop OpenGL error emulation. It does not
change the configured compile/link-status policy. MG_BACKEND_DIAGNOSTICS error
draining is disabled in this mode. Use MG_VRM_DIAGNOSTICS alone for the first
comparison; strict errors may change application control flow.

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

Bundled library: 6528552 bytes
SHA-256: d4b79f0728af69ab114940957d37638bdc8c8f03365e14207d1e3a83f5d99695
