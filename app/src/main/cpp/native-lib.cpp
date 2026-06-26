#include <algorithm>
#include <android/bitmap.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <cmath>
#include <cstring>
#include <jni.h>
#include <string>
#include <vector>

#define LOG_TAG "NativeRenderer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Use Fixed-point math for coordinate steps (16.16)
#define FP_SHIFT 16
#define FP_SCALE (1 << FP_SHIFT)

inline uint32_t blend(uint32_t s, uint32_t d) {
  uint8_t a = (s >> 24) & 0xFF;
  if (a == 0) return d;
  if (a == 255) return s;

  // Fast blending using integer arithmetic
  uint32_t rb = ((((s & 0x00FF00FF) * a) + ((d & 0x00FF00FF) * (255 - a))) >> 8) & 0x00FF00FF;
  uint32_t g = ((((s & 0x0000FF00) * a) + ((d & 0x0000FF00) * (255 - a))) >> 8) & 0x0000FF00;
  return 0xFF000000 | rb | g;
}

/**
 * Optimized Fast Sampler (Nearest Neighbor)
 */
void drawBitmapFast(ANativeWindow_Buffer *buffer, JNIEnv *env, jobject bitmap,
                    int offsetX, int offsetY, bool useBlending, float scale) {
    if (!bitmap) return;
    AndroidBitmapInfo info;
    void *pixels;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != 0 ||
        AndroidBitmap_lockPixels(env, bitmap, &pixels) != 0) return;

    uint32_t *destBase = (uint32_t *)buffer->bits;
    uint32_t *srcBase = (uint32_t *)pixels;
    uint32_t srcStride = info.stride / 4;

    int sw = (int)info.width;
    int sh = (int)info.height;
    int dw = (int)(sw * scale);
    int dh = (int)(sh * scale);

    int startY = std::max(0, offsetY);
    int endY = std::min((int)buffer->height, offsetY + dh);
    int startX = std::max(0, offsetX);
    int endX = std::min((int)buffer->width, offsetX + dw);

    if (scale == 1.0f) {
        for (int y = startY; y < endY; ++y) {
            uint32_t *dRow = destBase + y * buffer->stride;
            int srcY = y - offsetY;
            if (srcY < 0 || srcY >= sh) continue;
            uint32_t *sRow = srcBase + srcY * srcStride;

            if (useBlending) {
                for (int x = startX; x < endX; ++x) {
                    int sx = x - offsetX;
                    if (sx >= 0 && sx < sw) {
                        dRow[x] = blend(sRow[sx], dRow[x]);
                    }
                }
            } else {
                int xOffsetInSrc = startX - offsetX;
                if (xOffsetInSrc >= 0 && xOffsetInSrc < sw) {
                    int copyLen = std::min(endX - startX, sw - xOffsetInSrc);
                    if (copyLen > 0) {
                        memcpy(dRow + startX, sRow + xOffsetInSrc, copyLen * 4);
                    }
                }
            }
        }
    } else {
        int step = (int)((1.0f / scale) * FP_SCALE);
        int srcX_start_fp = (startX > offsetX) ? ((startX - offsetX) * step) : 0;

        for (int y = startY; y < endY; ++y) {
            uint32_t *dRow = destBase + y * buffer->stride;
            int srcY = ((y - offsetY) * step) >> FP_SHIFT;
            if (srcY < 0) srcY = 0; else if (srcY >= sh) srcY = sh - 1;
            uint32_t *sRow = srcBase + srcY * srcStride;

            int srcX_fp = srcX_start_fp;
            if (useBlending) {
                for (int x = startX; x < endX; ++x) {
                    int sx = srcX_fp >> FP_SHIFT;
                    if (sx >= 0 && sx < sw) {
                        dRow[x] = blend(sRow[sx], dRow[x]);
                    }
                    srcX_fp += step;
                }
            } else {
                for (int x = startX; x < endX; ++x) {
                    int sx = srcX_fp >> FP_SHIFT;
                    if (sx >= 0 && sx < sw) {
                        dRow[x] = sRow[sx];
                    }
                    srcX_fp += step;
                }
            }
        }
    }
    AndroidBitmap_unlockPixels(env, bitmap);
}

