package com.pocketagent.ui.models

import com.google.common.truth.Truth.assertThat
import com.pocketagent.core.database.entity.ModelRoleEntity
import com.pocketagent.core.database.entity.ModelTierEntity
import com.pocketagent.modelrouter.ModelRole
import com.pocketagent.modelrouter.ModelTier
import org.junit.Test

/**
 * 模型配置页的纯逻辑测试。
 *
 * ═══════════════════════════════════════════════════════════════
 *  为什么这些测试值得写
 * ═══════════════════════════════════════════════════════════════
 *
 * 这一页绝大部分判断都下沉到了仓储层和 `modelrouter`（那里有 100+ 用例）。
 * 留在这里的只有两处映射和一段价格解析 —— 而**这三处的共同点**是：
 * 写错了不会报错，只会安静地给出一个看起来合理的错值。
 *
 * 最典型的是 [EditorState.parsePrice]：把空输入解析成 `0.0` 而不是 `null`，
 * 编译器不会说话、界面照样显示、测试不测就永远不知道 ——
 * 但后果是预算熔断器认为这个模型免费，于是**永远不熔断**。
 *
 * 另一个是两处枚举映射。刻意用 `when` 而不是 `valueOf`/`ordinal`，
 * 因为后两者在枚举常量改名或重排时**编译通过、运行时出错**。
 * 下面的测试把"每个常量都有映射"这件事钉死。
 */
class ModelsViewModelTest {

    // ═══════════════════════════════════════════════════════════
    //  价格解析
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `空输入解析为 null 而不是 0`() {
        // ★ 这是本文件最重要的一条。
        //
        //   把"用户没填"当成"免费"会让预算熔断完全失效 ——
        //   `estimatedCost` 会算出 0，熔断器看到 0 就以为还有额度。
        //   用户以为自己在省钱，实际在烧钱。
        assertThat(EditorState().parsePrice("")).isNull()
        assertThat(EditorState().parsePrice("   ")).isNull()
    }

    @Test
    fun `非法输入解析为 null 而不是 0`() {
        // 用户刚敲了一个 "0." 或误触了字母 —— 那不是一个合法价格，
        // 但也绝不该被理解成免费。
        //
        // ⚠️ "0." 这一条曾经**真的挂过**：Kotlin 的 `toDoubleOrNull()`
        //    接受"小数点后为空"的写法，返回 0.0 而不是 null。
        //    而这个字符串正是用户敲 "0.27" 时的必经中间态 ——
        //    若此时保存，模型会被记成免费，预算熔断对它永不生效。
        assertThat(EditorState().parsePrice("0.")).isNull()
        assertThat(EditorState().parsePrice("1.")).isNull()
        assertThat(EditorState().parsePrice("abc")).isNull()
        assertThat(EditorState().parsePrice("1.2.3")).isNull()
        assertThat(EditorState().parsePrice("$1")).isNull()
        assertThat(EditorState().parsePrice("1,5")).isNull()
    }

    @Test
    fun `用户明确填 0 才是免费`() {
        // "确知免费"（如智谱 GLM-4-Flash）与"价格未知"是两个不同的概念，
        // 界面上都显示 $0.00 是错的，但在数据层必须能区分。
        assertThat(EditorState().parsePrice("0")).isEqualTo(0.0)
        assertThat(EditorState().parsePrice("0.0")).isEqualTo(0.0)
        assertThat(EditorState().parsePrice("0.000")).isEqualTo(0.0)
    }

    @Test
    fun `正常价格解析正确`() {
        assertThat(EditorState().parsePrice("0.27")).isEqualTo(0.27)
        assertThat(EditorState().parsePrice("15")).isEqualTo(15.0)
        assertThat(EditorState().parsePrice(" 2.5 ")).isEqualTo(2.5)
    }

    @Test
    fun `负数价格被拒绝`() {
        // 负价格会让 `estimatedCost` 算出负成本，而熔断器一看到负值
        // 就会以为"还有额度"，把预算保护整个废掉 ——
        // 上游 Provider 的注释里点名警告过这件事。
        assertThat(EditorState().parsePrice("-1")).isNull()
        assertThat(EditorState().parsePrice("-0.01")).isNull()
    }

    // ═══════════════════════════════════════════════════════════
    //  档位映射
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `每个界面档位都有对应的数据库档位`() {
        // 穷举 —— 新增一个 ModelTier 却忘了加映射，这条会红。
        // 若用 valueOf，漏掉的那个会编译通过、运行时抛异常。
        ModelTier.values().forEach { tier ->
            val entity = tier.toEntity()
            assertThat(entity).isNotNull()
        }
    }

    @Test
    fun `档位映射保持语义而不是靠位置`() {
        assertThat(ModelTier.LIGHT.toEntity()).isEqualTo(ModelTierEntity.LIGHT)
        assertThat(ModelTier.STANDARD.toEntity()).isEqualTo(ModelTierEntity.STANDARD)
        assertThat(ModelTier.HEAVY.toEntity()).isEqualTo(ModelTierEntity.HEAVY)
    }

    @Test
    fun `档位往返转换是恒等的`() {
        ModelTier.values().forEach { tier ->
            // 借道实体再回来，应该还是原来那个
            val roundTrip = tier.toEntity().name
            assertThat(roundTrip).isEqualTo(tier.name)
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  角色映射
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `每个数据库角色都能映射回界面角色`() {
        ModelRoleEntity.entries.forEach { entity ->
            assertThat(entity.toRoleOrNull()).isNotNull()
        }
    }

    @Test
    fun `角色映射两个方向都对得上`() {
        ModelRoleEntity.SCHEDULER.toRoleOrNull()?.let { role ->
            assertThat(role).isEqualTo(ModelRole.SCHEDULER)
            assertThat(role.toEntity()).isEqualTo(ModelRoleEntity.SCHEDULER)
        }
        ModelRoleEntity.WORKER.toRoleOrNull()?.let { role ->
            assertThat(role).isEqualTo(ModelRole.WORKER)
            assertThat(role.toEntity()).isEqualTo(ModelRoleEntity.WORKER)
        }
    }

    @Test
    fun `角色往返转换覆盖全部界面角色`() {
        ModelRole.entries.forEach { role ->
            assertThat(role.toEntity().toRoleOrNull()).isEqualTo(role)
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  表单默认值
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `表单默认档位是均衡`() {
        // 默认不能是 LIGHT —— 那会让用户配的第一个模型干不了复杂活，
        // 而他不理解为什么任务总是失败。
        // 也不能是 HEAVY —— 默认就该是最贵的选择是种冒犯。
        assertThat(EditorState().tier).isEqualTo(ModelTier.STANDARD)
    }

    @Test
    fun `表单默认角色是执行任务`() {
        // ★ 默认必须是 WORKER，不能是 SCHEDULER。
        //
        //   绝大多数用户只会配一个模型。若默认成调度者，
        //   那个唯一的模型就变成了"只判断难度、不干活" ——
        //   任务永远跑不起来，而界面上看起来一切正常。
        assertThat(EditorState().roles).containsExactly(ModelRoleEntity.WORKER)
    }

    @Test
    fun `表单默认没有价格`() {
        assertThat(EditorState().parsePrice(EditorState().inputPrice)).isNull()
        assertThat(EditorState().parsePrice(EditorState().outputPrice)).isNull()
    }
}
