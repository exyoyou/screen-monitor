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
    private val context: Context
) : StorageRepository {
    
    companion object {
        const val TAG = "StorageRepository"
        const val SCREENSHOT_DIR = "ScreenCaptures"
        const val VIDEO_DIR = "ScreenRecord"
        private const val PREFS_NAME = "migration_failures"
        private const val KEY_FAILED_MIGRATIONS = "failed_migrations"
    }
    
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    @Volatile
    private var currentConfig: MonitorConfig = MonitorConfig.default()
    
    @Volatile
    private var isMigrating = false
    
    private fun saveFailedMigrations(failures: List<Pair<String, String>>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val data = failures.joinToString(";") { "${it.first}|${it.second}" }
        prefs.edit().putString(KEY_FAILED_MIGRATIONS, data).apply()
    }
    
    private fun loadFailedMigrations(): List<Pair<String, String>> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val data = prefs.getString(KEY_FAILED_MIGRATIONS, "") ?: ""
        return if (data.isEmpty()) emptyList() else data.split(";").map {
            val parts = it.split("|", limit = 2)
            parts[0] to parts[1]
        }
    }
    
    private fun clearFailedMigrations() {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().remove(KEY_FAILED_MIGRATIONS).apply()
    }
    
    /**
     * 获取根目录（支持优先外部存储）
     */
    override fun getRootDir(): File {
        val config = currentConfig
        val baseDir = if (config.preferExternalStorage) {
            val ext = File("/storage/emulated/0", config.rootDir)
            if (ext.exists() && ext.canWrite()) {
                ext
            } else {
                Log.w(TAG, "外部存储不可用，使用内部存储")
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

    override suspend fun saveScreenshot(bitmap: Bitmap, filename: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            // 按日期创建子目录
            val dateStr = dateFormat.get()!!.format(Date())
            val dayDir = File(screenshotBaseDir, dateStr).apply {
                if (!exists()) mkdirs()
            }
            
            // 根据存储位置决定是否隐藏：外部存储时用 .tmp_png 扩展名隐藏
            val modifiedFilename = if (currentConfig.preferExternalStorage) {
                "${filename.substringBeforeLast('.')}.tmp_png"
            } else {
                filename
            }
            val file = File(dayDir, modifiedFilename)
            
            // 保存 Bitmap 为 JPG 格式
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
            }
            
            Log.d(TAG, "截图已保存：${file.name}")
            Result.success(file.name)
        } catch (e: Exception) {
            Log.e(TAG, "保存截图失败：${e.message}", e)
            Result.failure(e)
        }
    }
    
    override suspend fun getTotalSize(): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val size = calculateDirectorySize(screenshotBaseDir) + calculateDirectorySize(videoBaseDir)
            Result.success(size)
        } catch (e: Exception) {
            Log.e(TAG, "获取总大小失败：${e.message}", e)
            Result.failure(e)
        }
    }
    
    override suspend fun deleteOldestFiles(bytes: Long): Result<Int> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "清理存储：目标=${bytes / 1024 / 1024}MB")
            
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
                    Log.e(TAG, "删除文件失败：${file.name}")
                }
            }
            
            // 清理空目录
            cleanEmptyDirectories(screenshotBaseDir)
            cleanEmptyDirectories(videoBaseDir)
            
            Log.i(TAG, "存储已清理：$count 个文件，释放了 ${deleted / 1024 / 1024}MB")
            Result.success(count)
        } catch (e: Exception) {
            Log.e(TAG, "清理存储失败：${e.message}", e)
            Result.failure(e)
        }
    }
    
    override suspend fun listScreenshots(): Result<List<File>> = withContext(Dispatchers.IO) {
        try {
            val files = getAllFiles(screenshotBaseDir)
            Result.success(files)
        } catch (e: Exception) {
            Log.e(TAG, "列出截图失败：${e.message}", e)
            Result.failure(e)
        }
    }
    
    override suspend fun listVideos(): Result<List<File>> = withContext(Dispatchers.IO) {
        try {
            val files = getAllFiles(videoBaseDir)
            Result.success(files)
        } catch (e: Exception) {
            Log.e(TAG, "列出视频失败：${e.message}", e)
            Result.failure(e)
        }
    }
            
    override suspend fun getPendingUploadFiles(): List<String> = withContext(Dispatchers.IO) {
        try {
            (getAllFiles(screenshotBaseDir) + getAllFiles(videoBaseDir)).map { it.absolutePath }
        } catch (e: Exception) {
            Log.e(TAG, "获取待上传文件失败：${e.message}", e)
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
                
                // 如果目录为空（忽略隐藏文件），删除它
                val files = file.listFiles() ?: emptyArray()
                val nonHiddenFiles = files.filterNot { it.name.startsWith(".") }
                if (nonHiddenFiles.isEmpty()) {
                    file.delete()
                    Log.d(TAG, "删除了空目录：${file.name}")
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
            Log.w(TAG, "迁移已在进行中，跳过")
            return
        }
        
        scope.launch(Dispatchers.IO) {
            try {
                isMigrating = true
                migrateStorage(oldConfig, newConfig)
            } catch (e: Exception) {
                Log.e(TAG, "迁移失败：${e.message}", e)
            } finally {
                isMigrating = false
            }
        }
    }
    
    /**
     * 迁移存储文件
     */
    private suspend fun migrateStorage(oldConfig: MonitorConfig, newConfig: MonitorConfig): Boolean = withContext(Dispatchers.IO) {
        Log.i(TAG, "开始存储迁移...")
        
        // 计算旧路径
        val oldBaseDir = if (oldConfig.preferExternalStorage) {
            val ext = File("/storage/emulated/0", oldConfig.rootDir)
            if (ext.exists() && ext.canWrite()) {
                ext
            } else {
                File(context.filesDir, oldConfig.rootDir)
            }
        } else {
            File(context.filesDir, oldConfig.rootDir)
        }
        
        // 计算新路径（基于 newConfig，不回退）
        val newBaseDir = if (newConfig.preferExternalStorage) {
            File("/storage/emulated/0", newConfig.rootDir)
        } else {
            File(context.filesDir, newConfig.rootDir)
        }
        if (!newBaseDir.exists()) newBaseDir.mkdirs()
        
        // 管理根目录和截图目录的 .nomedia 文件
        val rootDir = getRootDir()
        manageNomediaFile(rootDir, newConfig.preferExternalStorage)
        // 检查是否需要迁移
        if (oldBaseDir.absolutePath == newBaseDir.absolutePath &&
            oldConfig.screenshotDir == newConfig.screenshotDir &&
            oldConfig.videoDir == newConfig.videoDir &&
            oldConfig.templateDir == newConfig.templateDir) {
            Log.d(TAG, "存储路径相同，无需迁移")
            return@withContext true
        }
        
        var totalMoved = 0
        val allFailedMigrations = mutableListOf<Pair<String, String>>()
        
        // 重试之前失败的迁移
        val previousFailures = loadFailedMigrations()
        val retrySuccess = mutableListOf<Pair<String, String>>()
        for ((source, target) in previousFailures) {
            val sourceFile = File(source)
            val targetFile = File(target)
            if (sourceFile.exists() && targetFile.exists()) {
                // 目标已存在，删除源文件（重复）
                try {
                    if (sourceFile.delete()) {
                        Log.d(TAG, "删除了重复源文件：${source}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "删除重复源文件失败：${source}", e)
                }
                retrySuccess.add(source to target)
            } else if (sourceFile.exists() && !targetFile.exists()) {
                // 尝试迁移
                try {
                    if (sourceFile.renameTo(targetFile)) {
                        Log.d(TAG, "重试迁移成功：${source}")
                        retrySuccess.add(source to target)
                    } else {
                        allFailedMigrations.add(source to target)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "重试迁移失败：${source}", e)
                    allFailedMigrations.add(source to target)
                }
            } else if (!sourceFile.exists() && targetFile.exists()) {
                // 源不存在但目标存在，视为成功
                retrySuccess.add(source to target)
            } else {
                // 都不存在，可能已清理，移除
                retrySuccess.add(source to target)
            }
        }
        val remainingFailures = previousFailures.filterNot { it in retrySuccess }
        
        // 迁移已知目录（支持目录名变化）
        val knownDirs = listOf(
            Triple(oldConfig.screenshotDir, newConfig.screenshotDir, "截图"),
            Triple(oldConfig.videoDir, newConfig.videoDir, "视频"),
            Triple(oldConfig.templateDir, newConfig.templateDir, "模板")
        )
        
        for ((oldName, newName, desc) in knownDirs) {
            val oldDir = File(oldBaseDir, oldName)
            val newDir = File(newBaseDir, newName)
            if (oldDir.exists()) {
                Log.d(TAG, "迁移${desc}目录：从 ${oldDir.absolutePath} 到 ${newDir.absolutePath}")
                val (moved, failed) = migrateDirectory(oldDir, newDir, newConfig.preferExternalStorage)
                totalMoved += moved
                allFailedMigrations.addAll(failed)
            }
        }
        
        // 迁移其他目录（假设名字不变）
        val knownOldNames = knownDirs.map { it.first }
        val otherDirs = oldBaseDir.listFiles()?.filter { it.isDirectory && it.name !in knownOldNames } ?: emptyList()
        
        for (oldDir in otherDirs) {
            val newDir = File(newBaseDir, oldDir.name)
            Log.d(TAG, "迁移其他目录：从 ${oldDir.absolutePath} 到 ${newDir.absolutePath}")
            val (moved, failed) = migrateDirectory(oldDir, newDir, newConfig.preferExternalStorage)
            totalMoved += moved
            allFailedMigrations.addAll(failed)
        }
        
        val totalFailed = allFailedMigrations.size
        Log.i(TAG, "迁移完成：移动=$totalMoved, 失败=$totalFailed")
        
        // 保存失败的迁移
        val allFailures = remainingFailures + allFailedMigrations
        saveFailedMigrations(allFailures)
        
        // 清理旧目录只有在完全成功时
        if (totalFailed == 0) {
            clearFailedMigrations()
            cleanupOldDirectory(oldBaseDir)
        }
        
        // 返回迁移是否完全成功
        totalFailed == 0
    }
    
    /**
     * 迁移目录内容（递归）
     * @return Pair(成功数量, 失败迁移列表)
     */
    private fun migrateDirectory(sourceDir: File, targetDir: File, preferExternal: Boolean): Pair<Int, List<Pair<String, String>>> {
        if (!sourceDir.exists() || !sourceDir.canRead()) {
            return 0 to emptyList()
        }
        
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }
        
        var movedCount = 0
        val failedMigrations = mutableListOf<Pair<String, String>>()
        
        val files = sourceDir.listFiles() ?: return 0 to emptyList()
        
        for (file in files) {
            try {
                if (file.isDirectory) {
                    // 递归处理子目录
                    val targetSubDir = File(targetDir, file.name)
                    val (moved, subFailed) = migrateDirectory(file, targetSubDir, preferExternal)
                    movedCount += moved
                    failedMigrations.addAll(subFailed)
                } else {
                    // 根据存储位置决定文件名：外部存储时用 .tmp_png 隐藏，内部存储时还原为 .png
                    val targetName = if (preferExternal) {
                        if (file.name.endsWith(".tmp_png")) file.name else file.name.removeSuffix(".png") + ".tmp_png"
                    } else {
                        if (file.name.endsWith(".tmp_png")) file.name.removeSuffix(".tmp_png") + ".png" else file.name
                    }
                    val targetFile = File(targetDir, targetName)
                    
                    // 如果目标已存在且大小相同，删除源文件
                    if (targetFile.exists() && targetFile.length() == file.length()) {
                        file.delete()
                        Log.d(TAG, "删除了重复文件：${file.name}")
                        movedCount++
                    } else if (file.renameTo(targetFile)) {
                        movedCount++
                        Log.d(TAG, "已移动：${file.name} -> ${targetFile.name}")
                    } else {
                        // 尝试复制+删除
                        file.inputStream().use { input ->
                            targetFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        if (file.delete()) {
                            movedCount++
                            Log.d(TAG, "已复制并删除：${file.name} -> ${targetFile.name}")
                        } else {
                            failedMigrations.add(file.absolutePath to targetFile.absolutePath)
                            Log.w(TAG, "复制后删除失败：${file.name}")
                        }
                    }
                }
            } catch (e: Exception) {
                val targetName = if (preferExternal) {
                    if (file.name.endsWith(".tmp_png")) file.name else "${file.name.substringBeforeLast('.')}.tmp_png"
                } else {
                    if (file.name.endsWith(".tmp_png")) "${file.name.substringBeforeLast('.')}.png" else file.name
                }
                val targetFile = File(targetDir, targetName)
                failedMigrations.add(file.absolutePath to targetFile.absolutePath)
                Log.e(TAG, "迁移 ${file.name} 失败：${e.message}")
            }
        }
        
        return movedCount to failedMigrations
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
                    Log.i(TAG, "清理了旧目录：${oldBaseDir.absolutePath}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "清理旧目录失败：${e.message}")
        }
    }
    
    /**
     * 管理 .nomedia 文件以隐藏媒体文件
     */
    private fun manageNomediaFile(dir: File, create: Boolean) {
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val nomediaFile = File(dir, ".nomedia")
        try {
            if (create) {
                if (!nomediaFile.exists()) {
                    nomediaFile.createNewFile()
                    Log.d(TAG, "创建了 .nomedia 文件：${nomediaFile.absolutePath}")
                }
            } else {
                if (nomediaFile.exists()) {
                    nomediaFile.delete()
                    Log.d(TAG, "删除了 .nomedia 文件：${nomediaFile.absolutePath}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "管理 .nomedia 文件失败：${e.message}", e)
        }
    }

    override fun updateConfig(config: MonitorConfig) {
        val oldConfig = currentConfig
        val hasFailures = loadFailedMigrations().isNotEmpty()
        Log.d(TAG, "检测到配置变化: preferExternalStorage ${oldConfig.preferExternalStorage} -> ${config.preferExternalStorage}, rootDir ${oldConfig.rootDir} -> ${config.rootDir}, 有失败迁移=${hasFailures}")
        if (config.preferExternalStorage != oldConfig.preferExternalStorage ||
            config.rootDir != oldConfig.rootDir ||
            config.screenshotDir != oldConfig.screenshotDir ||
            config.videoDir != oldConfig.videoDir ||
            config.templateDir != oldConfig.templateDir ||
            hasFailures) {
            Log.i(TAG, "存储路径已更改或有失败迁移需要重试：优先外部存储=${config.preferExternalStorage}, 根目录=${config.rootDir}, 截图目录=${config.screenshotDir}, 视频目录=${config.videoDir}, 模板目录=${config.templateDir}, 有失败=${hasFailures}")
            
            // 异步迁移文件
            migrateStorageAsync(oldConfig, config)
        }
        currentConfig = config
    }
}
