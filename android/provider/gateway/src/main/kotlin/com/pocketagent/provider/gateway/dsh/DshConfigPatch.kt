package com.pocketagent.provider.gateway.dsh

/**
 * 把本地网关的接入事实渲染成 **dsh 能读的 YAML 片段**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  为什么这是一个"渲染器"而不是"写文件的"
 * ═══════════════════════════════════════════════════════════════
 *
 * 直觉做法是写一个 `writeDshConfig(home: File, …)` —— 直接落盘。
 * 但真机实测（2026-09-22，K60）证明这条路**走不通**：
 *
 * ```
 * adb shell ls -d /sdcard/Android/data/com.termux         → No such file
 * adb shell ls -d /data/data/com.termux/files/home        → 属于 u0_a489，跨 UID 不可写
 * ```
 *
 * dsh 跑在 Termux 的 **proot Debian 容器**里，配置在
 * `/data/data/com.termux/files/usr/var/lib/proot-distro/containers/debian/rootfs/root/.dsh/`，
 * 属于另一 UID。**Kotlin 进程写不进去。**
 *
 * 于是"生成内容"与"投递内容"必须分开：
 * - **本类**：`baseUrl` + `token` → YAML 文本。**纯字符串变换，零 Android 依赖。**
 * - `DshConfigSink`（在 `:keymgmt`）：把文本送到 dsh 那边，要 Android 权限。
 *
 * ⚠️ 把两者揉在一起的写法在真机上会得到 `FileNotFoundException: /data/data/com.termux/…`，
 *    而那时你会以为"路径拼错了" —— **实际是权限上根本不通**。
 *    这与本项目一贯的模式一致（`CredentialSource` / `UsageRecorder` 也是契约在此、
 *    实现在上层）。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 关键约束：真 Key 绝不进这里
 * ═══════════════════════════════════════════════════════════════
 *
 * dsh 官方 `dsh-credentials-local` 的 README 自己写明：
 *
 * > Only your OS user can read the file, but **agent tool processes run as
 * > that same user, so this store cannot isolate secrets from the agent.**
 *
 * **dsh 无法把凭据与 agent 隔离开** —— 所以它拿到的东西必须是**用完即废**的。
 * 我们写进去的是 [token]：只对 `127.0.0.1` 上那**一个**端口有效，
 * 且每次 `HttpGatewayServer.start()` 重新生成。
 *
 * ⚠️ **本类的构造器上没有"真 Key"这个位置** —— 这不是靠纪律避免泄露，
 *    而是靠**类型上不存在**。加一个 `apiKey` 参数就是打破这条。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 两处落点，与真机上读到的结构逐字对应
 * ═══════════════════════════════════════════════════════════════
 *
 * ① `~/.dsh/settings.yaml` —— 加一个 `llm-deepseek:` 段。
 *    依据（真机 `dsh-llm-deepseek/lib/index.d.ts` 原文）：
 *
 *    > the plugin layers its `cordis.yml` entry config under the optional
 *    > **`llm-deepseek` user-settings section (`ctx.settings`)** … so a changed
 *    > **base URL, catalog, or key reaches the very next request without
 *    > restarting anything**.
 *
 *    段名就是插件自己的 `name` —— 真机 `lib/index.js`：
 *    `const name = "llm-deepseek"; const NS = "llm-deepseek";`
 *    → **`settings.yaml` 里的键名必须逐字是 `llm-deepseek`**。
 *
 * ② `~/.dsh/.credentials.yaml` —— 在 `refs:` 下加 `环境变量名: 值`。
 *    依据（`dsh-credentials-local/lib/types/index.d.ts`）：
 *    `refs: Map<string, string>`（环境变量名 → 明文值），
 *    而 `CredentialRef` 是 "a POSIX-style environment-variable name"。
 *
 *    ⚠️ 真机上 `.credentials.yaml` 里**已经躺着明文真 Key**
 *    （`refs: {DEEPSEEK_API_KEY: sk-…}`）—— dsh 自己的凭据本来就是明文的。
 *    我们的 token 写进同一个文件**不降低任何安全级别**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么不改 profile 的 cordis.patch.yml
 * ═══════════════════════════════════════════════════════════════
 *
 * 研讨文档（`docs/BYOK模型接入与dsh代理设计-v1.0.md` §2.5）曾设想写
 * `cordis.patch.yml` 的 `- id: llm-deepseek / config: {baseURL: …}`。
 * 真机实读后**改为写 settings 层**，理由有三：
 *
 * 1. **settings 层是热重载的**（见上引原文），而 patch 层要
 *    `patchReload: "startup"`（真机 `package.json` 里就是）→ 改完要重启
 * 2. **settings 层是官方给用户配置用的**；patch 层是"改 bundle 组合"用的。
 *    我们做的事（换端点）本质是用户配置，放用户层更不容易被上游升级冲掉
 * 3. **不用碰 profile 目录** —— 那目录由 pnpm 管理（有 node_modules 符号链接、
 *    `pnpm-workspace.yaml`），手改会在下次 dsh 自我修复时被覆盖
 */
