// Android JNI adapter for the platform-neutral stickerwebp_core API.

#include <android/bitmap.h>
#include <jni.h>
#include <stdint.h>

#include "stickerwebp_core.h"

#define JNI(name) Java_com_royna_stickersftw_conversion_NativeWebpAnimEncoder_##name

static jbyteArray to_java_bytes(JNIEnv* env, SftwWebpData* data) {
    if (data == NULL || data->bytes == NULL || data->size == 0 || data->size > INT32_MAX) {
        return NULL;
    }
    jbyteArray output = (*env)->NewByteArray(env, (jsize) data->size);
    if (output != NULL) {
        (*env)->SetByteArrayRegion(
                env, output, 0, (jsize) data->size, (const jbyte*) data->bytes);
    }
    return output;
}

JNIEXPORT jlong JNICALL JNI(nativeCreate)(
        JNIEnv* env, jclass clazz, jint width, jint height, jint loop_count, jboolean minimize_size) {
    (void) env;
    (void) clazz;
    return (jlong) (intptr_t) sftw_webp_encoder_create(
            width, height, loop_count, minimize_size ? 1 : 0);
}

JNIEXPORT jboolean JNICALL JNI(nativeConfigure)(
        JNIEnv* env, jclass clazz, jlong handle, jfloat quality, jint alpha_quality, jint method) {
    (void) env;
    (void) clazz;
    return sftw_webp_encoder_configure(
            (SftwWebpEncoder*) (intptr_t) handle, quality, alpha_quality, method)
            ? JNI_TRUE
            : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL JNI(nativeAddFrame)(
        JNIEnv* env,
        jclass clazz,
        jlong handle,
        jobject bitmap,
        jboolean is_premultiplied,
        jint timestamp_ms) {
    (void) clazz;
    SftwWebpEncoder* encoder = (SftwWebpEncoder*) (intptr_t) handle;
    if (encoder == NULL || bitmap == NULL) return JNI_FALSE;

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return JNI_FALSE;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return JNI_FALSE;

    void* pixels = NULL;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return JNI_FALSE;
    }
    const int ok = sftw_webp_encoder_add_rgba(
            encoder,
            (const uint8_t*) pixels,
            (int) info.width,
            (int) info.height,
            (int) info.stride,
            is_premultiplied ? 1 : 0,
            timestamp_ms);
    AndroidBitmap_unlockPixels(env, bitmap);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jbyteArray JNICALL JNI(nativeAssemble)(
        JNIEnv* env, jclass clazz, jlong handle, jint total_duration_ms) {
    (void) clazz;
    SftwWebpData data = {0};
    if (!sftw_webp_encoder_assemble(
            (SftwWebpEncoder*) (intptr_t) handle, total_duration_ms, &data)) {
        return NULL;
    }
    jbyteArray output = to_java_bytes(env, &data);
    sftw_webp_data_clear(&data);
    return output;
}

JNIEXPORT jbyteArray JNICALL JNI(nativeEncodeRepeatedFrame)(
        JNIEnv* env,
        jclass clazz,
        jobject bitmap,
        jboolean is_premultiplied,
        jint frame_duration_ms,
        jint loop_count,
        jfloat quality,
        jint alpha_quality,
        jint method) {
    (void) clazz;
    if (bitmap == NULL) return NULL;

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return NULL;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return NULL;

    void* pixels = NULL;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) return NULL;
    SftwWebpData data = {0};
    const int ok = sftw_webp_encode_repeated_rgba(
            (const uint8_t*) pixels,
            (int) info.width,
            (int) info.height,
            (int) info.stride,
            is_premultiplied ? 1 : 0,
            frame_duration_ms,
            loop_count,
            quality,
            alpha_quality,
            method,
            &data);
    AndroidBitmap_unlockPixels(env, bitmap);
    if (!ok) return NULL;

    jbyteArray output = to_java_bytes(env, &data);
    sftw_webp_data_clear(&data);
    return output;
}

JNIEXPORT void JNICALL JNI(nativeRelease)(JNIEnv* env, jclass clazz, jlong handle) {
    (void) env;
    (void) clazz;
    sftw_webp_encoder_delete((SftwWebpEncoder*) (intptr_t) handle);
}
