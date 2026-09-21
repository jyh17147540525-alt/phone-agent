package com.pocketagent.plugin.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 插件包解压防护的测试。
 *
 * 这类防护最容易写成"看起来对但漏一个变形"，所以下面把**每个变形都单独列一条**，
 * 而不是笼统地"测几个危险路径"。
 */
class PluginBundleTest {

    // ═══════════════════════════════════════════════════════════
    //  一、Zip Slip —— 路径穿越
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `正常条目名被接受`() {
        for (ok in listOf(
            "plugin.json",
            "rules/main.json",
            "assets/icons/icon.png",
            "a/b/c/d/e.txt",
            "带中文的目录/规则.json",
        )) {
            assertTrue("「$ok」应该被接受", PluginBundle.isSafeEntryName(ok))
        }
    }

    @Test
    fun `目录条目被接受`() {
        // zip 里目录条目以 / 结尾，剥掉尾斜杠后仍是合法路径
        assertTrue(PluginBundle.isSafeEntryName("rules/"))
        assertTrue(PluginBundle.isSafeEntryName("assets/icons/"))
    }

    /**
     * ★ 核心安全用例。
     *
     * 每一个变体都必须被拦下。攻击者不会只用一种写法 ——
     * 只要漏掉一个，前面所有的防护都等于零。
     */
    @Test
    fun `路径穿越的各种变形都被拦下`() {
        val attacks = listOf(
            "../evil.json",                          // 最朴素
            "../../evil.json",                       // 往上两层
            "a/../../evil.json",                     // 藏在中间
            "a/b/../../../../data/data/x",           // 往上四层
            "..",                                    // 光秃秃的父目录
            "a/..",                                  // 以 .. 结尾
            "a/../",                                 // 以 ../ 结尾的目录条目
            "..\\evil.json",                         // 反斜杠变体
            "a\\..\\..\\evil.json",                  // 全反斜杠
            "\\evil.json",                           // 以反斜杠开头
            "/etc/passwd",                           // 绝对路径
            "/data/data/com.pocketagent/databases/x",// 直捣本体数据目录
            "C:/Windows/system32/x.dll",             // Windows 盘符
            "C:evil.json",                           // 盘符相对路径（最阴的一种）
            "a//b.json",                             // 空段
            "./evil.json",                           // 当前目录段
            "a/./b.json",
            "evil.json\u0000.png",                   // NUL 截断：绕过扩展名检查
            "",                                      // 空
            "   ",                                   // 纯空白
            "/",                                     // 光一个斜杠
        )

        for (attack in attacks) {
            assertFalse(
                "「${attack.replace("\u0000", "\\0")}」必须被拦下",
                PluginBundle.isSafeEntryName(attack),
            )
        }
    }

    @Test
    fun `NUL 截断单独验证`() {
        // 这条单独拎出来是因为它最容易被忽略：
        // "evil.json\0.png" 在扩展名检查里是 .png，在文件系统 API 里是 evil.json
        assertFalse(PluginBundle.isSafeEntryName("evil.json\u0000.png"))
        assertFalse(PluginBundle.isSafeEntryName("\u0000"))
    }

    // ═══════════════════════════════════════════════════════════
    //  二、扩展名
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `插件包扩展名识别忽略大小写`() {
        assertTrue(PluginBundle.hasBundleExtension("wechat.pagent"))
        assertTrue(PluginBundle.hasBundleExtension("WeChat.PAGENT"))
    }