data class DshConfigPatch(
    /** 网关地址，形如 `http://127.0.0.1:12345/v1`。取自 `HttpGatewayServer.baseUrl`。 */
    val baseUrl: String,

    /** 本地随机 token。取自 `HttpGatewayServer.token`，**不是**用户的 API Key。 */
    val token: String,

    /**
     * 凭据引用名 —— 写进 `settings.yaml` 的 `apiKeyEnv`，同时是
     * `.credentials.yaml` 的 `refs` 键。
     *
     * ⚠️ **刻意用一个属于我们的名字**（`POCKETAGENT_LOCAL_TOKEN`）而不是复用
     *    `DEEPSEEK_API_KEY`：真机上那个键已经存着用户的真 Key，
     *    覆盖它等于**毁掉用户的 DeepSeek 凭据**（他若想绕过网关直连就没法用了）。
     *    两个名字并存、各管一条链路，互不干扰。
     */
    val credentialRef: String = DEFAULT_CREDENTIAL_REF,

    /**
     * 对外声明的模型清单。值为**虚拟模型名**（与 `HttpGatewayServer.VIRTUAL_MODEL` 一致）。
     *
     * ⚠️ 真机 `index.d.ts` 原文：`models` 是 "Advisory models exposed to discovery
     *    consumers; **requests remain unrestricted**" —— 清单只是"给界面看的"，
     *    不拦请求。所以放虚拟名是安全的。
     *
     * ⚠️ 但**必须放**：不放的话 dsh 会回落到内置 `DEFAULT_MODELS`
     *    （`deepseek-flash` / `deepseek-v4-pro` …），用户在 dsh 界面上会看到
     *    一堆他其实用不了的真实模型名 —— 点了就报错，而错误来自我们这边，
     *    排查方向会跑到 dsh 去。
     */
    val advertisedModelIds: List<String> = listOf(VIRTUAL_MODEL_ID),
) {

    init {
        require(baseUrl.isNotBlank()) { "baseUrl 不能为空" }
        require(token.isNotBlank()) { "token 不能为空 —— 空 token 会让网关对所有请求回 401" }
        require(credentialRef.isNotBlank()) { "credentialRef 不能为空" }
        require(advertisedModelIds.isNotEmpty()) {
            "advertisedModelIds 不能为空 —— 空清单会让 dsh 回落到内置真实模型名"
        }
        // ⚠️ 凭据引用必须是 POSIX 环境变量名 —— 它不是"随便一个字符串"：
        //    dsh 侧会拿它去查环境层、查凭据文件、查 .env。
        //    写成 `pocketagent-token`（带连字符）在多数 shell 里不是合法变量名，
        //    而失败表现是"凭据查不到"，不是"名字非法"。
        require(credentialRef.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) {
            "credentialRef 必须是 POSIX 环境变量名（字母/下划线开头，仅含字母数字下划线），" +
                "当前为 $credentialRef"
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  产出：settings.yaml 的 llm-deepseek 段
    // ─────────────────────────────────────────────────────────────

    /**
     * `settings.yaml` 里要写入的 `llm-deepseek:` 段（**含该键本身**，缩进 0）。
     *
     * 形态（与真机 `settings.yaml` 的既有风格一致：2 空格缩进、无文档头）：
     *
     * ```yaml
     * llm-deepseek:
     *   baseURL: "http://127.0.0.1:12345/v1"
     *   apiKeyEnv: "POCKETAGENT_LOCAL_TOKEN"
     *   models:
     *     - id: "pocketagent-auto"
     * ```
     *
     * ⚠️ 刻意**不写 `agent-default-model`** —— 它已经在真机的 `settings.yaml` 里
     *    （`provider: deepseek-official` / `model: deepseek-flash`）。
     *    我们只该**追加/替换自己的段**，不该整文件重写。见 [mergeIntoSettingsYaml]。
     */
    fun settingsSection(): String = buildString {
        appendLine("$SETTINGS_NS:")
        appendLine("  baseURL: ${yamlScalar(baseUrl)}")
        appendLine("  apiKeyEnv: ${yamlScalar(credentialRef)}")
        appendLine("  models:")
        for (id in advertisedModelIds) {
            appendLine("    - id: ${yamlScalar(id)}")
        }
    }.trimEnd('\n')

    // ─────────────────────────────────────────────────────────────
    //  产出：.credentials.yaml 的 refs 条目
    // ─────────────────────────────────────────────────────────────

    /**
     * `.credentials.yaml` 里要写入 `refs:` 下的**一条**条目（缩进 2）。
     *
     * ⚠️ 只返回条目而不是整个文档 —— `refs` 下可能有别人的键
     *    （真机上就有 `DEEPSEEK_API_KEY`），整文档重写会**删掉用户的凭据**。
     */
    fun credentialEntry(): String = "  ${yamlScalar(credentialRef)}: ${yamlScalar(token)}"

    /**
     * 一份**全新的**最小凭据文档 —— 只在目标文件**不存在**时用。
     *
     * ═══════════════════════════════════════════════════════════
     *  ⚠️ 它与 [mergeIntoCredentialsYaml] 是**互补**的，不是替代
     * ═══════════════════════════════════════════════════════════
     *
     * [mergeIntoCredentialsYaml] 在找不到 `refs:` 时返回 `null`，理由是
     * **"我不处理我不理解的既有文件"** —— 那份文件里有用户的真 Key，
     * 猜错结构会毁掉它。
     *
     * 但**文件不存在时没有"不理解"的对象**：那里没有任何东西可毁。
     * 所以这时可以、也应该生成一份新的。少了这一条，
     * 首次接入就永远拿不到凭据文件，而 `apiKeyEnv` 指向一个不存在的凭据
     * → dsh 每请求报 `MISSING_CREDENTIAL` → 用户去查"token 是不是过期了"。
     *
     * ⚠️ **只允许对"我们自己的落点"用这个方法**。写进 dsh 真实配置目录
     *    的 sink 必须**禁止新建**凭据文件（见 `DshConfigSink.mayCreateCredentialsFile`）——
     *    因为真实的 `.credentials.yaml` 可能还要求 `records:` 等我们没见过的段，
     *    凭空造一份可能让 dsh 直接读不动配置。
     *
     * `version: 1` 取自真机上那份文件（2026-09-22 实地读取）。
     */
    fun credentialsDocument(): String = buildString {
        append("version: 1\n")
        append("refs:\n")
        append(credentialEntry()).append('\n')
    }

    // ─────────────────────────────────────────────────────────────
    //  合并：把上面的片段插进既有文档
    // ─────────────────────────────────────────────────────────────

    /**
     * 把 [settingsSection] 合并进一份既有的 `settings.yaml`。
     *
     * ═══════════════════════════════════════════════════════════
     *  ⚠️ 这是"逐行手术"而不是"解析再重建"
     * ═══════════════════════════════════════════════════════════
     *
     * 用一个真正的 YAML 库（snakeyaml）会更"正确"，但有三个问题：
     * 1. 多一个依赖，而本模块**必须保持零 Android 依赖**以进离线验证器
     * 2. YAML 库会**重排格式**：注释全丢、缩进重算、引号风格统一
     *    → 用户手工加的注释（真机上就有 `# ---- user patch layer ----` 这类）
     *    会在我们"更新配置"时凭空消失
     * 3. `settings.yaml` 里可能有我们**不认识的新键**（dsh 升级带来的），
     *    解析再重建会把不认识的东西**静默丢掉**
     *
     * 所以：**只做两件事** —— 找到 `llm-deepseek:` 这一段的起止行并替换；
     * 找不到就追加到文件末尾。**其余内容一个字节都不动。**
     *
     * @param existing 既有的 settings.yaml 全文。空串表示文件还不存在。
     * @return 合并后的全文。**保证幂等**：同一份 patch 合并两次结果相同。
     */
    fun mergeIntoSettingsYaml(existing: String): String {
        val normalized = existing.replace("\r\n", "\n").replace("\r", "\n")
        val lines = normalized.split('\n').toMutableList()

        // 定位我们的段：一行**顶格**且恰好是 `llm-deepseek:`。
        // ⚠️ 只看顶格那行 —— 缩进的 `llm-deepseek:` 是别的段里的嵌套键
        //    （某个 plugin 的 config 里正好有同名子键），不是我们要找的段。
        val start = lines.indexOfFirst { it == "$SETTINGS_NS:" }

        if (start < 0) {
            val head = normalized.trimEnd('\n')
            return if (head.isEmpty()) {
                settingsSection() + "\n"
            } else {
                // 空一行分隔：真机 settings.yaml 的段落之间就是空行
                head + "\n\n" + settingsSection() + "\n"
            }
        }

        // 段的结束行：下一个顶格且非空非注释的行，或文件结束。
        // ⚠️ 缩进行属于本段；`#` 开头的注释行**也归属本段**（它多半在描述本段），
        //    一并替换掉 —— 留着会让旧注释描述新内容。
        var end = start + 1
        while (end < lines.size) {
            val line = lines[end]
            val isTopLevelKey = line.isNotEmpty() && !line[0].isWhitespace()
            if (isTopLevelKey) break
            end++
        }

        // 替换整个段（含其后的连续空行，避免反复追加时越积越多）
        var trimEnd = end
        while (trimEnd > start + 1 && lines[trimEnd - 1].isBlank()) trimEnd--

        lines.subList(start, trimEnd).clear()
        lines.addAll(start, settingsSection().split('\n'))

        return lines.joinToString("\n").trimEnd('\n') + "\n"
    }

    /**
     * 把 [credentialEntry] 合并进一份既有的 `.credentials.yaml`。
     *
     * ⚠️ **比 settings 的合并危险得多** —— 这个文件里存着用户的真 Key。
     *    所以策略是**最保守的**：
     *    - 只在 `refs:` 块内、键名**恰好**是本 patch 的键时替换那**一行**
     *    - 找不到 `refs:` 块 → **返回 null 表示"我不处理"**，而不是"我来补一个"
     *      （补 `refs:` 需要理解版本与文档指令，做错会毁掉整份凭据）
     *    - 任何其它行**一律原样保留**
     *
     * @return 合并后的全文；`null` 表示**无法安全合并**，调用方应放弃并向用户提示，
     *         ⚠️ **绝不能退化成"整文件覆盖"**。
     */
    fun mergeIntoCredentialsYaml(existing: String): String? {
        val normalized = existing.replace("\r\n", "\n").replace("\r", "\n")
        val lines = normalized.split('\n').toMutableList()

        // `refs:` 必须存在 —— 它是我们唯一被授权改的块
        val refsStart = lines.indexOfFirst { it == "refs:" }
        if (refsStart < 0) return null

        // refs 块的结束：下一个**顶格且非注释**的行（`records:` 之类）或文件结束。
        // ⚠️ 缩进行与注释行都属于本块。
        var refsEnd = refsStart + 1
        while (refsEnd < lines.size) {
            val line = lines[refsEnd]
            val isTopLevelKey = line.isNotEmpty() && !line[0].isWhitespace() &&
                !line.trimStart().startsWith("#")
            if (isTopLevelKey) break
            refsEnd++
        }

        // ⚠️ **必须按"解析出的键名"比对，不能按字符串前缀比对**。
        //
        //    这里踩过一个真实的坑（离线单测抓到的）：[credentialEntry] 渲染出的
        //    键是**带引号**的（`"POCKETAGENT_LOCAL_TOKEN": "…"`），而第一版
        //    用 `"  $credentialRef:"` 去前缀匹配 —— 永远匹配不上 →
        //    每次合并都**追加一条重复键** → 幂等性、token 轮换全部失效。
        //    而 YAML 里重复键的语义是"后者胜"或"直接报错"（取决于解析器），
        //    表现会是"轮换后旧 token 有时还在生效"。
        //
        //    兼容两种形态：我们自己写的（带引号）与用户手写的（裸键）。
        val keyLine = (refsStart + 1 until refsEnd)
            .firstOrNull { yamlKeyOf(lines[it]) == credentialRef }

        if (keyLine != null) {
            lines[keyLine] = credentialEntry()
        } else {
            lines.add(refsEnd, credentialEntry())
        }

        return lines.joinToString("\n")
    }

    companion object {
        /**
         * 虚拟模型名。
         *
         * ⚠️ 必须与 `HttpGatewayServer.VIRTUAL_MODEL` 一致 —— 它在
         *    `provider:gateway` 模块内，本类同模块可见；单测会钉住这个一致性。
         */
        const val VIRTUAL_MODEL_ID = "pocketagent-auto"

        /**
         * `settings.yaml` 里我们这个段的键名。
         *
         * ⚠️ **必须逐字是 `llm-deepseek`** —— 真机 `lib/index.js`：
         *    `const name = "llm-deepseek"; const NS = "llm-deepseek";`
         *    而 `installSection(ctx, NS, …)` 用的就是这个 NS。
         *    写成 `pocketagent` 或 `deepseek` 都会**静默失效**：
         *    dsh 不认识的段会被忽略，表现是"配置写进去了但 baseURL 没生效"。
         */
        const val SETTINGS_NS = "llm-deepseek"

        /**
         * 凭据引用名。**不要**复用 `DEEPSEEK_API_KEY`（那会覆盖用户的真 Key）。
         * 见 [DshConfigPatch.credentialRef] 的注释。
         */
        const val DEFAULT_CREDENTIAL_REF = "POCKETAGENT_LOCAL_TOKEN"

        /**
         * 从一行 YAML 里取出**键名**（已去掉引号与缩进）。取不到返回 null。
         *
         * ⚠️ 存在的理由见 [mergeIntoCredentialsYaml] 的注释 —— 第一版用字符串前缀
         *    匹配，撞上了"我们写带引号的键、比对用裸键"的不一致，
         *    后果是**每次合并都追加一条重复键**，且完全静默。
         *
         * 实现取第一个 `:` 之前的部分。安全性依据：凭据引用名是 POSIX 环境变量名
         * （构造器已 `require` 校验过），**不含冒号**，所以第一个 `:` 一定是键值分隔符。
         * 行内注释（`KEY: value  # note`）不影响 —— 它落在 `:` 之后。
         */
        fun yamlKeyOf(line: String): String? {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return null
            if (!trimmed.contains(':')) return null
            val raw = trimmed.substringBefore(':').trim()
            // 去掉可能存在的单/双引号包裹
            val unquoted = when {
                raw.length >= 2 && raw.startsWith('"') && raw.endsWith('"') ->
                    raw.substring(1, raw.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
                raw.length >= 2 && raw.startsWith('\'') && raw.endsWith('\'') ->
                    raw.substring(1, raw.length - 1).replace("''", "'")
                else -> raw
            }
            return unquoted.ifEmpty { null }
        }

        /**
         * 把值渲染成 YAML 标量。
         *
         * ⚠️ **无条件加双引号** —— 这是本文件最容易被"顺手简化"掉的一处，
         *    代价却是静默的：
         *
         *    - `baseURL: http://127.0.0.1:8080/v1` 裸写时 `:` 在值内其实合法，
         *      但 `#` 不是（`…/v1#x` 会被当注释截断）
         *    - token 是随机串，**可能含 `:`、`#`、`@`、前导 `*` / `&` / `%`** ——
         *      这些在裸标量下各有各的解析规则
         *    - 最阴的一条：**恰好是 `yes` / `no` / `on` / `off` / `null` 这类
         *      会被解析成布尔或空值的字符串**，token 短时有概率命中
         *
         *    加引号 → 全是字面量，一条都不用想。转义只处理 `\` 和 `"`。
         */
        fun yamlScalar(value: String): String {
            val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
            return "\"$escaped\""
        }
    }
}
