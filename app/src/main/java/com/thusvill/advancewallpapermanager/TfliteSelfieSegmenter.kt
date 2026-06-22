package com.thusvill.advancewallpapermanager

import android.content.Context
import android.graphics.Bitmap
import com.google.android.gms.tflite.java.TfLite
import org.tensorflow.lite.InterpreterApi
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TfliteSelfieSegmenter(private val context: Context) {

    private var interpreter: InterpreterApi? = null
    private val modelPath = "selfie_segmenter.tflite"
    var isInitialized = false
        private set

    fun initialize(onComplete: (Boolean) -> Unit) {
        TfLite.initialize(context).addOnSuccessListener {
            try {
                val model = FileUtil.loadMappedFile(context, modelPath)
                val options = InterpreterApi.Options()
                    .setRuntime(InterpreterApi.Options.TfLiteRuntime.FROM_SYSTEM_ONLY)
                    .setNumThreads(4)
                
                interpreter = InterpreterApi.create(model, options)
                isInitialized = true
                onComplete(true)
            } catch (e: Exception) {
                e.printStackTrace()
                onComplete(false)
            }
        }.addOnFailureListener {
            onComplete(false)
        }
    }

    fun segment(bitmap: Bitmap): ByteBuffer? {
        val interpreter = this.interpreter ?: return null

        // 1. Prepare Input
        val imageProcessor = ImageProcessor.Builder()
            .add(ResizeOp(256, 256, ResizeOp.ResizeMethod.BILINEAR))
            .add(NormalizeOp(0f, 255f))
            .build()

        var tensorImage = TensorImage(interpreter.getInputTensor(0).dataType())
        tensorImage.load(bitmap)
        tensorImage = imageProcessor.process(tensorImage)

        // 2. Prepare Output
        val outputBuffer = ByteBuffer.allocateDirect(256 * 256 * 4)
        outputBuffer.order(ByteOrder.nativeOrder())

        // 3. Run Inference
        interpreter.run(tensorImage.buffer, outputBuffer)
        
        return outputBuffer
    }

    fun close() {
        interpreter?.close()
    }
}
