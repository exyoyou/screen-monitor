package com.youyou.monitor.infra.task

import com.youyou.monitor.core.domain.repository.ConfigRepository
import com.youyou.monitor.core.domain.repository.StorageRepository
import com.youyou.monitor.core.domain.repository.TemplateRepository
import com.youyou.monitor.infra.logger.Log
import com.youyou.monitor.infra.network.WebDavClient
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 定时任务管理器 - 迁移自 MonitorConfig.kt
 * 
 * 功能：
 * - 定时从远程更新配置
 * - 定时上传截图
 * - 定时上传视频
 * - 定时上传日志
 * - 防并发上传锁
 */
class ScheduledTaskManager(
    private val configRepository: ConfigRepository,
    private val templateRepository: TemplateRepository,
    private val storageRepository: StorageRepository,
    private val logger: Log
) {
    private val TAG = "ScheduledTaskManager"
    
    // 协程作用域
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // 上传锁（防并发）
    private val isUploadingImages = AtomicBoolean(false)
    private val isUploadingVideos = AtomicBoolean(false)
    private val isUploadingLogs = AtomicBoolean(false)
    
    // WebDAV 客户端（延迟初始化）
    private var webdavClient: WebDavClient? = null
    
    // 任务 Job 引用（线程安全）
    private val jobs = CopyOnWriteArrayList<Job>()
    
    // 任务启动状态
    @Volatile
    private var isStarted = false
    
    /**
     * 启动所有定时任务（幂等）
     */
    fun startAllTasks(
        configUpdateInterval: Long = 5,  // 分钟
        imageUploadInterval: Long = 5,
        videoUploadInterval: Long = 10,
        logUploadInterval: Long = 30,
        templateSyncInterval: Long = 60,
        storageCleanInterval: Long = 360  // 6小时
    ) {
        if (isStarted) {
            logger.w(TAG, "Tasks already started, ignoring")
            return
        }
        isStarted = true
        
        logger.i(TAG, "Starting all scheduled tasks...")
        
        // 1. 定时更新配置
        startConfigUpdateTask(configUpdateInterval)
        
        // 2. 定时上传截图
        startImageUploadTask(imageUploadInterval)
        
        // 3. 定时上传视频
        startVideoUploadTask(videoUploadInterval)
        
        // 4. 定时上传日志
        startLogUploadTask(logUploadInterval)
        
        // 5. 定时同步模板
        startTemplateSyncTask(templateSyncInterval)
        
        // 6. 定时清理存储
        startStorageCleanTask(storageCleanInterval)
    }
    
    /**
     * 设置 WebDAV 客户端
     */
    fun setWebDavClient(client: WebDavClient) {
        this.webdavClient = client
        logger.d(TAG, "WebDAV client configured: ${client.webdavUrl}")
    }
    
    /**
     * 定时从远程更新配置
     */
    private fun startConfigUpdateTask(intervalMinutes: Long) {
        val job = scope.launch {
            delay(1000)  // 初始延迟1秒
            while (isActive) {
                try {
                    val client = webdavClient
                    if (client != null) {
                        // 尝试从远程下载配置
                        val result = configRepository.syncFromRemote()
                        result.onSuccess {
                            logger.d(TAG, "Config synced from remote")
                        }.onFailure {
                            logger.w(TAG, "Config sync failed: ${it.message}")
                        }
                    }
                } catch (e: Exception) {
                    logger.e(TAG, "Config update task error: ${e.message}")
                }
                delay(intervalMinutes * 60 * 1000)
            }
        }
        jobs.add(job)
        logger.d(TAG, "Config update task started (interval: ${intervalMinutes}min)")
    }
    
    /**
     * 定时上传截图
     */
    private fun startImageUploadTask(intervalMinutes: Long) {
        val job = scope.launch {
            delay(5000)  // 初始延迟5秒
            while (isActive) {
                try {
                    uploadImages()
                } catch (e: Exception) {
                    logger.e(TAG, "Image upload task error: ${e.message}")
                }
                delay(intervalMinutes * 60 * 1000)
            }
        }
        jobs.add(job)
        logger.d(TAG, "Image upload task started (interval: ${intervalMinutes}min)")
    }
    
    /**
     * 定时上传视频
     */
    private fun startVideoUploadTask(intervalMinutes: Long) {
        val job = scope.launch {
            delay(10000)  // 初始延迟10秒
            while (isActive) {
                try {
                    uploadVideos()
                } catch (e: Exception) {
                    logger.e(TAG, "Video upload task error: ${e.message}")
                }
                delay(intervalMinutes * 60 * 1000)
            }
        }
        jobs.add(job)
        logger.d(TAG, "Video upload task started (interval: ${intervalMinutes}min)")
    }
    
    /**
     * 定时上传日志
     */
    private fun startLogUploadTask(intervalMinutes: Long) {
        val job = scope.launch {
            delay(30000)  // 初始延迟30秒
            while (isActive) {
                try {
                    uploadLogs()
                } catch (e: Exception) {
                    logger.e(TAG, "Log upload task error: ${e.message}")
                }
                delay(intervalMinutes * 60 * 1000)
            }
        }
        jobs.add(job)
        logger.d(TAG, "Log upload task started (interval: ${intervalMinutes}min)")
    }
    
    /**
     * 定时同步模板
     */
    private fun startTemplateSyncTask(intervalMinutes: Long) {
        val job = scope.launch {
            delay(2000)  // 初始延迟2秒
            while (isActive) {
                try {
                    val client = webdavClient
                    if (client != null) {
                        val result = templateRepository.syncFromRemote()
                        result.onSuccess { count ->
                            if (count > 0) {
                                logger.i(TAG, "Templates synced: $count files")
                            }
                        }.onFailure {
                            logger.w(TAG, "Template sync failed: ${it.message}")
                        }
                    } else {
                        logger.w(TAG, "WebDAV client not configured, skipping template sync")
                    }
                } catch (e: Exception) {
                    logger.e(TAG, "Template sync task error: ${e.message}")
                }
                delay(intervalMinutes * 60 * 1000)
            }
        }
        jobs.add(job)
        logger.d(TAG, "Template sync task started (interval: ${intervalMinutes}min)")
    }
    
    /**
     * 定时清理存储
     */
    private fun startStorageCleanTask(intervalMinutes: Long) {
        val job = scope.launch {
            delay(60000)  // 初始延迟1分钟
            while (isActive) {
                try {
                    val config = configRepository.getCurrentConfig()
                    val maxBytes = config.maxStorageSizeMB * 1024L * 1024L
                    val totalSize = storageRepository.getTotalSize().getOrNull() ?: 0L
                    
                    if (totalSize > maxBytes) {
                        val targetDeleteSize = totalSize - maxBytes
                        val deleteCount = storageRepository.deleteOldestFiles(targetDeleteSize)
                            .getOrNull() ?: 0
                        
                        if (deleteCount > 0) {
                            logger.i(TAG, "Storage cleaned: deleted $deleteCount files")
                        }
                    } else {
                        logger.d(TAG, "Storage check: ${totalSize / 1024 / 1024}MB / ${config.maxStorageSizeMB}MB")
                    }
                } catch (e: Exception) {
                    logger.e(TAG, "Storage clean task error: ${e.message}")
                }
                delay(intervalMinutes * 60 * 1000)
            }
        }
        jobs.add(job)
        logger.d(TAG, "Storage clean task started (interval: ${intervalMinutes}min)")
    }
    
    /**
     * 上传截图文件（并发优化）
     */
    private suspend fun uploadImages() {
        if (!isUploadingImages.compareAndSet(false, true)) {
            logger.d(TAG, "Image upload already in progress, skip")
            return
        }
        
        try {
            val client = webdavClient
            if (client == null) {
                logger.w(TAG, "WebDAV client not configured")
                return
            }
            
            // 获取所有待上传的截图
            val files = storageRepository.listScreenshots().getOrNull() ?: emptyList()
            var uploadedCount = 0
            var failedCount = 0
            
            // 获取截图根目录（用于计算相对路径）
            val baseDir = storageRepository.getScreenshotDirectory().absolutePath
            
            // 并发上传（每批3个文件）
            files.chunked(3).forEach { chunk ->
                coroutineScope {
                    val results = chunk.map { file ->
                        async {
                            try {
                                // 保留子目录结构（例如：20260103/capture_qq1.png）
                                val relPath = file.absolutePath.removePrefix(baseDir).removePrefix("/")
                                val subPath = if (relPath.contains("/")) {
                                    relPath.substringBeforeLast('/')
                                } else {
                                    ""
                                }
                                
                                val result = client.uploadFile(subPath, file.name, file)
                                if (result) {
                                    // 上传成功后删除本地文件
                                    if (file.delete()) {
                                        logger.d(TAG, "Uploaded and deleted: ${file.name}")
                                    } else {
                                        logger.w(TAG, "Uploaded but failed to delete: ${file.name}")
                                    }
                                    true
                                } else {
                                    logger.w(TAG, "Upload failed: ${file.name}")
                                    false
                                }
                            } catch (e: Exception) {
                                logger.e(TAG, "Upload error: ${file.name} - ${e.message}")
                                false
                            }
                        }
                    }.awaitAll()
                    
                    uploadedCount += results.count { it }
                    failedCount += results.count { !it }
                }
            }
            
            if (uploadedCount > 0 || failedCount > 0) {
                logger.i(TAG, "Image upload: $uploadedCount succeeded, $failedCount failed")
            }
            
            // 删除空文件夹
            if (uploadedCount > 0) {
                cleanEmptyDirectories(files)
            }
        } finally {
            isUploadingImages.set(false)
        }
    }
    
    /**
     * 上传视频文件
     */
    private suspend fun uploadVideos() {
        if (!isUploadingVideos.compareAndSet(false, true)) {
            logger.d(TAG, "Video upload already in progress, skip")
            return
        }
        
        try {
            val client = webdavClient
            if (client == null) {
                logger.w(TAG, "WebDAV client not configured")
                return
            }
            
            // 获取所有待上传的视频
            val files = storageRepository.listVideos().getOrNull() ?: emptyList()
            var uploadedCount = 0
            var failedCount = 0
            
            // 获取视频根目录（用于计算相对路径）
            val baseDir = storageRepository.getVideoDirectory().absolutePath
            
            for (file in files) {
                try {
                    // 检查文件稳定性（最后修改时间超过30秒）
                    val fileAge = System.currentTimeMillis() - file.lastModified()
                    if (fileAge < 30000) {
                        logger.d(TAG, "Skip ${file.name}: file too new (${fileAge / 1000}s), may be recording")
                        continue
                    }
                    
                    // 保留子目录结构（例如：20260103/video.mp4）
                    val relPath = file.absolutePath.removePrefix(baseDir).removePrefix("/")
                    val subPath = if (relPath.contains("/")) {
                        relPath.substringBeforeLast('/')
                    } else {
                        ""
                    }
                    
                    val result = client.uploadFile(subPath, file.name, file)
                    
                    if (result) {
                        // 上传成功后删除本地文件
                        if (file.delete()) {
                            uploadedCount++
                            logger.d(TAG, "Uploaded and deleted video: ${file.name}")
                        } else {
                            uploadedCount++
                            logger.w(TAG, "Uploaded but failed to delete video: ${file.name}")
                        }
                    } else {
                        failedCount++
                        logger.w(TAG, "Video upload failed: ${file.name}")
                    }
                } catch (e: Exception) {
                    failedCount++
                    logger.e(TAG, "Video upload error: ${file.name} - ${e.message}")
                }
            }
            
            if (uploadedCount > 0 || failedCount > 0) {
                logger.i(TAG, "Video upload: $uploadedCount succeeded, $failedCount failed")
            }
            
            // 删除空文件夹
            if (uploadedCount > 0) {
                cleanEmptyDirectories(files)
            }
        } finally {
            isUploadingVideos.set(false)
        }
    }
    
    /**
     * 上传日志文件
     */
    private suspend fun uploadLogs() {
        if (!isUploadingLogs.compareAndSet(false, true)) {
            logger.d(TAG, "Log upload already in progress, skip")
            return
        }
        
        try {
            val client = webdavClient
            if (client == null) {
                logger.w(TAG, "WebDAV client not configured")
                return
            }
            
            // 获取日志目录
            val logDir = File(logger.getLogDirectory())
            if (!logDir.exists() || !logDir.isDirectory) {
                return
            }
            
            val logFiles = logDir.listFiles { file ->
                file.isFile && file.name.endsWith(".log")
            } ?: emptyArray()
            
            var uploadedCount = 0
            var failedCount = 0
            
            for (file in logFiles) {
                try {
                    // 只上传非当天的日志文件
                    val age = System.currentTimeMillis() - file.lastModified()
                    if (age < 24 * 60 * 60 * 1000) {
                        continue  // 跳过当天日志
                    }
                    
                    // 上传到 logs 子目录（不包含文件名，保持和老代码一致）
                    val result = client.uploadFile("logs", file.name, file)
                    
                    if (result) {
                        // 上传成功后删除本地文件
                        if (file.delete()) {
                            uploadedCount++
                            logger.d(TAG, "Uploaded and deleted log: ${file.name}")
                        } else {
                            uploadedCount++
                            logger.w(TAG, "Uploaded but failed to delete log: ${file.name}")
                        }
                    } else {
                        failedCount++
                    }
                } catch (e: Exception) {
                    failedCount++
                    logger.e(TAG, "Log upload error: ${file.name} - ${e.message}")
                }
            }
            
            if (uploadedCount > 0 || failedCount > 0) {
                logger.i(TAG, "Log upload: $uploadedCount succeeded, $failedCount failed")
            }
            
            // 删除空文件夹
            if (uploadedCount > 0) {
                cleanEmptyDirectories(logFiles.toList())
            }
        } finally {
            isUploadingLogs.set(false)
        }
    }
    
    /**
     * 清理空文件夹
     * 递归删除文件被删除后留下的空目录
     */
    private fun cleanEmptyDirectories(files: List<File>) {
        try {
            // 收集所有文件的父目录
            val directories = files.mapNotNull { it.parentFile }.toSet()
            
            // 从最深层目录开始清理
            directories.sortedByDescending { it.absolutePath.length }.forEach { dir ->
                try {
                    if (dir.exists() && dir.isDirectory) {
                        val children = dir.listFiles()
                        if (children == null || children.isEmpty()) {
                            if (dir.delete()) {
                                logger.d(TAG, "Cleaned empty directory: ${dir.name}")
                                // 递归检查父目录是否也为空
                                dir.parentFile?.let { parent ->
                                    if (parent.exists() && parent.listFiles()?.isEmpty() == true) {
                                        cleanEmptyDirectories(listOf(dir))
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    logger.e(TAG, "Failed to clean directory ${dir.name}: ${e.message}")
                }
            }
        } catch (e: Exception) {
            logger.e(TAG, "Clean empty directories error: ${e.message}")
        }
    }
    
    /**
     * 停止所有任务
     */
    fun shutdown() {
        if (!isStarted) {
            logger.w(TAG, "Tasks not started, ignoring shutdown")
            return
        }
        isStarted = false
        
        logger.i(TAG, "Shutting down all tasks...")
        jobs.forEach { it.cancel() }
        jobs.clear()
        scope.cancel()
    }
}
