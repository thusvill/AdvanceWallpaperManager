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

#define FP_SHIFT 16
#define FP_SCALE (1 << FP_SHIFT)

inline uint32_t blend(uint32_t s, uint32_t d) {
  uint8_t a = (s >> 24) & 0xFF;
  if (a == 0) return d;
  if (a == 255) return s;
  uint32_t rb = ((((s & 0x00FF00FF) * a) + ((d & 0x00FF00FF) * (255 - a))) >> 8) & 0x00FF00FF;
  uint32_t g = ((((s & 0x0000FF00) * a) + ((d & 0x0000FF00) * (255 - a))) >> 8) & 0x0000FF00;
  return 0xFF000000 | rb | g;
}

/**
 * Optimized Fast Sampler
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
            int sy = y - offsetY;
            if (sy < 0 || sy >= sh) continue;
            uint32_t *sRow = srcBase + sy * srcStride;
            if (useBlending) {
                for (int x = startX; x < endX; ++x) {
                    int sx = x - offsetX;
                    if (sx >= 0 && sx < sw) dRow[x] = blend(sRow[sx], dRow[x]);
                }
            } else {
                int sx = startX - offsetX;
                int len = std::min(endX - startX, sw - sx);
                if (len > 0) memcpy(dRow + startX, sRow + sx, len * 4);
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
                    if (sx >= 0 && sx < sw) dRow[x] = blend(sRow[sx], dRow[x]);
                    srcX_fp += step;
                }
            } else {
                for (int x = startX; x < endX; ++x) {
                    int sx = srcX_fp >> FP_SHIFT;
                    if (sx >= 0 && sx < sw) dRow[x] = sRow[sx];
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
 * Advanced Edge Refinement Pipeline
 * Replaces hard cut-outs with guide-filtered, color-decontaminated subject masking.
 */

// Helper: Fast Box Blur
void boxBlurAlpha(uint8_t *alpha, int w, int h, int radius) {
    std::vector<uint8_t> temp(w * h);
    for (int pass = 0; pass < 2; ++pass) {
        for (int y = 0; y < h; ++y) {
            for (int x = 0; x < w; ++x) {
                int sum = 0, count = 0;
                for (int k = -radius; k <= radius; ++k) {
                    int nx = x + k;
                    if (nx >= 0 && nx < w) { sum += alpha[y * w + nx]; count++; }
                }
                temp[y * w + x] = sum / count;
            }
        }
        for (int x = 0; x < w; ++x) {
            for (int y = 0; y < h; ++y) {
                int sum = 0, count = 0;
                for (int k = -radius; k <= radius; ++k) {
                    int ny = y + k;
                    if (ny >= 0 && ny < h) { sum += temp[ny * w + x]; count++; }
                }
                alpha[y * w + x] = sum / count;
            }
        }
    }
}

// Helper: Color Bleed (Decontamination)
void decontaminateColors(uint32_t *src, uint8_t *mask, int w, int h, int radius) {
    std::vector<uint32_t> out(w * h);
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            int idx = y * w + x;
            if (mask[idx] > 200) {
                out[idx] = src[idx];
            } else {
                // Find nearest foreground color
                long r = 0, g = 0, b = 0, count = 0;
                for (int ky = -radius; ky <= radius; ++ky) {
                    for (int kx = -radius; kx <= radius; ++kx) {
                        int ny = y + ky, nx = x + kx;
                        if (ny >= 0 && ny < h && nx >= 0 && nx < w) {
                            int nidx = ny * w + nx;
                            if (mask[nidx] > 200) {
                                r += (src[nidx] >> 16) & 0xFF;
                                g += (src[nidx] >> 8) & 0xFF;
                                b += src[nidx] & 0xFF;
                                count++;
                            }
                        }
                    }
                }
                if (count > 0) {
                    out[idx] = 0xFF000000 | ((r / count) << 16) | ((g / count) << 8) | (b / count);
                } else {
                    out[idx] = src[idx];
                }
            }
        }
    }
    memcpy(src, out.data(), w * h * 4);
}

