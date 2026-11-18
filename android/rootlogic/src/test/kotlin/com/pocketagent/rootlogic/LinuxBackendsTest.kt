package com.pocketagent.rootlogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 执行后端的选择。
 *
 * 这里钉的是**顺序与诚实的边界**：root 优先、Termux 次之、内置兜底；
 * 而"内置"必须明确说自己装不了包、断不了网 —— 让用户以为有 Linux 然后发现
 * `apt` 用不了，比一开始就说清楚更伤信任。
 */
class LinuxBackendsTest {

    @Test
    fun `root 可用时选 root 通道`() {
        val choice = LinuxBackends.choose(BackendFacts(rootAvailable = true))

        assertEquals(ExecBackend.ROOT_CHROOT, choice.backend)
        assertTrue(choice.backend.realLinux)
        assertFalse(choice.degraded)
        assertTrue(choice.capabilities.containsAll(
            setOf(LinuxCapability.PACKAGES, LinuxCapability.SHELL, LinuxCapability.NETWORK_ISOLATION),
        ))
    }

    @Test
    fun `root 不可用但有 Termux 时选 Termux`() {
        val choice = LinuxBackends.choose(BackendFacts(termuxUsable = true, builtinPython = true))

        assertEquals(ExecBackend.TERMUX, choice.backend)
        assertTrue(choice.capabilities.contains(LinuxCapability.PACKAGES))
        // Termux 是 proot，没有 network namespace
        assertFalse(choice.capabilities.contains(LinuxCapability.NETWORK_ISOLATION))
        assertFalse(choice.degraded)
    }

    @Test
    fun `两个都没有时用内置引擎兜底，并且标明是降级`() {
        val choice = LinuxBackends.choose(BackendFacts(builtinPython = true, builtinMedia = true))

        assertEquals(ExecBackend.BUILTIN, choice.backend)
        assertTrue("降级必须显式", choice.degraded)
        assertFalse(choice.backend.realLinux)
        assertTrue(choice.capabilities.contains(LinuxCapability.PYTHON))
        assertTrue(choice.capabilities.contains(LinuxCapability.MEDIA))
        assertFalse("装包做不到就别说能", choice.capabilities.contains(LinuxCapability.PACKAGES))
        assertFalse(choice.capabilities.contains(LinuxCapability.SHELL))
    }

    @Test
    fun `内置只有 Python 时不吹自己能转码`() {
        val choice = LinuxBackends.choose(BackendFacts(builtinPython = true))

        assertTrue(choice.capabilities.contains(LinuxCapability.PYTHON))
        assertFalse(choice.capabilities.contains(LinuxCapability.MEDIA))
        assertTrue(choice.capabilities.contains(LinuxCapability.TEXT))
    }

    @Test
    fun `内置只有媒体时也能用，但没有 Python`() {
        val choice = LinuxBackends.choose(BackendFacts(builtinMedia = true))

        assertEquals(ExecBackend.BUILTIN, choice.backend)
        assertTrue(choice.capabilities.contains(LinuxCapability.MEDIA))
        assertFalse(choice.capabilities.contains(LinuxCapability.PYTHON))
    }

    @Test
    fun `什么都没有时如实说缺什么`() {
        val choice = LinuxBackends.choose(BackendFacts())

        assertEquals(ExecBackend.NONE, choice.backend)
        assertTrue(choice.capabilities.isEmpty())
        assertTrue(choice.explain.contains("Termux"))
        assertTrue(choice.explain.contains("root"))
    }

    @Test
    fun `降级文案要点明装不了包与断不了网`() {
        val choice = LinuxBackends.choose(BackendFacts(builtinPython = true))
        assertTrue(choice.explain.contains("装不了"))
        assertTrue(choice.explain.contains("断不了网"))
    }

    @Test
    fun `提示词那一行短且带能力清单`() {
        val line = LinuxBackends.promptLine(LinuxBackends.choose(BackendFacts(rootAvailable = true)))

        assertTrue(line.contains("root 通道"))
        assertTrue(line.contains("Python"))
        assertTrue("提示词里不该出现内部类型名", !line.contains("ExecBackend"))
        assertTrue(line.length < 200)
    }

    @Test
    fun `降级时提示词里要写清这是降级`() {
        val line = LinuxBackends.promptLine(LinuxBackends.choose(BackendFacts(builtinPython = true)))
        assertTrue(line.contains("降级"))
    }

    @Test
    fun `每一种选择都有非空且不带堆栈的说明`() {
        val facts = listOf(
            BackendFacts(rootAvailable = true),
            BackendFacts(termuxUsable = true),
            BackendFacts(builtinPython = true, builtinMedia = true),
            BackendFacts(),
        )
        for (f in facts) {
            val choice = LinuxBackends.choose(f)
            assertTrue(choice.explain.isNotBlank())
            assertFalse(choice.explain.contains("Exception"))
        }
    }

    @Test
    fun `root 与 Termux 都给真 Linux 标记`() {
        assertTrue(ExecBackend.ROOT_CHROOT.realLinux)
        assertTrue(ExecBackend.TERMUX.realLinux)
        assertFalse(ExecBackend.BUILTIN.realLinux)
        assertFalse(ExecBackend.NONE.realLinux)
    }
}