void internalRenderFrame(JNIEnv *env, jobject surface, jobject base_bitmap,
                         jobject mask_bitmap, jobject time_bitmap,
                         jfloat clock_x, jfloat clock_y,
                         jfloat wallpaper_scale, jfloat offset_x, jfloat offset_y) {
    ANativeWindow *window = ANativeWindow_fromSurface(env, surface);
    if (!window) return;
    int32_t w = ANativeWindow_getWidth(window);
    int32_t h = ANativeWindow_getHeight(window);
    ANativeWindow_setBuffersGeometry(window, w, h, WINDOW_FORMAT_RGBA_8888);
    ANativeWindow_Buffer buffer;
    if (ANativeWindow_lock(window, &buffer, nullptr) < 0) {
        ANativeWindow_release(window);
        return;
    }

    bool needsClear = true;
    if (base_bitmap) {
        AndroidBitmapInfo bInfo;
        AndroidBitmap_getInfo(env, base_bitmap, &bInfo);
        if (offset_x <= 0 && offset_y <= 0 && (offset_x + bInfo.width * wallpaper_scale) >= w && (offset_y + bInfo.height * wallpaper_scale) >= h) needsClear = false;
    }
    if (needsClear) memset(buffer.bits, 0, buffer.stride * buffer.height * 4);

    if (base_bitmap) drawBitmapFast(&buffer, env, base_bitmap, (int)offset_x, (int)offset_y, false, wallpaper_scale);
    if (time_bitmap) {
        AndroidBitmapInfo tInfo;
        AndroidBitmap_getInfo(env, time_bitmap, &tInfo);
        drawBitmapFast(&buffer, env, time_bitmap, (int)(w * clock_x - (int)tInfo.width / 2), (int)(h * clock_y - (int)tInfo.height / 2), true, 1.0f);
    }
    if (mask_bitmap) drawBitmapFast(&buffer, env, mask_bitmap, (int)offset_x, (int)offset_y, true, wallpaper_scale);

    ANativeWindow_unlockAndPost(window);
    ANativeWindow_release(window);
}

extern "C" JNIEXPORT void JNICALL
Java_com_thusvill_advancewallpapermanager_CustomDepthWallpaperService_renderNativeFrame(
    JNIEnv *env, jobject thiz, jobject surface, jstring time_text,
    jobject base_bitmap, jobject mask_bitmap, jobject time_bitmap,
    jfloat clock_x, jfloat clock_y, jfloat wallpaper_scale, jfloat offset_x, jfloat offset_y) {
  internalRenderFrame(env, surface, base_bitmap, mask_bitmap, time_bitmap,
                      clock_x, clock_y, wallpaper_scale, offset_x, offset_y);
}

extern "C" JNIEXPORT void JNICALL
Java_com_thusvill_advancewallpapermanager_NativeLib_renderNativeFrame(
    JNIEnv *env, jobject thiz, jobject surface, jstring time_text,
    jobject base_bitmap, jobject mask_bitmap, jobject time_bitmap,
    jfloat clock_x, jfloat clock_y, jfloat wallpaper_scale, jfloat offset_x, jfloat offset_y) {
  internalRenderFrame(env, surface, base_bitmap, mask_bitmap, time_bitmap,
                      clock_x, clock_y, wallpaper_scale, offset_x, offset_y);
}

/**
 * Professional Bilinear Mask Sampler
 */
inline float sampleMaskBilinear(const float *mask, int mw, int mh, float fx, float fy) {
    if (fx < 0) fx = 0; if (fx > mw - 1) fx = mw - 1;
    if (fy < 0) fy = 0; if (fy > mh - 1) fy = mh - 1;

    int x1 = (int)fx;
    int y1 = (int)fy;
    int x2 = std::min(x1 + 1, mw - 1);
    int y2 = std::min(y1 + 1, mh - 1);

    float dx = fx - (float)x1;
    float dy = fy - (float)y1;

    float v1 = mask[y1 * mw + x1];
    float v2 = mask[y1 * mw + x2];
    float v3 = mask[y2 * mw + x1];
    float v4 = mask[y2 * mw + x2];

    return v1 * (1.0f - dx) * (1.0f - dy) + v2 * dx * (1.0f - dy) + v3 * (1.0f - dx) * dy + v4 * dx * dy;
}

/**
 * High-Quality Box Blur on Alpha Channel
 */
void applyAlphaSoftener(uint32_t *pixels, int w, int h, int radius) {
    if (radius <= 0) return;
    std::vector<uint8_t> alpha(w * h);
    for (int i = 0; i < w * h; ++i) alpha[i] = (pixels[i] >> 24) & 0xFF;

    std::vector<uint8_t> temp(w * h);
    for (int pass = 0; pass < 3; ++pass) {
        // Horizontal
        for (int y = 0; y < h; ++y) {
            int rowOff = y * w;
            for (int x = 0; x < w; ++x) {
                int sum = 0;
                int count = 0;
                for (int k = -radius; k <= radius; ++k) {
                    int nx = x + k;
                    if (nx >= 0 && nx < w) {
                        sum += alpha[rowOff + nx];
                        count++;
                    }
                }
                temp[rowOff + x] = sum / count;
            }
        }
        // Vertical
        for (int x = 0; x < w; ++x) {
            for (int y = 0; y < h; ++y) {
                int sum = 0;
                int count = 0;
                for (int k = -radius; k <= radius; ++k) {
                    int ny = y + k;
                    if (ny >= 0 && ny < h) {
                        sum += temp[ny * w + x];
                        count++;
                    }
                }
                alpha[y * w + x] = sum / count;
            }
        }
    }

    for (int i = 0; i < w * h; ++i) {
        pixels[i] = (alpha[i] << 24) | (pixels[i] & 0x00FFFFFF);
    }
}

