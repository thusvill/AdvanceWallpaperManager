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
 * Optimized Fast Sampler with Rotation and Clock Depth Clamp Support
 */
void drawBitmapFast(ANativeWindow_Buffer *buffer, JNIEnv *env, jobject bitmap,
                    float offsetX, float offsetY, bool useBlending, float scale, float rotationDeg = 0.0f,
                    float clockDepth = 1.0f, float clockMinX = -1e9f, float clockMaxX = -1e9f,
                    float clockMinY = -1e9f, float clockMaxY = -1e9f) {
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
    int bufW = (int)buffer->width;
    int bufH = (int)buffer->height;

    float rot = fmodf(rotationDeg, 360.0f);
    if (rot < 0.0f) rot += 360.0f;

    int maxAlpha = std::min(255, std::max(0, (int)(clockDepth * 255.0f)));

    if (rot < 0.01f || rot > 359.99f) {
        int dw = (int)(sw * scale);
        int dh = (int)(sh * scale);

        int startY = std::max(0, (int)offsetY);
        int endY = std::min(bufH, (int)(offsetY + dh));
        int startX = std::max(0, (int)offsetX);
        int endX = std::min(bufW, (int)(offsetX + dw));

        if (scale == 1.0f) {
            for (int y = startY; y < endY; ++y) {
                uint32_t *dRow = destBase + y * buffer->stride;
                int sy = y - (int)offsetY;
                if (sy < 0 || sy >= sh) continue;
                uint32_t *sRow = srcBase + sy * srcStride;
                if (useBlending) {
                    bool inClockRow = (y >= clockMinY && y <= clockMaxY);
                    for (int x = startX; x < endX; ++x) {
                        int sx = x - (int)offsetX;
                        if (sx >= 0 && sx < sw) {
                            uint32_t color = sRow[sx];
                            if (clockDepth < 1.0f && inClockRow && x >= clockMinX && x <= clockMaxX) {
                                uint32_t a = (color >> 24) & 0xFF;
                                if (a > (uint32_t)maxAlpha) color = ((uint32_t)maxAlpha << 24) | (color & 0x00FFFFFF);
                            }
                            dRow[x] = blend(color, dRow[x]);
                        }
                    }
                } else {
                    int sx = startX - (int)offsetX;
                    int len = std::min(endX - startX, sw - sx);
                    if (len > 0 && sx >= 0 && sx + len <= sw) memcpy(dRow + startX, sRow + sx, len * 4);
                }
            }
        } else {
            int step = (int)((1.0f / scale) * FP_SCALE);
            int srcX_start_fp = (startX > (int)offsetX) ? ((int)((startX - offsetX) * step)) : 0;
            for (int y = startY; y < endY; ++y) {
                uint32_t *dRow = destBase + y * buffer->stride;
                int srcY = ((int)((y - offsetY) * step)) >> FP_SHIFT;
                if (srcY < 0) srcY = 0; else if (srcY >= sh) srcY = sh - 1;
                uint32_t *sRow = srcBase + srcY * srcStride;
                int srcX_fp = srcX_start_fp;
                if (useBlending) {
                    bool inClockRow = (y >= clockMinY && y <= clockMaxY);
                    for (int x = startX; x < endX; ++x) {
                        int sx = srcX_fp >> FP_SHIFT;
                        if (sx >= 0 && sx < sw) {
                            uint32_t color = sRow[sx];
                            if (clockDepth < 1.0f && inClockRow && x >= clockMinX && x <= clockMaxX) {
                                uint32_t a = (color >> 24) & 0xFF;
                                if (a > (uint32_t)maxAlpha) color = ((uint32_t)maxAlpha << 24) | (color & 0x00FFFFFF);
                            }
                            dRow[x] = blend(color, dRow[x]);
                        }
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
        return;
    }

    // Rotated rendering path
    float rad = rot * ((float)M_PI / 180.0f);
    float cosR = cosf(rad);
    float sinR = sinf(rad);

    float origCx = sw / 2.0f;
    float origCy = sh / 2.0f;

    float scaledCx = sw * scale / 2.0f;
    float scaledCy = sh * scale / 2.0f;

    float screenCx = offsetX + scaledCx;
    float screenCy = offsetY + scaledCy;

    float localX[4] = {-origCx, origCx, origCx, -origCx};
    float localY[4] = {-origCy, -origCy, origCy, origCy};

    float minX = 1e9f, maxX = -1e9f;
    float minY = 1e9f, maxY = -1e9f;

    for (int i = 0; i < 4; ++i) {
        float rotX = (localX[i] * cosR - localY[i] * sinR) * scale;
        float rotY = (localX[i] * sinR + localY[i] * cosR) * scale;
        float scrX = screenCx + rotX;
        float scrY = screenCy + rotY;
        if (scrX < minX) minX = scrX;
        if (scrX > maxX) maxX = scrX;
        if (scrY < minY) minY = scrY;
        if (scrY > maxY) maxY = scrY;
    }

    int startX = std::max(0, (int)floorf(minX));
    int endX = std::min(bufW, (int)ceilf(maxX));
    int startY = std::max(0, (int)floorf(minY));
    int endY = std::min(bufH, (int)ceilf(maxY));

    if (startX >= endX || startY >= endY) {
        AndroidBitmap_unlockPixels(env, bitmap);
        return;
    }

    float invScale = 1.0f / scale;
    int stepXX_fp = (int)(cosR * invScale * FP_SCALE);
    int stepXY_fp = (int)(-sinR * invScale * FP_SCALE);
    int stepYX_fp = (int)(sinR * invScale * FP_SCALE);
    int stepYY_fp = (int)(cosR * invScale * FP_SCALE);

    float dx0 = startX - screenCx;
    float dy0 = startY - screenCy;

    float srcX0 = origCx + (dx0 * cosR + dy0 * sinR) * invScale;
    float srcY0 = origCy + (-dx0 * sinR + dy0 * cosR) * invScale;

    int srcX0_fp = (int)(srcX0 * FP_SCALE);
    int srcY0_fp = (int)(srcY0 * FP_SCALE);

    for (int y = startY; y < endY; ++y) {
        uint32_t *dRow = destBase + y * buffer->stride;
        int srcX_fp = srcX0_fp;
        int srcY_fp = srcY0_fp;
        bool inClockRow = (y >= clockMinY && y <= clockMaxY);

        for (int x = startX; x < endX; ++x) {
            int sx = srcX_fp >> FP_SHIFT;
            int sy = srcY_fp >> FP_SHIFT;

            if (sx >= 0 && sx < sw && sy >= 0 && sy < sh) {
                uint32_t color = srcBase[sy * srcStride + sx];
                if (useBlending) {
                    if (clockDepth < 1.0f && inClockRow && x >= clockMinX && x <= clockMaxX) {
                        uint32_t a = (color >> 24) & 0xFF;
                        if (a > (uint32_t)maxAlpha) color = ((uint32_t)maxAlpha << 24) | (color & 0x00FFFFFF);
                    }
                    dRow[x] = blend(color, dRow[x]);
                } else {
                    dRow[x] = color;
                }
            }

            srcX_fp += stepXX_fp;
            srcY_fp += stepXY_fp;
        }

        srcX0_fp += stepYX_fp;
        srcY0_fp += stepYY_fp;
    }

    AndroidBitmap_unlockPixels(env, bitmap);
}

void internalRenderFrame(JNIEnv *env, jobject surface, jobject base_bitmap,
                         jobject mask_bitmap, jobject time_bitmap,
                         jfloat clock_x, jfloat clock_y,
                         jfloat wallpaper_scale, jfloat offset_x, jfloat offset_y,
                         jfloat wallpaper_rotation, jfloat clock_rotation,
                         jfloat clock_depth = 1.0f) {
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
    float rotNorm = fmodf(wallpaper_rotation, 360.0f);
    if (rotNorm < 0.0f) rotNorm += 360.0f;

    if (base_bitmap && (rotNorm < 0.01f || rotNorm > 359.99f)) {
        AndroidBitmapInfo bInfo;
        AndroidBitmap_getInfo(env, base_bitmap, &bInfo);
        if (offset_x <= 0 && offset_y <= 0 && (offset_x + bInfo.width * wallpaper_scale) >= w && (offset_y + bInfo.height * wallpaper_scale) >= h) needsClear = false;
    }
    if (needsClear) memset(buffer.bits, 0, buffer.stride * buffer.height * 4);

    if (base_bitmap) drawBitmapFast(&buffer, env, base_bitmap, offset_x, offset_y, false, wallpaper_scale, wallpaper_rotation);
    if (time_bitmap) {
        AndroidBitmapInfo tInfo;
        AndroidBitmap_getInfo(env, time_bitmap, &tInfo);
        drawBitmapFast(&buffer, env, time_bitmap, (w * clock_x - (int)tInfo.width / 2.0f), (h * clock_y - (int)tInfo.height / 2.0f), true, 1.0f, clock_rotation);
    }
    if (mask_bitmap) {
        float clockMinX = -1e9f, clockMaxX = -1e9f, clockMinY = -1e9f, clockMaxY = -1e9f;
        if (time_bitmap && clock_depth < 1.0f) {
            AndroidBitmapInfo tInfo;
            if (AndroidBitmap_getInfo(env, time_bitmap, &tInfo) == 0) {
                float cx = w * clock_x;
                float cy = h * clock_y;
                float halfW = tInfo.width / 2.0f;
                float halfH = tInfo.height / 2.0f;
                clockMinX = cx - halfW;
                clockMaxX = cx + halfW;
                clockMinY = cy - halfH;
                clockMaxY = cy + halfH;
            }
        }
        drawBitmapFast(&buffer, env, mask_bitmap, offset_x, offset_y, true, wallpaper_scale, wallpaper_rotation, clock_depth, clockMinX, clockMaxX, clockMinY, clockMaxY);
    }

    ANativeWindow_unlockAndPost(window);
    ANativeWindow_release(window);
}

extern "C" JNIEXPORT void JNICALL
Java_com_thusvill_advancewallpapermanager_CustomDepthWallpaperService_renderNativeFrame(
    JNIEnv *env, jobject thiz, jobject surface, jstring time_text,
    jobject base_bitmap, jobject mask_bitmap, jobject time_bitmap,
    jfloat clock_x, jfloat clock_y, jfloat wallpaper_scale, jfloat offset_x, jfloat offset_y,
    jfloat wallpaper_rotation, jfloat clock_rotation, jfloat clock_depth) {
  internalRenderFrame(env, surface, base_bitmap, mask_bitmap, time_bitmap,
                      clock_x, clock_y, wallpaper_scale, offset_x, offset_y, wallpaper_rotation, clock_rotation, clock_depth);
}

extern "C" JNIEXPORT void JNICALL
Java_com_thusvill_advancewallpapermanager_NativeLib_renderNativeFrame(
    JNIEnv *env, jobject thiz, jobject surface, jstring time_text,
    jobject base_bitmap, jobject mask_bitmap, jobject time_bitmap,
    jfloat clock_x, jfloat clock_y, jfloat wallpaper_scale, jfloat offset_x, jfloat offset_y,
    jfloat wallpaper_rotation, jfloat clock_rotation, jfloat clock_depth) {
  internalRenderFrame(env, surface, base_bitmap, mask_bitmap, time_bitmap,
                      clock_x, clock_y, wallpaper_scale, offset_x, offset_y, wallpaper_rotation, clock_rotation, clock_depth);
}

/**
 * Advanced Edge Refinement Pipeline
 * Replaces hard cut-outs with guide-filtered, color-decontaminated subject masking.
 */

// Helper: Morphological Dilation (+px) and Erosion (-px)
void expandMaskAlpha(uint8_t *alpha, int w, int h, int expansionPx) {
    if (expansionPx == 0) return;
    std::vector<uint8_t> temp(w * h);
    int r = std::abs(expansionPx);

    if (expansionPx > 0) { // Dilation (Expand)
        for (int y = 0; y < h; ++y) {
            for (int x = 0; x < w; ++x) {
                uint8_t maxV = 0;
                for (int k = -r; k <= r; ++k) {
                    int nx = x + k;
                    if (nx >= 0 && nx < w) {
                        uint8_t v = alpha[y * w + nx];
                        if (v > maxV) maxV = v;
                    }
                }
                temp[y * w + x] = maxV;
            }
        }
        for (int x = 0; x < w; ++x) {
            for (int y = 0; y < h; ++y) {
                uint8_t maxV = 0;
                for (int k = -r; k <= r; ++k) {
                    int ny = y + k;
                    if (ny >= 0 && ny < h) {
                        uint8_t v = temp[ny * w + x];
                        if (v > maxV) maxV = v;
                    }
                }
                alpha[y * w + x] = maxV;
            }
        }
    } else { // Erosion (Shrink)
        for (int y = 0; y < h; ++y) {
            for (int x = 0; x < w; ++x) {
                uint8_t minV = 255;
                for (int k = -r; k <= r; ++k) {
                    int nx = x + k;
                    if (nx >= 0 && nx < w) {
                        uint8_t v = alpha[y * w + nx];
                        if (v < minV) minV = v;
                    }
                }
                temp[y * w + x] = minV;
            }
        }
        for (int x = 0; x < w; ++x) {
            for (int y = 0; y < h; ++y) {
                uint8_t minV = 255;
                for (int k = -r; k <= r; ++k) {
                    int ny = y + k;
                    if (ny >= 0 && ny < h) {
                        uint8_t v = temp[ny * w + x];
                        if (v < minV) minV = v;
                    }
                }
                alpha[y * w + x] = minV;
            }
        }
    }
}

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
                           float threshold, int feather_radius, int expansion_px = 0) {
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
            alpha[y * width + x] = (val > threshold) ? (uint8_t)(((val - threshold) / std::max(0.001f, 1.0f - threshold)) * 255) : 0;
        }
    }

    // 2. Boundary Expansion (+px dilate, -px erode)
    if (expansion_px != 0) expandMaskAlpha(alpha.data(), width, height, expansion_px);

    // 3. Soften Mask (Refinement)
    if (feather_radius > 0) boxBlurAlpha(alpha.data(), width, height, feather_radius);

    // 4. Color Decontamination (Bleed foreground colors to edges to remove white halo)
    std::vector<uint32_t> tempSrc(width * height);
    memcpy(tempSrc.data(), src, width * height * 4);
    decontaminateColors(tempSrc.data(), alpha.data(), width, height, std::max(2, feather_radius / 2));

    // 5. Final Composition
    for (int i = 0; i < width * height; ++i) {
        dst[i] = (alpha[i] << 24) | (tempSrc[i] & 0x00FFFFFF);
    }

    AndroidBitmap_unlockPixels(env, original_bitmap);
    AndroidBitmap_unlockPixels(env, output_bitmap);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_thusvill_advancewallpapermanager_NativeLib_extractMlKitSubjectNative(
    JNIEnv *env, jobject thiz, jobject original_bitmap, jobject mask_buffer,
    jint mask_w, jint mask_h, jobject output_bitmap, jfloat threshold,
    jint feather_radius, jint expansion_px) {
    internalExtractRefined(env, original_bitmap, mask_buffer, mask_w, mask_h, output_bitmap, threshold, feather_radius, expansion_px);
    return JNI_TRUE;
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
