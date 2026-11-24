// Sviluppo "neutro" dei RAW con LibRaw: WB di macchina, sRGB, 8 bit, metà risoluzione.
#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <cstdio>
#include <cstring>
#include <memory>
#include "libraw/libraw.h"

#define TAG "RawDecoder"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

static jobject makeBitmap(JNIEnv *env, const libraw_processed_image_t *img) {
    if (!img || img->type != LIBRAW_IMAGE_BITMAP || img->colors != 3 || img->bits != 8) return nullptr;
    jclass bmpCls = env->FindClass("android/graphics/Bitmap");
    jclass cfgCls = env->FindClass("android/graphics/Bitmap$Config");
    if (!bmpCls || !cfgCls) return nullptr;
    jmethodID create = env->GetStaticMethodID(
        bmpCls, "createBitmap", "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    jfieldID argbField = env->GetStaticFieldID(cfgCls, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
    jobject cfg = env->GetStaticObjectField(cfgCls, argbField);
    jobject bmp = env->CallStaticObjectMethod(bmpCls, create, (jint) img->width, (jint) img->height, cfg);
    if (env->ExceptionCheck() || !bmp) {
        env->ExceptionClear();
        return nullptr;
    }
    AndroidBitmapInfo info;
    void *pixels = nullptr;
    if (AndroidBitmap_getInfo(env, bmp, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        AndroidBitmap_lockPixels(env, bmp, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return nullptr;
    }
    const unsigned char *src = img->data;
    for (unsigned y = 0; y < info.height; y++) {
        auto *row = reinterpret_cast<unsigned char *>(pixels) + (size_t) y * info.stride;
        const unsigned char *s = src + (size_t) y * img->width * 3;
        for (unsigned x = 0; x < info.width; x++) {
            row[x * 4 + 0] = s[x * 3 + 0];
            row[x * 4 + 1] = s[x * 3 + 1];
            row[x * 4 + 2] = s[x * 3 + 2];
            row[x * 4 + 3] = 0xFF;
        }
    }
    AndroidBitmap_unlockPixels(env, bmp);
    return bmp;
}

static void setParams(LibRaw &lr, bool half) {
    auto &p = lr.imgdata.params;
    p.half_size = half ? 1 : 0;
    p.use_camera_wb = 1;
    p.use_camera_matrix = 1;
    p.output_color = 1;   // sRGB
    p.output_bps = 8;
    p.user_qual = 0;      // interpolazione più veloce (X-Trans 1 passata)
}

static jobject develop(JNIEnv *env, LibRaw &lr) {
    int rc = lr.unpack();
    if (rc != LIBRAW_SUCCESS) { LOGW("unpack: %s", libraw_strerror(rc)); return nullptr; }
    rc = lr.dcraw_process();
    if (rc != LIBRAW_SUCCESS) { LOGW("process: %s", libraw_strerror(rc)); return nullptr; }
    libraw_processed_image_t *img = lr.dcraw_make_mem_image(&rc);
    if (!img) { LOGW("mem_image: %s", libraw_strerror(rc)); return nullptr; }
    jobject bmp = makeBitmap(env, img);
    LibRaw::dcraw_clear_mem(img);
    return bmp;
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_hsteex_rawviewer_data_RawDecoder_nativeDecodeFd(JNIEnv *env, jobject, jint fd, jboolean half) {
    try {
        auto lr = std::make_unique<LibRaw>();
        setParams(*lr, half);
        char path[64];
        snprintf(path, sizeof(path), "/proc/self/fd/%d", fd);
        int rc = lr->open_file(path);
        if (rc != LIBRAW_SUCCESS) { LOGW("open_file: %s", libraw_strerror(rc)); return nullptr; }
        return develop(env, *lr);
    } catch (...) {
        LOGW("exception");
        return nullptr;
    }
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_hsteex_rawviewer_data_RawDecoder_nativeDecodeBuffer(JNIEnv *env, jobject, jobject buffer, jint len, jboolean half) {
    try {
        void *data = env->GetDirectBufferAddress(buffer);
        if (!data) return nullptr;
        auto lr = std::make_unique<LibRaw>();
        setParams(*lr, half);
        int rc = lr->open_buffer(data, (size_t) len);
        if (rc != LIBRAW_SUCCESS) { LOGW("open_buffer: %s", libraw_strerror(rc)); return nullptr; }
        return develop(env, *lr);
    } catch (...) {
        LOGW("exception");
        return nullptr;
    }
}
