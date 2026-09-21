package com.pocketagent.plugin.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PluginValidator] 单元测试。
 *
 * 这个测试类守两条线：
 *  1. **不能放进来不该进来的**（禁止能力、路径穿越、无域名限制的网络能力）
 *  2. **不能把正常插件拦在门外**（校验过严会把生态掐死在摇篮里）
 *
 * 第 2 条同样重要 —— 一个连 `1.0.0-beta` 都判定为非法的版本校验，
 * 会让一半社区插件装不上，然后用户会去装那个"不需要校验"的修改版。
 */
class PluginValidatorTest {

    private fun manifest(
        id: String = "community.example.demo",
        name: String = "示例插件",
        version: String = "1.0.0",
        apiVersion: Int = PluginManifest.CURRENT_API_VERSION,
        level: PluginLevel = PluginLevel.L1_RULES,
        capabilities: List<PluginCapability> = listOf(PluginCapability.SCREEN_READ),
        targetApps: List<String> = listOf("com.tencent.mm"),
        description: String? = "一个用于测试的插件",
        entry: String? = "rules.json",
        allowedHosts: List<String> = emptyList(),
        sha256: String? = "a".repeat(64),
        settingsSchema: List<SettingField> = emptyList(),
    ) = PluginManifest(
        id = id,
        name = name,
        version = version,
        apiVersion = apiVersion,
        level = level,
        capabilities = capabilities,
        targetApps = targetApps,
        description = description,
        entry = entry,
        allowedHosts = allowedHosts,
        sha256 = sha256,
        settingsSchema = settingsSchema,
    )

    private fun assertRejected(result: PluginValidationResult, fieldHint: String? = null) {
        assertTrue("期望被拒绝，实际通过", result is PluginValidationResult.Rejected)
        val errors = (result as PluginValidationResult.Rejected).errors
        if (fieldHint != null) {
            assertTrue(
                "错误里应包含字段 $fieldHint，实际：${errors.map { it.field }}",
                errors.any { it.field == fieldHint },
            )
        }
    }

    private fun assertAccepted(result: PluginValidationResult) {
        assertTrue(
            "期望通过，实际被拒：${(result as? PluginValidationResult.Rejected)?.errors?.map { it.message }}",
            result is PluginValidationResult.Accepted,
        )
    }

    // ═══════════════════════════════════════════════════════════
    //  一、合法插件必须能通过
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `合法的 L1 规则包通过校验`() {
        assertAccepted(PluginValidator.validate(manifest()))
    }

    @Test
    fun `合法的 L2 脚本通过校验`() {
        assertAccepted(
            PluginValidator.validate(
                manifest(level = PluginLevel.L2_SCRIPT, entry = "index.js")
            )
        )
    }

    @Test
    fun `带预发布标记的版本号合法`() {
        assertAccepted(PluginValidator.validate(manifest(version = "1.0.0-beta.1")))
        assertAccepted(PluginValidator.validate(manifest(version = "2.3.4-rc.1+build.7")))
    }

    @Test
    fun `没有描述只是警告不是错误`() {
        // 校验过严会把生态掐死。缺描述该提示，但不该拦安装
        val result = PluginValidator.validate(manifest(description = null))

        assertAccepted(result)
        assertTrue((result as PluginValidationResult.Accepted).warnings.any { it.field == "description" })
    }

    // ═══════════════════════════════════════════════════════════
    //  二、ID 与名称
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `ID 必须是反向域名形式`() {
        assertRejected(PluginValidator.validate(manifest(id = "demo")), "id")
        assertRejected(PluginValidator.validate(manifest(id = "community.demo")), "id")
    }

    @Test
    fun `ID 不能含大写字母`() {
        assertRejected(PluginValidator.validate(manifest(id = "Community.Example.Demo")), "id")
    }

    @Test
    fun `ID 段不能以数字或连字符开头`() {
        assertRejected(PluginValidator.validate(manifest(id = "community.1example.demo")), "id")
        assertRejected(PluginValidator.validate(manifest(id = "community.-example.demo")), "id")
    }

    @Test
    fun `ID 不能首尾带空白`() {
        // " community.x.y " 和 "community.x.y" 是两个不同的 id，
        // 留着它会让"同 id 去重"失效，进而可能绕过已安装检查
        assertRejected(PluginValidator.validate(manifest(id = " community.example.demo")), "id")
    }

    @Test
    fun `不能冒充官方命名空间`() {
        // ★ 安全用例：这是插件生态里最常见的欺骗手法
        for (id in listOf(
            "com.pocketagent.official.cleaner",
            "app.pocketagent.helper",
            "io.pocketagent.core",
        )) {
            assertRejected(PluginValidator.validate(manifest(id = id)), "id")
        }
    }

