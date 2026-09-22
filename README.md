# Advance Wallpaper Manager

![Android](https://img.shields.io/badge/Android-37%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white)
![C++20](https://img.shields.io/badge/C%2B%2B-20%20%2F%20NDK-00599C?style=for-the-badge&logo=c%2B%2B&logoColor=white)

Advance Wallpaper Manager is an Android application for creating depth-effect live wallpapers. It renders a clock layer behind extracted subject foreground masks using on-device machine learning models and a native C++ rendering pipeline.

## Screenshots

| Main Gallery | Depth Editor | Clock Customization |
| :---: | :---: | :---: |
| ![Gallery Screen Placeholder](docs/images/gallery_screen.png) | ![Editor Screen Placeholder](docs/images/editor_screen.png) | ![Clock Settings Placeholder](docs/images/clock_settings.png) |

| AI Model Tuning | Wallpaper Transformation | Live Wallpaper Preview |
| :---: | :---: | :---: |
| ![Model Settings Placeholder](docs/images/model_settings.png) | ![Wallpaper Transform Placeholder](docs/images/wallpaper_transform.png) | ![Live Preview Placeholder](docs/images/live_preview.gif) |

## Features

### Rendering Engine
* Layered composition rendering base image, clock text, and foreground subject mask in a single pass.
* Synchronized gesture transformations: scale, pan, and rotation applied to wallpaper and subject mask simultaneously.
* Occlusion clamp (`clockDepth`) limiting foreground opacity over clock text.

### Subject Segmentation
* Google ML Kit Subject Segmentation API integration.
* TensorFlow Lite models (`selfie_segmenter.tflite` and `deeplabv3_mobilenetv2.tflite`) for offline processing.
* Native C++ edge refinement pipeline:
  * Confidence threshold ($\tau$)
  * Boundary expansion and shrinking via morphological dilation ($+\text{px}$) and erosion ($-\text{px}$)
  * Box blur feathering
  * Color decontamination to prevent edge halos
  * Automatic fallback to ML Kit if local model fails

### Clock Customization
* Horizontal and vertical stacked text layouts.
* System font detection and custom font imports (`.ttf`, `.otf`, `.zip`).
* Font embedding inside saved configuration archives.
* Adjustable size, thickness, height stretch, letter spacing, line spacing, and auto color extraction.

### Bundle Storage (`.dwp`)
* Self-contained ZIP archives (`.dwp`) containing:
  * `config.json`
  * `base.png`
  * `mask.png`
  * `preview.png`
  * `font.ttf`
* Import and export support via Android Storage Access Framework.

### Rotation Scheduler
* Scheduled background wallpaper rotation using WorkManager.
* Intervals: Daily, Hourly, or On-Awake (pre-loads assets when screen turns off).

## Tech Stack

* Kotlin 2.0, C++20 (Android NDK)
* Jetpack Compose, Material 3, Navigation Compose
* Kotlin Coroutines and Flow
* Android NDK `ANativeWindow` and `AndroidBitmap`
* Google ML Kit Subject Segmentation
* TensorFlow Lite 2.16
* Android WorkManager

## Native Rendering Pipeline

The C++ pipeline in `native-lib.cpp` renders directly to `ANativeWindow_Buffer` using `RGBA_8888` pixel arrays.

```
[ ANativeWindow Buffer ]
   ├── 1. Draw Base Wallpaper  (Scale, Rotation, Panning)
   ├── 2. Draw Clock Bitmap    (Centered, Scaled, Rotation)
   └── 3. Draw Subject Mask    (Scale, Rotation, Occlusion Clamp)
```

### Formulations

1. Inverse 2D Affine Sampling:
   $$x = \text{origCx} + (dx0 \cdot \cos\theta + dy0 \cdot \sin\theta) \cdot \text{invScale}$$
   $$y = \text{origCy} + (-dx0 \cdot \sin\theta + dy0 \cdot \cos\theta) \cdot \text{invScale}$$

2. Clock Occlusion Clamp:
   $$\alpha_{\text{clock\_area}}(x, y) = \min(\alpha(x, y), \lfloor \text{clockDepth} \times 255 \rfloor)$$

3. Morphological Expansion:
   * Dilation ($+\text{px}$): $\alpha_{\text{new}}(x, y) = \max_{k \in [-r, r]} \alpha(x + k, y)$
   * Erosion ($-\text{px}$): $\alpha_{\text{new}}(x, y) = \min_{k \in [-r, r]} \alpha(x + k, y)$

## Building

### Requirements
* Android Studio 2024.2+
* Android SDK 37 (Min SDK 29)
* CMake 3.22.1+
* JDK 17

### Steps
1. Clone the repository:
   ```bash
   git clone https://github.com/your-username/AdvanceWallpaperManager.git
   ```
2. Build debug APK:
   ```bash
   ./gradlew app:assembleDebug
   ```

## Usage

1. Load an image in the Source tab.
2. Select segmentation mode under Model tab and adjust threshold or expansion parameters.
3. Customize font, size, or position under Clock tab.
4. Scale, rotate, or position wallpaper under Wallpaper tab.
5. Tap Apply & Auto-Save in the Apply tab.

## License

```text
Copyright 2025 Advance Wallpaper Manager

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
