package com.pocketagent.ui.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketagent.core.database.entity.ModelRoleEntity
import com.pocketagent.modelrouter.ModelConfig
import com.pocketagent.modelrouter.ModelSetupSummary
import com.pocketagent.modelrouter.ModelTier
import com.pocketagent.ui.design.GlassSurface
import com.pocketagent.ui.design.PaBadge
import com.pocketagent.ui.design.PaBadgeTone
import com.pocketagent.ui.design.PaBanner
import com.pocketagent.ui.design.PaBannerTone
import com.pocketagent.ui.design.PaButton
import com.pocketagent.ui.design.PaButtonStyle
import com.pocketagent.ui.design.PaColor
import com.pocketagent.ui.design.PaEmptyState
import com.pocketagent.ui.design.PaFilterChip
import com.pocketagent.ui.design.PaIconButton
import com.pocketagent.ui.design.PaRadius
import com.pocketagent.ui.design.PaScreen
import com.pocketagent.ui.design.PaSectionTitle
import com.pocketagent.ui.design.PaSpace
import com.pocketagent.ui.design.PaTextField
import com.pocketagent.ui.design.PaType
import com.pocketagent.ui.keys.StoreState

/**
 * 模型配置。
 *
 * ═══════════════════════════════════════════════════════════════
 *  这一页真正要回答的问题
 * ═══════════════════════════════════════════════════════════════
 *
 * 用户在 Key 页填完 Key 之后，会产生一个很自然的误解：
 * **"我填了 Key，它就能用了。"**
 *
 * 不能。Key 只是"访问厂商的通行证"，而**具体用哪个模型**是另一件事 ——
 * 一个 Key 下可能挂着十几个模型，价格差几十倍。不选清楚的话：
 *   · 便宜任务会被派给贵模型（白花钱）
 *   · 或者根本跑不起来（一个都没配）
 *
 * 所以这一页的核心不是"列表管理"，而是让用户看清三件事：
 *   1. **我现在这套配置能不能跑起来？** → 顶部体检提示
 *   2. **简单任务和复杂任务分别会派给谁？** → 按档位分组
 *   3. **我配了这么多有没有白配的？** → 禁用 / 没设用途的显式标注
 *
 * 第 3 点最容易被忽视：一个"已启用但没有用途"的模型，
 * 界面上看起来完全正常，但调度时会被**静默跳过** ——
 * 用户会以为配好了，然后奇怪为什么从来没用上。
 * 所以这里必须把它标出来，而不是让它混在列表里。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 依赖边界纪律
 * ═══════════════════════════════════════════════════════════════
 *
 * 本页**不参与任何匹配过程**：
 *   · 顶部那条提示直接来自 `ModelSetupSummary.blockers`（空 = 不显示任何东西）
 *   · 按档位分组直接来自 `ModelSetupSummary.byTier`
 *   · "能不能跑"直接来自 `ModelSetupSummary.runnable`
 *
 * 界面自己写 `models.filter { ... }` 看起来只是几行，但它把一条业务规则
 * **复制到了没有测试覆盖的地方**，而且错了不报错、只是显示得很正常。
 */
@Composable
fun ModelsScreen(
    viewModel: ModelsViewModel,
    onBack: () -> Unit,
    /** 去配置 API Key。一条凭据都没有时，"添加模型"表单是走不通的 */
    onOpenKeys: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    PaScreen(
        title = "模型配置",
        subtitle = "决定哪个任务交给哪个模型",
        onBack = onBack,
        modifier = modifier,
    ) {
        when (val store = state.store) {
            StoreState.Opening -> OpeningState()

            StoreState.Ready -> ReadyContent(
                state = state,
                viewModel = viewModel,
                onOpenKeys = onOpenKeys,
            )

            is StoreState.Unrecoverable -> StoreBroken(
                title = "加密存储已经打不开了",
                reason = store.reason,
                hint = "模型配置和 API Key 存在同一处，所以它一起打不开了。" +
                    "请到「API Key」页处理 —— 那里有重建的入口。",
                onRetry = viewModel::open,
            )

            is StoreState.Retryable -> StoreBroken(
                title = "暂时打不开加密存储",
                reason = store.reason,
                hint = "这通常只是暂时的问题，先重试一次。",
                onRetry = viewModel::open,
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  存储打不开
// ═══════════════════════════════════════════════════════════════

@Composable
private fun OpeningState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            color = PaColor.Accent,
            strokeWidth = 2.dp,
            modifier = Modifier.size(28.dp),
        )
    }
}

/**
 * 打不开时的降级界面。
 *
 * ⚠️ 这里**不给"重建存储"按钮** —— 它只在 Key 页提供。
 *    原因：重建会同时清掉 Key 和模型配置，而这一页看不到 Key 的处境。
 *    在"模型页"给出一个会删掉 API Key 的按钮，用户点之前
 *    根本不会想到它影响的是另一页的东西。
 *
 *    两个页面提供同一个破坏性动作，还会带来一个更实际的问题：
 *    用户在 A 页重建、B 页的界面状态不会自动刷新 —— 他会看到
 *    一个还在报错的页面，而故障其实已经解决了。
 */
@Composable
private fun StoreBroken(
    title: String,
    reason: String,
    hint: String,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = PaSpace.screenH, vertical = PaSpace.xs),
        verticalArrangement = Arrangement.spacedBy(PaSpace.s),
    ) {
        PaBanner(title = title, tone = PaBannerTone.Danger, description = reason)

        Text(text = hint, style = PaType.caption, color = PaColor.TextSecondary)

        PaButton(
            text = "重试",
            onClick = onRetry,
            style = PaButtonStyle.Primary,
            fillWidth = true,
        )
    }
}