    @Test
    fun `名称不能为空或过长`() {
        assertRejected(PluginValidator.validate(manifest(name = "")), "name")
        assertRejected(PluginValidator.validate(manifest(name = "　")), "name")
        assertRejected(PluginValidator.validate(manifest(name = "长".repeat(61))), "name")
    }

    // ═══════════════════════════════════════════════════════════
    //  三、版本与 API 兼容性
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `非法版本号被拒`() {
        for (v in listOf("1.0", "v1.0.0", "1.0.0.0", "abc", "")) {
            assertRejected(PluginValidator.validate(manifest(version = v)), "version")
        }
    }

    @Test
    fun `要求更高 API 版本的插件被拒并给出升级提示`() {
        val result = PluginValidator.validate(
            manifest(apiVersion = PluginManifest.CURRENT_API_VERSION + 1)
        )

        assertRejected(result, "apiVersion")
        val msg = (result as PluginValidationResult.Rejected).errors.first { it.field == "apiVersion" }.message
        assertTrue("应提示用户升级本体：$msg", msg.contains("升级"))
    }

    @Test
    fun `apiVersion 为 0 或负数被拒`() {
        assertRejected(PluginValidator.validate(manifest(apiVersion = 0)), "apiVersion")
        assertRejected(PluginValidator.validate(manifest(apiVersion = -1)), "apiVersion")
    }

    // ═══════════════════════════════════════════════════════════
    //  四、级别
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `L3 原生插件当前一律拒绝`() {
        // L3 等于把任意代码加载进本进程，签名校验成熟前不开放
        val result = PluginValidator.validate(
            manifest(level = PluginLevel.L3_NATIVE, entry = "plugin.apk")
        )

        assertRejected(result, "level")
    }

    // ═══════════════════════════════════════════════════════════
    //  五、入口路径 —— 路径穿越
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `入口路径不能包含双点`() {
        // ★ 安全用例：../../ 是插件读取其他插件甚至本体数据的最常见手法
        for (entry in listOf(
            "../secret.json",
            "rules/../../secret.json",
            "a/../../../../data/data/com.pocketagent/databases/db",
        )) {
            assertRejected(PluginValidator.validate(manifest(entry = entry)), "entry")
        }
    }

    @Test
    fun `入口路径不能是绝对路径`() {
        assertRejected(PluginValidator.validate(manifest(entry = "/sdcard/rules.json")), "entry")
        assertRejected(PluginValidator.validate(manifest(entry = "\\Windows\\rules.json")), "entry")
    }

    @Test
    fun `入口路径不能含反斜杠`() {
        assertRejected(PluginValidator.validate(manifest(entry = "sub\\rules.json")), "entry")
    }

    @Test
    fun `入口扩展名必须与级别匹配`() {
        assertRejected(PluginValidator.validate(manifest(entry = "rules.js")), "entry")
        assertRejected(
            PluginValidator.validate(manifest(level = PluginLevel.L2_SCRIPT, entry = "index.json")),
            "entry",
        )
    }

    @Test
    fun `缺少入口文件被拒`() {
        assertRejected(PluginValidator.validate(manifest(entry = null)), "entry")
        assertRejected(PluginValidator.validate(manifest(entry = "")), "entry")
    }

    @Test
    fun `子目录下的入口是合法的`() {
        assertAccepted(PluginValidator.validate(manifest(entry = "rules/main.json")))
    }

    // ═══════════════════════════════════════════════════════════
    //  六、网络能力
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `申请网络能力却没有域名白名单，直接拒绝`() {
        // ★ 安全用例：没有白名单等于可以把屏幕内容发到任意服务器
        val result = PluginValidator.validate(
            manifest(capabilities = listOf(PluginCapability.NETWORK_REQUEST))
        )

        assertRejected(result, "allowedHosts")
    }

    @Test
    fun `通配所有域名被拒绝`() {
        val result = PluginValidator.validate(
            manifest(
                capabilities = listOf(PluginCapability.NETWORK_REQUEST),
                allowedHosts = listOf("*"),
            )
        )

        assertRejected(result, "allowedHosts")
    }

    @Test
    fun `域名白名单不能带协议或路径`() {
        assertRejected(
            PluginValidator.validate(
                manifest(
                    capabilities = listOf(PluginCapability.NETWORK_REQUEST),
                    allowedHosts = listOf("https://api.example.com"),
                )
            ),
            "allowedHosts",
        )
        assertRejected(
            PluginValidator.validate(
                manifest(
                    capabilities = listOf(PluginCapability.NETWORK_REQUEST),
                    allowedHosts = listOf("api.example.com/v1"),
                )
            ),
            "allowedHosts",
        )
    }

