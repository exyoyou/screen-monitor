package com.youyou.monitor.infra.repository

import android.content.Context
import com.youyou.monitor.core.domain.model.AiConfig
import com.youyou.monitor.core.domain.model.AiWebDavServer
import com.youyou.monitor.core.domain.repository.AiConfigRepository
import com.youyou.monitor.infra.logger.Log
import com.youyou.monitor.infra.network.WebDavClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * AI 配置仓储实现
 *
 * 功能：
 * - 独立的AI配置文件管理 (ai_config.json)
 * - AI专用WebDAV服务器配置
 * - 配置变更通知
 */
class AiConfigRepositoryImpl(
    private val context: Context
) : AiConfigRepository {

    companion object {
        const val TAG = "AiConfigRepository"
        const val AI_CONFIG_FILE_NAME = "ai_config.json"
        const val DEFAULT_AI_CONFIG_ASSET = "ai_config_default.json"
    }

    private val aiConfigFile = File(context.filesDir, AI_CONFIG_FILE_NAME)
    private val _aiConfigFlow = MutableStateFlow(AiConfig.default())

    init {
        // 初始化时加载AI配置
        loadAiConfig()
    }

    override fun getAiConfigFlow(): Flow<AiConfig> = _aiConfigFlow.asStateFlow()

    override suspend fun getCurrentAiConfig(): AiConfig = _aiConfigFlow.value

    override suspend fun updateAiConfig(config: AiConfig) = withContext(Dispatchers.IO) {
        try {
            // 更新内存
            _aiConfigFlow.value = config

            // 保存到本地
            saveAiConfig(config)

            Log.i(TAG, "AI config updated: currentModelId=${config.currentModelId}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update AI config: ${e.message}", e)
        }
    }

    override suspend fun syncFromRemote(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Syncing AI config from remote...")

            val currentConfig = _aiConfigFlow.value
            if (currentConfig.webdavServers.isEmpty()) {
                Log.w(TAG, "No AI WebDAV servers configured")
                return@withContext Result.failure(Exception("No AI WebDAV servers configured"))
            }

            // 尝试从每个服务器下载配置，选择第一个成功的
            for (server in currentConfig.webdavServers) {
                if (server.url.isEmpty()) continue

                try {
                    Log.d(TAG, "Trying to download AI config from: ${server.url}")
                    val client = WebDavClient.fromAiServer(server)

                    // 从远程配置目录下载AI配置文件
                    val remoteConfigPath = "${currentConfig.remoteConfigDir}/$AI_CONFIG_FILE_NAME"
                    val configData = client.downloadFile(server.baseDir, remoteConfigPath)

                    if (configData.isNotEmpty()) {
                        // 解析并更新配置
                        val remoteConfig = parseAiConfigFromJson(String(configData))
                        updateAiConfig(remoteConfig)

                        Log.i(TAG, "AI config synced from ${server.url}")
                        return@withContext Result.success(Unit)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to sync from server ${server.url}: ${e.message}")
                }
            }

            Log.w(TAG, "Failed to sync AI config from any server")
            Result.failure(Exception("Failed to sync AI config from any server"))
        } catch (e: Exception) {
            Log.e(TAG, "AI config sync failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun syncToRemote(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Syncing AI config to remote...")

            val currentConfig = _aiConfigFlow.value
            if (currentConfig.webdavServers.isEmpty()) {
                Log.w(TAG, "No AI WebDAV servers configured")
                return@withContext Result.failure(Exception("No AI WebDAV servers configured"))
            }

            val configJson = aiConfigToJson(currentConfig)
            val configData = configJson.toString().toByteArray()

            // 上传到所有配置的服务器
            var successCount = 0
            for (server in currentConfig.webdavServers) {
                if (server.url.isEmpty()) continue

                try {
                    val client = WebDavClient.fromAiServer(server)
                    val remoteConfigPath = "${currentConfig.remoteConfigDir}/$AI_CONFIG_FILE_NAME"

                    // 创建临时文件
                    val tempFile = File.createTempFile("ai_config", ".json").apply {
                        writeBytes(configData)
                        deleteOnExit()
                    }

                    val uploaded = client.uploadFile(server.baseDir, remoteConfigPath, tempFile)
                    if (uploaded) {
                        successCount++
                        Log.d(TAG, "AI config uploaded to ${server.url}")
                    }

                    // 清理临时文件
                    tempFile.delete()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to upload to server ${server.url}: ${e.message}")
                }
            }

            if (successCount > 0) {
                Log.i(TAG, "AI config synced to $successCount servers")
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to upload AI config to any server"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "AI config upload failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * 加载AI配置
     */
    private fun loadAiConfig() {
        try {
            val config = if (aiConfigFile.exists()) {
                // 优先从本地文件加载
                val configText = aiConfigFile.readText()
                parseAiConfigFromJson(configText)
            } else {
                // 从assets加载默认配置
                val defaultConfigText = context.assets.open(DEFAULT_AI_CONFIG_ASSET).bufferedReader().use { it.readText() }
                val defaultConfig = parseAiConfigFromJson(defaultConfigText)
                // 保存默认配置到本地
                saveAiConfig(defaultConfig)
                defaultConfig
            }

            _aiConfigFlow.value = config
            Log.d(TAG, "AI config loaded: currentModelId=${config.currentModelId}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load AI config: ${e.message}", e)
            // 使用默认配置
            _aiConfigFlow.value = AiConfig.default()
        }
    }

    /**
     * 保存AI配置到本地
     */
    private fun saveAiConfig(config: AiConfig) {
        try {
            val configJson = aiConfigToJson(config)
            aiConfigFile.writeText(configJson.toString(2))
            Log.d(TAG, "AI config saved to ${aiConfigFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save AI config: ${e.message}", e)
        }
    }

    /**
     * 解析JSON到AI配置
     */
    private fun parseAiConfigFromJson(jsonText: String): AiConfig {
        val json = JSONObject(jsonText)

        val webdavServers = if (json.has("webdavServers")) {
            val serversArray = json.getJSONArray("webdavServers")
            (0 until serversArray.length()).map { i ->
                val serverJson = serversArray.getJSONObject(i)
                AiWebDavServer(
                    url = serverJson.getString("url"),
                    username = serverJson.getString("username"),
                    password = serverJson.getString("password"),
                    baseDir = serverJson.optString("baseDir", "")
                )
            }
        } else {
            emptyList()
        }

        return AiConfig(
            currentModelId = json.optString("currentModelId", "chat_detector_v1"),
            enableAutoSync = json.optBoolean("enableAutoSync", true),
            syncIntervalHours = json.optInt("syncIntervalHours", 24),
            remoteModelDir = json.optString("remoteModelDir", "AI/Models"),
            remoteConfigDir = json.optString("remoteConfigDir", "AI/Config"),
            webdavServers = webdavServers
        )
    }

    /**
     * AI配置转换为JSON
     */
    private fun aiConfigToJson(config: AiConfig): JSONObject {
        val json = JSONObject()

        json.put("currentModelId", config.currentModelId)
        json.put("enableAutoSync", config.enableAutoSync)
        json.put("syncIntervalHours", config.syncIntervalHours)
        json.put("remoteModelDir", config.remoteModelDir)
        json.put("remoteConfigDir", config.remoteConfigDir)

        val serversArray = JSONArray()
        config.webdavServers.forEach { server ->
            val serverJson = JSONObject()
            serverJson.put("url", server.url)
            serverJson.put("username", server.username)
            serverJson.put("password", server.password)
            serverJson.put("baseDir", server.baseDir)
            serversArray.put(serverJson)
        }
        json.put("webdavServers", serversArray)

        return json
    }
}