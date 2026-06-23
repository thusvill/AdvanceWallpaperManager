package com.thusvill.advancewallpapermanager

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenter
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallClient
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest


import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp


class TfliteSelfieSegmenter(

    private val context: Context,
    private val config: Config = Config.selfie()
) {

    private var interpreter: Interpreter? = null
    private var mlKitSegmenter: SubjectSegmenter? = null
    private val TAG = "TfliteSelfieSegmenter"
    private val mainHandler = Handler(Looper.getMainLooper())

    var isInitialized = false
        private set

    fun initialize(onComplete: (Boolean) -> Unit) {
        Thread {
            try {
                if (config.pipeline == Pipeline.MLKIT_SUBJECT) {
                    ensureMlKitInitialized()
                    val options = SubjectSegmenterOptions.Builder()
                        .enableForegroundConfidenceMask()
                        .build()
                    mlKitSegmenter = SubjectSegmentation.getClient(options)
                    isInitialized = true
                    Log.i(TAG, "ML Kit Subject Segmenter initialized.")
                } else {
                    ensureInterpreter()
                    Log.i(TAG, "Interpreter initialized from bundled asset.")
                }
                mainHandler.post { onComplete(true) }
            } catch (e: Exception) {
                Log.e(TAG, "Initialization failed: ${e.localizedMessage}", e)
                mainHandler.post { onComplete(false) }
            }
        }.start()
    }
    private fun ensureMlKitInitialized() {
        val moduleInstallClient: ModuleInstallClient = ModuleInstall.getClient(context)
        val api = SubjectSegmentation.getClient(SubjectSegmenterOptions.Builder().build())

        val request = ModuleInstallRequest.newBuilder()
            .addApi(api)
            .build()

        moduleInstallClient.areModulesAvailable(api)
            .addOnSuccessListener { response ->
                if (!response.areModulesAvailable()) {
                    moduleInstallClient.installModules(request)
                        .addOnSuccessListener {
                            Log.d("TfliteSelfie", "Module installed successfully")
                        }
                        .addOnFailureListener { e ->
                            Log.e("TfliteSelfie", "Module install failed", e)
                        }
                }
            }
    }

    fun segment(bitmap: Bitmap): SegmentationMask? {
        if (!isInitialized) {
            if (config.pipeline == Pipeline.MLKIT_SUBJECT) {
                val options = SubjectSegmenterOptions.Builder()
                    .enableForegroundConfidenceMask()
                    .build()
                mlKitSegmenter = SubjectSegmentation.getClient(options)
                isInitialized = true
            } else {
                ensureInterpreter()
            }
        }
        return try {
            if (config.pipeline == Pipeline.MLKIT_SUBJECT) {
                segmentWithMlKit(bitmap)
            } else {
                segmentWithTfLite(bitmap)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Inference crashed: ${e.localizedMessage}", e)
            null
        }
    }


    private fun segmentWithMlKit(bitmap: Bitmap): SegmentationMask? {
        val segmenter = mlKitSegmenter ?: throw IllegalStateException("ML Kit Segmenter not initialized")
        val image = InputImage.fromBitmap(bitmap, 0)
        val result = Tasks.await(segmenter.process(image))
        val floatBuffer = result.foregroundConfidenceMask ?: return null

        val maskWidth = bitmap.width
        val maskHeight = bitmap.height

        Log.d(TAG, "ML Kit Mask capacity: ${floatBuffer.capacity()}, expected: ${maskWidth * maskHeight}")

        val byteBuffer = ByteBuffer.allocateDirect(floatBuffer.capacity() * 4).order(ByteOrder.nativeOrder())
        floatBuffer.rewind()
        byteBuffer.asFloatBuffer().put(floatBuffer)
        byteBuffer.rewind()

        return SegmentationMask(byteBuffer, maskWidth, maskHeight)
    }

    private fun segmentWithTfLite(bitmap: Bitmap): SegmentationMask? {
        var resizedBitmap: Bitmap? = null
        try {
            val currentInterpreter = ensureInterpreter()
            val inputTensor = currentInterpreter.getInputTensor(0)
            val inputShape = inputTensor.shape()
            val inputHeight = if (inputShape.size > 1) inputShape[1] else bitmap.height
            val inputWidth = if (inputShape.size > 2) inputShape[2] else bitmap.width
            resizedBitmap = Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true)
            val inputBuffer = bitmapToInputBuffer(resizedBitmap, inputTensor.dataType(), config.inputMean, config.inputStd)

            val outputTensor = currentInterpreter.getOutputTensor(0)
            val rawOutputBuffer = ByteBuffer.allocateDirect(outputTensor.numBytes())
            rawOutputBuffer.order(ByteOrder.nativeOrder())

            currentInterpreter.run(inputBuffer, rawOutputBuffer)

            return toMask(rawOutputBuffer, outputTensor.shape(), outputTensor.dataType())
        } finally {
            if (resizedBitmap !== null && resizedBitmap !== bitmap) {
                resizedBitmap.recycle()
            }
        }
    }

    fun close() {
        interpreter?.close()
        interpreter = null
        mlKitSegmenter?.close()
        mlKitSegmenter = null
        isInitialized = false
    }

    @Synchronized
    private fun ensureInterpreter(): Interpreter {
        interpreter?.let { return it }
        if (!assetExists(config.modelPath)) {
            throw IllegalStateException("Missing model asset: ${config.modelPath}")
        }
        val model = loadMappedFile(config.modelPath)
        val options = Interpreter.Options()
            .setNumThreads(4)

        return Interpreter(model, options).also {
            interpreter = it
            isInitialized = true
        }
    }

    private fun assetExists(assetName: String): Boolean {
        return try {
            context.assets.open(assetName).close()
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun loadMappedFile(assetName: String): ByteBuffer {
        context.assets.openFd(assetName).use { assetFileDescriptor ->
            FileInputStream(assetFileDescriptor.fileDescriptor).channel.use { channel ->
                return channel.map(
                    java.nio.channels.FileChannel.MapMode.READ_ONLY,
                    assetFileDescriptor.startOffset,
                    assetFileDescriptor.declaredLength
                )
            }
        }
    }

    private fun bitmapToInputBuffer(
        bitmap: Bitmap,
        dataType: DataType,
        mean: Float,
        std: Float
    ): ByteBuffer {
        val width = bitmap.width
        val height = bitmap.height
        val pixelCount = width * height
        val buffer = when (dataType) {
            DataType.FLOAT32 -> ByteBuffer.allocateDirect(pixelCount * 3 * 4)
            DataType.UINT8 -> ByteBuffer.allocateDirect(pixelCount * 3)
            else -> throw IllegalArgumentException("Unsupported input tensor type: $dataType")
        }
        buffer.order(ByteOrder.nativeOrder())

        val pixels = IntArray(pixelCount)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            when (dataType) {
                DataType.FLOAT32 -> {
                    buffer.putFloat((r - mean) / std)
                    buffer.putFloat((g - mean) / std)
                    buffer.putFloat((b - mean) / std)
                }
                DataType.UINT8 -> {
                    buffer.put(r.toByte())
                    buffer.put(g.toByte())
                    buffer.put(b.toByte())
                }
                else -> error("Unsupported input tensor type: $dataType")
            }
        }
        buffer.rewind()
        return buffer
    }

    private fun toMask(rawOutput: ByteBuffer, outputShape: IntArray, dataType: DataType): SegmentationMask {
        rawOutput.order(ByteOrder.nativeOrder())
        rawOutput.rewind()

        return if (
            config.outputMode == OutputMode.ARGMAX_CLASS ||
            config.outputMode == OutputMode.TARGET_CLASS_CONFIDENCE
        ) {
            toClassMask(rawOutput, outputShape, dataType)
        } else {
            toSingleChannelMask(rawOutput, outputShape, dataType)
        }
    }

    private fun toSingleChannelMask(rawOutput: ByteBuffer, outputShape: IntArray, dataType: DataType): SegmentationMask {
        val height = if (outputShape.size > 1) outputShape[1] else 256
        val width = if (outputShape.size > 2) outputShape[2] else 256
        val floatBuffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        repeat(width * height) {
            floatBuffer.putFloat(readScalar(rawOutput, dataType))
        }
        floatBuffer.rewind()
        return SegmentationMask(floatBuffer, width, height)
    }

    private fun toClassMask(rawOutput: ByteBuffer, outputShape: IntArray, dataType: DataType): SegmentationMask {
        val height = if (outputShape.size > 1) outputShape[1] else 257
        val width = if (outputShape.size > 2) outputShape[2] else 257
        val classCount = if (outputShape.size > 3) outputShape[3] else 1
        val floatBuffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())

        if (classCount <= 1) {
            repeat(width * height) {
                val classId = readClassId(rawOutput, dataType)
                floatBuffer.putFloat(if (classId == config.targetClassIndex) 1f else 0f)
            }
            floatBuffer.rewind()
            return SegmentationMask(floatBuffer, width, height)
        }

        val scores = FloatArray(classCount)
        repeat(width * height) {
            var bestClass = 0
            var bestScore = Float.NEGATIVE_INFINITY
            for (classIndex in 0 until classCount) {
                val score = readScalar(rawOutput, dataType)
                scores[classIndex] = score
                if (score > bestScore) {
                    bestScore = score
                    bestClass = classIndex
                }
            }

            var scoreSum = 0f
            for (score in scores) {
                scoreSum += exp(score - bestScore)
            }
            val bestConfidence = if (scoreSum > 0f) 1f / scoreSum else 0f
            val targetConfidence = if (scoreSum > 0f) {
                exp(scores[config.targetClassIndex.coerceIn(0, classCount - 1)] - bestScore) / scoreSum
            } else {
                0f
            }

            if (config.outputMode == OutputMode.TARGET_CLASS_CONFIDENCE) {
                floatBuffer.putFloat(targetConfidence)
            } else {
                floatBuffer.putFloat(
                    if (bestClass == config.targetClassIndex && bestConfidence >= config.minConfidence) 1f else 0f
                )
            }
        }

        floatBuffer.rewind()
        return SegmentationMask(floatBuffer, width, height)
    }

    private fun readClassId(buffer: ByteBuffer, dataType: DataType): Int {
        return when (dataType) {
            DataType.FLOAT32 -> buffer.float.toInt()
            DataType.UINT8 -> buffer.get().toInt() and 0xFF
            DataType.INT8 -> buffer.get().toInt()
            else -> throw IllegalArgumentException("Unsupported output tensor type: $dataType")
        }
    }

    private fun readScalar(buffer: ByteBuffer, dataType: DataType): Float {
        return when (dataType) {
            DataType.FLOAT32 -> buffer.float
            DataType.UINT8 -> (buffer.get().toInt() and 0xFF) / 255f
            DataType.INT8 -> buffer.get() / 127f
            else -> throw IllegalArgumentException("Unsupported output tensor type: $dataType")
        }
    }

    data class SegmentationMask(
        val buffer: ByteBuffer,
        val width: Int,
        val height: Int
    )

    data class Config(
        val pipeline: Pipeline = Pipeline.TFLITE,
        val modelPath: String = "",
        val inputMean: Float = 0f,
        val inputStd: Float = 255f,
        val outputMode: OutputMode = OutputMode.SINGLE_CHANNEL,
        val targetClassIndex: Int = 15,
        val minConfidence: Float = 0f
    ) {
        companion object {
            fun selfie() = Config(
                pipeline = Pipeline.TFLITE,
                modelPath = "selfie_segmenter.tflite",
                inputMean = 0f,
                inputStd = 255f,
                outputMode = OutputMode.SINGLE_CHANNEL
            )

            fun deepLabV3MobileNetV2(
                targetClassIndex: Int = 15,
                minConfidence: Float = 0f
            ) = Config(
                pipeline = Pipeline.TFLITE,
                modelPath = "deeplabv3_mobilenetv2.tflite",
                inputMean = 127.5f,
                inputStd = 127.5f,
                outputMode = OutputMode.ARGMAX_CLASS,
                targetClassIndex = targetClassIndex,
                minConfidence = minConfidence
            )

            fun deepLabV3MobileNetV2Confidence(targetClassIndex: Int = 15) = Config(
                pipeline = Pipeline.TFLITE,
                modelPath = "deeplabv3_mobilenetv2.tflite",
                inputMean = 127.5f,
                inputStd = 127.5f,
                outputMode = OutputMode.TARGET_CLASS_CONFIDENCE,
                targetClassIndex = targetClassIndex
            )

            fun mlKitSubject() = Config(
                pipeline = Pipeline.MLKIT_SUBJECT
            )
        }
    }

    enum class Pipeline {
        TFLITE,
        MLKIT_SUBJECT
    }

    enum class OutputMode {
        SINGLE_CHANNEL,
        ARGMAX_CLASS,
        TARGET_CLASS_CONFIDENCE
    }
}