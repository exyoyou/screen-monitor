package com.youyou.monitor.infra.repository

import android.content.Context
import com.youyou.monitor.core.domain.model.MonitorConfig
import com.youyou.monitor.core.domain.repository.ConfigRepository
import com.youyou.monitor.infra.logger.Log
import com.youyou.monitor.infra.network.WebDavClient
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
    private val context: Context
) : ConfigRepository {
    
    companion object {
        const val TAG = "ConfigRepository"
        const val CONFIG_FILE_NAME = "config.json"
        const val DEFAULT_CONFIG_ASSET = "monitor_config_default.json"
    }
    
    private val configFile = File(context.filesDir, CONFIG_FILE_NAME)
    private val _configFlow = MutableStateFlow(MonitorConfig.default())
    
    // WebDAV 客户端（可选，用于远程同步）
    private var webdavClient: WebDavClient? = null
    
    // 设备ID提供者（外部设置，用于创建 WebDavClient）
    private var deviceIdProvider: (() -> String)? = null
    
    // 配置变化回调（用于通知 webdavServers 变化）: (newServers, fastestServer, fastestClient)
    private var onWebDavServersChanged: ((List<com.youyou.monitor.core.domain.model.WebDavServer>, com.youyou.monitor.core.domain.model.WebDavServer?, WebDavClient?) -> Unit)? = null
    
    init {
        // 初始化时加载配置（优先级：本地文件 > assets 默认 > 硬编码默认）
        loadLocalConfig()
    }
    
    override fun getConfigFlow(): Flow<MonitorConfig> = _configFlow.asStateFlow()
    
    override suspend fun getCurrentConfig(): MonitorConfig = _configFlow.value
    
    override suspend fun updateConfig(config: MonitorConfig) = withContext(Dispatchers.IO) {
        try {
            // 更新内存
            _configFlow.value = config
            
            // 保存到本地
            saveLocalConfig(config)
            
            Log.i(TAG, "Config updated: threshold=${config.matchThreshold}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update config: ${e.message}", e)
        }
    }
    
    override suspend fun syncFromRemote(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Syncing config from remote...")
            
            // 获取当前配置中的所有服务器
            val currentConfig = _configFlow.value
            if (currentConfig.webdavServers.isEmpty()) {
                Log.w(TAG, "No WebDAV servers configured")
                return@withContext Result.failure(Exception("No WebDAV servers configured"))
            }
            
            // 测试所有服务器的连接速度
            Log.d(TAG, "Testing connection speed for all ${currentConfig.webdavServers.size} servers...")
            val serverResults = mutableListOf<Pair<com.youyou.monitor.core.domain.model.WebDavServer, Long>>()
            val tempClients = mutableListOf<WebDavClient>()  // 追踪临时客户端
            
            for (server in currentConfig.webdavServers) {
                if (server.url.isEmpty()) continue
                
                try {
                    Log.d(TAG, "Testing server: ${server.url}")
                    val client = WebDavClient.fromServer(server, deviceIdProvider)
                    tempClients.add(client)  // 追踪以便后续关闭
                    
                    // 测试连接并记录响应时间
                    val startTime = System.currentTimeMillis()
                    val connected = client.testConnection()
                    val responseTime = System.currentTimeMillis() - startTime
                    
                    if (connected) {
                        serverResults.add(server to responseTime)
                        Log.d(TAG, "Server ${server.url} responded in ${responseTime}ms")
                    } else {
                        Log.d(TAG, "Server ${server.url} connection failed")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to test ${server.url}: ${e.message}")
                }
            }
            
            if (serverResults.isEmpty()) {
                // 关闭所有临时客户端
                tempClients.forEach { it.close() }
                Log.e(TAG, "All WebDAV servers failed to connect")
                return@withContext Result.failure(Exception("All WebDAV servers failed to connect"))
            }
            
            // 按响应时间排序，选择最快的服务器
            serverResults.sortBy { it.second }
            val fastestServer = serverResults.first()
            Log.i(TAG, "Fastest server: ${fastestServer.first.url} (${fastestServer.second}ms)")
            
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
                
                val remotePath = "/" + server.monitorDir.trim('/')
                Log.d(TAG, "Downloading config from fastest server: $remotePath/$CONFIG_FILE_NAME")
                val data = client.downloadFile(remotePath, CONFIG_FILE_NAME)
                
                if (data.isEmpty()) {
                    Log.e(TAG, "Config file not found on fastest server")
                    return@withContext Result.failure(Exception("Config file not found"))
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
                Log.i(TAG, "Triggering WebDAV configuration with fastest server (serversChanged=$serversChanged)")
                onWebDavServersChanged?.invoke(newServers, server, client)
                
                Log.i(TAG, "Config synced successfully from fastest server: ${server.url}")
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to download config from fastest server: ${e.message}")
                Result.failure(e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync config from remote: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * 设置 WebDAV 客户端（用于远程同步）
     */
    fun setWebDavClient(client: WebDavClient) {
        this.webdavClient = client
        Log.d(TAG, "WebDAV client configured")
    }
    
    /**
     * 设置设备ID提供者（用于创建 WebDavClient）
     */
    fun setDeviceIdProvider(provider: (() -> String)?) {
        this.deviceIdProvider = provider
        Log.d(TAG, "DeviceIdProvider configured")
    }
    
    /**
     * 设置 webdavServers 变化回调
     * @param callback 回调参数：(newServers, fastestServer, fastestClient)
     */
    fun setOnWebDavServersChanged(callback: (List<com.youyou.monitor.core.domain.model.WebDavServer>, com.youyou.monitor.core.domain.model.WebDavServer?, WebDavClient?) -> Unit) {
        this.onWebDavServersChanged = callback
        Log.d(TAG, "WebDAV servers change callback registered")
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
                Log.i(TAG, "Local config loaded from ${configFile.absolutePath}")
                return
            }
            
            // 本地配置不存在，尝试从 assets 加载默认配置
            Log.d(TAG, "No local config found, loading from assets...")
            val defaultConfig = loadDefaultConfigFromAssets()
            if (defaultConfig != null) {
                _configFlow.value = defaultConfig
                // 首次运行，将 assets 配置复制到本地
                saveLocalConfig(defaultConfig)
                Log.i(TAG, "Default config loaded from assets and saved locally")
                return
            }
            
            // assets 也加载失败，使用硬编码默认值
            Log.w(TAG, "Failed to load config from assets, using hardcoded defaults")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load local config: ${e.message}", e)
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
            Log.e(TAG, "Failed to load default config from assets: ${e.message}", e)
            null
        }
    }
    
    /**
     * 保存本地配置
     */
    private fun saveLocalConfig(config: MonitorConfig) {
        try {
            val json = serializeConfig(config)
            configFile.writeText(json)
            Log.d(TAG, "Local config saved")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save local config: ${e.message}", e)
        }
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
}
