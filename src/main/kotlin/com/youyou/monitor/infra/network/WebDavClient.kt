package com.youyou.monitor.infra.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.youyou.monitor.infra.logger.Log
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File

/**
 * WebDAV 客户端（重构版 - 从旧代码迁移）
 * 
 * 改进：
 * - 移除 FFI.getMyId() 依赖（解耦）
 * - 使用 Log 替代 Log
 * - 更清晰的错误处理
 * - 实现 Closeable 避免连接泄漏
 */
class WebDavClient(
    val webdavUrl: String,
    val username: String,
    val password: String,
    val monitorDir: String = "Monitor",
    val remoteUploadDir: String,
    private val deviceIdProvider: () -> String = { "" },
    val connectTimeout: Long = DEFAULT_CONNECT_TIMEOUT_MS,
    val readTimeout: Long = DEFAULT_READ_TIMEOUT_MS,
    val writeTimeout: Long = DEFAULT_WRITE_TIMEOUT_MS
) : java.io.Closeable {

    companion object {
        const val TAG = "WebDavClient"
        const val DEFAULT_MAX_RETRY = 3
        const val DEFAULT_RETRY_DELAY_MS = 2000L
        const val LARGE_FILE_THRESHOLD = 10 * 1024 * 1024 // 10MB
        const val DEFAULT_CONNECT_TIMEOUT_MS = 5000L  // 连接超时 5s
        const val DEFAULT_READ_TIMEOUT_MS = 10000L    // 读取超时 10s
        const val DEFAULT_WRITE_TIMEOUT_MS = 30000L   // 写入超时 30s
        
        /**
         * 工厂方法：从 WebDavServer 配置创建客户端
         */
        fun fromServer(
            server: com.youyou.monitor.core.domain.model.WebDavServer,
            deviceIdProvider: (() -> String)? = null
        ): WebDavClient {
            return WebDavClient(
                webdavUrl = server.url,
                username = server.username,
                password = server.password,
                monitorDir = server.monitorDir,
                remoteUploadDir = server.remoteUploadDir,
                deviceIdProvider = deviceIdProvider ?: { "" }
            )
        }
    }
    
    init {
        Log.d(TAG, "WebDavClient已创建: url=$webdavUrl, monitorDir=$monitorDir, remoteUploadDir=$remoteUploadDir")
    }
    
    // 设备ID缓存（只缓存非空值，空值会重试）
    @Volatile
    private var cachedDeviceId: String? = null
    
    /**
     * 获取设备ID（智能缓存：非空值缓存，空值重试）
     * 解决首次获取时 FFI 未初始化导致永远为空的问题
     */
    private val deviceId: String
        get() {
            // 如果已缓存非空值，直接返回
            cachedDeviceId?.let { return it }
            
            // 否则尝试获取
            return try {
                val id = deviceIdProvider()
                if (id.isNotBlank()) {
                    cachedDeviceId = id  // 缓存非空值
                    Log.d(TAG, "设备ID已获取: $id")
                    id
                } else {
                    Log.d(TAG, "设备ID为空，下次将继续重试")
                    ""  // 返回空但不缓存，下次继续尝试
                }
            } catch (e: Exception) {
                Log.e(TAG, "获取设备ID失败: ${e.message}")
                ""  // 异常时返回空但不缓存
            }
        }

    // 持有 OkHttpClient 引用以便关闭
    private val okHttpClient: OkHttpClient by lazy {
        val authInterceptor = okhttp3.Interceptor { chain ->
            val original = chain.request()
            val credentials = okhttp3.Credentials.basic(username, password)
            val authenticated = original.newBuilder()
                .header("Authorization", credentials)
                .build()
            chain.proceed(authenticated)
        }
        
        OkHttpClient.Builder()
            .connectTimeout(connectTimeout, java.util.concurrent.TimeUnit.MILLISECONDS)
            .readTimeout(readTimeout, java.util.concurrent.TimeUnit.MILLISECONDS)
            .writeTimeout(writeTimeout, java.util.concurrent.TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)  // 快速失败，不重试
            .addInterceptor(authInterceptor)
            .build()
    }
    
    private val sardine: OkHttpSardine by lazy {
        OkHttpSardine(okHttpClient)
    }

    /**
     * 检查当前网络是否为WiFi
     */
    private fun isWifiConnected(): Boolean {
        val context = com.youyou.monitor.MonitorService.getInstance().getApplicationContext()
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /**
     * 测试连接
     */
    suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "正在测试连接: $webdavUrl")
            sardine.list(webdavUrl.trimEnd('/'))
            Log.d(TAG, "连接测试成功")
            true
        } catch (e: Exception) {
            Log.e(TAG, "连接测试失败：${e.javaClass.simpleName}：${e.message}")
            false
        }
    }

    /**
     * 流式上传文件
     */
    suspend fun uploadFile(
        remotePath: String,
        fileName: String,
        file: File,
        maxRetry: Int = DEFAULT_MAX_RETRY,
        delayMillis: Long = DEFAULT_RETRY_DELAY_MS
    ): Boolean = withContext(Dispatchers.IO) {
        var attempt = 0
        var lastException: Exception? = null
        
        val isLargeFile = file.length() > LARGE_FILE_THRESHOLD
        val actualMaxRetry = if (isLargeFile) 1 else maxRetry
        
        if (isLargeFile) {
            Log.d(TAG, "检测到大文件 (${file.length() / 1024 / 1024}MB)")
            // 检查网络类型：大文件只允许在WiFi下上传
            if (!isWifiConnected()) {
                Log.e(TAG, "大文件上传失败：未连接到WiFi")
                return@withContext false
            }
        }
        
        // 缓存 deviceId，避免重复调用 getter
        val currentDeviceId = deviceId
        
        val baseDir = "/$remoteUploadDir".replace("//", "/")
        Log.d(TAG, "上传路径计算: remoteUploadDir=$remoteUploadDir, baseDir=$baseDir")
        
        val baseDirWithDeviceId = if (currentDeviceId.isNotBlank()) {
            baseDir.trimEnd('/') + "/" + currentDeviceId
        } else {
            baseDir
        }
        Log.d(TAG, "带设备ID的上传路径: baseDirWithDeviceId=$baseDirWithDeviceId, deviceId=$currentDeviceId")
        
        val fullRemotePath = if (remotePath.isNullOrBlank()) {
            baseDirWithDeviceId
        } else {
            baseDirWithDeviceId.trimEnd('/') + "/" + remotePath.trim('/')
        }
        
        val fullUrl = webdavUrl.trimEnd('/') + fullRemotePath.trimEnd('/') + "/" + fileName
        val sizeMB = file.length() / 1024.0 / 1024.0
        Log.d(TAG, "正在上传: $fullUrl (%.2fMB)".format(sizeMB))
        
        while (attempt < actualMaxRetry) {
            try {
                val startTime = System.currentTimeMillis()
                sardine.put(fullUrl, file, "application/octet-stream")
                val elapsedSeconds = (System.currentTimeMillis() - startTime) / 1000.0
                val speedMBps = sizeMB / elapsedSeconds
                
                Log.d(TAG, "上传成功：$fileName (%.2fMB in %.1fs, %.1fMB/s)".format(sizeMB, elapsedSeconds, speedMBps))
                return@withContext true
            } catch (e: java.net.SocketTimeoutException) {
                Log.e(TAG, "上传超时：$fileName (尝试 ${attempt + 1}/$actualMaxRetry)")
                lastException = e
                attempt++
                if (attempt < actualMaxRetry) {
                    kotlinx.coroutines.delay(delayMillis)
                }
            } catch (e: java.io.IOException) {
                Log.e(TAG, "上传IO错误：$fileName - ${e.message}")
                lastException = e
                attempt++
                if (attempt < actualMaxRetry) {
                    kotlinx.coroutines.delay(delayMillis)
                }
            } catch (e: Exception) {
                Log.e(TAG, "上传错误：$fileName - ${e.javaClass.simpleName}：${e.message}")
                return@withContext false
            }
        }
        
        Log.e(TAG, "上传在 $actualMaxRetry 次尝试后失败：$fileName")
        false
    }

    /**
     * 下载文件
     */
    suspend fun downloadFile(
        remotePath: String,
        fileName: String,
        maxRetry: Int = DEFAULT_MAX_RETRY
    ): ByteArray = withContext(Dispatchers.IO) {
        var attempt = 0
        
        while (attempt < maxRetry) {
            try {
                // 确保路径正确拼接，remotePath可能有或没有前导斜杠
                val normalizedPath = "/" + remotePath.trim('/')
                val fullPath = normalizedPath + "/" + fileName
                val fullUrl = webdavUrl.trimEnd('/') + fullPath
                
                Log.d(TAG, "正在尝试下载: $fullUrl")
                val bytes = sardine.get(fullUrl).use { it.readBytes() }
                Log.d(TAG, "已下载：$fileName (${bytes.size} bytes)")
                return@withContext bytes
            } catch (e: Exception) {
                Log.e(TAG, "下载错误：$fileName (尝试 ${attempt + 1}/$maxRetry) - ${e.javaClass.simpleName}：${e.message}")
                attempt++
                if (attempt < maxRetry) {
                    kotlinx.coroutines.delay(DEFAULT_RETRY_DELAY_MS)
                }
            }
        }
        
        Log.e(TAG, "下载在 $maxRetry 次尝试后失败：$fileName")
        ByteArray(0)
    }

    /**
     * 列出目录内容
     */
    suspend fun listDirectory(remotePath: String): List<String> = withContext(Dispatchers.IO) {
        try {
            val fullUrl = webdavUrl.trimEnd('/') + "/" + remotePath.trim('/')
            val resources = sardine.list(fullUrl)
            
            val fileNames = resources.mapNotNull { resource ->
                // resource.name 可能包含完整路径，只取文件名部分
                val fullName = resource.name?.trim('/') ?: ""
                val fileName = fullName.substringAfterLast('/')
                
                if (fileName.isNotEmpty() && !resource.isDirectory &&
                    (fileName.endsWith(".png", ignoreCase = true) || 
                     fileName.endsWith(".jpg", ignoreCase = true) ||
                     fileName.endsWith(".jpeg", ignoreCase = true))) {
                    fileName
                } else null
            }
            
            Log.d(TAG, "Listed ${fileNames.size} files in $remotePath: $fileNames")
            fileNames
        } catch (e: Exception) {
            Log.e(TAG, "List directory error: $remotePath - ${e.message}")
            emptyList()
        }
    }

    /**
     * 删除文件
     */
    suspend fun deleteFile(remotePath: String, fileName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val fullPath = remotePath.trimEnd('/') + "/" + fileName
            val fullUrl = webdavUrl.trimEnd('/') + fullPath
            
            sardine.delete(fullUrl)
            Log.d(TAG, "Deleted: $fileName")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Delete error: $fileName - ${e.message}")
            false
        }
    }
    
    /**
     * 关闭客户端，释放资源
     * 修复 OkHttp 连接泄漏问题
     */
    override fun close() {
        try {
            // 关闭 OkHttpClient 的连接池和线程池
            okHttpClient.dispatcher.executorService.shutdown()
            okHttpClient.connectionPool.evictAll()
            Log.d(TAG, "WebDavClient closed, connections released")
        } catch (e: Exception) {
            Log.w(TAG, "Error closing WebDavClient: ${e.message}")
        }
    }
}
