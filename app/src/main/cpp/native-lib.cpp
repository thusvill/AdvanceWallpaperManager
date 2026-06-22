#include <jni.h>
#include <string>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <cstring>
#include <algorithm>

#define LOG_TAG "NativeRenderer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

inline uint32_t blend(uint32_t s, uint32_t d) {
    uint8_t a = (s >> 24) & 0xFF;
    if (a == 0) return d;
    if (a == 255) return s;

    uint32_t rb = ((((s & 0x00FF00FF) * a) + ((d & 0x00FF00FF) * (255 - a))) >> 8) & 0x00FF00FF;
    uint32_t g  = ((((s & 0x0000FF00) * a) + ((d & 0x0000FF00) * (255 - a))) >> 8) & 0x0000FF00;
    return 0xFF000000 | rb | g;
}

void drawBitmap(ANativeWindow_Buffer* buffer, JNIEnv* env, jobject bitmap, int offsetX, int offsetY, bool useBlending) {
    if (!bitmap) return;
    AndroidBitmapInfo info;
    void* pixels;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != 0 || AndroidBitmap_lockPixels(env, bitmap, &pixels) != 0) {
        return;
    }

    uint32_t* destBase = (uint32_t*)buffer->bits;
    uint32_t* srcBase = (uint32_t*)pixels;
    uint32_t srcStride = info.stride / 4;

    int startY = std::max(0, offsetY);
    int endY = std::min((int)buffer->height, offsetY + (int)info.height);
    int startX = std::max(0, offsetX);
    int endX = std::min((int)buffer->width, offsetX + (int)info.width);

    for (int y = startY; y < endY; ++y) {
        uint32_t* dRow = destBase + y * buffer->stride;
        uint32_t* sRow = srcBase + (y - offsetY) * srcStride;
        for (int x = startX; x < endX; ++x) {
            uint32_t sPix = sRow[x - offsetX];
            if (useBlending) {
                dRow[x] = blend(sPix, dRow[x]);
            } else {
                dRow[x] = sPix;
            }
        }
    }
    AndroidBitmap_unlockPixels(env, bitmap);
}

