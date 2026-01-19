package com.youyou.monitor.core.domain.model

/**
 * 检测结果
 */
data class MatchResult(
    val detectedApp: String,
    val score: Double,
    val scale: Float,
    val timeMs: Long,
    val isWeak: Boolean = false
)

/**
 * 图像帧数据
 * 
 * @param scale 屏幕缩放比例（1=原始分辨率，2=半分辨率，用于性能优化）
 */
data class ImageFrame(
    val width: Int,
    val height: Int,
    val data: ByteArray,
    val scale: Int = 1,
    val timestamp: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ImageFrame
        if (width != other.width) return false
        if (height != other.height) return false
        if (!data.contentEquals(other.data)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + data.contentHashCode()
        return result
    }
}

/**
 * WebDAV 服务器配置
 */
data class WebDavServer(
    val url: String,
    val username: String,
    val password: String,
    val monitorDir: String = "Monitor",
    val remoteUploadDir: String = "Monitor/upload"
)

/**
 * 监控配置
 */
data class MonitorConfig(
    val matchThreshold: Double = 0.92,
    val matchCooldownMs: Long = 3000L,
    val detectPerSecond: Int = 1,
    val maxStorageSizeMB: Int = 1024,
    val screenshotDir: String = "ScreenCaptures",
    val videoDir: String = "ScreenRecord",
    val matcherType: String = "grayscale",
    val preferExternalStorage: Boolean = false,
    val rootDir: String = "PingerLove",
    val webdavServers: List<WebDavServer> = emptyList(),
    val modelId: String? = null,  // 当前使用的AI模型ID
    val aiModelDir: String = "AI/Models",  // AI模型远程目录
    val aiConfigDir: String = "AI/Config",  // AI配置远程目录
    val enableModelAutoSync: Boolean = true,  // 是否启用模型自动同步
    val modelSyncIntervalHours: Int = 24  // 模型同步间隔（小时）
) {
    companion object {
        fun default() = MonitorConfig()
    }
}

/**
 * AI WebDAV 服务器配置
 */
data class AiWebDavServer(
    val url: String,
    val username: String,
    val password: String,
    val baseDir: String = ""  // 基础目录，所有AI相关文件都在此目录下
)

/**
 * AI 模型配置
 */
data class AiConfig(
    val currentModelId: String = "chat_detector_v1",
    val enableAutoSync: Boolean = true,
    val syncIntervalHours: Int = 24,
    val remoteModelDir: String = "AI/Models",
    val remoteConfigDir: String = "AI/Config",
    val webdavServers: List<AiWebDavServer> = emptyList()
) {
    companion object {
        fun default() = AiConfig()
    }
}
