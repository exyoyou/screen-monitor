package com.youyou.monitor.infra.repository

import android.content.Context
import com.youyou.monitor.core.domain.model.AiConfig
import com.youyou.monitor.core.domain.repository.AiConfigRepository
import com.youyou.monitor.core.domain.repository.ModelInfo
import com.youyou.monitor.core.domain.repository.ModelRepository
import com.youyou.monitor.infra.logger.Log
import com.youyou.monitor.infra.network.WebDavClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * AI 模型仓储实现
 *
 * 支持功能：
 * - 多个模型版本管理
 * - 远程模型同步（WebDAV）
 * - 模型切换和缓存
 * - 模型元数据管理
 */
class ModelRepositoryImpl(
    private val context: Context,
    private val aiConfigRepository: AiConfigRepository
) : ModelRepository {

    companion object {
        const val TAG = "ModelRepository"
        const val MODEL_DIR = "Models"
        const val MODEL_CONFIG_FILE = "models.json"
        const val DEFAULT_MODEL_ID = "chat_detector_v1"
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Volatile
    private var currentAiConfig: AiConfig = AiConfig.default()

    @Volatile
    private var currentModelId: String = DEFAULT_MODEL_ID

    init {
        // 监听AI配置变更
        aiConfigRepository.getAiConfigFlow()
            .onEach { newConfig ->
                currentAiConfig = newConfig
                // 更新WebDAV配置
                updateWebDavFromAiConfig(newConfig)
                // 如果配置中有指定的模型ID，使用它
                val modelId = newConfig.currentModelId
                if (modelId != currentModelId) {
                    Log.d(TAG, "Switching model due to AI config change: $modelId")
                    scope.launch {
                        switchModel(modelId)
                    }
                }
            }
            .launchIn(scope)

        // 初始化时尝试加载当前模型
        scope.launch {
            loadCurrentModelFromAiConfig()
        }
    }

    private val modelDir: File
        get() = File(getRootDir(), MODEL_DIR).apply {
            if (!exists()) mkdirs()
        }

    private val modelConfigFile: File
        get() = File(modelDir, MODEL_CONFIG_FILE)

    private fun getRootDir(): File {
        return if (currentAiConfig.webdavServers.isNotEmpty() &&
                   currentAiConfig.webdavServers.first().baseDir.isNotEmpty()) {
            // 如果有WebDAV配置，使用外部存储
            context.getExternalFilesDir(null) ?: context.filesDir
        } else {
            context.filesDir
        }
    }

    // WebDAV 客户端（可选）
    private var webdavClient: WebDavClient? = null
    private var remoteModelDir: String = "AI/Models"  // 默认使用配置中的路径
    private var remoteConfigDir: String = "AI/Config"  // 模型配置文件目录

    override suspend fun getCurrentModel(): ModelInfo? = withContext(Dispatchers.IO) {
        try {
            val models = loadModelConfig()
            models.find { it.id == currentModelId && it.isDownloaded }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get current model: ${e.message}")
            null
        }
    }

    override suspend fun listAvailableModels(): List<ModelInfo> = withContext(Dispatchers.IO) {
        try {
            loadModelConfig()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list available models: ${e.message}")
            emptyList()
        }
    }

    override suspend fun switchModel(modelId: String): Result<ModelInfo> = withContext(Dispatchers.IO) {
        try {
            val models = loadModelConfig()
            val targetModel = models.find { it.id == modelId }

            if (targetModel == null) {
                return@withContext Result.failure(Exception("Model not found: $modelId"))
            }

            if (!targetModel.isDownloaded) {
                Log.d(TAG, "Model not downloaded, downloading first: $modelId")
                val downloadResult = downloadModel(modelId)
                if (downloadResult.isFailure) {
                    return@withContext Result.failure(downloadResult.exceptionOrNull()!!)
                }
            }

            // 验证模型文件是否存在
            val modelFile = File(modelDir, targetModel.fileName)
            if (!modelFile.exists()) {
                return@withContext Result.failure(Exception("Model file not found: ${targetModel.fileName}"))
            }

            currentModelId = modelId

            // 保存到AI配置
            saveCurrentModelToAiConfig(modelId)

            Log.i(TAG, "Switched to model: ${targetModel.name} (${targetModel.id})")

            Result.success(targetModel.copy(isDownloaded = true))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to switch model: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun syncModelsFromRemote(): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val client = webdavClient
            if (client == null) {
                Log.w(TAG, "WebDAV client not configured")
                return@withContext Result.failure(Exception("WebDAV client not configured"))
            }

            Log.d(TAG, "Syncing models from remote: $remoteModelDir")

            // 1. 下载模型配置文件
            val configDownloaded = downloadModelConfig(client)
            if (!configDownloaded) {
                Log.w(TAG, "Failed to download model config")
            }

            // 2. 重新加载配置
            val models = loadModelConfig()
            if (models.isEmpty()) {
                Log.w(TAG, "No models configured")
                return@withContext Result.success(0)
            }

            // 3. 检查本地模型状态并下载缺失的模型
            var syncCount = 0
            for (model in models) {
                val modelFile = File(modelDir, model.fileName)
                if (!modelFile.exists()) {
                    Log.d(TAG, "Downloading missing model: ${model.name}")
                    try {
                        val downloadResult = downloadModel(model.id)
                        if (downloadResult.isSuccess) {
                            syncCount++
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to download model ${model.id}: ${e.message}")
                    }
                }
            }

            Log.i(TAG, "Model sync completed: $syncCount models downloaded")

            // 如果当前模型不存在，切换到默认模型
            val currentModel = models.find { it.id == currentModelId }
            if (currentModel == null || !currentModel.isDownloaded) {
                val defaultModel = models.find { it.id == DEFAULT_MODEL_ID }
                if (defaultModel != null && defaultModel.isDownloaded) {
                    Log.d(TAG, "Switching to default model: ${defaultModel.name}")
                    switchModel(DEFAULT_MODEL_ID)
                }
            }

            Result.success(syncCount)
        } catch (e: Exception) {
            Log.e(TAG, "Model sync failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * 同步模型配置到远程服务器
     */
    override suspend fun syncModelConfigToRemote(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val client = webdavClient
            if (client == null) {
                Log.w(TAG, "WebDAV client not configured")
                return@withContext Result.failure(Exception("WebDAV client not configured"))
            }

            if (!modelConfigFile.exists()) {
                Log.w(TAG, "Local model config not found")
                return@withContext Result.failure(Exception("Local model config not found"))
            }

            val configData = modelConfigFile.readBytes()

            // 创建临时文件进行上传
            val tempFile = File.createTempFile("model_config", ".json").apply {
                writeBytes(configData)
                deleteOnExit()
            }

            // 构造完整的远程路径：baseDir + remoteConfigDir + fileName
            val fullRemotePath = "${client.monitorDir}/${remoteConfigDir}"
            val uploaded = client.uploadFile(fullRemotePath, MODEL_CONFIG_FILE, tempFile)

            // 清理临时文件
            tempFile.delete()

            if (uploaded) {
                Log.i(TAG, "Model config uploaded to $remoteConfigDir/$MODEL_CONFIG_FILE")
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to upload model config"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Model config sync failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun downloadModel(modelId: String): Result<ModelInfo> = withContext(Dispatchers.IO) {
        try {
            val client = webdavClient
            if (client == null) {
                return@withContext Result.failure(Exception("WebDAV client not configured"))
            }

            val models = loadModelConfig()
            val model = models.find { it.id == modelId }

            if (model == null) {
                return@withContext Result.failure(Exception("Model not found: $modelId"))
            }

            Log.d(TAG, "Downloading model: ${model.name} (${model.fileName})")

            // 构造完整的远程路径：baseDir + remoteModelDir + fileName
            val fullRemotePath = "${client.monitorDir}/${remoteModelDir}"
            val data = client.downloadFile(fullRemotePath, model.fileName)
            if (data.isEmpty()) {
                return@withContext Result.failure(Exception("Downloaded empty model file"))
            }

            // 保存模型文件
            val modelFile = File(modelDir, model.fileName)
            modelFile.writeBytes(data)

            // 更新模型状态
            val updatedModel = model.copy(
                isDownloaded = true,
                fileSize = data.size.toLong(),
                lastModified = System.currentTimeMillis()
            )

            saveModelConfig(models.map { if (it.id == modelId) updatedModel else it })

            Log.i(TAG, "Model downloaded: ${model.name} (${data.size} bytes)")

            Result.success(updatedModel)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download model: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun deleteModel(modelId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (modelId == DEFAULT_MODEL_ID) {
                return@withContext Result.failure(Exception("Cannot delete default model"))
            }

            val models = loadModelConfig()
            val model = models.find { it.id == modelId }

            if (model == null) {
                return@withContext Result.failure(Exception("Model not found: $modelId"))
            }

            // 删除文件
            val modelFile = File(modelDir, model.fileName)
            if (modelFile.exists()) {
                val deleted = modelFile.delete()
                if (!deleted) {
                    Log.w(TAG, "Failed to delete model file: ${model.fileName}")
                }
            }

            // 更新配置
            val updatedModels = models.map { if (it.id == modelId) it.copy(isDownloaded = false) else it }
            saveModelConfig(updatedModels)

            // 如果删除的是当前模型，切换到默认模型
            if (currentModelId == modelId) {
                switchModel(DEFAULT_MODEL_ID)
            }

            Log.i(TAG, "Model deleted: ${model.name}")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete model: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * 根据AI配置更新WebDAV客户端
     */
    private fun updateWebDavFromAiConfig(aiConfig: AiConfig) {
        // 更新远程目录路径
        remoteModelDir = aiConfig.remoteModelDir
        remoteConfigDir = aiConfig.remoteConfigDir

        // 如果有AI WebDAV服务器配置，尝试创建客户端
        if (aiConfig.webdavServers.isNotEmpty()) {
            // 使用第一个可用的服务器
            val server = aiConfig.webdavServers.firstOrNull { it.url.isNotEmpty() }
            if (server != null) {
                try {
                    val client = WebDavClient.fromAiServer(server)
                    setWebDavClient(client, aiConfig.remoteModelDir)
                    Log.d(TAG, "WebDAV client updated from AI config: ${server.url}")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to create WebDAV client from AI config: ${e.message}")
                }
            }
        }

        Log.d(TAG, "AI WebDAV config updated: modelDir=$remoteModelDir, configDir=$remoteConfigDir")
    }

    /**
     * 设置 WebDAV 客户端
     */
    fun setWebDavClient(client: WebDavClient, baseRemoteDir: String = "AI/Models") {
        this.webdavClient = client
        this.remoteModelDir = baseRemoteDir
        Log.d(TAG, "WebDAV configured: remoteModelDir=$remoteModelDir")
    }

    /**
     * 下载模型配置文件
     */
    private suspend fun downloadModelConfig(client: WebDavClient): Boolean {
        return try {
            // 构造完整的远程路径：baseDir + remoteConfigDir + fileName
            val fullRemotePath = "${client.monitorDir}/${remoteConfigDir}"
            val configData = client.downloadFile(fullRemotePath, MODEL_CONFIG_FILE)
            if (configData.isNotEmpty()) {
                modelConfigFile.writeBytes(configData)
                Log.d(TAG, "Model config downloaded from $fullRemotePath")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to download model config: ${e.message}")
            false
        }
    }

    /**
     * 加载模型配置
     */
    private suspend fun loadModelConfig(): List<ModelInfo> = withContext(Dispatchers.IO) {
        try {
            if (!modelConfigFile.exists()) {
                // 创建默认配置
                return@withContext createDefaultModelConfig()
            }

            val configText = modelConfigFile.readText()
            val jsonArray = JSONObject(configText).getJSONArray("models")

            val models = mutableListOf<ModelInfo>()
            for (i in 0 until jsonArray.length()) {
                val jsonObj = jsonArray.getJSONObject(i)
                val model = ModelInfo(
                    id = jsonObj.getString("id"),
                    name = jsonObj.getString("name"),
                    version = jsonObj.getString("version"),
                    fileName = jsonObj.getString("fileName"),
                    fileSize = jsonObj.optLong("fileSize", 0L),
                    description = jsonObj.optString("description"),
                    downloadUrl = jsonObj.optString("downloadUrl"),
                    lastModified = jsonObj.optLong("lastModified", 0L)
                )

                // 检查文件是否存在
                val modelFile = File(modelDir, model.fileName)
                models.add(model.copy(isDownloaded = modelFile.exists()))
            }

            models
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model config: ${e.message}", e)
            createDefaultModelConfig()
        }
    }

    /**
     * 保存模型配置
     */
    private suspend fun saveModelConfig(models: List<ModelInfo>) = withContext(Dispatchers.IO) {
        try {
            val jsonObject = JSONObject()
            val jsonArray = org.json.JSONArray()

            for (model in models) {
                val modelObj = JSONObject().apply {
                    put("id", model.id)
                    put("name", model.name)
                    put("version", model.version)
                    put("fileName", model.fileName)
                    put("fileSize", model.fileSize)
                    put("description", model.description)
                    put("downloadUrl", model.downloadUrl)
                    put("lastModified", model.lastModified)
                }
                jsonArray.put(modelObj)
            }

            jsonObject.put("models", jsonArray)
            modelConfigFile.writeText(jsonObject.toString(2))

            Log.d(TAG, "Model config saved")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save model config: ${e.message}", e)
        }
    }

    /**
     * 创建默认模型配置
     */
    private fun createDefaultModelConfig(): List<ModelInfo> {
        return listOf(
            ModelInfo(
                id = DEFAULT_MODEL_ID,
                name = "聊天界面检测模型 v1",
                version = "1.0.0",
                fileName = "chat_detector.tflite",
                fileSize = 0L,
                description = "用于检测微信、QQ等聊天应用的界面",
                isDownloaded = File(modelDir, "chat_detector.tflite").exists()
            )
        )
    }

    /**
     * 从AI配置加载当前模型
     */
    private suspend fun loadCurrentModelFromAiConfig() {
        try {
            val aiConfig = aiConfigRepository.getCurrentAiConfig()
            currentModelId = aiConfig.currentModelId
            Log.d(TAG, "Loaded current model from AI config: $currentModelId")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load current model from AI config: ${e.message}")
        }
    }

    /**
     * 保存当前模型到AI配置
     */
    private suspend fun saveCurrentModelToAiConfig(modelId: String) {
        try {
            val currentConfig = aiConfigRepository.getCurrentAiConfig()
            val updatedConfig = currentConfig.copy(currentModelId = modelId)
            aiConfigRepository.updateAiConfig(updatedConfig)
            Log.d(TAG, "Current model saved to AI config: $modelId")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save current model to AI config: ${e.message}")
        }
    }
}