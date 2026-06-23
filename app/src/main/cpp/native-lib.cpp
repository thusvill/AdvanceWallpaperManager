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

inline float clampf(float v, float lo, float hi) {
  return std::max(lo, std::min(v, hi));
}

// Safe smoothstep with epsilon check for division by zero
inline float smoothstep(float edge0, float edge1, float x) {
  float denom = edge1 - edge0;
  // Use small epsilon to avoid division by zero
  if (denom < 1e-6f && denom > -1e-6f)
    return x < edge0 ? 0.0f : 1.0f;
  float t = clampf((x - edge0) / denom, 0.0f, 1.0f);
  return t * t * (3.0f - 2.0f * t);
}

// Convert RGBA pixel to 8-bit grayscale using ITU-R formula with integer
// arithmetic Result: (R*77 + G*150 + B*29) >> 8 ≈ standard 0.299*R + 0.587*G +
// 0.114*B
inline uint8_t rgbToGrayscale(uint32_t pixel) {
  uint8_t r = (pixel >> 16) & 0xFF;
  uint8_t g = (pixel >> 8) & 0xFF;
  uint8_t b = pixel & 0xFF;
  return (uint8_t)((r * 77 + g * 150 + b * 29) >> 8);
}

// Compute Sobel edge magnitude at position (x, y) in grayscale image
// Returns normalized magnitude [0.0, 1.0]
static inline float computeSobelEdgeMagnitude(const uint8_t *gray, int w, int h,
                                              int x, int y) {
  // Sobel kernels
  static const int gx[3][3] = {{-3, 0, 3}, {-10, 0, 10}, {-3, 0, 3}};
  static const int gy[3][3] = {{-3, -10, -3}, {0, 0, 0}, {3, 10, 3}};

  if (x <= 0 || y <= 0 || x >= w - 1 || y >= h - 1) {
    return 0.0f;
  }

  int sumX = 0, sumY = 0;
  for (int ky = -1; ky <= 1; ++ky) {
    for (int kx = -1; kx <= 1; ++kx) {
      uint8_t val = gray[(y + ky) * w + (x + kx)];
      sumX += val * gx[ky + 1][kx + 1];
      sumY += val * gy[ky + 1][kx + 1];
    }
  }
  // Compute magnitude and normalize: sqrt(sumX² + sumY²) / 2048
  float magnitude = std::sqrt((float)(sumX * sumX + sumY * sumY)) / 2048.0f;
  return clampf(magnitude, 0.0f, 1.0f);
}

inline uint32_t blend(uint32_t s, uint32_t d) {
  uint8_t a = (s >> 24) & 0xFF;
  if (a == 0)
    return d;
  if (a == 255)
    return s;

  uint32_t rb =
      ((((s & 0x00FF00FF) * a) + ((d & 0x00FF00FF) * (255 - a))) >> 8) &
      0x00FF00FF;
  uint32_t g =
      ((((s & 0x0000FF00) * a) + ((d & 0x0000FF00) * (255 - a))) >> 8) &
      0x0000FF00;
  return 0xFF000000 | rb | g;
}

