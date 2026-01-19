package com.youyou.monitor.core.matcher

import android.content.Context
import android.graphics.Bitmap
import com.youyou.monitor.core.domain.model.MatchResult
import com.youyou.monitor.core.domain.repository.ModelRepository
import com.youyou.monitor.infra.logger.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * AI 匹配器 - 使用 TensorFlow Lite 进行聊天界面检测
 */
class AIMatcher(
    private val context: Context,
    private val modelRepository: ModelRepository
) {
    private val TAG = "AIMatcher"

    private var interpreter: Interpreter? = null
    private var isInitialized = false

    // 模型配置
    private val MODEL_NAME = "chat_detector.tflite"
    private val INPUT_SIZE = 224  // 模型输入尺寸
    private val CONFIDENCE_THRESHOLD = 0.7f  // 置信度阈值

    // 聊天应用类型枚举
    enum class ChatApp(val displayName: String) {
        WECHAT_DAY("微信-日间模式"),
        WECHAT_NIGHT("微信-夜间模式"),
        QQ("QQ"),
        WEIBO("微博"),
        DOUYIN("抖音"),
        UNKNOWN("未知")
    }

    init {
        // 异步初始化模型
        GlobalScope.launch {
            initializeModel()
        }
    }

    /**
     * 初始化 TFLite 模型
     */
    private suspend fun initializeModel() {
        try {
            Log.d(TAG, "Initializing TFLite model...")

            // 加载模型文件
            val modelBuffer = loadModelFile()
            if (modelBuffer == null) {
                Log.w(TAG, "Model file not found, AI matcher will return null results")
                isInitialized = false
                return
            }

            // 创建解释器
            val options = Interpreter.Options().apply {
                setNumThreads(4)  // 使用4个线程
                setUseNNAPI(true)  // 启用 NNAPI
            }

            interpreter = Interpreter(modelBuffer, options)
            isInitialized = true

            Log.i(TAG, "TFLite model initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize TFLite model: ${e.message}", e)
            Log.w(TAG, "AI matcher will return null results until model is available")
        }
    }

    /**
     * 加载模型文件
     */
    private suspend fun loadModelFile(): ByteBuffer? = withContext(Dispatchers.IO) {
        try {
            // 从ModelRepository获取当前模型信息
            val currentModel = modelRepository.getCurrentModel()
            if (currentModel == null) {
                Log.w(TAG, "No current model available")
                return@withContext null
            }

            // 检查模型文件是否存在
            val modelFile = File(context.filesDir, "Models/${currentModel.fileName}")
            if (!modelFile.exists()) {
                Log.w(TAG, "Model file not found: ${modelFile.absolutePath}")
                return@withContext null
            }

            // 读取模型文件
            val inputStream = modelFile.inputStream()
            val modelBuffer = ByteBuffer.allocateDirect(inputStream.available())
            modelBuffer.order(ByteOrder.nativeOrder())

            val buffer = ByteArray(1024)
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                modelBuffer.put(buffer, 0, bytesRead)
            }

            modelBuffer.rewind()
            inputStream.close()

            Log.d(TAG, "Model file loaded: ${currentModel.name} (${modelBuffer.capacity()} bytes)")
            modelBuffer
        } catch (e: IOException) {
            Log.e(TAG, "Failed to load model file: ${e.message}")
            null
        }
    }

    suspend fun match(bitmap: Bitmap, scale: Int): MatchResult? = withContext(Dispatchers.Default) {
        if (!isInitialized || interpreter == null) {
            Log.w(TAG, "AI matcher not initialized")
            return@withContext null
        }

        try {
            // 预处理图像
            val processedImage = preprocessImage(bitmap)

            // 执行推理
            val outputBuffer = runInference(processedImage)

            // 解析结果
            val result = parseResults(outputBuffer, scale)

            if (result != null) {
                Log.i(TAG, "AI detected: ${result.detectedApp} (score: ${result.score})")
                return@withContext result
            }

        } catch (e: Exception) {
            Log.e(TAG, "AI inference failed: ${e.message}", e)
        }

        return@withContext null
    }

    /**
     * 预处理图像
     */
    private fun preprocessImage(bitmap: Bitmap): TensorImage {
        // 创建图像处理器
        val imageProcessor = ImageProcessor.Builder()
            .add(ResizeOp(INPUT_SIZE, INPUT_SIZE, ResizeOp.ResizeMethod.BILINEAR))
            .add(NormalizeOp(0f, 255f))  // 归一化到 [0,1]
            .build()

        // 创建 TensorImage
        val tensorImage = TensorImage(DataType.FLOAT32)
        tensorImage.load(bitmap)

        // 应用预处理
        return imageProcessor.process(tensorImage)
    }

    /**
     * 执行推理
     */
    private fun runInference(inputImage: TensorImage): TensorBuffer {
        val interpreter = interpreter ?: throw IllegalStateException("Interpreter not initialized")

        // 获取输出张量形状
        val outputShape = interpreter.getOutputTensor(0).shape()
        val outputBuffer = TensorBuffer.createFixedSize(outputShape, DataType.FLOAT32)

        // 执行推理
        interpreter.run(inputImage.buffer, outputBuffer.buffer)

        return outputBuffer
    }

    /**
     * 解析推理结果
     */
    private fun parseResults(outputBuffer: TensorBuffer, scale: Int): MatchResult? {
        val probabilities = outputBuffer.floatArray

        // 找到最高置信度的类别
        var maxConfidence = 0f
        var bestClass = -1

        for (i in probabilities.indices) {
            if (probabilities[i] > maxConfidence) {
                maxConfidence = probabilities[i]
                bestClass = i
            }
        }

        // 检查置信度阈值
        if (maxConfidence < CONFIDENCE_THRESHOLD) {
            Log.d(TAG, "Detection confidence too low: $maxConfidence < $CONFIDENCE_THRESHOLD")
            return null
        }

        // 转换为聊天应用类型
        val chatApp = when (bestClass) {
            0 -> ChatApp.WECHAT_DAY
            1 -> ChatApp.WECHAT_NIGHT
            2 -> ChatApp.QQ
            3 -> ChatApp.WEIBO
            4 -> ChatApp.DOUYIN
            else -> ChatApp.UNKNOWN
        }

        if (chatApp == ChatApp.UNKNOWN) {
            return null
        }

        return MatchResult(
            detectedApp = chatApp.displayName,
            score = maxConfidence.toDouble(),
            scale = scale.toFloat(),
            timeMs = System.currentTimeMillis()
        )
    }

    fun release() {
        try {
            interpreter?.close()
            interpreter = null
            isInitialized = false
            Log.d(TAG, "AI matcher resources released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing AI matcher: ${e.message}")
        }
    }
}