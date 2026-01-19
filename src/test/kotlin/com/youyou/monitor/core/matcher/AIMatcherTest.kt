package com.youyou.monitor.core.matcher

import org.junit.Assert.*
import org.junit.Test

/**
 * AI 匹配器单元测试
 */
class AIMatcherTest {

    @Test
    fun testChatAppEnum() {
        // 测试枚举值
        assertEquals("微信-日间模式", AIMatcher.ChatApp.WECHAT_DAY.displayName)
        assertEquals("微信-夜间模式", AIMatcher.ChatApp.WECHAT_NIGHT.displayName)
        assertEquals("QQ", AIMatcher.ChatApp.QQ.displayName)
        assertEquals("微博", AIMatcher.ChatApp.WEIBO.displayName)
        assertEquals("抖音", AIMatcher.ChatApp.DOUYIN.displayName)
        assertEquals("未知", AIMatcher.ChatApp.UNKNOWN.displayName)
    }
}