    @Test
    fun `合法的域名白名单通过`() {
        assertAccepted(
            PluginValidator.validate(
                manifest(
                    capabilities = listOf(PluginCapability.SCREEN_READ, PluginCapability.NETWORK_REQUEST),
                    allowedHosts = listOf("api.example.com", "*.example.org", "127.0.0.1"),
                )
            )
        )
    }

    @Test
    fun `没申请网络能力却填了白名单，只是警告`() {
        val result = PluginValidator.validate(manifest(allowedHosts = listOf("api.example.com")))

        assertAccepted(result)
        assertTrue((result as PluginValidationResult.Accepted).warnings.any { it.field == "allowedHosts" })
    }

    // ═══════════════════════════════════════════════════════════
    //  七、能力
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `能力列表有重复项被拒`() {
        assertRejected(
            PluginValidator.validate(
                manifest(capabilities = listOf(PluginCapability.SCREEN_READ, PluginCapability.SCREEN_READ))
            ),
            "capabilities",
        )
    }

    @Test
    fun `能力过多被拒`() {
        val all = PluginCapability.entries.toList()
        assertTrue(all.size > 12)
        assertRejected(PluginValidator.validate(manifest(capabilities = all)), "capabilities")
    }

    @Test
    fun `L1 声明用不到的能力时给出警告`() {
        // 过度申请权限是警告信号：L1 规则包结构上无法使用截图
        val result = PluginValidator.validate(
            manifest(capabilities = listOf(PluginCapability.SCREEN_READ, PluginCapability.SCREEN_CAPTURE))
        )

        assertAccepted(result)
        val warning = (result as PluginValidationResult.Accepted).warnings
            .firstOrNull { it.field == "capabilities" }
        assertTrue("应有过度申请警告", warning != null)
        assertTrue("应指出具体是哪个能力：${warning!!.message}", warning.message.contains("screen.capture"))
    }

    @Test
    fun `L1 声明它用得上的能力不产生警告`() {
        val result = PluginValidator.validate(
            manifest(
                capabilities = listOf(
                    PluginCapability.SCREEN_READ,
                    PluginCapability.ACTION_CLICK,
                    PluginCapability.APP_LAUNCH,
                    PluginCapability.LLM_CALL,
                )
            )
        )

        assertAccepted(result)
        assertFalse(
            (result as PluginValidationResult.Accepted).warnings.any { it.field == "capabilities" },
        )
    }

    // ═══════════════════════════════════════════════════════════
    //  八、目标应用
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `非法包名被拒`() {
        assertRejected(PluginValidator.validate(manifest(targetApps = listOf("微信"))), "targetApps")
        assertRejected(PluginValidator.validate(manifest(targetApps = listOf("com"))), "targetApps")
    }

    @Test
    fun `能模拟点击但不限定目标应用时给出警告`() {
        val result = PluginValidator.validate(
            manifest(capabilities = listOf(PluginCapability.ACTION_CLICK), targetApps = emptyList())
        )

        assertAccepted(result)
        val warning = (result as PluginValidationResult.Accepted).warnings
            .firstOrNull { it.field == "targetApps" }
        assertTrue("应警告未限定目标应用", warning != null)
        assertTrue("应说清后果：${warning!!.message}", warning.message.contains("任何应用"))
    }

    @Test
    fun `只读屏幕且不限定目标应用不产生警告`() {
        // 只读不操作，风险等级不同，不该同样告警
        val result = PluginValidator.validate(
            manifest(capabilities = listOf(PluginCapability.SCREEN_READ), targetApps = emptyList())
        )

        assertAccepted(result)
        assertFalse((result as PluginValidationResult.Accepted).warnings.any { it.field == "targetApps" })
    }

    // ═══════════════════════════════════════════════════════════
    //  九、完整性
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `sha256 格式错误被拒`() {
        assertRejected(PluginValidator.validate(manifest(sha256 = "abc")), "sha256")
        assertRejected(PluginValidator.validate(manifest(sha256 = "z".repeat(64))), "sha256")
    }

    @Test
    fun `缺少 sha256 只是警告`() {
        // 本地导入的插件本来就没有哈希，这里拦掉等于禁止本地导入
        val result = PluginValidator.validate(manifest(sha256 = null))

        assertAccepted(result)
        assertTrue((result as PluginValidationResult.Accepted).warnings.any { it.field == "sha256" })
    }

