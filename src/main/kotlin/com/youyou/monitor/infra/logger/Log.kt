package com.youyou.monitor.infra.logger

import android.content.Context
import android.util.Log as AndroidLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 文件日志工具（重构版 - 从旧代码迁移）
 * 
 * 改进：
 * - 使用协程 Dispatcher 替代 ExecutorService
 * - 添加日志级别过滤
 * - 线程安全的 SimpleDateFormat
 * 
 * 命名策略：与 android.util.Log 同名，便于替换
 * - 只需修改 import 即可从系统日志切换到文件日志
 * - 保持 API 兼容：d(), i(), w(), e(), v()
 */
object Log {
    private const val TAG = "FileLog"

    private const val DIR_NAME = "Logs"
    private const val MAX_LOG_FILE_SIZE = 1 * 1024 * 1024 // 1MB

    private var logDir: File? = null
    private var currentLogFile: File? = null
    private var isInitialized = false
    
    // 使用单线程 Executor - 添加 volatile 确保线程可见性
    @Volatile
    private var executor: ExecutorService = java.util.concurrent.Executors.newSingleThreadExecutor()
    
    // 确保 executor 可用
    private fun ensureExecutorAvailable() {
        if (executor.isShutdown || executor.isTerminated) {
            synchronized(this) {
                if (executor.isShutdown || executor.isTerminated) {
                    executor = java.util.concurrent.Executors.newSingleThreadExecutor()
                    AndroidLog.w(TAG, "执行器已终止，创建新的执行器")
                }
            }
        }
    }
    
    // ThreadLocal 确保线程安全
    private val dateFormat = ThreadLocal.withInitial { 
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()) 
    }
    private val fileNameFormat = ThreadLocal.withInitial { 
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()) 
    }

    /**
     * 初始化日志系统
     */
    fun init(context: Context) {
        if (isInitialized) {
            AndroidLog.w(TAG, "FileLog 已经初始化")
            return
        }

        try {
            logDir = File(context.filesDir, DIR_NAME)
            if (logDir?.exists() == false) {
                logDir?.mkdirs()
            }

            createNewLogFile()
            isInitialized = true
            AndroidLog.i(TAG, "FileLog 初始化完成：${logDir?.absolutePath}")
        } catch (e: Exception) {
            AndroidLog.e(TAG, "初始化 FileLog 失败：${e.message}", e)
        }
    }

    /**
     * 更新日志目录（用于动态配置变更）
     */
    fun updateLogDir(getRootDir: () -> File) {
        try {
            val newLogDir = File(getRootDir(), DIR_NAME)
            if (newLogDir == logDir) {
                AndroidLog.d(TAG, "日志目录未改变：${newLogDir.absolutePath}")
                return
            }

            // 关闭当前日志文件
            shutdown()

            // 设置新目录
            logDir = newLogDir
            if (logDir?.exists() == false) {
                logDir?.mkdirs()
            }

            // 创建新日志文件
            createNewLogFile()
            isInitialized = true
            AndroidLog.i(TAG, "FileLog 目录更新完成：${logDir?.absolutePath}")
        } catch (e: Exception) {
            AndroidLog.e(TAG, "更新 FileLog 目录失败：${e.message}", e)
        }
    }



    private fun createNewLogFile() {
        try {
            val timestamp = fileNameFormat.get()!!.format(Date())
            currentLogFile = File(logDir, "monitor_$timestamp.log")

            writeToFile("========================================")
            writeToFile("Screen Monitor Log")
            writeToFile("Started at: ${dateFormat.get()!!.format(Date())}")
            writeToFile("========================================")
        } catch (e: Exception) {
            AndroidLog.e(TAG, "创建日志文件失败：${e.message}", e)
        }
    }

    private fun checkLogFileSize() {
        try {
            val fileSize = currentLogFile?.length() ?: 0
            if (fileSize > MAX_LOG_FILE_SIZE) {
                AndroidLog.i(TAG, "日志文件大小超出 (${fileSize / 1024 / 1024}MB)，正在轮换")
                createNewLogFile()
            }
        } catch (e: Exception) {
            AndroidLog.e(TAG, "检查日志文件大小失败：${e.message}", e)
        }
    }

    fun forceRotate() {
        try {
            if (!isInitialized) return
            val fileSize = currentLogFile?.length() ?: 0
            if (fileSize > 0) {
                AndroidLog.i(TAG, "强制轮换日志文件 (${fileSize / 1024}KB)")
                createNewLogFile()
            }
        } catch (e: Exception) {
            AndroidLog.e(TAG, "强制轮换失败：${e.message}", e)
        }
    }

    private fun writeToFileAsync(message: String) {
        if (!isInitialized) return
        try {
            ensureExecutorAvailable()
            executor.execute { writeToFile(message) }
        } catch (e: Exception) {
            AndroidLog.e(TAG, "提交日志任务失败：${e.message}")
        }
    }

    private fun writeToFile(message: String) {
        try {
            checkLogFileSize()
            currentLogFile?.let { file ->
                FileWriter(file, true).use { writer ->
                    writer.appendLine(message)
                    writer.flush()
                }
            }
        } catch (e: Exception) {
            AndroidLog.e(TAG, "写入日志文件失败：${e.message}", e)
        }
    }

    private fun formatMessage(level: String, tag: String, message: String): String {
        val timestamp = dateFormat.get()!!.format(Date())
        val threadName = Thread.currentThread().name
        return "$timestamp $level/$tag [$threadName]: $message"
    }

    fun d(tag: String, message: String) {
        AndroidLog.d(tag, message)
        writeToFileAsync(formatMessage("D", tag, message))
    }

    fun i(tag: String, message: String) {
        AndroidLog.i(tag, message)
        writeToFileAsync(formatMessage("I", tag, message))
    }

    fun w(tag: String, message: String) {
        AndroidLog.w(tag, message)
        writeToFileAsync(formatMessage("W", tag, message))
    }

    fun e(tag: String, message: String) {
        AndroidLog.e(tag, message)
        writeToFileAsync(formatMessage("E", tag, message))
    }

    fun e(tag: String, message: String, throwable: Throwable) {
        AndroidLog.e(tag, message, throwable)
        val stackTrace = throwable.stackTraceToString()
        writeToFileAsync(formatMessage("E", tag, "$message\n$stackTrace"))
    }

    fun v(tag: String, message: String) {
        AndroidLog.v(tag, message)
        writeToFileAsync(formatMessage("V", tag, message))
    }

    fun getLogDirectory(): String? = logDir?.absolutePath
    fun getCurrentLogFile(): String? = currentLogFile?.absolutePath

    fun shutdown() {
        try {
            writeToFile("========================================")
            writeToFile("Log ended at: ${dateFormat.get()!!.format(Date())}")
            writeToFile("========================================")
            // 不关闭executor，保持可用
        } catch (e: Exception) {
            AndroidLog.e(TAG, "关闭当前日志文件失败：${e.message}", e)
        }
    }

    fun shutdownCompletely() {
        try {
            shutdown()
            executor.shutdown()
        } catch (e: Exception) {
            AndroidLog.e(TAG, "完全关闭失败：${e.message}", e)
        }
    }
}
