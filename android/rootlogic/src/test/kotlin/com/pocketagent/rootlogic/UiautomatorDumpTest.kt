package com.pocketagent.rootlogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `uiautomator dump` 解析器的测试。
 *
 * ★ 样本刻意用**真机 shape**：`uiautomator dump` 输出的属性名是带连字符的
 *   （`content-desc` / `resource-id` / `long-clickable`），不是驼峰 ——
 *   照驼峰写测试会让解析器在真机上一片空白地"成功"。
 */
class UiautomatorDumpTest {

    private val xml = """
        <?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
        <hierarchy rotation="0">
          <node index="0" text="" resource-id="" class="android.widget.FrameLayout"
                package="com.android.settings" content-desc="" checkable="false" checked="false"
                clickable="false" enabled="true" focusable="false" focused="false"
                scrollable="false" long-clickable="false" password="false" selected="false"
                bounds="[0,0][1080,2400]">
            <node index="0" text="无线和网络" resource-id="android:id/title"
                  class="android.widget.TextView" package="com.android.settings" content-desc=""
                  checkable="false" checked="false" clickable="true" enabled="true"
                  focusable="true" focused="false" scrollable="false" long-clickable="false"
                  password="false" selected="false" bounds="[0,200][1080,320]" />
            <node index="1" text="" resource-id="android:id/switch_widget"
                  class="android.widget.Switch" package="com.android.settings" content-desc="WLAN"
                  checkable="true" checked="true" clickable="true" enabled="true"
                  focusable="true" focused="false" scrollable="false" long-clickable="false"
                  password="false" selected="false" bounds="[900,200][1060,320]" />
            <node index="2" text="" resource-id="" class="android.widget.EditText"
                  package="com.android.settings" content-desc="搜索" checkable="false"
                  checked="false" clickable="true" enabled="false" focusable="true"
                  focused="true" scrollable="false" long-clickable="false" password="true"
                  selected="false" bounds="[0,360][1080,440]" />
          </node>
        </hierarchy>
    """.trimIndent()

    @Test
    fun `解析真机形状的 dump`() {
        val tree = UiautomatorDump.parse(xml).getOrThrow()

        assertEquals("com.android.settings", tree.packageName)
        assertEquals(1080, tree.width)
        assertEquals(2400, tree.height)
        assertEquals(4, tree.nodes.size)
    }

    @Test
    fun `带连字符的属性名被正确读到`() {
        val tree = UiautomatorDump.parse(xml).getOrThrow()
        val switch = tree.nodes.first { it.className == "android.widget.Switch" }

        assertEquals("android:id/switch_widget", switch.resourceId)
        assertEquals("WLAN", switch.contentDescription)
        assertTrue(switch.checkable)
        assertTrue(switch.checked)
        assertTrue(switch.clickable)
        assertFalse("long-clickable 缺失时按 false", switch.longClickable)
    }

    @Test
    fun `空文本读成 null 而不是空串`() {
        val tree = UiautomatorDump.parse(xml).getOrThrow()
        val switch = tree.nodes.first { it.className == "android.widget.Switch" }

        // ★ 空串会让上层写出「文本是 ''」这种噪声，null 才是"没有文本"。
        assertEquals(null, switch.text)
        assertTrue("content-desc 也是标签来源", switch.hasLabel)
    }

    @Test
    fun `enable 缺失时按 true 而不是 false`() {
        val noEnabled = """
            <hierarchy rotation="0">
              <node index="0" text="确定" class="android.widget.Button" package="com.x"
                    content-desc="" bounds="[10,10][110,110]" clickable="true" />
            </hierarchy>
        """.trimIndent()

        val node = UiautomatorDump.parse(noEnabled).getOrThrow().nodes.single()

        // ★ 缺失按 false 会把整棵可用的树判成"全部禁用"，比不知道更糟。
        assertTrue(node.enabled)
    }

    @Test
    fun `nodeId 是索引路径且逐层递增`() {
        val tree = UiautomatorDump.parse(xml).getOrThrow()

        assertEquals(listOf("0", "0/0", "0/1", "0/2"), tree.nodes.map { it.nodeId })
        assertEquals(listOf(0, 1, 1, 1), tree.nodes.map { it.depth })
    }

