package com.youyou.monitor.infra.repository

import android.content.Context
import android.graphics.Bitmap
import com.youyou.monitor.core.domain.model.MonitorConfig
import com.youyou.monitor.core.domain.repository.ConfigRepository
import com.youyou.monitor.core.domain.repository.StorageRepository
import com.youyou.monitor.infra.logger.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * 存储仓储实现
 * 
 * 功能：
 * - 截图保存（按日期分目录）
 * - 存储空间管理
 * - 清理旧文件
 * - 获取待上传文件
 * - 动态存储路径（支持外部存储）
 */
class StorageRepositoryImpl(
    private val context: Context,
    private val configRepository: ConfigRepository
) : StorageRepository {
    
    companion object {
        const val TAG = "StorageRepository"
        const val SCREENSHOT_DIR = "ScreenCaptures"
        const val VIDEO_DIR = "ScreenRecord"
    }
    
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    @Volatile
    private var currentConfig: MonitorConfig = MonitorConfig.default()
    
    @Volatile
    private var isMigrating = false
    
    init {
        // 监听配置变更
        configRepository.getConfigFlow()
            .onEach { newConfig ->
                val oldConfig = currentConfig
                if (newConfig.preferExternalStorage != oldConfig.preferExternalStorage ||
                    newConfig.rootDir != oldConfig.rootDir) {
                    Log.i(TAG, "Storage path changed: preferExternal=${newConfig.preferExternalStorage}, root=${newConfig.rootDir}")
                    
                    // 异步迁移文件
                    migrateStorageAsync(oldConfig, newConfig)
                }
                currentConfig = newConfig
            }
            .launchIn(scope)
    }
    
    /**
     * 获取根目录（支持优先外部存储）
     */
    private fun getRootDir(): File {
        val config = currentConfig
        val baseDir = if (config.preferExternalStorage) {
            val ext = File("/storage/emulated/0", config.rootDir)
            if (ext.exists() && ext.canWrite()) {
                ext
            } else {
                Log.w(TAG, "External storage not available, using internal")
                File(context.filesDir, config.rootDir)
            }
        } else {
            File(context.filesDir, config.rootDir)
        }
        if (!baseDir.exists()) baseDir.mkdirs()
        return baseDir
    }
    
    private val screenshotBaseDir: File
        get() = File(getRootDir(), currentConfig.screenshotDir).apply {
            if (!exists()) mkdirs()
        }
    
    private val videoBaseDir: File
        get() = File(getRootDir(), currentConfig.videoDir).apply {
            if (!exists()) mkdirs()
        }
    
    // 日期格式化（ThreadLocal 确保线程安全）
    private val dateFormat = ThreadLocal.withInitial {
        SimpleDateFormat("yyyyMMdd", Locale.US)
    }
    private val timestampFormat = ThreadLocal.withInitial {
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    }
    
    override suspend fun saveScreenshot(data: ByteArray, tag: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            // 按日期创建子目录
            val dateStr = dateFormat.get()!!.format(Date())
            val dayDir = File(screenshotBaseDir, dateStr).apply {
                if (!exists()) mkdirs()
            }
            
            // 生成文件名
            val timestamp = timestampFormat.get()!!.format(Date())
            val fileName = "${timestamp}_${tag}.jpg"
            val file = File(dayDir, fileName)
            
            // 保存文件
            file.writeBytes(data)
            
            Log.d(TAG, "Screenshot saved: ${file.name} (${data.size / 1024}KB)")
            Result.success(file.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save screenshot: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    override suspend fun saveScreenshot(bitmap: Bitmap, filename: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            // 按日期创建子目录
            val dateStr = dateFormat.get()!!.format(Date())
            val dayDir = File(screenshotBaseDir, dateStr).apply {
                if (!exists()) mkdirs()
            }
            
            val file = File(dayDir, filename)
            
            // 保存 Bitmap
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
            }
            
            Log.d(TAG, "Screenshot saved: ${file.name}")
            Result.success(file.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save screenshot: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    override suspend fun getTotalSize(): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val size = calculateDirectorySize(screenshotBaseDir) + calculateDirectorySize(videoBaseDir)
            Result.success(size)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get total size: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    override suspend fun deleteOldestFiles(bytes: Long): Result<Int> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Cleaning storage: target=${bytes / 1024 / 1024}MB")
            
            // 获取所有文件并按修改时间排序
            val allFiles = (getAllFiles(screenshotBaseDir) + getAllFiles(videoBaseDir))
                .sortedBy { it.lastModified() }
            
            var deleted = 0L
            var count = 0
            
            for (file in allFiles) {
                if (deleted >= bytes) break
                
                try {
                    val size = file.length()
                    if (file.delete()) {
                        deleted += size
                        count++
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to delete file: ${file.name}")
                }
            }
            
            // 清理空目录
            cleanEmptyDirectories(screenshotBaseDir)
            cleanEmptyDirectories(videoBaseDir)
            
            Log.i(TAG, "Storage cleaned: $count files, ${deleted / 1024 / 1024}MB freed")
            Result.success(count)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clean storage: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    override suspend fun listScreenshots(): Result<List<File>> = withContext(Dispatchers.IO) {
        try {
            val files = getAllFiles(screenshotBaseDir)
            Result.success(files)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list screenshots: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    override suspend fun listVideos(): Result<List<File>> = withContext(Dispatchers.IO) {
        try {
            val files = getAllFiles(videoBaseDir)
            Result.success(files)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list videos: ${e.message}", e)
            Result.failure(e)
        }
    }
            
    override suspend fun getPendingUploadFiles(): List<String> = withContext(Dispatchers.IO) {
        try {
            (getAllFiles(screenshotBaseDir) + getAllFiles(videoBaseDir)).map { it.absolutePath }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get pending upload files: ${e.message}", e)
            emptyList()
        }
    }
    
    override fun getRootDirPath(): String {
        return getRootDir().absolutePath
    }
    
    /**
     * 递归计算目录大小
     */
    private fun calculateDirectorySize(dir: File): Long {
        if (!dir.exists() || !dir.canRead()) return 0L
        
        var size = 0L
        val files = dir.listFiles() ?: return 0L
        
        for (file in files) {
            size += if (file.isDirectory) {
                calculateDirectorySize(file)
            } else {
                file.length()
            }
        }
        
        return size
    }
    
    /**
     * 递归获取所有文件
     */
    private fun getAllFiles(dir: File): List<File> {
        if (!dir.exists() || !dir.canRead()) return emptyList()
        
        val result = mutableListOf<File>()
        val files = dir.listFiles() ?: return emptyList()
        
        for (file in files) {
            if (file.isDirectory) {
                result.addAll(getAllFiles(file))
            } else {
                result.add(file)
            }
        }
        
        return result
    }
    
    /**
     * 清理空目录
     */
    private fun cleanEmptyDirectories(dir: File) {
        if (!dir.exists() || !dir.isDirectory) return
        
        val files = dir.listFiles() ?: return
        
        for (file in files) {
            if (file.isDirectory) {
                cleanEmptyDirectories(file)
                
                // 如果目录为空，删除它
                if (file.listFiles()?.isEmpty() == true) {
                    file.delete()
                    Log.d(TAG, "Deleted empty directory: ${file.name}")
                }
            }
        }
    }
    
    /**
     * 获取存储目录
     */
    override fun getScreenshotDirectory(): File = screenshotBaseDir
    
    override fun getVideoDirectory(): File = videoBaseDir
    
    /**
     * 异步迁移存储文件（从旧路径到新路径）
     */
    private fun migrateStorageAsync(oldConfig: MonitorConfig, newConfig: MonitorConfig) {
        if (isMigrating) {
            Log.w(TAG, "Migration already in progress, skipping")
            return
        }
        
        scope.launch(Dispatchers.IO) {
            try {
                isMigrating = true
                migrateStorage(oldConfig, newConfig)
            } catch (e: Exception) {
                Log.e(TAG, "Migration failed: ${e.message}", e)
            } finally {
                isMigrating = false
            }
        }
    }
    
    /**
     * 迁移存储文件
     */
    private suspend fun migrateStorage(oldConfig: MonitorConfig, newConfig: MonitorConfig) = withContext(Dispatchers.IO) {
        Log.i(TAG, "Starting storage migration...")
        
        // 计算旧路径
        val oldBaseDir = if (oldConfig.preferExternalStorage) {
            val ext = File("/storage/emulated/0", oldConfig.rootDir)
            if (ext.exists()) ext else File(context.filesDir, oldConfig.rootDir)
        } else {
            File(context.filesDir, oldConfig.rootDir)
        }
        
        val oldScreenshotDir = File(oldBaseDir, oldConfig.screenshotDir)
        val oldVideoDir = File(oldBaseDir, oldConfig.videoDir)
        
        // 新路径
        val newScreenshotDir = screenshotBaseDir
        val newVideoDir = videoBaseDir
        
        // 检查是否需要迁移
        if (oldScreenshotDir.absolutePath == newScreenshotDir.absolutePath) {
            Log.d(TAG, "Screenshot paths are the same, no migration needed")
            return@withContext
        }
        
        var totalMoved = 0
        var totalFailed = 0
        
        // 迁移截图
        if (oldScreenshotDir.exists()) {
            Log.d(TAG, "Migrating screenshots from ${oldScreenshotDir.absolutePath}")
            val (moved, failed) = migrateDirectory(oldScreenshotDir, newScreenshotDir)
            totalMoved += moved
            totalFailed += failed
        }
        
        // 迁移视频
        if (oldVideoDir.exists()) {
            Log.d(TAG, "Migrating videos from ${oldVideoDir.absolutePath}")
            val (moved, failed) = migrateDirectory(oldVideoDir, newVideoDir)
            totalMoved += moved
            totalFailed += failed
        }
        
        Log.i(TAG, "Migration completed: moved=$totalMoved, failed=$totalFailed")
        
        // 清理旧目录
        if (totalFailed == 0) {
            cleanupOldDirectory(oldBaseDir)
        }
    }
    
    /**
     * 迁移目录内容（递归）
     * @return Pair(成功数量, 失败数量)
     */
    private fun migrateDirectory(sourceDir: File, targetDir: File): Pair<Int, Int> {
        if (!sourceDir.exists() || !sourceDir.canRead()) {
            return 0 to 0
        }
        
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }
        
        var movedCount = 0
        var failedCount = 0
        
        val files = sourceDir.listFiles() ?: return 0 to 0
        
        for (file in files) {
            try {
                if (file.isDirectory) {
                    // 递归处理子目录
                    val targetSubDir = File(targetDir, file.name)
                    val (moved, failed) = migrateDirectory(file, targetSubDir)
                    movedCount += moved
                    failedCount += failed
                } else {
                    // 移动文件
                    val targetFile = File(targetDir, file.name)
                    
                    // 如果目标已存在且大小相同，删除源文件
                    if (targetFile.exists() && targetFile.length() == file.length()) {
                        file.delete()
                        Log.d(TAG, "Deleted duplicate: ${file.name}")
                    } else if (file.renameTo(targetFile)) {
                        movedCount++
                        Log.d(TAG, "Moved: ${file.name}")
                    } else {
                        // 尝试复制+删除
                        file.inputStream().use { input ->
                            targetFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        if (file.delete()) {
                            movedCount++
                            Log.d(TAG, "Copied and deleted: ${file.name}")
                        } else {
                            failedCount++
                            Log.w(TAG, "Failed to delete after copy: ${file.name}")
                        }
                    }
                }
            } catch (e: Exception) {
                failedCount++
                Log.e(TAG, "Failed to migrate ${file.name}: ${e.message}")
            }
        }
        
        return movedCount to failedCount
    }
    
    /**
     * 清理旧的存储目录
     */
    private fun cleanupOldDirectory(oldBaseDir: File) {
        try {
            if (!oldBaseDir.exists()) return
            
            // 递归删除空目录
            cleanEmptyDirectories(oldBaseDir)
            
            // 如果根目录也空了，删除它
            if (oldBaseDir.listFiles()?.isEmpty() == true) {
                if (oldBaseDir.delete()) {
                    Log.i(TAG, "Cleaned up old directory: ${oldBaseDir.absolutePath}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cleanup old directory: ${e.message}")
        }
    }
}