extern "C" JNIEXPORT void JNICALL
Java_com_thusvill_advancewallpapermanager_CustomDepthWallpaperService_renderNativeFrame(
        JNIEnv* env,
        jobject thiz,
        jobject surface,
        jstring time_text,
        jobject base_bitmap,
        jobject mask_bitmap,
        jobject time_bitmap,
        jfloat clock_x,
        jfloat clock_y) {

    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (!window) return;

    int32_t w = ANativeWindow_getWidth(window);
    int32_t h = ANativeWindow_getHeight(window);
    ANativeWindow_setBuffersGeometry(window, w, h, WINDOW_FORMAT_RGBA_8888);

    ANativeWindow_Buffer buffer;
    if (ANativeWindow_lock(window, &buffer, nullptr) < 0) {
        ANativeWindow_release(window);
        return;
    }

    if (base_bitmap) {
        drawBitmap(&buffer, env, base_bitmap, 0, 0, false);
    }

    if (time_bitmap) {
        AndroidBitmapInfo tInfo;
        AndroidBitmap_getInfo(env, time_bitmap, &tInfo);
        int tx = (int)(w * clock_x) - (int)(tInfo.width / 2);
        int ty = (int)(h * clock_y) - (int)(tInfo.height / 2);
        drawBitmap(&buffer, env, time_bitmap, tx, ty, true);
    }

    if (mask_bitmap) {
        drawBitmap(&buffer, env, mask_bitmap, 0, 0, true);
    }

    ANativeWindow_unlockAndPost(window);
    ANativeWindow_release(window);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_thusvill_advancewallpapermanager_MainActivity_extractMaskNative(
        JNIEnv* env,
        jobject thiz,
        jobject original_bitmap,
        jobject mask_buffer,
        jint mask_w,
        jint mask_h,
        jobject output_bitmap) {

    AndroidBitmapInfo origInfo;
    void* origPixels;
    if (AndroidBitmap_getInfo(env, original_bitmap, &origInfo) < 0 ||
        AndroidBitmap_lockPixels(env, original_bitmap, &origPixels) < 0) {
        return JNI_FALSE;
    }

    AndroidBitmapInfo outInfo;
    void* outPixels;
    if (AndroidBitmap_getInfo(env, output_bitmap, &outInfo) < 0 ||
        AndroidBitmap_lockPixels(env, output_bitmap, &outPixels) < 0) {
        AndroidBitmap_unlockPixels(env, original_bitmap);
        return JNI_FALSE;
    }

    float* mask = (float*)env->GetDirectBufferAddress(mask_buffer);
    if (!mask) {
        AndroidBitmap_unlockPixels(env, original_bitmap);
        AndroidBitmap_unlockPixels(env, output_bitmap);
        return JNI_FALSE;
    }

    uint32_t* srcBase = (uint32_t*)origPixels;
    uint32_t* dstBase = (uint32_t*)outPixels;
    uint32_t srcStride = origInfo.stride / 4;
    uint32_t dstStride = outInfo.stride / 4;

    int width = (int)origInfo.width;
    int height = (int)origInfo.height;

    // Scale factors from Original to Mask coordinates
    float scaleX = (float)mask_w / (float)width;
    float scaleY = (float)mask_h / (float)height;

    for (int y = 0; y < height; ++y) {
        uint32_t* sRow = srcBase + y * srcStride;
        uint32_t* dRow = dstBase + y * dstStride;

        int maskY = (int)((float)y * scaleY);
        if (maskY >= mask_h) maskY = mask_h - 1;
        float* maskRow = mask + maskY * mask_w;

        for (int x = 0; x < width; ++x) {
            int maskX = (int)((float)x * scaleX);
            if (maskX >= mask_w) maskX = mask_w - 1;

            if (maskRow[maskX] > 0.5f) {
                dRow[x] = sRow[x];
            } else {
                dRow[x] = 0x00000000;
            }
        }
    }

    AndroidBitmap_unlockPixels(env, original_bitmap);
    AndroidBitmap_unlockPixels(env, output_bitmap);

    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_thusvill_advancewallpapermanager_MainActivity_extractEdgesNative(
        JNIEnv* env,
        jobject thiz,
        jobject original_bitmap,
        jobject output_bitmap,
        jfloat threshold) {

    AndroidBitmapInfo info;
    void* pixels;
    if (AndroidBitmap_getInfo(env, original_bitmap, &info) < 0 ||
        AndroidBitmap_lockPixels(env, original_bitmap, &pixels) < 0) {
        return JNI_FALSE;
    }

    void* outPixels;
    if (AndroidBitmap_lockPixels(env, output_bitmap, &outPixels) < 0) {
        AndroidBitmap_unlockPixels(env, original_bitmap);
        return JNI_FALSE;
    }

    int w = (int)info.width;
    int h = (int)info.height;
    uint32_t stride = info.stride / 4;

    uint32_t* src = (uint32_t*)pixels;
    uint32_t* dst = (uint32_t*)outPixels;

    // Gray scale buffer for processing
    uint8_t* gray = (uint8_t*)malloc(w * h);
    for (int i = 0; i < w * h; ++i) {
        uint32_t p = src[i];
        uint8_t r = (p >> 16) & 0xFF;
        uint8_t g = (p >> 8) & 0xFF;
        uint8_t b = p & 0xFF;
        gray[i] = (uint8_t)(0.299f * r + 0.587f * g + 0.114f * b);
    }

    // Sobel Operator
    int gx[3][3] = {{-1, 0, 1}, {-2, 0, 2}, {-1, 0, 1}};
    int gy[3][3] = {{-1, -2, -1}, {0, 0, 0}, {1, 2, 1}};

    for (int y = 1; y < h - 1; ++y) {
        for (int x = 1; x < w - 1; ++x) {
            int sumX = 0;
            int sumY = 0;
            for (int ky = -1; ky <= 1; ++ky) {
                for (int kx = -1; kx <= 1; ++kx) {
                    uint8_t val = gray[(y + ky) * w + (x + kx)];
                    sumX += val * gx[ky + 1][kx + 1];
                    sumY += val * gy[ky + 1][kx + 1];
                }
            }
            int mag = abs(sumX) + abs(sumY);
            if (mag > (int)(threshold * 255.0f)) {
                dst[y * stride + x] = src[y * stride + x];
            } else {
                dst[y * stride + x] = 0x00000000;
            }
        }
    }

    free(gray);
    AndroidBitmap_unlockPixels(env, original_bitmap);
    AndroidBitmap_unlockPixels(env, output_bitmap);
    return JNI_TRUE;
}
