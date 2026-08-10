// JNI over libwebp's animation encoder.
//
// Android can decode animated WebP but cannot encode it -- Bitmap.compress
// writes a single frame -- so an animated sticker needs libwebp's
// WebPAnimEncoder either way. This replaces a third-party AAR whose
// prebuilt .so files were linked at a 4KB page size and therefore will not
// load on a 16KB-page device.
//
// Deliberately narrow: three entry points covering exactly what
// WebpAnimationEncoder.kt calls. The AAR it replaces also shipped a decoder,
// a Drawable and a Compose surface, none of which this app ever used.

#include <android/bitmap.h>
#include <jni.h>
#include <stdlib.h>
#include <string.h>

#include "webp/encode.h"
#include "webp/mux.h"

typedef struct {
    WebPAnimEncoder* encoder;
    WebPConfig config;
    int width;
    int height;
} Encoder;

#define JNI(name) Java_com_royna_stickersftw_conversion_NativeWebpAnimEncoder_##name

JNIEXPORT jlong JNICALL JNI(nativeCreate)(
        JNIEnv* env, jclass clazz, jint width, jint height, jint loopCount, jboolean minimizeSize) {
    (void) env;
    (void) clazz;
    if (width <= 0 || height <= 0) return 0;

    WebPAnimEncoderOptions options;
    if (!WebPAnimEncoderOptionsInit(&options)) return 0;
    options.anim_params.loop_count = loopCount;
    options.minimize_size = minimizeSize ? 1 : 0;

    Encoder* state = (Encoder*) calloc(1, sizeof(Encoder));
    if (state == NULL) return 0;

    state->encoder = WebPAnimEncoderNew(width, height, &options);
    if (state->encoder == NULL) {
        free(state);
        return 0;
    }
    state->width = width;
    state->height = height;

    if (!WebPConfigInit(&state->config)) {
        WebPAnimEncoderDelete(state->encoder);
        free(state);
        return 0;
    }
    return (jlong) (intptr_t) state;
}

JNIEXPORT jboolean JNICALL JNI(nativeConfigure)(
        JNIEnv* env, jclass clazz, jlong handle, jfloat quality, jint alphaQuality, jint method) {
    (void) env;
    (void) clazz;
    Encoder* state = (Encoder*) (intptr_t) handle;
    if (state == NULL) return JNI_FALSE;

    state->config.quality = quality;
    state->config.alpha_quality = alphaQuality;
    state->config.method = method;
    state->config.lossless = 0;
    return WebPValidateConfig(&state->config) ? JNI_TRUE : JNI_FALSE;
}

// Bitmaps arrive premultiplied (Bitmap.Config.ARGB_8888 always is), and
// libwebp expects straight alpha. Undoing it per pixel here rather than in
// Kotlin keeps a whole extra Bitmap copy out of the conversion, which matters
// when a pack is 90 frames of 512x512.
static void unpremultiply(uint8_t* rgba, size_t pixels) {
    for (size_t i = 0; i < pixels; ++i) {
        uint8_t* p = rgba + i * 4;
        const uint32_t a = p[3];
        if (a == 0) {
            p[0] = p[1] = p[2] = 0;
        } else if (a < 255) {
            p[0] = (uint8_t) ((p[0] * 255 + a / 2) / a);
            p[1] = (uint8_t) ((p[1] * 255 + a / 2) / a);
            p[2] = (uint8_t) ((p[2] * 255 + a / 2) / a);
        }
    }
}

JNIEXPORT jboolean JNICALL JNI(nativeAddFrame)(
        JNIEnv* env, jclass clazz, jlong handle, jobject bitmap, jint timestampMs) {
    (void) clazz;
    Encoder* state = (Encoder*) (intptr_t) handle;
    if (state == NULL || bitmap == NULL) return JNI_FALSE;

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return JNI_FALSE;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return JNI_FALSE;
    if ((int) info.width != state->width || (int) info.height != state->height) return JNI_FALSE;

    void* pixels = NULL;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) return JNI_FALSE;

    jboolean ok = JNI_FALSE;
    WebPPicture picture;
    if (WebPPictureInit(&picture)) {
        picture.use_argb = 1;
        picture.width = state->width;
        picture.height = state->height;

        const size_t count = (size_t) state->width * (size_t) state->height;
        uint8_t* rgba = (uint8_t*) malloc(count * 4);
        if (rgba != NULL) {
            // Row by row: the bitmap's stride is not necessarily width * 4.
            for (int y = 0; y < state->height; ++y) {
                memcpy(rgba + (size_t) y * state->width * 4,
                       (uint8_t*) pixels + (size_t) y * info.stride,
                       (size_t) state->width * 4);
            }
            unpremultiply(rgba, count);
            if (WebPPictureImportRGBA(&picture, rgba, state->width * 4)) {
                ok = WebPAnimEncoderAdd(state->encoder, &picture, timestampMs, &state->config)
                     ? JNI_TRUE : JNI_FALSE;
            }
            free(rgba);
        }
        WebPPictureFree(&picture);
    }

    AndroidBitmap_unlockPixels(env, bitmap);
    return ok;
}

JNIEXPORT jbyteArray JNICALL JNI(nativeAssemble)(
        JNIEnv* env, jclass clazz, jlong handle, jint totalDurationMs) {
    (void) clazz;
    Encoder* state = (Encoder*) (intptr_t) handle;
    if (state == NULL) return NULL;

    // A final frame at the total duration is what gives the last real frame
    // its on-screen time; without it the animation ends the instant that
    // frame is drawn.
    if (!WebPAnimEncoderAdd(state->encoder, NULL, totalDurationMs, NULL)) return NULL;

    WebPData data;
    WebPDataInit(&data);
    if (!WebPAnimEncoderAssemble(state->encoder, &data)) {
        WebPDataClear(&data);
        return NULL;
    }

    jbyteArray out = (*env)->NewByteArray(env, (jsize) data.size);
    if (out != NULL) {
        (*env)->SetByteArrayRegion(env, out, 0, (jsize) data.size, (const jbyte*) data.bytes);
    }
    WebPDataClear(&data);
    return out;
}

JNIEXPORT void JNICALL JNI(nativeRelease)(JNIEnv* env, jclass clazz, jlong handle) {
    (void) env;
    (void) clazz;
    Encoder* state = (Encoder*) (intptr_t) handle;
    if (state == NULL) return;
    WebPAnimEncoderDelete(state->encoder);
    free(state);
}
