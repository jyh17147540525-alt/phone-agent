package com.pocketagent.core.common

/**
 * **屏幕快照的不透明视图** —— 一个空的标记接口。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 2026-11-01：它存在的唯一理由是**解开一个模块依赖环**
 * ═══════════════════════════════════════════════════════════════
 *
 * `:agent` 的 `AgentOrchestrator` 声明了两个契约：
 *
 * ```kotlin
 * interface Grounder  { suspend fun locate(desc, snapshot, allowCache): GroundResult }
 * interface Verifier  { suspend fun verify(expectation, before, after): VerifyResult }
 * ```
 *
 * 两者的参数都是 `:perception.ScreenSnapshot`。而 `:agent` 因此
 * `implementation(project(":perception"))`。
 *
 * ⚠️ 而 `:perception` 的**四个文件全部**带 Android 依赖
 *    （`Bitmap` / `Rect` / `AccessibilityNodeInfo`）。
 *    于是 `:agent` 也就有了 Android 依赖，进不了离线验证器。
 *    连锁后果：`AgentOrchestrator` 验不了 ⇒ `AmbiguityResolver`
 *    一直没能接线（它会产出 `Ambiguous`，但没人负责消歧）。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么用空接口而不是别的办法
 * ═══════════════════════════════════════════════════════════════
 *
 * **查过事实**：`AgentOrchestrator` 对 `ScreenSnapshot`
 * **只做参数传递，从不读它任何字段**。
 * ⇒ 它需要的只是"某个快照"这个概念，不需要那个类型的内部结构。
 *
 * 其他候选方案都被否掉：
 * · 泛型化 → 契约签名变复杂，而两个契约都还没有实现方
 * · 拆 `:perception` 的位图与元信息 → 动感知管线核心类型，
 *   而那条线离线验不了，风险不对等
 * · 让 `:agent` 定义接口、`:perception` 实现它 → **循环依赖**
 *
 * ⇒ 放在两边都已经依赖的 `:core:common` 里，代价最小。
 *
 * ⚠️ 这个接口**不该长出成员**。一旦它有了字段，就说明 `:agent`
 *    真的需要快照内容 —— 那时应当重新考虑拆分方案，
 *    而不是往这里继续堆。
 */
interface SnapshotView
