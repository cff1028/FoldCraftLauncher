#ifndef FCL_QUEST_VR_STEREO_SYNC_H
#define FCL_QUEST_VR_STEREO_SYNC_H

#include <EGL/egl.h>
#include <stdbool.h>
#include "openxr/openxr.h"

void quest_vr_stereo_session(XrInstance instance, XrSession session,
                             EGLDisplay display, EGLContext context);
void quest_vr_stereo_running(XrSession session, bool running);
void quest_vr_stereo_forget(XrInstance instance, XrSession session);
void quest_vr_stereo_before_release(XrSession session);

#endif
