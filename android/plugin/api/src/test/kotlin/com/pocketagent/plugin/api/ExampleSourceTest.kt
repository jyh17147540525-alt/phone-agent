package com.pocketagent.plugin.api

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 校验 `community-source/plugins-src/` 下的示例插件**真的能装**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  为什么这个测试必须存在
 * ═══════════════════════════════════════════════════════════════
 *
 * 示例插件是给所有人抄的模板。如果它只是"看起来合法" —— 字段名对、
 * 缩进整齐、读起来像那么回事 —— 那它就是个假货：用户照着抄，
 * 抄出来的插件在市场里点安装会报错，而报错信息不会告诉他
 * "你抄的那个例子本身是坏的"。
 *
 * 所以这里**不重新实现一套校验规则**，而是直接调用客户端的
 * [PluginValidator] 与真实的反序列化器。规则只有一份，
 * 校验器改了，这个测试立刻跟着变。
 *
 * ⚠️ 这条原则对 `build_source.py` 同样成立：那个脚本刻意**不做**完整校验，
 *    只检查打包所需的前提。在 Python 里再实现一遍能力白名单，
 *    等于同一套规则维护两份 —— 漂移之后最糟的情况是脚本放行了
 *    校验器会拒绝的插件，用户拿到的是"打包成功、安装失败"。
 *
 * 本测试在**没有 Android SDK** 的机器上也能跑：plugin/api 零 Android 依赖，
 * 测试只读文件、只跑纯函数。
 */
class ExampleSourceTest {

    /**
     * 与 PluginImporter 的 STRICT 配置保持一致。
     *
     * 用宽松配置测出来的"能解析"没有意义 —— 客户端装插件时用的是严格配置，
     * 测试比生产更宽容就等于没测。
     */
    private val strictJson = Json {
        ignoreUnknownKeys = true
        isLenient = false
        explicitNulls = false
    }

    private val sourceRoot: File by lazy {
        val found = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .take(12)
            .map { File(it, "community-source/plugins-src") }
            .firstOrNull { it.isDirectory }

        requireNotNull(found) {
            "找不到 community-source/plugins-src。" +
                "这个测试依赖仓库布局，工作目录是 ${System.getProperty("user.dir")}。" +
                "如果插件契约模块被单独拷出去用，请把这个测试一起排除掉。"
        }
        found
    }