    @Test
    fun `password 标记被保留`() {
        val tree = UiautomatorDump.parse(xml).getOrThrow()
        val edit = tree.nodes.first { it.className == "android.widget.EditText" }

        // ★ 这是 :safety 判"这是不是密码框"的第一优先信号，丢了就只能靠猜。
        assertTrue(edit.password)
        assertTrue(edit.focused)
        assertFalse(edit.enabled)
    }

    @Test
    fun `tappable 只给有面积的节点`() {
        val zero = """
            <hierarchy rotation="0">
              <node index="0" text="" class="android.view.View" package="com.x"
                    bounds="[0,0][0,0]" clickable="true" />
              <node index="1" text="好" class="android.view.View" package="com.x"
                    bounds="[0,0][100,100]" clickable="true" />
            </hierarchy>
        """.trimIndent()

        val tree = UiautomatorDump.parse(zero).getOrThrow()

        assertEquals(2, tree.nodes.size)
        // 零面积节点保留在 nodes 里（它是布局的一部分），但不进 tappable
        assertEquals(listOf("1"), tree.tappable.map { it.nodeId })
    }

    @Test
    fun `没有 hierarchy 时报 NotADump`() {
        val result = UiautomatorDump.parse("su: permission denied")

        val failure = (result.exceptionOrNull() as UiautomatorDump.DumpException).reason
        assertTrue(failure is UiautomatorDump.Failure.NotADump)
        // 预览要能看出是什么东西 —— 排查时这句就是全部线索
        assertTrue((failure as UiautomatorDump.Failure.NotADump).preview.contains("permission denied"))
    }

    @Test
    fun `空输出报 NotADump 而不是 Empty`() {
        val failure = (UiautomatorDump.parse("   ").exceptionOrNull()
            as UiautomatorDump.DumpException).reason

        // ★ 空输出 ≠ 没有界面：多半是命令没跑起来。这两者给用户的指引完全不同。
        assertTrue(failure is UiautomatorDump.Failure.NotADump)
    }

    @Test
    fun `XML 坏了报 Malformed`() {
        val broken = "<hierarchy rotation=\"0\"><node text=\"x\" bounds=\"[0,0][1,1]\"></hierarchy>"

        val failure = (UiautomatorDump.parse(broken).exceptionOrNull()
            as UiautomatorDump.DumpException).reason

        assertTrue(failure is UiautomatorDump.Failure.Malformed)
    }

    @Test
    fun `有 hierarchy 但零节点报 Empty`() {
        val failure = (UiautomatorDump.parse("<hierarchy rotation=\"0\" />").exceptionOrNull()
            as UiautomatorDump.DumpException).reason

        assertEquals(UiautomatorDump.Failure.Empty, failure)
    }

    @Test
    fun `bounds 解析容错`() {
        val rect = UiautomatorDump.parseBounds("[100,200][300,400]")
        assertEquals(100, rect.left)
        assertEquals(200, rect.top)
        assertEquals(300, rect.right)
        assertEquals(400, rect.bottom)
        assertEquals(200, rect.width)
        assertEquals(200, rect.height)
        assertEquals(200, rect.centerX)
        assertEquals(300, rect.centerY)
    }

    @Test
    fun `反序的 bounds 也被纠正`() {
        // dump 偶发给出 [x2,y2][x1,y1]（右到左的布局），负宽度会让点击坐标算错
        val rect = UiautomatorDump.parseBounds("[300,400][100,200]")

        assertEquals(100, rect.left)
        assertEquals(200, rect.top)
        assertEquals(200, rect.width)
        assertEquals(200, rect.height)
    }

    @Test
    fun `bounds 坏掉时退化成零矩形而不是抛异常`() {
        val rect = UiautomatorDump.parseBounds("oops")

        assertTrue(rect.isEmpty)
        assertEquals(0, rect.width)
    }

    @Test
    fun `labeled 只给有字或有无障碍描述的节点`() {
        val tree = UiautomatorDump.parse(xml).getOrThrow()

        // 「无线和网络」有 text、「Switch」有 content-desc；FrameLayout 两者都空
        assertEquals(listOf("0/0", "0/1", "0/2"), tree.labeled.map { it.nodeId })
    }
}
