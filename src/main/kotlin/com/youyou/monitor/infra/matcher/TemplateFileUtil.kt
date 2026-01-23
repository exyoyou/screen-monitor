package com.youyou.monitor.infra.matcher

import java.io.File

object TemplateFileUtil {
    // 远程文件使用的标准扩展
    val REMOTE_IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg")

    // 本地可能出现的扩展（支持外部存储使用的 tmp_ 方案）
    val LOCAL_IMAGE_EXTENSIONS = REMOTE_IMAGE_EXTENSIONS + setOf("tmp_png", "tmp_jpg")

    fun isRemoteImageName(name: String): Boolean {
        val idx = name.lastIndexOf('.')
        if (idx <= 0) return false
        val ext = name.substring(idx + 1).lowercase()
        return REMOTE_IMAGE_EXTENSIONS.contains(ext)
    }

    fun isLocalImageFile(file: File): Boolean {
        val ext = file.extension.lowercase()
        return LOCAL_IMAGE_EXTENSIONS.contains(ext)
    }

    /**
     * 把本地名（如 name.tmp_png）规范化为远程名（name.png）
     */
    fun normalizeLocalToRemote(localName: String): String {
        val tmpIndex = localName.lastIndexOf(".tmp_")
        return if (tmpIndex != -1) {
            localName.substring(0, tmpIndex) + "." + localName.substring(tmpIndex + 5)
        } else {
            localName
        }
    }

    /**
     * 将远程名转换为本地名（如果 preferExternalStorage 则使用 .tmp_<ext>）
     */
    fun remoteToLocalName(remoteName: String, preferExternal: Boolean): String {
        if (!preferExternal) return remoteName
        val idx = remoteName.lastIndexOf('.')
        if (idx <= 0) return remoteName
        val base = remoteName.substring(0, idx)
        val ext = remoteName.substring(idx + 1)
        return "$base.tmp_$ext"
    }
}