    /** 以 `_` 开头的目录是模板/草稿，不参与打包，也不该被校验 */
    private fun publishedPlugins(): List<File> =
        sourceRoot.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith("_") }
            ?.sortedBy { it.name }
            .orEmpty()

    private fun readManifest(dir: File): PluginManifest =
        strictJson.decodeFromString(
            PluginManifest.serializer(),
            File(dir, "plugin.json").readText(Charsets.UTF_8),
        )

    // ═══════════════════════════════════════════════════════════
    //  测试
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `至少有一个示例插件`() {
        // 防止 sourceRoot 找错地方之后，下面的测试全部"因为集合为空"而通过。
        // 一个空集合上的 forEach 断言永远成立 —— 那是测试最隐蔽的失效方式。
        assertTrue("plugins-src 下没有找到任何已发布的插件", publishedPlugins().isNotEmpty())
    }

    @Test
    fun `每个示例插件的清单都能通过校验`() {
        for (dir in publishedPlugins()) {
            val manifest = readManifest(dir)

            val result = PluginValidator.validate(manifest)
            val accepted = result as? PluginValidationResult.Accepted
                ?: run {
                    val errors = (result as PluginValidationResult.Rejected)
                        .errors.joinToString("\n    ") { "[${it.field}] ${it.message}" }
                    throw AssertionError("${dir.name} 的清单校验不通过：\n    $errors")
                }

            // 警告不拦安装，但示例插件**不该有**警告 —— 它是给人抄的。
            //
            // 唯一豁免的是 sha256 缺失，而且这条豁免有硬理由：
            // 对 L1 插件来说这个字段**结构上填不了**。rules.json 内嵌了整份
            // manifest，而 manifest 的 sha256 又要去哈希 rules.json —— 循环依赖。
            // 真正起作用的哈希在源索引里（覆盖整个 .pagent，解压前校验），
            // 那个由 build_source.py 生成。
            val unexpected = accepted.warnings.filter { it.field != "sha256" }
            assertTrue(
                "${dir.name} 的清单产生了意料之外的警告：\n    " +
                    unexpected.joinToString("\n    ") { "[${it.field}] ${it.message}" },
                unexpected.isEmpty(),
            )
        }
    }

    @Test
    fun `入口文件真的在包里`() {
        for (dir in publishedPlugins()) {
            val manifest = readManifest(dir)
            val entry = manifest.entry

            assertTrue("${dir.name} 没有声明 entry", !entry.isNullOrBlank())
            assertTrue(
                "${dir.name} 的 entry「$entry」在包里不存在 —— 装上去会是一个加载不了的插件",
                File(dir, entry!!).isFile,
            )

            // 顺便确认包内路径也过得了 zip slip 检查。
            // 一个 entry 合法但文件名不合法的组合，会在解压阶段才炸。
            assertTrue(
                "${dir.name} 的 entry「$entry」不是安全的包内路径",
                PluginBundle.isSafeEntryName(entry),
            )
        }
    }

    @Test
    fun `规则文件能解析且内嵌的清单与 plugin_json 完全一致`() {
        for (dir in publishedPlugins()) {
            val manifest = readManifest(dir)
            val rulesFile = File(dir, manifest.entry!!)

            val pack = try {
                strictJson.decodeFromString(
                    RulePack.serializer(),
                    rulesFile.readText(Charsets.UTF_8),
                )
            } catch (e: Exception) {
                throw AssertionError(
                    "${dir.name} 的 ${rulesFile.name} 无法按 RulePack 解析：${e.message}", e,
                )
            }

            // ⚠️ 这是本测试里最要紧的一条断言。
            //
            // 两份清单一旦漂移，市场页显示的是 plugin.json 里的版本，
            // 而实际运行的是 rules.json 里的规则 —— 没有任何一处会报错。
            // 用户看到"已更新到 1.0.1"，跑的还是 1.0.0 的行为。
            assertEquals(
                "${dir.name}：plugin.json 与 rules.json 里的 manifest 不一致。" +
                    "这两份必须逐字段相同 —— 改了一份就必须改另一份。",
                manifest,
                pack.manifest,
            )

            assertTrue("${dir.name} 的规则列表为空，这个插件什么都不会做", pack.rules.isNotEmpty())
        }
    }

    @Test
    fun `能点击的插件每条规则都排除了支付类页面`() {
        // 这是本项目的一条安全约定，不是规范建议：
        // **只要插件能模拟点击，就必须显式排除支付、转账这类页面。**
        //
        // 一个误点「确认付款」的插件，比一个根本不能用的插件糟糕一万倍 ——
        // 前者用户会损失钱，后者用户只是卸载掉。
        //
        // 把约定写成测试的理由很直接：约定只写在文档里，就等于没有约定。
        for (dir in publishedPlugins()) {
            val manifest = readManifest(dir)
            val canClick = manifest.capabilities.any {
                it == PluginCapability.ACTION_CLICK ||
                    it == PluginCapability.ACTION_INPUT ||
                    it == PluginCapability.ACTION_GESTURE
            }
            if (!canClick) continue

            val pack = strictJson.decodeFromString(
                RulePack.serializer(),
                File(dir, manifest.entry!!).readText(Charsets.UTF_8),
            )

            for (rule in pack.rules) {
                assertTrue(
                    "${dir.name} 的规则「${rule.id}」可以模拟操作，但 excludeConditions 是空的。" +
                        "请至少排除「支付」「转账」这类文本 —— 否则这条规则可能在付款页面上触发。",
                    rule.match.excludeConditions.isNotEmpty(),
                )
            }
        }
    }

    @Test
    fun `不限定目标应用的插件必须靠规则里的包名兜底`() {
        // targetApps 为空 = 声明"对所有应用生效"，客户端会给用户额外警告。
        // 这种情况下如果规则里连 packageName 都不写，那就是真的哪里都没限制。
        //
        // 两层限制至少要有一层。示例插件是给人抄的，不能示范一个"哪都不限"的写法。
        for (dir in publishedPlugins()) {
            val manifest = readManifest(dir)
            if (manifest.targetApps.isNotEmpty()) continue

            val pack = strictJson.decodeFromString(
                RulePack.serializer(),
                File(dir, manifest.entry!!).readText(Charsets.UTF_8),
            )
            for (rule in pack.rules) {
                assertTrue(
                    "${dir.name} 既没有限定 targetApps，规则「${rule.id}」也没有写 packageName —— " +
                        "这个插件会对手机上的任何应用生效。",
                    !rule.match.packageName.isNullOrBlank(),
                )
            }
        }
    }

    @Test
    fun `示例插件的 id 不占用官方保留前缀`() {
        // 客户端会拒绝 community 之外的人使用 com.pocketagent.* 这类前缀。
        // 示例插件更不该占 —— 它是模板，抄的人会连 id 一起抄走。
        for (dir in publishedPlugins()) {
            val id = readManifest(dir).id
            val reserved = listOf("com.pocketagent.", "app.pocketagent.", "io.pocketagent.", "org.pocketagent.")
            assertTrue(
                "${dir.name} 的 id「$id」占用了官方保留前缀",
                reserved.none { id.startsWith(it) },
            )
        }
    }
}
