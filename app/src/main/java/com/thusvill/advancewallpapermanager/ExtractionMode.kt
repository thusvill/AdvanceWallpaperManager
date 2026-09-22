/*
 * Copyright (C) 2026 Advance Wallpaper Manager
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.thusvill.advancewallpapermanager

enum class ExtractionMode(val displayName: String, val statusLabel: String, val toastLabel: String) {
    SELFIE_AI("Selfie AI", "Status: Extracting Selfie...", "Extracted Selfie Mask"),
    DEEPLAB_V3_MOBILENET_V2("DeepLab V3", "Status: Running DeepLab...", "Extracted DeepLab Mask"),
    EDGE("Edge Detection", "Status: Finding Edges...", "Extracted Edge Mask"),
    HYBRID_DEPTH("Hybrid Depth", "Status: Hybrid Processing...", "Hybrid Mask Ready"),
    SALIENCY_MATTE("Saliency Matte", "Status: Refining Matte...", "Saliency Matte Ready"),
    MLKIT_SUBJECT("ML Kit Subject", "Status: ML Kit Segmenting...", "ML Kit Mask Ready")
}
