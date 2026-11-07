package com.pocketagent.rootlogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通道身份识别。
 *
 * 只认 uid：用户名在 ROM 之间会变，uid 是内核定的。
 * 这条判据错了的后果很具体 —— shell 被当成"不可用"，虚拟屏那条免 root 的路就永远走不通。
 */
class ProbeIdentityTest {

    @Test
    fun `uid 零是 root`() {
        assertEquals(PrivilegeIdentity.ROOT, ProbeIdentity.parse("uid=0(root) gid=0(root) groups=0(root)"))
    }

    @Test
    fun `uid 二千是 shell`() {
        assertEquals(
            PrivilegeIdentity.SHELL,
            ProbeIdentity.parse("uid=2000(shell) gid=2000(shell) groups=2000(shell),3003(inet)"),
        )
    }

    @Test
    fun `普通应用身份两者都不是`() {
        assertEquals(PrivilegeIdentity.NONE, ProbeIdentity.parse("uid=10123(u0_a123) gid=10123(u0_a123)"))
    }

    @Test
    fun `空输出不猜身份`() {
        assertEquals(PrivilegeIdentity.NONE, ProbeIdentity.parse(""))
    }

    @Test
    fun `名字里带 root 字样不算 root`() {
        // 有些 ROM 的 shell 上下文里也出现 root 字样；只认 uid 才不会误判
        assertEquals(
            PrivilegeIdentity.SHELL,
            ProbeIdentity.parse("uid=2000(shell) context=u:r:shell:s0 unrelated=root"),
        )
    }

    @Test
    fun `每种身份的说明都说清能干什么`() {
        val root = ProbeIdentity.describe(PrivilegeIdentity.ROOT)
        val shell = ProbeIdentity.describe(PrivilegeIdentity.SHELL)
        val none = ProbeIdentity.describe(PrivilegeIdentity.NONE)

        assertTrue(root.contains("root"))
        assertTrue("shell 的说明要提到虚拟屏这条路的边界", shell.contains("虚拟屏"))
        assertTrue(none.contains("用不了"))
        assertEquals(3, setOf(root, shell, none).size)
    }
}
