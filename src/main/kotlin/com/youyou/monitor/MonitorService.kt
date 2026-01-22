package com.youyou.monitor

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.youyou.monitor.core.domain.model.ImageFrame
import com.youyou.monitor.core.domain.model.MonitorConfig
import com.youyou.monitor.core.domain.usecase.CleanStorageUseCase
import com.youyou.monitor.core.domain.usecase.ManageTemplatesUseCase
import com.youyou.monitor.infra.logger.Log
import com.youyou.monitor.infra.network.WebDavClient
import com.youyou.monitor.infra.processor.AdvancedFrameProcessor
import com.youyou.monitor.core.domain.repository.ConfigRepository
import com.youyou.monitor.infra.repository.ConfigRepositoryImpl
import com.youyou.monitor.infra.repository.TemplateRepositoryImpl
import com.youyou.monitor.infra.task.ScheduledTaskManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.component.inject
import org.opencv.android.OpenCVLoader
import java.nio.ByteBuffer

/**
 * 屏幕监控服务（Facade 模式 - 对外统一接口）
 * 
 * 完整功能：
 * - 高级帧处理（频率限制、去重、质量检测）
 * - 定时任务（配置同步、上传、清理）
 * - WebDAV 配置管理
 * - 模板同步
 * 
 * 使用示例：
 * ```kotlin
 * // 1. 初始化（Application onCreate）
 * MonitorService.init(applicationContext)
 * 
 * // 2. 配置 WebDAV（可选）
 * val monitor = MonitorService.getInstance()
 * monitor.configureWebDav(url, username, password)
 * 
 * // 3. 启动监控
 * monitor.start()
 * 
 * // 4. 处理帧
 * monitor.onFrameAvailable(buffer, width, height)
 * 
 * // 5. 停止
 * monitor.stop()
 * ```
 */