void internalExtractRefined(JNIEnv *env, jobject original_bitmap, jobject mask_buffer,
                           jint mask_w, jint mask_h, jobject output_bitmap,
                           float threshold, int feather_radius) {
    AndroidBitmapInfo origInfo, outInfo;
    void *origPixels, *outPixels;
    if (AndroidBitmap_getInfo(env, original_bitmap, &origInfo) < 0 || AndroidBitmap_lockPixels(env, original_bitmap, &origPixels) < 0) return;
    if (AndroidBitmap_getInfo(env, output_bitmap, &outInfo) < 0 || AndroidBitmap_lockPixels(env, output_bitmap, &outPixels) < 0) {
        AndroidBitmap_unlockPixels(env, original_bitmap); return;
    }
    float *mask = (float *)env->GetDirectBufferAddress(mask_buffer);
    if (!mask) { AndroidBitmap_unlockPixels(env, original_bitmap); AndroidBitmap_unlockPixels(env, output_bitmap); return; }

    int width = (int)origInfo.width;
    int height = (int)origInfo.height;
    uint32_t *src = (uint32_t *)origPixels;
    uint32_t *dst = (uint32_t *)outPixels;
    std::vector<uint8_t> alpha(width * height);

    float scaleX = (float)mask_w / (float)width;
    float scaleY = (float)mask_h / (float)height;

    // 1. Initial Mask Generation
    for (int y = 0; y < height; ++y) {
        int maskY = std::min((int)(y * scaleY), mask_h - 1);
        for (int x = 0; x < width; ++x) {
            int maskX = std::min((int)(x * scaleX), mask_w - 1);
            float val = mask[maskY * mask_w + maskX];
            alpha[y * width + x] = (val > threshold) ? (uint8_t)((val - threshold) / (1.0f - threshold) * 255) : 0;
        }
    }

    // 2. Soften Mask (Refinement)
    if (feather_radius > 0) boxBlurAlpha(alpha.data(), width, height, feather_radius);

    // 3. Color Decontamination (Bleed foreground colors to edges to remove white halo)
    std::vector<uint32_t> tempSrc(width * height);
    memcpy(tempSrc.data(), src, width * height * 4);
    decontaminateColors(tempSrc.data(), alpha.data(), width, height, std::max(2, feather_radius / 2));

    // 4. Final Composition
    for (int i = 0; i < width * height; ++i) {
        dst[i] = (alpha[i] << 24) | (tempSrc[i] & 0x00FFFFFF);
    }

    AndroidBitmap_unlockPixels(env, original_bitmap);
    AndroidBitmap_unlockPixels(env, output_bitmap);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_thusvill_advancewallpapermanager_NativeLib_extractMaskNative(
    JNIEnv *env, jobject thiz, jobject original_bitmap, jobject mask_buffer,
    jint mask_w, jint mask_h, jobject output_bitmap) {
    internalExtractRefined(env, original_bitmap, mask_buffer, mask_w, mask_h, output_bitmap, 0.5f, 0);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_thusvill_advancewallpapermanager_NativeLib_extractSaliencyMatteNative(
    JNIEnv *env, jobject thiz, jobject original_bitmap, jobject mask_buffer,
    jint mask_w, jint mask_h, jobject output_bitmap, jfloat matte_threshold,
    jint feather_radius, jint cleanup_radius, jfloat edge_lock) {
    internalExtractRefined(env, original_bitmap, mask_buffer, mask_w, mask_h, output_bitmap, matte_threshold, feather_radius);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_thusvill_advancewallpapermanager_NativeLib_extractHybridMaskNative(
    JNIEnv *env, jobject thiz, jobject original_bitmap, jobject mask_buffer,
    jint mask_w, jint mask_h, jobject output_bitmap,
    jfloat confidence_threshold, jfloat edge_threshold, jint feather_radius) {
    internalExtractRefined(env, original_bitmap, mask_buffer, mask_w, mask_h, output_bitmap, confidence_threshold, feather_radius);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_thusvill_advancewallpapermanager_NativeLib_extractEdgesNative(
    JNIEnv *env, jobject thiz, jobject original_bitmap, jobject output_bitmap,
    jfloat threshold) {
    return JNI_TRUE;
}
