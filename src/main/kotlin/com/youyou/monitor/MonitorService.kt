package com.youyou.monitor

import android.content.Context
import com.youyou.monitor.core.domain.model.ImageFrame
import com.youyou.monitor.core.domain.model.MonitorConfig
import com.youyou.monitor.core.domain.usecase.CleanStorageUseCase
import com.youyou.monitor.core.domain.usecase.ManageTemplatesUseCase
import com.youyou.monitor.core.domain.usecase.ProcessFrameUseCase
import com.youyou.monitor.infra.config.WebDavConfigManager
import com.youyou.monitor.infra.logger.Log
import com.youyou.monitor.infra.network.WebDavClient
import com.youyou.monitor.infra.processor.AdvancedFrameProcessor
import com.youyou.monitor.core.domain.repository.ConfigRepository
import com.youyou.monitor.infra.repository.ConfigRepositoryImpl
import com.youyou.monitor.infra.repository.TemplateRepositoryImpl
import com.youyou.monitor.infra.task.ScheduledTaskManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
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
                            Log.i(TAG, "Log system initialized")
                            
                            // 2. 初始化 OpenCV
                            if (!OpenCVLoader.initDebug()) {
                                Log.e(TAG, "OpenCV initialization failed!")
                                throw RuntimeException("OpenCV initialization failed")
                            }
                            Log.i(TAG, "OpenCV initialized successfully")
                            
                            // 3. 初始化 Koin（注意：initKoin 内部也会调用 Log.init，但这里已经初始化过了）
                            com.youyou.monitor.di.initKoin(context)
                            this.deviceIdProvider = deviceIdProvider
                            instance = MonitorService(context.applicationContext)
                            
                            val deviceIdLog = try { deviceIdProvider?.invoke() ?: "<none>" } catch (e: Exception) { "<error>" }
                            Log.i(TAG, "MonitorService initialized successfully (deviceIdProvider=${if (deviceIdProvider != null) "provided" else "null"}, testId=$deviceIdLog)")
                        } catch (e: Exception) {
                            Log.e(TAG, "Initialization failed: ${e.message}", e)
                            throw e  // 重新抛出，确保调用方知道失败
                        }
                    }
                }
            }
        }
        
        /**
         * 获取实例
         */
        fun getInstance(): MonitorService {
            return instance ?: throw IllegalStateException(
                "MonitorService not initialized. Call init(context) first."
            )
        }
    }
    
    // 依赖注入
    private val processFrameUseCase: ProcessFrameUseCase by inject()
    private val manageTemplatesUseCase: ManageTemplatesUseCase by inject()
    private val cleanStorageUseCase: CleanStorageUseCase by inject()
    private val advancedFrameProcessor: AdvancedFrameProcessor by inject()
    private val scheduledTaskManager: ScheduledTaskManager by inject()
    private val configRepository: ConfigRepository by inject()  // 修改：注入接口而不是实现类
    private val templateRepository: com.youyou.monitor.core.domain.repository.TemplateRepository by inject()
    
    // WebDAV 配置管理器（延迟初始化）
    private val webdavConfigManager: WebDavConfigManager by lazy {
        val configRepo = get<ConfigRepository>()
        val templateRepo = get<TemplateRepositoryImpl>()
        WebDavConfigManager(configRepo as ConfigRepositoryImpl, templateRepo)
    }
    
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isRunning = false
    
    // 当前使用的 WebDAV 客户端（需要关闭）
    private var currentWebDavClient: WebDavClient? = null
    
    init {
        Log.d(TAG, "MonitorService initialized")
        
        // 设置设备ID提供者到 ConfigRepository
        (configRepository as? ConfigRepositoryImpl)?.setDeviceIdProvider(Companion.deviceIdProvider)
        
        // 注册配置变化监听：当 webdavServers 变化时自动重新配置
        (configRepository as? ConfigRepositoryImpl)?.setOnWebDavServersChanged { newServers, fastestServer, fastestClient ->
            Log.i(TAG, "WebDAV servers changed, auto-reconfiguring with fastest server: ${fastestServer?.url}")
            scope.launch {
                // 使用 ConfigRepository 选择的最快服务器
                if (fastestServer != null && fastestClient != null) {
                    configureWebDavDirect(fastestServer, fastestClient)
                } else {
                    autoLoadConfiguration()
                }
            }
        }
    }
    

    
    /**
     * 启动监控
     */
    fun start() {
        Log.i(TAG, "=== MonitorService.start() called ===")
        if (isRunning) {
            Log.w(TAG, "Already running, skipping start")
            return
        }
        isRunning = true
        
        // 重置处理器状态
        advancedFrameProcessor.reset()
        Log.d(TAG, "Frame processor reset")
        
        // 自动加载配置（首次从 assets，后续从 WebDAV）
        Log.d(TAG, "Launching autoLoadConfiguration coroutine...")
        scope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "autoLoadConfiguration coroutine started on IO dispatcher")
                autoLoadConfiguration()
                Log.d(TAG, "autoLoadConfiguration completed")
            } catch (e: Exception) {
                Log.e(TAG, "Auto load configuration failed: ${e.message}", e)
            }
        }
        
        // 启动所有定时任务
        scheduledTaskManager.startAllTasks(
            configUpdateInterval = 5,     // 5分钟更新配置
            imageUploadInterval = 5,       // 5分钟上传截图
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
            Log.i(TAG, "Using fastest WebDAV server: ${server.url}")
            
            // 关闭旧客户端
            currentWebDavClient?.close()
            currentWebDavClient = client
            
            // 配置到各个 Repository
            (configRepository as? ConfigRepositoryImpl)?.setWebDavClient(client)
            (templateRepository as? TemplateRepositoryImpl)?.setWebDavClient(client, server.templateDir)
            scheduledTaskManager.setWebDavClient(client)
            
            // 同步模板
            templateRepository.syncFromRemote().onSuccess {
                Log.i(TAG, "Templates synced: $it templates")
            }.onFailure {
                Log.w(TAG, "Template sync failed: ${it.message}")
            }
            
            Log.i(TAG, "WebDAV configured with fastest server")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to configure WebDAV: ${e.message}", e)
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
            Log.i(TAG, "=== autoLoadConfiguration START ===")
            
            // 尝试从远程同步配置（会自动测试所有服务器并选择最快的）
            val syncResult = configRepository.syncFromRemote()
            
            if (syncResult.isSuccess) {
                Log.i(TAG, "Remote config synced, WebDAV auto-configured via callback")
                // syncFromRemote 成功后会自动触发回调，无需手动配置
                return@withContext
            }
            
            // 远程同步失败，使用本地配置降级
            Log.w(TAG, "Remote sync failed: ${syncResult.exceptionOrNull()?.message}, trying local config")
            
            val config = configRepository.getCurrentConfig()
            if (config.webdavServers.isEmpty()) {
                Log.w(TAG, "No WebDAV servers configured")
                return@withContext
            }
            
            // 降级策略：遍历测试所有服务器，使用第一个可用的
            for (server in config.webdavServers) {
                if (server.url.isEmpty()) continue
                
                var client: WebDavClient? = null
                try {
                    client = WebDavClient.fromServer(server, Companion.deviceIdProvider)
                    
                    Log.d(TAG, "Testing fallback server: ${server.url}")
                    if (client.testConnection()) {
                        Log.i(TAG, "Configuring with fallback server: ${server.url}")
                        configureWebDavDirect(server, client)
                        return@withContext
                    } else {
                        Log.w(TAG, "Fallback server ${server.url} not available")
                        client.close()  // 测试失败，关闭客户端
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to test fallback server ${server.url}: ${e.message}")
                    client?.close()  // 异常时关闭客户端
                }
            }
            
            Log.w(TAG, "All fallback servers failed")
        } catch (e: Exception) {
            Log.e(TAG, "autoLoadConfiguration failed: ${e.message}", e)
        }
    }
    
    /**
     * 停止监控
     */
    fun stop() {
        isRunning = false
        advancedFrameProcessor.shutdown()
        scheduledTaskManager.shutdown()
        
        // 关闭 WebDAV 客户端
        currentWebDavClient?.close()
        currentWebDavClient = null
        
        // 释放 TemplateMatcher 资源（Mat 对象）
        val matcher: com.youyou.monitor.core.matcher.TemplateMatcher by inject()
        matcher.release()
        
        scope.cancel()  // 取消协程作用域，防止内存泄漏
        
        // 关闭日志系统
        com.youyou.monitor.infra.logger.Log.shutdown()
    }

    /**
     * 处理帧（使用高级处理器）
     * 
     * @param scale 屏幕缩放比例（1=原始分辨率，2=半分辨率，用于性能优化）
     */
    fun onFrameAvailable(buffer: ByteBuffer, width: Int, height: Int, scale: Int = 1) {
        if (!isRunning) return
        
        // 立即在调用线程复制数据，避免 DirectByteBuffer 失效
        val data = try {
            val array = ByteArray(width * height * 4)
            buffer.position(0)
            buffer.get(array)
            array
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy buffer: ${e.message}")
            return
        }
        
        // 异步处理复制后的数据
        scope.launch {
            val frame = ImageFrame(width, height, data, scale)
            advancedFrameProcessor.onFrameAvailable(frame)
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
}
