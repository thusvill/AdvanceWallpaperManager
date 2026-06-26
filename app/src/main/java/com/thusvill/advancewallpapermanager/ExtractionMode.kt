package com.thusvill.advancewallpapermanager

enum class ExtractionMode(val displayName: String, val statusLabel: String, val toastLabel: String) {
    SELFIE_AI("Selfie AI", "Status: Extracting Selfie...", "Extracted Selfie Mask"),
    DEEPLAB_V3_MOBILENET_V2("DeepLab V3", "Status: Running DeepLab...", "Extracted DeepLab Mask"),
    EDGE("Edge Detection", "Status: Finding Edges...", "Extracted Edge Mask"),
    HYBRID_DEPTH("Hybrid Depth", "Status: Hybrid Processing...", "Hybrid Mask Ready"),
    SALIENCY_MATTE("Saliency Matte", "Status: Refining Matte...", "Saliency Matte Ready"),
    MLKIT_SUBJECT("ML Kit Subject", "Status: ML Kit Segmenting...", "ML Kit Mask Ready")
}