// ═══════════════════════════════════════════════════════════════
//  正常内容
// ═══════════════════════════════════════════════════════════════

@Composable
private fun ReadyContent(
    state: ModelsUiState,
    viewModel: ModelsViewModel,
    onOpenKeys: () -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<ModelConfig?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = PaSpace.screenH,
            end = PaSpace.screenH,
            top = PaSpace.xs,
            bottom = PaSpace.l,
        ),
        verticalArrangement = Arrangement.spacedBy(PaSpace.s),
    ) {
        state.problem?.let { problem ->
            item(key = "problem") {
                ProblemBanner(problem = problem, onDismiss = viewModel::dismissProblem)
            }
        }

        // ── 体检提示 ────────────────────────────────────────────
        //
        // ⚠️ blockers 为空时**什么都不显示**。
        //    刻意不显示"一切正常"那种绿条 —— 永远挂在界面上的提示
        //    会被用户无视，等它真的变成红色时他也不会看。
        if (state.summary.blockers.isNotEmpty()) {
            item(key = "blockers") {
                SetupBanner(summary = state.summary)
            }
        }

        // ── 添加表单 ────────────────────────────────────────────
        val editor = state.editor
        if (editor != null) {
            item(key = "editor") {
                EditorCard(
                    editor = editor,
                    credentials = state.llmCredentials,
                    onCredential = viewModel::editCredential,
                    onModelId = viewModel::editModelId,
                    onLabel = viewModel::editLabel,
                    onTier = viewModel::editTier,
                    onToggleRole = viewModel::toggleRole,
                    onInputPrice = viewModel::editInputPrice,
                    onOutputPrice = viewModel::editOutputPrice,
                    onSave = viewModel::save,
                    onCancel = viewModel::closeEditor,
                    onOpenKeys = onOpenKeys,
                )
            }
        }

        // ── 列表 ────────────────────────────────────────────────
        when {
            state.models.isEmpty() && editor == null ->
                item(key = "empty") {
                    PaEmptyState(
                        title = "还没有配置模型",
                        description = if (state.llmCredentials.isEmpty()) {
                            "模型配置要挂在一条 API Key 上。" +
                                "先去填一条 Key，再回来挑模型。"
                        } else {
                            "一个 Key 下可能挂着十几个模型，价格差几十倍。" +
                                "配上便宜的做简单任务、强的做复杂任务，能省下大部分开销。"
                        },
                        actionText = if (state.llmCredentials.isEmpty()) "去配置 API Key" else "添加模型",
                        onAction = if (state.llmCredentials.isEmpty()) onOpenKeys else viewModel::openEditor,
                    )
                }

            else -> {
                item(key = "listTitle") {
                    PaSectionTitle(
                        if (editor != null) "已配置"
                        else "已配置 ${state.models.size} 个"
                    )
                }

                // ★ 按档位分组渲染，而不是平铺。
                //
                //   平铺列表传达不了"这三个档位各有什么"这个最重要的事实 ——
                //   用户看到的是五条并列的记录，看不出"我缺一个便宜的档"。
                //   分组之后，"轻量 / 均衡 / 重型"三个标题本身就说明了
                //   任务会被怎么分发。
                groupByTier(state.models).forEach { (tier, models) ->
                    item(key = "tier-${tier.name}") {
                        TierHeader(tier = tier, count = models.size)
                    }
                    items(items = models, key = { it.id }) { model ->
                        ModelCard(
                            model = model,
                            onToggleEnabled = { viewModel.setEnabled(model.id, it) },
                            onTier = { viewModel.setTier(model.id, it) },
                            onToggleRole = { viewModel.toggleRoleOf(model, it) },
                            onDelete = { pendingDelete = model },
                        )
                    }
                }
            }
        }

        if (editor == null && state.models.isNotEmpty()) {
            item(key = "addButton") {
                Spacer(Modifier.height(PaSpace.xxs))
                PaButton(
                    text = "添加模型",
                    onClick = viewModel::openEditor,
                    style = PaButtonStyle.Glass,
                    icon = Icons.Default.Add,
                    fillWidth = true,
                    enabled = state.llmCredentials.isNotEmpty(),
                )
            }
            if (state.llmCredentials.isEmpty()) {
                item(key = "noKeyHint") {
                    Text(
                        text = "要添加模型，先有一条模型接口的 API Key。",
                        style = PaType.caption,
                        color = PaColor.TextTertiary,
                    )
                }
            }
        }
    }

    pendingDelete?.let { model ->
        DeleteDialog(
            model = model,
            onConfirm = {
                viewModel.delete(model.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

/**
 * 把模型按档位分组，**按 轻量 → 均衡 → 重型 排序**。
 *
 * ⚠️ 不能直接用 `groupBy`：它的键序是"首次出现序"（HashMap 保序），
 *    也就是"用户先配了哪个档"，而不是档位本身的顺序。
 *    结果会是：用户先配了重型，之后整页第一组就是重型，
 *    他不会觉得"这页按难度排"，只会觉得"顺序很乱"。
 *
 * 禁用的模型也进分组 —— 它有自己的视觉状态（灰掉 + 徽章），
 * 藏起来反而会让用户以为配置丢了。
 */
private fun groupByTier(models: List<ModelConfig>): List<Pair<ModelTier, List<ModelConfig>>> =
    ModelTier.values()
        .map { tier -> tier to models.filter { it.tier == tier } }
        .filter { (_, list) -> list.isNotEmpty() }

// ═══════════════════════════════════════════════════════════════
//  体检提示
// ═══════════════════════════════════════════════════════════════

/**
 * 配置体检提示。
 *
 * ⚠️ 文案立场：说「**还不能**跑任务」，不说「配置错误」。
 *    用户配了一半就去装插件是正常行为，不是错误状态。
 *    把它叫"错误"会让用户以为我们坏了；叫"还不能"才是实话 —— 差一个模型而已。
 *
 * 色调选择：**只差最后一个模型时用 Warning 而不是 Danger**。
 *    全是 Danger 等于没有重点 —— 这个应用里真正不可逆的事情（删 Key）
 *    必须是最刺眼的那个。
 */
@Composable
private fun SetupBanner(summary: ModelSetupSummary) {
    val tone = if (summary.all.isEmpty()) PaBannerTone.Info else PaBannerTone.Warning

    PaBanner(
        title = if (summary.runnable) "还能更省一点" else "还不能跑任务",
        tone = tone,
        description = if (summary.runnable) {
            "现在能跑，但下面这些地方可以再调一下。"
        } else {
            "任务需要至少一个「用来执行」的模型。"
        },
        details = summary.blockers,
    )
}

// ═══════════════════════════════════════════════════════════════
//  档位分组标题
// ═══════════════════════════════════════════════════════════════

/**
 * 档位标题。
 *
 * ⚠️ 标题后面挂**档位说明**（来自 `ModelTier.description`），不是只写"轻量"。
 *    "轻量"两个字本身不解释任何事 —— 用户不知道它是"便宜"还是"能力弱"。
 *    而档位选错的代价是真实的钱，所以必须把话说明白。
 */
@Composable
private fun TierHeader(tier: ModelTier, count: Int) {
    Column(modifier = Modifier.padding(top = PaSpace.xs)) {
        PaSectionTitle(text = "${tier.displayName} · $count")
        Spacer(Modifier.height(2.dp))
        Text(
            text = tier.description,
            style = PaType.caption,
            color = PaColor.TextTertiary,
        )
    }
}

// ═══════════════════════════════════════════════════════════════
//  模型卡片
// ═══════════════════════════════════════════════════════════════

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelCard(
    model: ModelConfig,
    onToggleEnabled: (Boolean) -> Unit,
    onTier: (ModelTier) -> Unit,
    onToggleRole: (ModelRoleEntity) -> Unit,
    onDelete: () -> Unit,
) {
    GlassSurface(contentPadding = PaSpace.m) {
        // ── 头部：名字 + 状态徽章 ──────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    // ⚠️ 用 effectiveLabel 而不是 label ——
                    //    用户没起名时它退化成厂商给的展示名，
                    //    而 label 是空串，会显示成一行空白
                    text = model.effectiveLabel,
                    style = PaType.body,
                    color = if (model.enabled) PaColor.TextPrimary else PaColor.TextTertiary,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = model.modelId,
                    style = PaType.caption,
                    color = PaColor.TextTertiary,
                )
            }

            // ★ "启用了但没有用途"必须显式标出来。
            //
            //   这是最容易让人困惑的状态：界面显示"已启用"，
            //   但它既不能调度也不能干活，调度时会被**静默跳过**。
            //   不标出来，用户会以为配好了，然后永远等不到它被使用。
            val badge = statusBadgeFor(model)
            if (badge != null) {
                PaBadge(text = badge.first, tone = badge.second)
            }
        }

        // ── 价格 ────────────────────────────────────────────────
        Spacer(Modifier.height(PaSpace.xs))
        Text(
            text = formatPrice(model),
            style = PaType.caption,
            color = PaColor.TextSecondary,
        )

        // ── 档位 ────────────────────────────────────────────────
        Spacer(Modifier.height(PaSpace.s))
        Text(text = "档位", style = PaType.caption, color = PaColor.TextTertiary)
        Spacer(Modifier.height(PaSpace.xs))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(PaSpace.xs),
            verticalArrangement = Arrangement.spacedBy(PaSpace.xs),
        ) {
            ModelTier.values().forEach { tier ->
                PaFilterChip(
                    text = tier.displayName,
                    selected = model.tier == tier,
                    onClick = { onTier(tier) },
                )
            }
        }

        // ── 用途 ────────────────────────────────────────────────
        Spacer(Modifier.height(PaSpace.s))
        Text(text = "用途", style = PaType.caption, color = PaColor.TextTertiary)
        Spacer(Modifier.height(PaSpace.xs))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(PaSpace.xs),
            verticalArrangement = Arrangement.spacedBy(PaSpace.xs),
        ) {
            PaFilterChip(
                text = "执行任务",
                selected = ModelRoleEntity.WORKER in model.roles.toEntityRoles(),
                onClick = { onToggleRole(ModelRoleEntity.WORKER) },
            )
            PaFilterChip(
                text = "当调度者",
                selected = ModelRoleEntity.SCHEDULER in model.roles.toEntityRoles(),
                onClick = { onToggleRole(ModelRoleEntity.SCHEDULER) },
            )
        }

        // ⚠️ 调度者不是"更高级的执行者"，是**另一个职能**。
        //    不说清楚的话，用户会以为"设成调度者 = 让它更强"，
        //    然后发现自己唯一的模型变成了调度者，任务反而跑不了。
        Spacer(Modifier.height(PaSpace.xs))
        Text(
            text = "「当调度者」的模型只负责判断任务难度、不做实际工作。" +
                "只有一个模型时，选「执行任务」就够了。",
            style = PaType.caption,
            color = PaColor.TextTertiary,
        )

        // ── 动作 ────────────────────────────────────────────────
        Spacer(Modifier.height(PaSpace.s))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PaSpace.xs),
        ) {
            PaButton(
                text = if (model.enabled) "停用" else "启用",
                onClick = { onToggleEnabled(!model.enabled) },
                style = PaButtonStyle.Glass,
            )
            Spacer(Modifier.weight(1f))
            PaIconButton(
                icon = Icons.Default.DeleteOutline,
                onClick = onDelete,
                contentDescription = "删除",
                tint = PaColor.TextTertiary,
            )
        }
    }
}