void drawBitmap(ANativeWindow_Buffer *buffer, JNIEnv *env, jobject bitmap,
                int offsetX, int offsetY, bool useBlending) {
  if (!bitmap)
    return;
  AndroidBitmapInfo info;
  void *pixels;
  if (AndroidBitmap_getInfo(env, bitmap, &info) != 0 ||
      AndroidBitmap_lockPixels(env, bitmap, &pixels) != 0) {
    return;
  }

  uint32_t *destBase = (uint32_t *)buffer->bits;
  uint32_t *srcBase = (uint32_t *)pixels;
  uint32_t srcStride = info.stride / 4;

  int startY = std::max(0, offsetY);
  int endY = std::min((int)buffer->height, offsetY + (int)info.height);
  int startX = std::max(0, offsetX);
  int endX = std::min((int)buffer->width, offsetX + (int)info.width);

  for (int y = startY; y < endY; ++y) {
    uint32_t *dRow = destBase + y * buffer->stride;
    uint32_t *sRow = srcBase + (y - offsetY) * srcStride;
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

// Unified rendering logic for both Service and MainActivity
void internalRenderFrame(JNIEnv *env, jobject surface, jobject base_bitmap,
                         jobject mask_bitmap, jobject time_bitmap,
                         jfloat clock_x, jfloat clock_y) {

  ANativeWindow *window = ANativeWindow_fromSurface(env, surface);
  if (!window)
    return;

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
  } else {
    memset(buffer.bits, 0, buffer.stride * buffer.height * 4);
  }

  if (time_bitmap) {
    AndroidBitmapInfo tInfo;
    AndroidBitmap_getInfo(env, time_bitmap, &tInfo);
    int tx = (int)((float)w * clock_x) - (int)(tInfo.width / 2);
    int ty = (int)((float)h * clock_y) - (int)(tInfo.height / 2);
    drawBitmap(&buffer, env, time_bitmap, tx, ty, true);
  }

  if (mask_bitmap) {
    drawBitmap(&buffer, env, mask_bitmap, 0, 0, true);
  }

  ANativeWindow_unlockAndPost(window);
  ANativeWindow_release(window);
}

extern "C" JNIEXPORT void JNICALL
Java_com_thusvill_advancewallpapermanager_CustomDepthWallpaperService_renderNativeFrame(
    JNIEnv *env, jobject thiz, jobject surface, jstring time_text,
    jobject base_bitmap, jobject mask_bitmap, jobject time_bitmap,
    jfloat clock_x, jfloat clock_y) {
  internalRenderFrame(env, surface, base_bitmap, mask_bitmap, time_bitmap,
                      clock_x, clock_y);
}

extern "C" JNIEXPORT void JNICALL
Java_com_thusvill_advancewallpapermanager_MainActivity_renderNativeFrame(
    JNIEnv *env, jobject thiz, jobject surface, jstring time_text,
    jobject base_bitmap, jobject mask_bitmap, jobject time_bitmap,
    jfloat clock_x, jfloat clock_y) {
  internalRenderFrame(env, surface, base_bitmap, mask_bitmap, time_bitmap,
                      clock_x, clock_y);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_thusvill_advancewallpapermanager_MainActivity_extractMaskNative(
    JNIEnv *env, jobject thiz, jobject original_bitmap, jobject mask_buffer,
    jint mask_w, jint mask_h, jobject output_bitmap) {

  AndroidBitmapInfo origInfo;
  void *origPixels;
  if (AndroidBitmap_getInfo(env, original_bitmap, &origInfo) < 0 ||
      AndroidBitmap_lockPixels(env, original_bitmap, &origPixels) < 0) {
    return JNI_FALSE;
  }

  AndroidBitmapInfo outInfo;
  void *outPixels;
  if (AndroidBitmap_getInfo(env, output_bitmap, &outInfo) < 0 ||
      AndroidBitmap_lockPixels(env, output_bitmap, &outPixels) < 0) {
    AndroidBitmap_unlockPixels(env, original_bitmap);
    return JNI_FALSE;
  }

  float *mask = (float *)env->GetDirectBufferAddress(mask_buffer);
  jlong capacity = env->GetDirectBufferCapacity(mask_buffer);
  if (!mask || capacity < (jlong)mask_w * mask_h * 4) {
    LOGE("extractMaskNative: Invalid mask buffer or capacity (got %lld, need %d)", capacity, mask_w * mask_h * 4);
    AndroidBitmap_unlockPixels(env, original_bitmap);
    AndroidBitmap_unlockPixels(env, output_bitmap);
    return JNI_FALSE;
  }

  uint32_t *srcBase = (uint32_t *)origPixels;
  uint32_t *dstBase = (uint32_t *)outPixels;
  uint32_t srcStride = origInfo.stride / 4;
  uint32_t dstStride = outInfo.stride / 4;

  int width = (int)origInfo.width;
  int height = (int)origInfo.height;

  float scaleX = (float)mask_w / (float)width;
  float scaleY = (float)mask_h / (float)height;

  for (int y = 0; y < height; ++y) {
    uint32_t *sRow = srcBase + y * srcStride;
    uint32_t *dRow = dstBase + y * dstStride;

    int maskY = (int)((float)y * scaleY);
    if (maskY >= mask_h)
      maskY = mask_h - 1;
    float *maskRow = mask + maskY * mask_w;

    for (int x = 0; x < width; ++x) {
      int maskX = (int)((float)x * scaleX);
      if (maskX >= mask_w)
        maskX = mask_w - 1;

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

static float sampleMaskBilinear(const float *mask, int maskW, int maskH,
                                float x, float y) {
  x = clampf(x, 0.0f, (float)(maskW - 1));
  y = clampf(y, 0.0f, (float)(maskH - 1));

  int x0 = (int)std::floor(x);
  int y0 = (int)std::floor(y);
  int x1 = std::min(x0 + 1, maskW - 1);
  int y1 = std::min(y0 + 1, maskH - 1);
  float tx = x - (float)x0;
  float ty = y - (float)y0;

  float a = mask[y0 * maskW + x0] * (1.0f - tx) + mask[y0 * maskW + x1] * tx;
  float b = mask[y1 * maskW + x0] * (1.0f - tx) + mask[y1 * maskW + x1] * tx;
  return a * (1.0f - ty) + b * ty;
}

// Optimized 1D horizontal/vertical erosion/dilation for separable morphology
// This is ~2-4× faster than 2D kernel approach
static void morph1D(const std::vector<uint8_t> &src, std::vector<uint8_t> &dst,
                    int w, int h, int radius, bool dilate, bool horizontal) {
  if (horizontal) {
    // Horizontal pass
    for (int y = 0; y < h; ++y) {
      for (int x = 0; x < w; ++x) {
        uint8_t result = dilate ? 0 : 255;
        for (int k = -radius; k <= radius; ++k) {
          int xx = std::min(std::max(x + k, 0), w - 1);
          uint8_t value = src[y * w + xx];
          result = dilate ? std::max(result, value) : std::min(result, value);
        }
        dst[y * w + x] = result;
      }
    }
  } else {
    // Vertical pass
    for (int y = 0; y < h; ++y) {
      for (int x = 0; x < w; ++x) {
        uint8_t result = dilate ? 0 : 255;
        for (int k = -radius; k <= radius; ++k) {
          int yy = std::min(std::max(y + k, 0), h - 1);
          uint8_t value = src[yy * w + x];
          result = dilate ? std::max(result, value) : std::min(result, value);
        }
        dst[y * w + x] = result;
      }
    }
  }
}

static void morph(const std::vector<uint8_t> &src, std::vector<uint8_t> &dst,
                  int w, int h, int radius, bool dilate) {
  // Separable approach: apply 1D horizontal then vertical
  // Horizontal pass: src → dst
  morph1D(src, dst, w, h, radius, dilate, true);
  // Vertical pass: dst → src (reuse as temporary)
  std::vector<uint8_t> temp = dst; // Copy result
  morph1D(temp, dst, w, h, radius, dilate, false);
}

static void boxBlurPass(const std::vector<float> &src, std::vector<float> &dst,
                        int w, int h, int radius, bool horizontal) {
  float kernelSize = (float)(radius * 2 + 1);
  float rcpKernelSize =
      1.0f / kernelSize; // Reciprocal: ~3× faster than division
  for (int y = 0; y < h; ++y) {
    for (int x = 0; x < w; ++x) {
      float sum = 0.0f;
      for (int k = -radius; k <= radius; ++k) {
        int xx = horizontal ? std::min(std::max(x + k, 0), w - 1) : x;
        int yy = horizontal ? y : std::min(std::max(y + k, 0), h - 1);
        sum += src[yy * w + xx];
      }
      dst[y * w + x] =
          sum * rcpKernelSize; // Multiplication instead of division
    }
  }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_thusvill_advancewallpapermanager_MainActivity_extractHybridMaskNative(
    JNIEnv *env, jobject thiz, jobject original_bitmap, jobject mask_buffer,
    jint mask_w, jint mask_h, jobject output_bitmap,
    jfloat confidence_threshold, jfloat edge_threshold, jint feather_radius) {

  AndroidBitmapInfo origInfo;
  void *origPixels;
  if (AndroidBitmap_getInfo(env, original_bitmap, &origInfo) < 0 ||
      AndroidBitmap_lockPixels(env, original_bitmap, &origPixels) < 0) {
    return JNI_FALSE;
  }

  AndroidBitmapInfo outInfo;
  void *outPixels;
  if (AndroidBitmap_getInfo(env, output_bitmap, &outInfo) < 0 ||
      AndroidBitmap_lockPixels(env, output_bitmap, &outPixels) < 0) {
    AndroidBitmap_unlockPixels(env, original_bitmap);
    return JNI_FALSE;
  }

  float *mask = (float *)env->GetDirectBufferAddress(mask_buffer);
  jlong capacity = env->GetDirectBufferCapacity(mask_buffer);
  if (!mask || mask_w <= 0 || mask_h <= 0 || capacity < (jlong)mask_w * mask_h * 4) {
    LOGE("extractSaliencyMatteNative: Invalid mask buffer or capacity (got %lld, need %d)", capacity, mask_w * mask_h * 4);
    AndroidBitmap_unlockPixels(env, original_bitmap);
    AndroidBitmap_unlockPixels(env, output_bitmap);
    return JNI_FALSE;
  }

  int w = (int)origInfo.width;
  int h = (int)origInfo.height;
  uint32_t srcStride = origInfo.stride / 4;
  uint32_t dstStride = outInfo.stride / 4;
  uint32_t *src = (uint32_t *)origPixels;
  uint32_t *dst = (uint32_t *)outPixels;

  std::vector<uint8_t> gray(w * h);
  std::vector<float> confidence(w * h);
  std::vector<uint8_t> hardMask(w * h);
  std::vector<uint8_t> scratch(w * h);

  // Pre-compute reciprocals to avoid division in hot loop
  float rcpW = (float)mask_w / (float)w;
  float rcpH = (float)mask_h / (float)h;
  float threshold = clampf(confidence_threshold, 0.01f, 0.95f);
  float edgeCutoff = clampf(edge_threshold, 0.01f, 0.5f);

  // Pre-compute smoothstep parameters to avoid recalculation in inner loop
  float confidenceAlpha_lower = threshold * 0.55f;
  float confidenceAlpha_upper = std::min(threshold + 0.35f, 0.98f);
  float edgeWeight_lower = edgeCutoff;
  float edgeWeight_upper = std::min(edgeCutoff + 0.22f, 0.75f);

  for (int y = 0; y < h; ++y) {
    for (int x = 0; x < w; ++x) {
      uint32_t p = src[y * srcStride + x];
      gray[y * w + x] = rgbToGrayscale(p);

      float mx = ((float)x + 0.5f) * rcpW - 0.5f;
      float my = ((float)y + 0.5f) * rcpH - 0.5f;
      float c =
          clampf(sampleMaskBilinear(mask, mask_w, mask_h, mx, my), 0.0f, 1.0f);
      confidence[y * w + x] = c;
      hardMask[y * w + x] = c >= threshold ? 255 : 0;
    }
  }

  morph(hardMask, scratch, w, h, 2, true);
  morph(scratch, hardMask, w, h, 2, false);
  morph(hardMask, scratch, w, h, 1, false);
  morph(scratch, hardMask, w, h, 1, true);

  int radius = std::max(1, std::min((int)feather_radius, 24));
  std::vector<float> alpha(w * h);
  std::vector<float> blurScratch(w * h);
  for (int i = 0; i < w * h; ++i) {
    alpha[i] = hardMask[i] > 0 ? 1.0f : 0.0f;
  }
  boxBlurPass(alpha, blurScratch, w, h, radius, true);
  boxBlurPass(blurScratch, alpha, w, h, radius, false);
  boxBlurPass(alpha, blurScratch, w, h, std::max(1, radius / 2), true);
  boxBlurPass(blurScratch, alpha, w, h, std::max(1, radius / 2), false);

  for (int y = 0; y < h; ++y) {
    for (int x = 0; x < w; ++x) {
      int idx = y * w + x;
      float edge = computeSobelEdgeMagnitude(gray.data(), w, h, x, y);

      float hard = hardMask[idx] > 0 ? 1.0f : 0.0f;
      float confidenceAlpha = smoothstep(
          confidenceAlpha_lower, confidenceAlpha_upper, confidence[idx]);
      float soft =
          clampf(alpha[idx] * 0.72f + confidenceAlpha * 0.28f, 0.0f, 1.0f);
      float edgeWeight = smoothstep(edgeWeight_lower, edgeWeight_upper, edge);
      float finalAlpha =
          clampf(soft * (1.0f - edgeWeight) + hard * edgeWeight, 0.0f, 1.0f);
      uint8_t a = (uint8_t)(finalAlpha * 255.0f + 0.5f);

      dst[y * dstStride + x] =
          (src[y * srcStride + x] & 0x00FFFFFF) | ((uint32_t)a << 24);
    }
  }

  AndroidBitmap_unlockPixels(env, original_bitmap);
  AndroidBitmap_unlockPixels(env, output_bitmap);
  return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_thusvill_advancewallpapermanager_MainActivity_extractSaliencyMatteNative(
    JNIEnv *env, jobject thiz, jobject original_bitmap, jobject mask_buffer,
    jint mask_w, jint mask_h, jobject output_bitmap, jfloat matte_threshold,
    jint feather_radius, jint cleanup_radius, jfloat edge_lock) {

  AndroidBitmapInfo origInfo;
  void *origPixels;
  if (AndroidBitmap_getInfo(env, original_bitmap, &origInfo) < 0 ||
      AndroidBitmap_lockPixels(env, original_bitmap, &origPixels) < 0) {
    return JNI_FALSE;
  }

  AndroidBitmapInfo outInfo;
  void *outPixels;
  if (AndroidBitmap_getInfo(env, output_bitmap, &outInfo) < 0 ||
      AndroidBitmap_lockPixels(env, output_bitmap, &outPixels) < 0) {
    AndroidBitmap_unlockPixels(env, original_bitmap);
    return JNI_FALSE;
  }

  float *mask = (float *)env->GetDirectBufferAddress(mask_buffer);
  jlong capacity = env->GetDirectBufferCapacity(mask_buffer);
  if (!mask || mask_w <= 0 || mask_h <= 0 || capacity < (jlong)mask_w * mask_h * 4) {
    LOGE("extractSaliencyMatteNative: Invalid mask buffer or capacity (got %lld, need %d)", capacity, mask_w * mask_h * 4);
    AndroidBitmap_unlockPixels(env, original_bitmap);
    AndroidBitmap_unlockPixels(env, output_bitmap);
    return JNI_FALSE;
  }

  int w = (int)origInfo.width;
  int h = (int)origInfo.height;
  uint32_t srcStride = origInfo.stride / 4;
  uint32_t dstStride = outInfo.stride / 4;
  uint32_t *src = (uint32_t *)origPixels;
  uint32_t *dst = (uint32_t *)outPixels;

  std::vector<uint8_t> gray(w * h);
  std::vector<float> confidence(w * h);
  std::vector<uint8_t> hardMask(w * h);
  std::vector<uint8_t> scratch(w * h);

  // Pre-compute reciprocals to avoid division in hot loop
  float rcpW = (float)mask_w / (float)w;
  float rcpH = (float)mask_h / (float)h;
  float threshold = clampf(matte_threshold, 0.01f, 0.95f);
  int cleanup = std::max(0, std::min((int)cleanup_radius, 8));

  // Pre-compute smoothstep parameters for alpha initialization
  float alpha_confidenceAlpha_lower = threshold * 0.42f;
  float alpha_confidenceAlpha_upper = std::min(threshold + 0.42f, 0.99f);
  // Pre-compute smoothstep parameters for edge weighting in final loop
  float final_confidenceAlpha_lower = threshold * 0.45f;
  float final_confidenceAlpha_upper = std::min(threshold + 0.38f, 0.99f);
  float lock = clampf(edge_lock, 0.0f, 1.0f);

  for (int y = 0; y < h; ++y) {
    for (int x = 0; x < w; ++x) {
      uint32_t p = src[y * srcStride + x];
      gray[y * w + x] = rgbToGrayscale(p);

      float mx = ((float)x + 0.5f) * rcpW - 0.5f;
      float my = ((float)y + 0.5f) * rcpH - 0.5f;
      float c =
          clampf(sampleMaskBilinear(mask, mask_w, mask_h, mx, my), 0.0f, 1.0f);
      confidence[y * w + x] = c;
      hardMask[y * w + x] = c >= threshold ? 255 : 0;
    }
  }

  if (cleanup > 0) {
    morph(hardMask, scratch, w, h, cleanup, true);
    morph(scratch, hardMask, w, h, cleanup, false);
    morph(hardMask, scratch, w, h, std::max(1, cleanup / 2), false);
    morph(scratch, hardMask, w, h, std::max(1, cleanup / 2), true);
  }

  int radius = std::max(1, std::min((int)feather_radius, 32));
  std::vector<float> alpha(w * h);
  std::vector<float> blurScratch(w * h);
  for (int i = 0; i < w * h; ++i) {
    float confidenceAlpha =
        smoothstep(alpha_confidenceAlpha_lower, alpha_confidenceAlpha_upper,
                   confidence[i]);
    float hard = hardMask[i] > 0 ? 1.0f : 0.0f;
    alpha[i] = clampf(hard * 0.65f + confidenceAlpha * 0.35f, 0.0f, 1.0f);
  }
  boxBlurPass(alpha, blurScratch, w, h, radius, true);
  boxBlurPass(blurScratch, alpha, w, h, radius, false);
  boxBlurPass(alpha, blurScratch, w, h, std::max(1, radius / 3), true);
  boxBlurPass(blurScratch, alpha, w, h, std::max(1, radius / 3), false);

  for (int y = 0; y < h; ++y) {
    for (int x = 0; x < w; ++x) {
      int idx = y * w + x;
      float edge = computeSobelEdgeMagnitude(gray.data(), w, h, x, y);

      float hard = hardMask[idx] > 0 ? 1.0f : 0.0f;
      float confidenceAlpha =
          smoothstep(final_confidenceAlpha_lower, final_confidenceAlpha_upper,
                     confidence[idx]);
      float soft =
          clampf(alpha[idx] * 0.65f + confidenceAlpha * 0.35f, 0.0f, 1.0f);
      float edgeWeight = smoothstep(0.08f, 0.42f, edge) * lock;
      float finalAlpha =
          clampf(soft * (1.0f - edgeWeight) + hard * edgeWeight, 0.0f, 1.0f);
      uint8_t a = (uint8_t)(finalAlpha * 255.0f + 0.5f);

      dst[y * dstStride + x] =
          (src[y * srcStride + x] & 0x00FFFFFF) | ((uint32_t)a << 24);
    }
  }

  AndroidBitmap_unlockPixels(env, original_bitmap);
  AndroidBitmap_unlockPixels(env, output_bitmap);
  return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_thusvill_advancewallpapermanager_MainActivity_extractEdgesNative(
    JNIEnv *env, jobject thiz, jobject original_bitmap, jobject output_bitmap,
    jfloat threshold) {

  AndroidBitmapInfo info;
  void *pixels;
  if (AndroidBitmap_getInfo(env, original_bitmap, &info) < 0 ||
      AndroidBitmap_lockPixels(env, original_bitmap, &pixels) < 0) {
    return JNI_FALSE;
  }

  void *outPixels;
  if (AndroidBitmap_lockPixels(env, output_bitmap, &outPixels) < 0) {
    AndroidBitmap_unlockPixels(env, original_bitmap);
    return JNI_FALSE;
  }

  int w = (int)info.width;
  int h = (int)info.height;
  uint32_t stride = info.stride / 4;

  uint32_t *src = (uint32_t *)pixels;
  uint32_t *dst = (uint32_t *)outPixels;

  std::vector<uint8_t> gray(w * h);
  for (int y = 0; y < h; ++y) {
    for (int x = 0; x < w; ++x) {
      uint32_t p = src[y * stride + x];
      gray[y * w + x] = rgbToGrayscale(p);
    }
  }

  // Pre-compute threshold squared for magnitude-squared comparison
  float thresholdSq = (threshold * 1024.0f) * (threshold * 1024.0f);

  for (int y = 1; y < h - 1; ++y) {
    for (int x = 1; x < w - 1; ++x) {
      // Use squared magnitude comparison to avoid sqrt() call
      int sumX = 0;
      int sumY = 0;
      static const int gx[3][3] = {{-3, 0, 3}, {-10, 0, 10}, {-3, 0, 3}};
      static const int gy[3][3] = {{-3, -10, -3}, {0, 0, 0}, {3, 10, 3}};
      for (int ky = -1; ky <= 1; ++ky) {
        for (int kx = -1; kx <= 1; ++kx) {
          uint8_t val = gray[(y + ky) * w + (x + kx)];
          sumX += val * gx[ky + 1][kx + 1];
          sumY += val * gy[ky + 1][kx + 1];
        }
      }
      float magSq = (float)(sumX * sumX + sumY * sumY);

      if (magSq > thresholdSq) {
        dst[y * stride + x] = src[y * stride + x];
      } else {
        dst[y * stride + x] = 0x00000000;
      }
    }
  }

  AndroidBitmap_unlockPixels(env, original_bitmap);
  AndroidBitmap_unlockPixels(env, output_bitmap);
  return JNI_TRUE;
}