void internalExtractMask(JNIEnv *env, jobject original_bitmap, jobject mask_buffer,
                        jint mask_w, jint mask_h, jobject output_bitmap,
                        float threshold, int feather_radius) {
    if (!original_bitmap || !mask_buffer || !output_bitmap) return;

    AndroidBitmapInfo origInfo, outInfo;
    void *origPixels, *outPixels;
    if (AndroidBitmap_getInfo(env, original_bitmap, &origInfo) < 0 || AndroidBitmap_lockPixels(env, original_bitmap, &origPixels) < 0) return;
    if (AndroidBitmap_getInfo(env, output_bitmap, &outInfo) < 0 || AndroidBitmap_lockPixels(env, output_bitmap, &outPixels) < 0) {
        AndroidBitmap_unlockPixels(env, original_bitmap); return;
    }

    float *mask = (float *)env->GetDirectBufferAddress(mask_buffer);
    jlong capacity = env->GetDirectBufferCapacity(mask_buffer);

    if (!mask || capacity < (jlong)mask_w * mask_h) {
        AndroidBitmap_unlockPixels(env, original_bitmap);
        AndroidBitmap_unlockPixels(env, output_bitmap);
        return;
    }

    uint32_t *src = (uint32_t *)origPixels;
    uint32_t *dst = (uint32_t *)outPixels;
    int width = (int)origInfo.width;
    int height = (int)origInfo.height;
    float scaleX = (float)mask_w / (float)width;
    float scaleY = (float)mask_h / (float)height;

    for (int y = 0; y < height; ++y) {
        uint32_t *sRow = src + y * (origInfo.stride / 4);
        uint32_t *dRow = dst + y * (outInfo.stride / 4);
        float fy = (float)y * scaleY;
        for (int x = 0; x < width; ++x) {
            float fx = (float)x * scaleX;
            float val = sampleMaskBilinear(mask, mask_w, mask_h, fx, fy);
            if (val > threshold) {
                uint32_t pix = sRow[x];
                float alphaFactor = std::min(1.0f, (val - threshold) / (1.0f - threshold));
                uint8_t a = (uint8_t)(alphaFactor * 255.0f);
                dRow[x] = (a << 24) | (pix & 0x00FFFFFF);
            } else {
                dRow[x] = 0;
            }
        }
    }

    if (feather_radius > 0) {
        applyAlphaSoftener(dst, width, height, feather_radius);
    }

    AndroidBitmap_unlockPixels(env, original_bitmap);
    AndroidBitmap_unlockPixels(env, output_bitmap);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_thusvill_advancewallpapermanager_NativeLib_extractMaskNative(
    JNIEnv *env, jobject thiz, jobject original_bitmap, jobject mask_buffer,
    jint mask_w, jint mask_h, jobject output_bitmap) {
    internalExtractMask(env, original_bitmap, mask_buffer, mask_w, mask_h, output_bitmap, 0.5f, 0);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_thusvill_advancewallpapermanager_NativeLib_extractSaliencyMatteNative(
    JNIEnv *env, jobject thiz, jobject original_bitmap, jobject mask_buffer,
    jint mask_w, jint mask_h, jobject output_bitmap, jfloat matte_threshold,
    jint feather_radius, jint cleanup_radius, jfloat edge_lock) {
    internalExtractMask(env, original_bitmap, mask_buffer, mask_w, mask_h, output_bitmap, matte_threshold, feather_radius);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_thusvill_advancewallpapermanager_NativeLib_extractHybridMaskNative(
    JNIEnv *env, jobject thiz, jobject original_bitmap, jobject mask_buffer,
    jint mask_w, jint mask_h, jobject output_bitmap,
    jfloat confidence_threshold, jfloat edge_threshold, jint feather_radius) {
    internalExtractMask(env, original_bitmap, mask_buffer, mask_w, mask_h, output_bitmap, confidence_threshold, feather_radius);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_thusvill_advancewallpapermanager_NativeLib_extractEdgesNative(
    JNIEnv *env, jobject thiz, jobject original_bitmap, jobject output_bitmap,
    jfloat threshold) {
    return JNI_TRUE;
}
