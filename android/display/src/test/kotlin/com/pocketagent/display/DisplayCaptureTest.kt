package com.pocketagent.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **`:display` 模块的数据类语义**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么盯 equals —— 这个模块此前**零测试**
 * ═══════════════════════════════════════════════════════════════
 *
 * 385 行的模块，纳入离线验证后一直只有编译保护。
 * 而它的 `DisplayCapture` 手写了 `equals` —— 手写 equals 是最容易出错的地方，
 * 尤其当字段里有 `ByteArray`。
 *
 * ★ 2026-11-01 实测：原来的 `equals` **只比较 bytes**，
 *   忽略了尺寸与 mimeType。已修，下面几条是钉子。
 */
class DisplayCaptureTest {

    private fun cap(
        w: Int = 1280,
        h: Int = 720,
        bytes: ByteArray = byteArrayOf(1, 2, 3),
        mime: String = "image/webp",
    ) = DisplayCapture(sourceWidth = w, sourceHeight = h, bytes = bytes, mimeType = mime)

    // ═══════════════════════════════════════════════════════════
    //  ★ 修掉的那个 bug：只比 bytes
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `尺寸不同就不相等`() {
        // ══════════════════════════════════════════════════════
        //  ★ 这条是给修掉的 bug 钉的钉子
        // ══════════════════════════════════════════════════════
        // 原来只比 bytes，于是两张尺寸不同、字节相同的截图会判相等。
        // 用 equals 去重/判缓存命中的代码会因此得出错误结论。
        assertNotEquals(cap(w = 1280), cap(w = 1920))
        assertNotEquals(cap(h = 720), cap(h = 1080))
    }

    @Test
    fun `mimeType 不同就不相等`() {
        assertNotEquals(
            cap(mime = "image/webp"),
            cap(mime = "image/png"),
        )
    }

    @Test
    fun `字节不同就不相等`() {
        assertNotEquals(
            cap(bytes = byteArrayOf(1, 2, 3)),
            cap(bytes = byteArrayOf(1, 2, 4)),
        )
    }

    // ═══════════════════════════════════════════════════════════
    //  ByteArray 必须按内容比较
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `字节相同时相等 即使不是同一个数组实例`() {
        // ⚠️ 用 `==` 比 ByteArray 是**引用比较** ——
        //    与 `ToolOutcome.Success` 那处同一个陷阱。
        val a = byteArrayOf(9, 8, 7)
        val b = byteArrayOf(9, 8, 7)
        assertFalse("前置：两个数组不是同一实例", a === b)
        assertEquals(cap(bytes = a), cap(bytes = b))
    }

    @Test
    fun `hashCode 与 equals 一致`() {
        val a = cap(bytes = byteArrayOf(5, 6))
        val b = cap(bytes = byteArrayOf(5, 6))
        assertEquals(a, b)
        assertEquals("相等的对象必须有相同 hashCode", a.hashCode(), b.hashCode())
    }

    @Test
    fun `hashCode 区分不同内容`() {
        // ⚠️ 不能只用尺寸算 hashCode —— 那样不同内容会撞哈希，
        //    哈希容器会退化成链表（性能问题，不是正确性问题，但值得防）。
        assertNotEquals(
            cap(bytes = byteArrayOf(1)).hashCode(),
            cap(bytes = byteArrayOf(2)).hashCode(),
        )
        assertNotEquals(
            cap(w = 100).hashCode(),
            cap(w = 200).hashCode(),
        )
    }

    // ═══════════════════════════════════════════════════════════
    //  常规语义
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `自反性`() {
        val a = cap()
        assertEquals(a, a)
    }

    @Test
    fun `与 null 和非同类不相等`() {
        val a = cap()
        assertNotEquals(a, null)
        @Suppress("EqualsBetweenInconvertibleTypes")
        assertNotEquals(a, "不是截图")
    }

    @Test
    fun `默认 mimeType 是 webp`() {
        assertEquals("image/webp", cap().mimeType)
    }

    @Test
    fun `空字节数组也按内容比`() {
        assertEquals(cap(bytes = ByteArray(0)), cap(bytes = ByteArray(0)))
        assertNotEquals(cap(bytes = ByteArray(0)), cap(bytes = byteArrayOf(0)))
    }
}