    // ═══════════════════════════════════════════════════════════
    //  十、配置界面
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `配置项 key 重复被拒`() {
        assertRejected(
            PluginValidator.validate(
                manifest(
                    settingsSchema = listOf(
                        SettingField.Text("mode", "模式"),
                        SettingField.Switch("mode", "开关"),
                    )
                )
            ),
            "settingsSchema",
        )
    }

    @Test
    fun `下拉配置项没有可选项被拒`() {
        assertRejected(
            PluginValidator.validate(
                manifest(settingsSchema = listOf(SettingField.Select("level", "级别", options = emptyList())))
            ),
            "settingsSchema",
        )
    }

    @Test
    fun `下拉配置项默认下标越界被拒`() {
        assertRejected(
            PluginValidator.validate(
                manifest(
                    settingsSchema = listOf(
                        SettingField.Select(
                            key = "level",
                            label = "级别",
                            options = listOf(SettingField.Select.Option("a", "A")),
                            defaultIndex = 5,
                        )
                    )
                )
            ),
            "settingsSchema",
        )
    }

    @Test
    fun `配置项缺标签只是警告`() {
        val result = PluginValidator.validate(
            manifest(settingsSchema = listOf(SettingField.Text("mode", "")))
        )

        assertAccepted(result)
        assertTrue((result as PluginValidationResult.Accepted).warnings.any { it.field == "settingsSchema" })
    }

    // ═══════════════════════════════════════════════════════════
    //  十一、原始能力字符串检查（反序列化之前）
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `申请禁止前缀的能力被识别为错误`() {
        // ★ 核心安全用例。
        // 这一步必须在严格反序列化之前跑 —— 否则 payment.pay 只会变成
        // "枚举解析失败"，看起来像格式问题，而它其实是一次明确的越权尝试。
        for (id in listOf("payment.pay", "key.read", "crypto.sign", "system.shell")) {
            val issues = PluginValidator.inspectRawCapabilities(listOf(id))
            assertTrue("「$id」应被判为错误", issues.any { it.severity == IssueSeverity.ERROR })
            assertTrue(
                "应指明是禁止前缀：${issues.first().message}",
                issues.first().message.contains("永不开放"),
            )
        }
    }

    /**
     * 回归用例：未知能力是 **WARNING**，不是 ERROR。
     *
     * 曾经判成 ERROR，后果是市场把整个条目丢掉（见 PluginMarketTest 里的同名回归）。
     * 判据是「这个字段能不能被恶意利用」—— 我们**没有实现**的能力利用不了，
     * 所以这是兼容性问题，不是安全问题。
     */
    @Test
    fun `未知能力被识别为警告而非错误`() {
        val issues = PluginValidator.inspectRawCapabilities(listOf("screen.telepathy"))

        assertEquals("只应产生一条 issue", 1, issues.size)
        assertEquals(IssueSeverity.WARNING, issues.single().severity)
    }

    @Test
    fun `合法能力不产生任何问题`() {
        assertTrue(
            PluginValidator.inspectRawCapabilities(listOf("screen.read", "action.click", "app.launch")).isEmpty()
        )
    }

    @Test
    fun `能力字符串大小写与空白被归一化`() {
        assertTrue(PluginValidator.inspectRawCapabilities(listOf("  SCREEN.READ  ")).isEmpty())
    }

    @Test
    fun `禁止前缀检查不受大小写影响`() {
        assertTrue(
            PluginValidator.inspectRawCapabilities(listOf("PAYMENT.PAY"))
                .any { it.severity == IssueSeverity.ERROR },
        )
    }

    @Test
    fun `空能力列表不产生问题`() {
        assertTrue(PluginValidator.inspectRawCapabilities(emptyList()).isEmpty())
    }

    // ═══════════════════════════════════════════════════════════
    //  十二、风险汇总
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `风险汇总取最高值`() {
        assertEquals(
            PluginCapability.RiskLevel.CRITICAL,
            PluginValidator.summarizeRisk(
                listOf(PluginCapability.APP_LAUNCH, PluginCapability.SCREEN_CAPTURE)
            ),
        )
    }

    @Test
    fun `空能力列表的风险为 null`() {
        assertNull(PluginValidator.summarizeRisk(emptyList()))
    }

    // ═══════════════════════════════════════════════════════════
    //  十三、本地导入风险提示
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `高危插件要求手动输入确认词`() {
        val notice = ImportRiskNotice.forLocalImport(
            manifest(capabilities = listOf(PluginCapability.SCREEN_CAPTURE))
        )

        assertTrue("极高风险必须要求输入确认词", notice.requireTypedConfirmation)
        assertEquals(PluginCapability.RiskLevel.CRITICAL, notice.highestRisk)
    }