class MonitorService private constructor(
    private val context: Context
) : KoinComponent {
    
    companion object {
        private const val TAG = "MonitorService"
        
        @Volatile
        private var instance: MonitorService? = null
        
        // 设备ID获取函数（由外部app层传入）
        private var deviceIdProvider: (() -> String)? = null
        
        // 根目录路径通知函数（由外部app层传入）
        private var notifyRootDirPathProvider: (() -> String)? = null
        
        /**
         * 初始化（Application onCreate 调用）
         * @param deviceIdProvider 设备ID获取函数（例如：{ FFI.getMyId() }）
         */
        fun init(context: Context, deviceIdProvider: (() -> String)? = null) {
            if (instance == null) {
                synchronized(this) {
                    if (instance == null) {
                        try {
                            // 1. 最先初始化日志系统
                            Log.init(context)
                            Log.i(TAG, "日志系统初始化完成")
                            
                            // 2. 初始化 OpenCV
                            if (!OpenCVLoader.initDebug()) {
                                Log.e(TAG, "OpenCV初始化失败！")
                                throw RuntimeException("OpenCV initialization failed")
                            }
                            Log.i(TAG, "OpenCV初始化成功")
                            
                            // 3. 初始化 Koin（注意：initKoin 内部也会调用 Log.init，但这里已经初始化过了）
                            com.youyou.monitor.di.initKoin(context)
                            this.deviceIdProvider = deviceIdProvider
                            instance = MonitorService(context.applicationContext)
                            
                            // 不要在 init 时调用 deviceIdProvider，避免过早触发 FFI.getMyId()
                            Log.i(TAG, "MonitorService初始化成功 (deviceIdProvider=${if (deviceIdProvider != null) "已提供" else "未提供"})")
                        } catch (e: Exception) {
                            Log.e(TAG, "初始化失败：${e.message}", e)
                            throw e  // 重新抛出，确保调用方知道失败
                        }
                    }
                }
            }
        }
        
        /**
         * 设置根目录路径通知提供者（Application onCreate 调用）
         * @param provider 根目录路径获取函数（例如：{ FFI.getRootDirPath() }）
         */
        fun setNotifyRootDirPathProvider(provider: (() -> String)? = null) {
            notifyRootDirPathProvider = provider
        }
        
        /**
         * 获取实例
         */
        fun getInstance(): MonitorService {
            return instance ?: throw IllegalStateException(
                "MonitorService未初始化，请先调用init(context)。"
            )
        }
    }
    
    // 依赖注入
    private val manageTemplatesUseCase: ManageTemplatesUseCase by inject()
    private val cleanStorageUseCase: CleanStorageUseCase by inject()
    private val advancedFrameProcessor: AdvancedFrameProcessor by inject()
    private val scheduledTaskManager: ScheduledTaskManager by inject()
    private val configRepository: ConfigRepository by inject()  // 修改：注入接口而不是实现类
    private val templateRepository: com.youyou.monitor.core.domain.repository.TemplateRepository by inject()
    private val storageRepository: com.youyou.monitor.core.domain.repository.StorageRepository by inject()
    
    // 协程作用域（使用 lazy 延迟初始化，支持 stop 后重新 start）
    @Volatile
    private var scope: CoroutineScope? = null
    
    // 缓存的 Job 引用（避免重复 Map 查找）
    @Volatile
    private var scopeJob: Job? = null
    
    private val scopeLock = Any()
    
    private fun startConfigMonitoring() {
        getScope().launch {
            configRepository.getConfigFlow()
                .distinctUntilChanged { old, new -> 
                    old.rootDir == new.rootDir && old.preferExternalStorage == new.preferExternalStorage 
                }
                .collect {
                    storageRepository.updateConfig(it)
                    Log.updateLogDir { storageRepository.getRootDir() }
                    notifyRootDirPathProvider?.invoke()
                }
        }
    }
    
    private fun getScope(): CoroutineScope {
        // 快速路径：如果 scope 和 job 都存在且活跃，直接返回
        val currentScope = scope
        val currentJob = scopeJob
        if (currentScope != null && currentJob != null && currentJob.isActive) {
            return currentScope
        }
        
        // 慢速路径：需要创建或重建 scope
        return synchronized(scopeLock) {
            val existingScope = scope
            val existingJob = scopeJob
            
            if (existingScope != null && existingJob != null && existingJob.isActive) {
                existingScope
            } else {
                val newJob = SupervisorJob()
                // 在初始化阶段使用 Default 调度器，避免 Main 调度器在 Application.onCreate 时不可用的问题
                val dispatcher = if (isRunning) Dispatchers.Main else Dispatchers.Default
                val newScope = CoroutineScope(dispatcher + newJob)
                scope = newScope
                scopeJob = newJob
                newScope
            }
        }
    }
    
    @Volatile
    private var isRunning = false
    
    // 当前使用的 WebDAV 客户端（需要关闭）
    @Volatile
    private var currentWebDavClient: WebDavClient? = null
    private val webDavClientLock = Any()
    
    // 帧处理状态（防止内存积压）
    @Volatile
    private var isProcessingFrame = false
    
    // 复用的帧缓冲区（避免频繁分配）
    @Volatile
    private var frameBuffer: ByteArray? = null
    private val frameBufferLock = Any()
    
    // 网络变化监听器（用于检测网络切换，如从内网WiFi到外网）
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        private var lastNetworkType: String? = null
        
        override fun onAvailable(network: Network) {
            Log.d(TAG, "网络可用: $network")
            checkNetworkChange()
        }
        
        override fun onLost(network: Network) {
            Log.d(TAG, "网络丢失: $network")
            checkNetworkChange()
        }
        
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            val currentType = when {
                networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
                else -> "OTHER"
            }
            
            if (currentType != lastNetworkType) {
                Log.i(TAG, "网络类型变化: $lastNetworkType -> $currentType")
                lastNetworkType = currentType
                
                // 网络类型变化时，重新评估WebDAV配置
                if (isRunning) {
                    getScope().launch(Dispatchers.IO) {
                        try {
                            Log.i(TAG, "由于网络变化，正在重新评估WebDAV配置")
                            reconfigureWebDavForNetwork()
                        } catch (e: Exception) {
                            Log.e(TAG, "网络变化时重新配置WebDAV失败：${e.message}", e)
                        }
                    }
                }
            }
        }
        
        private fun checkNetworkChange() {
            // 简单的网络变化检查，触发重新评估
            if (isRunning) {
                getScope().launch(Dispatchers.IO) {
                    kotlinx.coroutines.delay(1000) // 等待1秒让网络稳定
                    try {
                        reconfigureWebDavForNetwork()
                    } catch (e: Exception) {
                        Log.w(TAG, "网络变化重新配置失败：${e.message}")
                    }
                }
            }
        }
    }
    
    init {
        Log.d(TAG, "MonitorService已初始化")
        
        // 注册网络变化监听器
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
        Log.d(TAG, "网络变化监听器已注册")
        
        // 设置设备ID提供者到 ConfigRepository
        (configRepository as? ConfigRepositoryImpl)?.setDeviceIdProvider(Companion.deviceIdProvider)
        
        // 注册配置变化监听：当 webdavServers 变化时自动重新配置
        (configRepository as? ConfigRepositoryImpl)?.setOnWebDavServersChanged { newServers, fastestServer, fastestClient ->
            // 只在运行中才处理回调，避免 stop() 后创建新的协程
            if (!isRunning) {
                Log.d(TAG, "服务未运行，跳过WebDAV重新配置")
                return@setOnWebDavServersChanged
            }
            
            Log.i(TAG, "WebDAV服务器已变化，自动重新配置最快服务器：${fastestServer?.url}")
            try {
                getScope().launch {
                    // 使用 ConfigRepository 选择的最快服务器
                    if (fastestServer != null && fastestClient != null) {
                        configureWebDavDirect(fastestServer, fastestClient)
                    } else {
                        autoLoadConfiguration()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "启动WebDAV重新配置失败：${e.message}")
            }
        }
    }
    

    
    /**
     * 启动监控
     */
    fun start() {
        Log.i(TAG, "=== MonitorService.start() 被调用 ===")
        
        // 原子检查并设置（防止并发调用）
        synchronized(this) {
            if (isRunning) {
                Log.w(TAG, "已在运行中，跳过启动")
                return
            }
            isRunning = true
        }
        
        // 启动配置变化监听
        startConfigMonitoring()
        
        // 重置处理器状态
        advancedFrameProcessor.reset()
        Log.d(TAG, "帧处理器已重置")
        
        // 自动加载配置（首次从 assets，后续从 WebDAV）
        Log.d(TAG, "正在启动autoLoadConfiguration协程...")
        getScope().launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "autoLoadConfiguration协程在IO调度器上启动")
                autoLoadConfiguration()
                Log.d(TAG, "autoLoadConfiguration已完成")
            } catch (e: Exception) {
                Log.e(TAG, "自动加载配置失败: ${e.message}", e)
            }
        }
        
        // 启动所有定时任务
        scheduledTaskManager.startAllTasks(
            configUpdateInterval = if (BuildConfig.DEBUG) 1 else 6 * 60,     // DEBUG: 1分钟，非DEBUG: 6小时
            imageUploadInterval = if (BuildConfig.DEBUG) 60 else 5,       // 5分钟上传截图
            videoUploadInterval = 10,      // 10分钟上传视频
            logUploadInterval = 30,        // 30分钟上传日志
            templateSyncInterval = 60,     // 60分钟同步模板
            storageCleanInterval = 360     // 6小时清理存储
        )
    }
    
    /**
     * 直接使用指定的 WebDAV 服务器配置
     * （用于复用 ConfigRepository 选择的最快服务器）
     */
    private suspend fun configureWebDavDirect(
        server: com.youyou.monitor.core.domain.model.WebDavServer,
        client: com.youyou.monitor.infra.network.WebDavClient
    ) = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "正在使用最快的WebDAV服务器: ${server.url}")
            
            // 关闭旧客户端（加锁防止并发）
            val oldClient = synchronized(webDavClientLock) {
                val old = currentWebDavClient
                currentWebDavClient = client
                old
            }
            oldClient?.close()
            
            // 配置到各个 Repository
            (configRepository as? ConfigRepositoryImpl)?.setWebDavClient(client)
            (templateRepository as? TemplateRepositoryImpl)?.setWebDavClient(client, server.templateDir)
            scheduledTaskManager.setWebDavClient(client)
            
            // 同步模板
            templateRepository.syncFromRemote().onSuccess {
                Log.i(TAG, "模板已同步: $it 个模板")
            }.onFailure {
                Log.w(TAG, "模板同步失败: ${it.message}")
            }
            
            Log.i(TAG, "WebDAV已配置最快服务器")
        } catch (e: Exception) {
            Log.e(TAG, "配置WebDAV失败: ${e.message}", e)
        }
    }

    /**
     * 自动加载配置
     * 
     * 流程：
     * 1. 调用 ConfigRepository.syncFromRemote() 同步远程配置（自动选择最快服务器）
     * 2. syncFromRemote 成功后会触发回调，自动配置最快的 WebDAV
     * 3. 如果失败，降级到手动测试本地配置的服务器
     */
    private suspend fun autoLoadConfiguration() = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "=== autoLoadConfiguration 开始 ===")
            
            // 尝试从远程同步配置（会自动测试所有服务器并选择最快的）
            val syncResult = configRepository.syncFromRemote()
            
            if (syncResult.isSuccess) {
                Log.i(TAG, "远程配置已同步，WebDAV通过回调自动配置")
                // syncFromRemote 成功后会自动触发回调，无需手动配置
                return@withContext
            }
            
            // 远程同步失败，使用本地配置降级
            Log.w(TAG, "远程同步失败: ${syncResult.exceptionOrNull()?.message}，尝试本地配置")
            
            val config = configRepository.getCurrentConfig()
            if (config.webdavServers.isEmpty()) {
                Log.w(TAG, "未配置WebDAV服务器")
                return@withContext
            }
            
            // 降级策略：遍历测试所有服务器，使用第一个可用的
            for (server in config.webdavServers) {
                if (server.url.isEmpty()) continue
                
                var client: WebDavClient? = null
                try {
                    client = WebDavClient.fromServer(server, Companion.deviceIdProvider)
                    
                    Log.d(TAG, "正在测试降级服务器: ${server.url}")
                    if (client.testConnection()) {
                        Log.i(TAG, "正在配置降级服务器: ${server.url}")
                        configureWebDavDirect(server, client)
                        return@withContext
                    } else {
                        Log.w(TAG, "降级服务器 ${server.url} 不可用")
                        client.close()  // 测试失败，关闭客户端
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "测试降级服务器失败 ${server.url}: ${e.message}")
                    client?.close()  // 异常时关闭客户端
                }
            }
            
            Log.w(TAG, "所有降级服务器都失败")
        } catch (e: Exception) {
            Log.e(TAG, "autoLoadConfiguration失败: ${e.message}", e)
        }
    }

    /**
     * 网络变化时重新配置WebDAV
     * 用于处理从内网WiFi切换到外网的情况
     */
    private suspend fun reconfigureWebDavForNetwork() = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "=== reconfigureWebDavForNetwork 开始 ===")
            
            val config = configRepository.getCurrentConfig()
            if (config.webdavServers.isEmpty()) {
                Log.d(TAG, "未配置WebDAV服务器，跳过重新配置")
                return@withContext
            }
            
            // 获取当前网络类型
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            
            val isWifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            val isCellular = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
            
            Log.i(TAG, "当前网络 - WiFi: $isWifi, 移动数据: $isCellular")
            
            // 测试所有服务器，选择最快的可用服务器
            var fastestServer: com.youyou.monitor.core.domain.model.WebDavServer? = null
            var fastestClient: WebDavClient? = null
            var fastestResponseTime = Long.MAX_VALUE
            
            for (server in config.webdavServers) {
                if (server.url.isEmpty()) continue
                
                var client: WebDavClient? = null
                try {
                    client = WebDavClient.fromServer(server, Companion.deviceIdProvider)
                    
                    val startTime = System.currentTimeMillis()
                    val isAvailable = client.testConnection()
                    val responseTime = System.currentTimeMillis() - startTime
                    
                    if (isAvailable && responseTime < fastestResponseTime) {
                        fastestResponseTime = responseTime
                        fastestServer = server
                        fastestClient?.close() // 关闭之前的客户端
                        fastestClient = client
                        client = null // 防止被关闭
                        Log.d(TAG, "发现更快的服务器: ${server.url} (${responseTime}ms)")
                    } else {
                        Log.d(TAG, "服务器 ${server.url} ${if (isAvailable) "可用 (${responseTime}ms)" else "不可用"}")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "测试服务器失败 ${server.url}: ${e.message}")
                } finally {
                    client?.close() // 关闭测试用的客户端（除了最快的那个）
                }
            }
            
            if (fastestServer != null && fastestClient != null) {
                Log.i(TAG, "为当前网络重新配置最快服务器: ${fastestServer.url} (${fastestResponseTime}ms)")
                configureWebDavDirect(fastestServer, fastestClient)
            } else {
                Log.w(TAG, "当前网络下未找到可用的WebDAV服务器")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "reconfigureWebDavForNetwork失败: ${e.message}", e)
        }
    }
    
    /**
     * 停止监控
     */
    fun stop() {
        // 原子检查并设置（防止并发调用）
        synchronized(this) {
            if (!isRunning) {
                Log.w(TAG, "已在停止状态，跳过停止操作")
                return
            }
            isRunning = false
        }
        
        // 先取消协程，再关闭其他组件（避免协程还在使用已关闭的资源）
        scope?.cancel()
        scope = null
        scopeJob = null
        
        advancedFrameProcessor.shutdown()
        scheduledTaskManager.shutdown()
        
        // 关闭 WebDAV 客户端（加锁）
        val clientToClose = synchronized(webDavClientLock) {
            val client = currentWebDavClient
            currentWebDavClient = null
            client
        }
        clientToClose?.close()
        
        // 释放帧缓冲区（避免内存泄漏）
        synchronized(frameBufferLock) {
            frameBuffer = null
        }
        
        // 释放 TemplateMatcher 资源（Mat 对象）
        try {
            get<com.youyou.monitor.core.matcher.TemplateMatcherManager>().release()
        } catch (e: Exception) {
            Log.w(TAG, "释放TemplateMatcherManager失败: ${e.message}")
        }
        
        // 关闭日志系统
        com.youyou.monitor.infra.logger.Log.shutdown()
        
        // 取消注册网络监听器
        try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            connectivityManager.unregisterNetworkCallback(networkCallback)
            Log.d(TAG, "网络变化监听器已取消注册")
        } catch (e: Exception) {
            Log.w(TAG, "取消注册网络回调失败: ${e.message}")
        }
    }

    /**
     * 处理帧（使用高级处理器）
     * 
     * @param scale 屏幕缩放比例（1=原始分辨率，2=半分辨率，用于性能优化）
     */
    fun onFrameAvailable(buffer: ByteBuffer, width: Int, height: Int, scale: Int = 1) {
        if (!isRunning) return
        
        // 帧丢弃策略：如果上一帧还在处理中，跳过本帧（避免内存积压）
        if (isProcessingFrame) {
            return
        }
        
        isProcessingFrame = true
        
        // 立即在调用线程复制数据，避免 DirectByteBuffer 失效
        val data = try {
            val requiredSize = width * height * 4
            
            // 复用或创建 ByteArray（避免频繁分配）
            val array = synchronized(frameBufferLock) {
                if (frameBuffer == null || frameBuffer!!.size < requiredSize) {
                    frameBuffer = ByteArray(requiredSize)
                }
                frameBuffer!!
            }
            
            buffer.position(0)
            buffer.get(array, 0, requiredSize)
            
            // 复制到新数组（因为 frameBuffer 会被复用）
            array.copyOf(requiredSize)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy buffer: ${e.message}", e)
            isProcessingFrame = false
            return
        }
        
        // 异步处理复制后的数据
        try {
            getScope().launch {
                try {
                    val frame = ImageFrame(width, height, data, scale)
                    advancedFrameProcessor.onFrameAvailable(frame)
                } catch (e: Exception) {
                    Log.e(TAG, "Frame processing failed: ${e.message}", e)
                } finally {
                    isProcessingFrame = false
                }
            }
        } catch (e: Exception) {
            // getScope() 或 launch 可能失败（例如 stop 后）
            Log.e(TAG, "Failed to launch frame processing: ${e.message}", e)
            isProcessingFrame = false
        }
    }
    
    /**
     * 手动同步模板
     */
    suspend fun syncTemplates(): Result<Unit> {
        return manageTemplatesUseCase.syncTemplates()
    }
    
    /**
     * 清理存储
     */
    suspend fun cleanStorage(): Int {
        return cleanStorageUseCase.cleanup()
    }
    
    /**
     * 获取配置（Flow 监听）
     */
    fun getConfigFlow(): Flow<MonitorConfig> {
        return configRepository.getConfigFlow()
    }
    
    /**
     * 更新配置
     */
    suspend fun updateConfig(config: MonitorConfig) {
        configRepository.updateConfig(config)
    }
    
    /**
     * 获取根目录路径（用于 Flutter 调用）
     */
    fun getRootDirPath(): String {
        val storageRepo: com.youyou.monitor.core.domain.repository.StorageRepository by inject()
        return storageRepo.getRootDirPath()
    }
    
    /**
     * 获取应用上下文（用于需要 Context 的组件）
     */
    fun getApplicationContext(): Context {
        return context
    }
}
