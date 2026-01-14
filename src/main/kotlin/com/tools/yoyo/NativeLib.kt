package com.tools.yoyo

import android.hardware.HardwareBuffer
import java.nio.ByteBuffer

object NativeLib {
    /**
     * 将 `hardwareBuffer` 的字节写入调用方传入的预分配 direct `ByteBuffer`。
     * 返回 0 表示成功，负数表示错误（详见 native 实现）。
     * @param hardwareBuffer android.hardware.HardwareBuffer 对象
     * @param dest 预分配的 direct ByteBuffer
     */

    external fun nativeWriteHardwareBufferToBuffer(hardwareBuffer: HardwareBuffer, dest: ByteBuffer): Int

    // 用于应用启动时加载 'yoyo' native 库
    init {
        System.loadLibrary("yoyo")
    }
}