    @Test
    fun `低危插件不要求输入确认词`() {
        val notice = ImportRiskNotice.forLocalImport(
            manifest(capabilities = listOf(PluginCapability.APP_LAUNCH, PluginCapability.NOTIFICATION_POST))
        )

        assertFalse(notice.requireTypedConfirmation)
    }

    @Test
    fun `提示里逐条列出插件声明的能力`() {
        val notice = ImportRiskNotice.forLocalImport(
            manifest(
                capabilities = listOf(
                    PluginCapability.SCREEN_READ,
                    PluginCapability.ACTION_CLICK,
                    PluginCapability.NETWORK_REQUEST,
                ),
                allowedHosts = listOf("api.example.com"),
            )
        )

        val text = notice.bullets.joinToString("\n")
        assertTrue("应列出读取屏幕", text.contains("读取当前屏幕"))
        assertTrue("应列出模拟点击", text.contains("点击屏幕上的按钮"))
        assertTrue("应列出网络能力", text.contains("向指定网站发送请求"))
    }

    @Test
    fun `有网络能力时明确写出目标域名`() {
        // "它能联网"和"它能把你的屏幕发到 xxx.com"是两种信息量
        val notice = ImportRiskNotice.forLocalImport(
            manifest(
                capabilities = listOf(PluginCapability.NETWORK_REQUEST),
                allowedHosts = listOf("evil.example.com"),
            )
        )

        assertTrue(
            "必须写出具体域名：${notice.bullets}",
            notice.bullets.any { it.contains("evil.example.com") },
        )
    }

    @Test
    fun `能截图又联网时给出组合风险提示`() {
        val notice = ImportRiskNotice.forLocalImport(
            manifest(
                capabilities = listOf(PluginCapability.SCREEN_CAPTURE, PluginCapability.NETWORK_REQUEST),
                allowedHosts = listOf("api.example.com"),
            )
        )

        assertTrue(
            "应指出「截图 + 网络 = 可以把屏幕内容传出去」：${notice.bullets}",
            notice.bullets.any { it.contains("传出去") },
        )
    }

    @Test
    fun `会调用模型时提示会产生费用`() {
        val notice = ImportRiskNotice.forLocalImport(
            manifest(capabilities = listOf(PluginCapability.LLM_CALL))
        )

        assertTrue(notice.bullets.any { it.contains("费用") })
    }

    @Test
    fun `提示里必须说明本应用无法替用户追责`() {
        // 用户明确要求"风险由用户自行承担"，这句话必须出现在提示里
        val notice = ImportRiskNotice.forLocalImport(manifest())

        assertTrue(
            "缺少免责说明：${notice.bullets}",
            notice.bullets.any { it.contains("风险由你自行承担") },
        )
    }

    @Test
    fun `提示里必须说明安全页面拦截依然有效`() {
        // 不能让用户以为装了插件就完全失控 —— 护栏仍然在
        val notice = ImportRiskNotice.forLocalImport(manifest())

        assertTrue(notice.bullets.any { it.contains("支付") && it.contains("绕不过") })
    }

    @Test
    fun `没有任何能力的插件也给出提示`() {
        val notice = ImportRiskNotice.forLocalImport(manifest(capabilities = emptyList()))

        assertNull(notice.highestRisk)
        assertFalse(notice.requireTypedConfirmation)
        assertTrue(notice.bullets.any { it.contains("没有声明任何能力") })
    }

    @Test
    fun `清单为 null 时也能生成提示而不崩`() {
        // 解析失败的插件也要能走完提示流程，否则用户看到的是崩溃而不是原因
        val notice = ImportRiskNotice.forLocalImport(null)

        assertTrue(notice.summary.contains("未知插件"))
        assertTrue(notice.bullets.isNotEmpty())
    }

    @Test
    fun `能力按风险从高到低排列`() {
        val notice = ImportRiskNotice.forLocalImport(
            manifest(
                capabilities = listOf(
                    PluginCapability.APP_LAUNCH,       // LOW
                    PluginCapability.SCREEN_CAPTURE,   // CRITICAL
                    PluginCapability.ACTION_GESTURE,   // MEDIUM
                )
            )
        )

        val risks = notice.declaredCapabilities.map { it.risk.ordinal }
        assertEquals(risks.sortedDescending(), risks)
    }

    @Test
    fun `风险等级有中文标签`() {
        assertEquals("极高", PluginCapability.RiskLevel.CRITICAL.label)
        assertEquals("低", PluginCapability.RiskLevel.LOW.label)
    }
}
