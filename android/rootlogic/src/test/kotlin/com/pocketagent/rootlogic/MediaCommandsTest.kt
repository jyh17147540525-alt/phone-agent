package com.pocketagent.rootlogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 媒体动作的参数规则。
 *
 * 钉的是"什么时候该拒绝"：路径越界、格式不认识、输出会覆盖输入、动作名写错。
 * 这几条错了的后果不是报错，而是**写到别处**或者**把用户的原始文件覆盖掉**。
 */
class MediaCommandsTest {

    @Test
    fun `probe 只需要输入`() {
        assertEquals(
            MediaCommand.Probe("/work/a.mp4"),
            MediaCommands.plan("probe", "/work/a.mp4", null, null),
        )
    }

    @Test
    fun `抽音轨不写输出时按动作生成同名 m4a`() {
        val command = MediaCommands.plan("extract_audio", "/work/a.mp4", null, null) as MediaCommand.ExtractAudio
        assertEquals("/work/a.m4a", command.output)
    }

    @Test
    fun `截帧默认按毫秒零点，且被夹在合法区间`() {
        val low = MediaCommands.plan("snapshot", "/work/a.mp4", "/work/a.png", -5) as MediaCommand.Snapshot
        assertEquals(0L, low.atMs)

        val high = MediaCommands.plan("snapshot", "/work/a.mp4", "/work/a.png", Long.MAX_VALUE) as MediaCommand.Snapshot
        assertEquals(MediaCommands.MAX_AT_MS, high.atMs)
    }

    @Test
    fun `输出扩展名必须与动作匹配`() {
        assertThrows(MediaRejected::class.java) {
            MediaCommands.plan("extract_audio", "/work/a.mp4", "/work/a.mp4", null)
        }
        assertThrows(MediaRejected::class.java) {
            MediaCommands.plan("snapshot", "/work/a.mp4", "/work/a.mp4", 0)
        }
    }

    @Test
    fun `输出不能覆盖输入`() {
        val error = assertThrows(MediaRejected::class.java) {
            MediaCommands.plan("transcode", "/work/a.mp4", "/work/a.mp4", null)
        }
        assertTrue(error.message!!.contains("不能覆盖"))
    }

    @Test
    fun `工作区之外的路径被拒`() {
        assertThrows(MediaRejected::class.java) {
            MediaCommands.plan("probe", "/sdcard/a.mp4", null, null)
        }
        assertThrows(MediaRejected::class.java) {
            MediaCommands.plan("probe", "/work/../../etc/a.mp4", null, null)
        }
    }

    @Test
    fun `不认识的输入格式说清认哪些`() {
        val error = assertThrows(MediaRejected::class.java) {
            MediaCommands.plan("probe", "/work/a.txt", null, null)
        }
        assertTrue(error.message!!.contains("mp4"))
    }

    @Test
    fun `不认识的动作被拒`() {
        assertThrows(MediaRejected::class.java) {
            MediaCommands.plan("burn_subtitles", "/work/a.mp4", null, null)
        }
    }

    @Test
    fun `自动生成的名字是确定的`() {
        assertEquals("/work/a.m4a", MediaCommands.outputFor("/work/a.mp4", "extract_audio"))
        assertEquals("/work/a.png", MediaCommands.outputFor("/work/a.mp4", "snapshot"))
        assertEquals("/work/a.mp4", MediaCommands.outputFor("/work/a.mkv", "transcode"))
    }
}