/**
 * 状态徽章。返回 null = 不显示徽章。
 *
 * ⚠️ 只有**需要注意**的才给徽章。正常的模型不给 ——
 *    如果每条都挂一个绿色的"正常"，那徽章就退化成装饰，
 *    真正出问题的那条反而不会引起注意。
 */
private fun statusBadgeFor(model: ModelConfig): Pair<String, PaBadgeTone>? = when {
    !model.enabled -> "已停用" to PaBadgeTone.Neutral

    // 启用了但一个角色都没有 —— 最需要被看见的状态
    model.roles.isEmpty() -> "没设用途" to PaBadgeTone.Warning

    else -> null
}

/**
 * 格式化价格。
 *
 * ⚠️ 两级信息都缺时显示「**价格未知**」，绝不显示 `$0.00`。
 *
 *    这不只是文案问题：`$0.00` 会让用户以为这个模型免费，
 *    从而放心地用 —— 而它可能很贵。同时预算熔断器那边
 *    `estimatedCost` 会因为价格缺失而返回 null，熔断也不会触发。
 *    界面说"免费"、后台不熔断，两件事一起把用户的账单推上去。
 */
private fun formatPrice(model: ModelConfig): String {
    val input = model.inputPricePerMillion
    val output = model.outputPricePerMillion

    if (input == null && output == null) {
        return "价格未知 · 填了才能参与「最省钱」比较，也让预算熔断生效"
    }

    val inPart = input?.let { "输入 $${trimZeros(it)}" } ?: "输入 未知"
    val outPart = output?.let { "输出 $${trimZeros(it)}" } ?: "输出 未知"
    return "$inPart · $outPart （每百万 token）"
}