    @Test
    fun `其他扩展名不被当成插件包`() {
        for (bad in listOf("a.zip", "a.apk", "a.pagent.zip", "pagent", "a.js")) {
            assertFalse("「$bad」不该被当成插件包", PluginBundle.hasBundleExtension(bad))
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  三、Zip Bomb —— 预算控制
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `正常包在预算内`() {
        assertNull(
            PluginBundle.exceedsBudget(
                entryCount = 12,
                uncompressedBytes = 200L * 1024,
                compressedBytes = 20L * 1024,
            )
        )
    }

    @Test
    fun `条目数超限被拦下`() {
        val reason = PluginBundle.exceedsBudget(
            entryCount = PluginBundle.MAX_ENTRIES + 1,
            uncompressedBytes = 1024,
            compressedBytes = 512,
        )

        assertNotNull("条目过多必须拦下", reason)
        assertTrue("要说清原因：$reason", reason!!.contains("文件过多"))
    }

    @Test
    fun `解压体积超限被拦下`() {
        val reason = PluginBundle.exceedsBudget(
            entryCount = 3,
            uncompressedBytes = PluginBundle.MAX_UNCOMPRESSED_BYTES + 1,
            compressedBytes = 1024,
        )

        assertNotNull("体积过大必须拦下", reason)
        assertTrue("要说清原因：$reason", reason!!.contains("体积过大"))
    }

    @Test
    fun `压缩比异常被拦下`() {
        // ⚠️ 数据要精心挑：解压体积必须**在体积上限之内**，否则会先命中
        //    「体积过大」那条分支，压缩比规则根本没机会执行 —— 测试看着绿，
        //    实际测的是另一条逻辑。这是写防护类测试最容易犯的错。
        //
        //    30 MB 解压自 100 KB，压缩比 307 倍：体积没超（< 32 MB），
        //    但压缩比远超 200 倍上限。
        val reason = PluginBundle.exceedsBudget(
            entryCount = 5,
            uncompressedBytes = 30L * 1024 * 1024,
            compressedBytes = 100L * 1024,
        )

        assertNotNull("压缩比异常必须拦下", reason)
        assertTrue("要说清是压缩比问题，而不是别的：$reason", reason!!.contains("压缩比"))
    }

    @Test
    fun `体积与压缩比都超限时先报体积`() {
        // 顺序是刻意的：体积超限更直观，用户一眼能懂；压缩比是技术解释
        val reason = PluginBundle.exceedsBudget(
            entryCount = 5,
            uncompressedBytes = 1024L * 1024 * 1024,
            compressedBytes = 4L * 1024 * 1024,
        )

        assertNotNull(reason)
        assertTrue("应报体积问题：$reason", reason!!.contains("体积过大"))
    }

    /**
     * 小样本不该被压缩比规则误伤。
     *
     * 一个 200 字节的 JSON 压缩后可能只剩 20 字节，比值 10 倍看着正常；
     * 但如果只有几十字节，比值可能上百倍。没有最小样本量的话，
     * **正常的小插件会被拦下来** —— 防护写过头比不写还糟。
     */
    @Test
    fun `小文件不会被压缩比规则误伤`() {
        assertNull(
            "小样本不该按压缩比判定",
            PluginBundle.exceedsBudget(
                entryCount = 2,
                uncompressedBytes = 900,
                compressedBytes = 9,   // 100 倍，但样本太小
            )
        )
    }

    @Test
    fun `边界值恰好等于上限时放行`() {
        assertNull(
            PluginBundle.exceedsBudget(
                entryCount = PluginBundle.MAX_ENTRIES,
                uncompressedBytes = PluginBundle.MAX_UNCOMPRESSED_BYTES,
                compressedBytes = PluginBundle.MAX_UNCOMPRESSED_BYTES / PluginBundle.MAX_COMPRESSION_RATIO,
            )
        )
    }

    @Test
    fun `常量之间的关系是自洽的`() {
        // 清单文件本身必须能塞进包里 —— 这类"常量之间打架"的 bug 没人会去想
        assertEquals("plugin.json", PluginBundle.MANIFEST_NAME)
        assertTrue("清单名必须是安全条目名", PluginBundle.isSafeEntryName(PluginBundle.MANIFEST_NAME))
        assertTrue("上限必须为正", PluginBundle.MAX_ENTRIES > 0)
        assertTrue(PluginBundle.MAX_UNCOMPRESSED_BYTES > 0)
        assertTrue(PluginBundle.MAX_COMPRESSION_RATIO > 1)
    }
}
