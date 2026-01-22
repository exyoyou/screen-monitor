package com.youyou.monitor.infra.repository

import android.content.Context
import com.youyou.monitor.core.domain.model.MonitorConfig
import com.youyou.monitor.core.domain.repository.ConfigRepository
import com.youyou.monitor.core.domain.repository.StorageRepository
import com.youyou.monitor.infra.logger.Log
import com.youyou.monitor.infra.network.WebDavClient
import com.youyou.monitor.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * 配置仓储实现
 *
 * 功能：
 * - 本地配置文件管理
 * - 远程配置同步（WebDAV）
 * - 配置变更通知（Flow）
 */
class ConfigRepositoryImpl(
    private val context: Context,
    private val storageRepository: StorageRepository
) : ConfigRepository {

    companion object {
        const val TAG = "ConfigRepository"
        const val CONFIG_FILE_NAME = "config.json"
        const val DEBUG_CONFIG_FILE_NAME = "debug_config.json"
        const val DEFAULT_CONFIG_ASSET = "monitor_config_default.json"
    }

    /**
     * 获取远程配置文件名
     * 在 debug 模式下使用 debug_config.json，否则使用 config.json
     */
    private fun getRemoteConfigFileName(): String {
        return if (BuildConfig.DEBUG) {
            DEBUG_CONFIG_FILE_NAME
        } else {
            CONFIG_FILE_NAME
        }
    }

    private var configFile = File(context.filesDir, CONFIG_FILE_NAME) // 临时初始化，后续会更新

    private val _configFlow = MutableStateFlow(MonitorConfig.default())

    // WebDAV 客户端（可选，用于远程同步）
    private var webdavClient: WebDavClient? = null

    // 设备ID提供者（外部设置，用于创建 WebDavClient）
    private var deviceIdProvider: (() -> String)? = null

    // 配置变化回调（用于通知 webdavServers 变化）: (newServers, fastestServer, fastestClient)
    private var onWebDavServersChanged: ((List<com.youyou.monitor.core.domain.model.WebDavServer>, com.youyou.monitor.core.domain.model.WebDavServer?, WebDavClient?) -> Unit)? =
        null

    init {
        // 初始化时加载配置（优先级：本地文件 > assets 默认 > 硬编码默认）
        loadLocalConfig()
    }

    override fun getConfigFlow(): Flow<MonitorConfig> = _configFlow.asStateFlow()

    override suspend fun getCurrentConfig(): MonitorConfig = _configFlow.value

    override suspend fun updateConfig(config: MonitorConfig) = withContext(Dispatchers.IO) {
        try {
            updateConfigFileLocation(config)
            Log.i(TAG, "配置已更新: threshold=${config.matchThreshold}")
        } catch (e: Exception) {
            Log.e(TAG, "更新配置失败: ${e.message}", e)
        }
    }

    override suspend fun syncFromRemote(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "正在从远程同步配置...")

            // 获取当前配置中的所有服务器
            val currentConfig = _configFlow.value
            if (currentConfig.webdavServers.isEmpty()) {
                Log.w(TAG, "未配置WebDAV服务器")
                return@withContext Result.failure(Exception("No WebDAV servers configured"))
            }

            // 测试所有服务器的连接速度
            Log.d(TAG, "正在测试所有 ${currentConfig.webdavServers.size} 个服务器的连接速度...")
            val serverResults =
                mutableListOf<Pair<com.youyou.monitor.core.domain.model.WebDavServer, Long>>()
            val tempClients = mutableListOf<WebDavClient>()  // 追踪临时客户端

            for (server in currentConfig.webdavServers) {
                if (server.url.isEmpty()) continue

                try {
                    Log.d(TAG, "正在测试服务器: ${server.url}")
                    val client = WebDavClient.fromServer(server, deviceIdProvider)
                    tempClients.add(client)  // 追踪以便后续关闭

                    // 测试连接并记录响应时间
                    val startTime = System.currentTimeMillis()
                    val connected = client.testConnection()
                    val responseTime = System.currentTimeMillis() - startTime

                    if (connected) {
                        serverResults.add(server to responseTime)
                        Log.d(TAG, "服务器 ${server.url} 在 ${responseTime}ms 内响应")
                    } else {
                        Log.d(TAG, "服务器 ${server.url} 连接失败")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "测试 ${server.url} 失败: ${e.message}")
                }
            }

            if (serverResults.isEmpty()) {
                // 关闭所有临时客户端
                tempClients.forEach { it.close() }
                Log.e(TAG, "所有WebDAV服务器连接失败")
                return@withContext Result.failure(Exception("All WebDAV servers failed to connect"))
            }

            // 按响应时间排序，选择最快的服务器
            serverResults.sortBy { it.second }
            val fastestServer = serverResults.first()
            Log.i(TAG, "最快服务器: ${fastestServer.first.url} (${fastestServer.second}ms)")

            // 关闭非最快的临时客户端，保留最快的
            val fastestServerUrl = fastestServer.first.url
            tempClients.forEach { client ->
                if (client.webdavUrl != fastestServerUrl) {
                    client.close()
                }
            }

            // 使用最快的服务器下载配置
            try {
                val server = fastestServer.first
                val client = WebDavClient.fromServer(server, deviceIdProvider)

                val remoteConfigFileName = getRemoteConfigFileName()
                val remotePath = "/" + server.monitorDir.trim('/')
                Log.d(TAG, "正在从最快服务器下载配置: $remotePath/$remoteConfigFileName")
                val data = client.downloadFile(remotePath, remoteConfigFileName)

                if (data.isEmpty()) {
                    Log.e(TAG, "在最快服务器上未找到配置文件: $remoteConfigFileName")
                    return@withContext Result.failure(Exception("Config file not found: $remoteConfigFileName"))
                }

                val json = String(data, Charsets.UTF_8)
                val config = parseConfig(json)

                // 检查 webdavServers 是否变化
                val oldServers = _configFlow.value.webdavServers
                val newServers = config.webdavServers
                val serversChanged = oldServers != newServers

                // 更新配置
                updateConfig(config)

                // 更新当前使用的 webdavClient 为最快的服务器
                this@ConfigRepositoryImpl.webdavClient = client

                // 触发回调，传递最快的服务器和客户端（无论配置是否变化）
                // 首次启动时配置未变化，但也需要配置 WebDAV
                Log.i(TAG, "正在使用最快服务器触发WebDAV配置 (serversChanged=$serversChanged)")
                onWebDavServersChanged?.invoke(newServers, server, client)

                Log.i(TAG, "配置已从最快服务器同步成功: ${server.url}")
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "从最快服务器下载配置失败: ${e.message}")
                Result.failure(e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "从远程同步配置失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * 设置 WebDAV 客户端（用于远程同步）
     */
    fun setWebDavClient(client: WebDavClient) {
        this.webdavClient = client
        Log.d(TAG, "WebDAV客户端已配置")
    }

    /**
     * 设置设备ID提供者（用于创建 WebDavClient）
     */
    fun setDeviceIdProvider(provider: (() -> String)?) {
        this.deviceIdProvider = provider
        Log.d(TAG, "DeviceIdProvider已配置")
    }

    /**
     * 设置 webdavServers 变化回调
     * @param callback 回调参数：(newServers, fastestServer, fastestClient)
     */
    fun setOnWebDavServersChanged(callback: (List<com.youyou.monitor.core.domain.model.WebDavServer>, com.youyou.monitor.core.domain.model.WebDavServer?, WebDavClient?) -> Unit) {
        this.onWebDavServersChanged = callback
        Log.d(TAG, "WebDAV服务器变化回调已注册")
    }

    /**
     * 加载本地配置
     *
     * 优先级：
     * 1. 本地持久化文件 (filesDir/monitor_config.json)
     * 2. assets 默认配置 (monitor_config_default.json)
     * 3. 硬编码默认值 (MonitorConfig.default())
     */
    private fun loadLocalConfig() {
        try {
            // 优先加载本地持久化配置
            if (configFile.exists()) {
                val json = configFile.readText()
                val config = parseConfig(json)
                _configFlow.value = config
                Log.i(TAG, "本地配置已从 ${configFile.absolutePath} 加载")
                return
            }

            // 本地配置不存在，尝试从 assets 加载默认配置
            Log.d(TAG, "未找到本地配置，正在从 assets 加载...")
            val defaultConfig = loadDefaultConfigFromAssets()
            if (defaultConfig != null) {
                // 首次运行，将 assets 配置复制到本地
                updateConfigFileLocation(defaultConfig)
                Log.i(TAG, "默认配置已从 assets 加载并保存到本地")
                return
            }

            // assets 也加载失败，使用硬编码默认值
            Log.w(TAG, "从 assets 加载配置失败，使用硬编码默认值")
        } catch (e: Exception) {
            Log.e(TAG, "加载本地配置失败: ${e.message}", e)
        }
    }

    /**
     * 从 assets 加载默认配置
     */
    private fun loadDefaultConfigFromAssets(): MonitorConfig? {
        return try {
            context.assets.open(DEFAULT_CONFIG_ASSET).use { inputStream ->
                val json = inputStream.bufferedReader().use { it.readText() }
                parseConfig(json)
            }
        } catch (e: Exception) {
            Log.e(TAG, "从 assets 加载默认配置失败: ${e.message}", e)
            null
        }
    }

    /**
     * 保存本地配置
     */
    private fun saveLocalConfig(config: MonitorConfig) {
    }

    /**
     * 解析配置 JSON
     */
    private fun parseConfig(json: String): MonitorConfig {
        val obj = JSONObject(json)

        // 解析 WebDAV 服务器列表
        val webdavServers = mutableListOf<com.youyou.monitor.core.domain.model.WebDavServer>()
        val serversArray = obj.optJSONArray("webdavServers")
        if (serversArray != null) {
            for (i in 0 until serversArray.length()) {
                val serverObj = serversArray.getJSONObject(i)
                webdavServers.add(
                    com.youyou.monitor.core.domain.model.WebDavServer(
                        url = serverObj.optString("url", ""),
                        username = serverObj.optString("username", ""),
                        password = serverObj.optString("password", ""),
                        monitorDir = serverObj.optString("monitorDir", "Monitor"),
                        remoteUploadDir = serverObj.optString("remoteUploadDir", "Monitor/upload"),
                        templateDir = serverObj.optString("templateDir", "Templates")
                    )
                )
            }
        }

        return MonitorConfig(
            matchThreshold = obj.optDouble("matchThreshold", 0.92),
            matchCooldownMs = obj.optLong("matchCooldownMs", 3000L),
            detectPerSecond = obj.optInt("detectPerSecond", 1),
            maxStorageSizeMB = obj.optInt("maxStorageSizeMB", 1024),
            screenshotDir = obj.optString("screenshotDir", "ScreenCaptures"),
            videoDir = obj.optString("videoDir", "ScreenRecord"),
            templateDir = obj.optString("templateDir", "Templates"),
            matcherType = obj.optString("matcherType", "grayscale"),
            preferExternalStorage = obj.optBoolean("preferExternalStorage", false),
            rootDir = obj.optString("rootDir", "PingerLove"),
            webdavServers = webdavServers
        )
    }

    /**
     * 序列化配置为 JSON
     */
    private fun serializeConfig(config: MonitorConfig): String {
        val obj = JSONObject()
        obj.put("matchThreshold", config.matchThreshold)
        obj.put("matchCooldownMs", config.matchCooldownMs)
        obj.put("detectPerSecond", config.detectPerSecond)
        obj.put("maxStorageSizeMB", config.maxStorageSizeMB)
        obj.put("screenshotDir", config.screenshotDir)
        obj.put("videoDir", config.videoDir)
        obj.put("templateDir", config.templateDir)
        obj.put("matcherType", config.matcherType)
        obj.put("preferExternalStorage", config.preferExternalStorage)
        obj.put("rootDir", config.rootDir)

        // 序列化 WebDAV 服务器列表
        val serversArray = org.json.JSONArray()
        config.webdavServers.forEach { server ->
            val serverObj = JSONObject()
            serverObj.put("url", server.url)
            serverObj.put("username", server.username)
            serverObj.put("password", server.password)
            serverObj.put("monitorDir", server.monitorDir)
            serverObj.put("remoteUploadDir", server.remoteUploadDir)
            serverObj.put("templateDir", server.templateDir)
            serversArray.put(serverObj)
        }
        obj.put("webdavServers", serversArray)

        return obj.toString(2)
    }

    private fun getConfigFile(config: MonitorConfig): File {
        val internalFile = File(context.filesDir, "${config.rootDir}/$CONFIG_FILE_NAME")

        return if (config.preferExternalStorage) {
            val externalDir = File("/storage/emulated/0/", config.rootDir)
            // 尝试创建外部目录
            if (externalDir.exists() || externalDir.mkdirs()) {
                val externalFile = File(externalDir, CONFIG_FILE_NAME)
                Log.d(TAG, "使用外部存储: ${externalFile.absolutePath}")
                externalFile
            } else {
                Log.w(TAG, "无法创建外部存储目录，使用内部存储")
                internalFile
            }
        } else {
            Log.d(TAG, "使用内部存储: ${internalFile.absolutePath}")
            internalFile
        }
    }


    /**
     * 更新配置文件位置
     */
    private fun updateConfigFileLocation(config: MonitorConfig) {
        val newConfigFile = getConfigFile(config)
        if (newConfigFile != configFile) {
            // 如果位置改变，需要迁移现有配置文件
            if (configFile.exists()) {
                if (!newConfigFile.exists())
                    try {
                        if (configFile.parentFile?.exists() == false) {
                            configFile.parentFile?.mkdirs()
                        }
                        configFile.copyTo(newConfigFile)
                        Log.i(TAG, "配置文件已迁移到新位置: ${newConfigFile.absolutePath}")
                    } catch (e: Exception) {
                        Log.e(TAG, "迁移配置文件失败: ${e.message}", e)
                    }
                else {
                    Log.w(TAG, "新配置文件已存在，跳过迁移")
                }
                configFile.delete()
                Log.i(TAG, "旧配置文件已删除 ${configFile.absolutePath}")
            }
            configFile = newConfigFile
        } else {
            Log.d(TAG, "配置文件位置未改变")
        }
        try {
            if (_configFlow.value != config) {
                val json = serializeConfig(config)
                configFile.writeText(json)
                Log.d(TAG, "本地配置已保存")
                _configFlow.value = config
            }
        } catch (e: Exception) {
            Log.e(TAG, "保存本地配置失败: ${e.message}", e)
        }
    }
}