/** 去掉多余的小数位：0.270 → 0.27，3.0 → 3 */
private fun trimZeros(value: Double): String {
    val rounded = kotlin.math.round(value * 1000) / 1000
    return if (rounded == rounded.toLong().toDouble()) {
        rounded.toLong().toString()
    } else {
        rounded.toString()
    }
}

// ═══════════════════════════════════════════════════════════════
//  添加表单
// ═══════════════════════════════════════════════════════════════

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditorCard(
    editor: EditorState,
    credentials: List<com.pocketagent.keymgmt.StoredCredential>,
    onCredential: (String) -> Unit,
    onModelId: (String) -> Unit,
    onLabel: (String) -> Unit,
    onTier: (ModelTier) -> Unit,
    onToggleRole: (ModelRoleEntity) -> Unit,
    onInputPrice: (String) -> Unit,
    onOutputPrice: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onOpenKeys: () -> Unit,
) {
    GlassSurface(contentPadding = PaSpace.m) {
        Text(text = "添加模型", style = PaType.headline, color = PaColor.TextPrimary)
        Spacer(Modifier.height(PaSpace.s))

        if (credentials.isEmpty()) {
            // 一条 LLM 凭据都没有 —— 表单填什么都存不下来。
            // 与其让用户填完再被拒，不如直接把他引到该去的地方。
            PaBanner(
                title = "还没有可用的 API Key",
                tone = PaBannerTone.Warning,
                description = "模型配置必须挂在一条「模型接口」用途的 Key 上。" +
                    "语音合成的 Key 不能用来调用模型。",
            )
            Spacer(Modifier.height(PaSpace.s))
            PaButton(
                text = "去配置 API Key",
                onClick = onOpenKeys,
                style = PaButtonStyle.Primary,
                fillWidth = true,
            )
            Spacer(Modifier.height(PaSpace.xs))
            PaButton(
                text = "取消",
                onClick = onCancel,
                style = PaButtonStyle.Glass,
                fillWidth = true,
            )
            return@GlassSurface
        }

        Text(text = "用哪条 Key", style = PaType.caption, color = PaColor.TextTertiary)
        Spacer(Modifier.height(PaSpace.xs))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(PaSpace.xs),
            verticalArrangement = Arrangement.spacedBy(PaSpace.xs),
        ) {
            credentials.forEach { credential ->
                PaFilterChip(
                    // 用 effectiveLabel：用户没起名时退化成服务商名
                    text = credential.effectiveLabel,
                    selected = credential.id == editor.credentialId,
                    onClick = { onCredential(credential.id) },
                )
            }
        }

        Spacer(Modifier.height(PaSpace.s))
        PaTextField(
            value = editor.modelId,
            onValueChange = onModelId,
            modifier = Modifier.fillMaxWidth(),
            placeholder = "模型 id，如 deepseek-chat",
            isError = editor.error != null,
        )

        Spacer(Modifier.height(PaSpace.xs))
        PaTextField(
            value = editor.label,
            onValueChange = onLabel,
            modifier = Modifier.fillMaxWidth(),
            placeholder = "备注名（可选，留空用厂商给的名字）",
        )

        Spacer(Modifier.height(PaSpace.s))
        Text(text = "档位", style = PaType.caption, color = PaColor.TextTertiary)
        Spacer(Modifier.height(PaSpace.xs))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(PaSpace.xs),
            verticalArrangement = Arrangement.spacedBy(PaSpace.xs),
        ) {
            ModelTier.values().forEach { tier ->
                PaFilterChip(
                    text = tier.displayName,
                    selected = editor.tier == tier,
                    onClick = { onTier(tier) },
                )
            }
        }
        Spacer(Modifier.height(PaSpace.xs))
        Text(
            text = editor.tier.description,
            style = PaType.caption,
            color = PaColor.TextTertiary,
        )

        Spacer(Modifier.height(PaSpace.s))
        Text(text = "用途", style = PaType.caption, color = PaColor.TextTertiary)
        Spacer(Modifier.height(PaSpace.xs))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(PaSpace.xs),
            verticalArrangement = Arrangement.spacedBy(PaSpace.xs),
        ) {
            PaFilterChip(
                text = "执行任务",
                selected = ModelRoleEntity.WORKER in editor.roles,
                onClick = { onToggleRole(ModelRoleEntity.WORKER) },
            )
            PaFilterChip(
                text = "当调度者",
                selected = ModelRoleEntity.SCHEDULER in editor.roles,
                onClick = { onToggleRole(ModelRoleEntity.SCHEDULER) },
            )
        }

        Spacer(Modifier.height(PaSpace.s))
        Row(horizontalArrangement = Arrangement.spacedBy(PaSpace.xs)) {
            PaTextField(
                value = editor.inputPrice,
                onValueChange = onInputPrice,
                modifier = Modifier.weight(1f),
                placeholder = "输入价 /M",
            )
            PaTextField(
                value = editor.outputPrice,
                onValueChange = onOutputPrice,
                modifier = Modifier.weight(1f),
                placeholder = "输出价 /M",
            )
        }
        Spacer(Modifier.height(PaSpace.xs))
        Text(
            text = "价格是可选的，填美元。留空表示未知 —— 未知的模型" +
                "不参与「最省钱」比较，也不会让预算熔断生效。" +
                "模型清单里有的会自动带上，不用手填。",
            style = PaType.caption,
            color = PaColor.TextTertiary,
        )

        if (editor.error != null) {
            Spacer(Modifier.height(PaSpace.xs))
            Text(text = editor.error, style = PaType.caption, color = PaColor.Danger)
        }

        Spacer(Modifier.height(PaSpace.s))
        Row(horizontalArrangement = Arrangement.spacedBy(PaSpace.xs)) {
            PaButton(
                text = "取消",
                onClick = onCancel,
                style = PaButtonStyle.Glass,
                enabled = !editor.saving,
                modifier = Modifier.weight(1f),
            )
            PaButton(
                text = if (editor.saving) "保存中…" else "保存",
                onClick = onSave,
                style = PaButtonStyle.Primary,
                enabled = !editor.saving && editor.modelId.isNotBlank(),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  删除确认
// ═══════════════════════════════════════════════════════════════

@Composable
private fun DeleteDialog(
    model: ModelConfig,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PaColor.Surface,
        shape = RoundedCornerShape(PaRadius.l),
        title = {
            Text(
                text = "删除这个模型？",
                style = PaType.headline,
                color = PaColor.TextPrimary,
            )
        },
        text = {
            Text(
                text = "「${model.effectiveLabel}」会从配置里移除。\n\n" +
                    "这不影响你的 API Key，也不影响厂商那边的任何东西 —— " +
                    "只是这个应用不再拿它干活。想用回来重新添加即可。",
                style = PaType.caption,
                color = PaColor.TextSecondary,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = "删除", style = PaType.label, color = PaColor.Danger)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "取消", style = PaType.label, color = PaColor.TextSecondary)
            }
        },
    )
}

// ═══════════════════════════════════════════════════════════════
//  错误横幅
// ═══════════════════════════════════════════════════════════════

@Composable
private fun ProblemBanner(problem: String, onDismiss: () -> Unit) {
    Column {
        PaBanner(title = "出错了", tone = PaBannerTone.Danger, description = problem)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onDismiss) {
                Text(text = "知道了", style = PaType.label, color = PaColor.TextSecondary)
            }
        }
    }
}
