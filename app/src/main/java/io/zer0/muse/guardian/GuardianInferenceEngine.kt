package io.zer0.muse.guardian

import io.zer0.common.Logger
import io.zer0.muse.data.guardian.PerceptionEventDao
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Muse Guardian · 端侧推理编排器(感知 → 推理 的汇聚点)。
 *
 * 读取最近 [WINDOW_MS] 的感知事件 → [PerceptionRuleEngine] 生成行为摘要 →
 * [EmotionClassifier] 在设备上判定情绪 → 更新 [state]。
 *
 * [state] / [current] 是下游唯一读取入口:
 *  - Phase 3:SystemPromptAssembler 读 [current] 注入 <perceived_state>,影响 Mood 的 Vibe。
 *  - Phase 5:Guardian 时间线订阅 [state] 展示情绪变化。
 *
 * 由 [GuardianPerceptionRunner] 在每次采集后调用 [refresh],复用其 5 分钟轮询,不另起调度。
 */
class GuardianInferenceEngine(
    private val dao: PerceptionEventDao,
    private val ruleEngine: PerceptionRuleEngine,
    private val classifier: EmotionClassifier,
    private val timeline: GuardianTimelineRepository? = null,
) {
    private val _state = MutableStateFlow<EmotionResult?>(null)
    val state: StateFlow<EmotionResult?> = _state.asStateFlow()

    /**
     * 情绪判定"变化"事件流(非 conflating,区别于 [state])。仅在状态真正变化时发一条,
     * 供 [GuardianActionCoordinator] 可靠地"检测到就执行"对症动作——用 SharedFlow 而非 StateFlow,
     * 是因为演示里 resetState→refresh 的 null→X 变化会被 StateFlow 合并掉、导致同状态重种不再触发。
     */
    private val _changes = MutableSharedFlow<EmotionResult>(extraBufferCapacity = 8)
    val changes: SharedFlow<EmotionResult> = _changes

    /** 当前最新的端侧情绪判定(可能为 null = 尚未推理过)。 */
    fun current(): EmotionResult? = _state.value

    /** 仅供演示播种器:把状态清回"未推理",使下一次 [refresh] 必然构成一次变化(可靠触发时间线与自动动作)。 */
    fun resetState() {
        _state.value = null
    }

    /** 基于最近 3 天感知事件重新推理一次并更新 [state]。 */
    suspend fun refresh() {
        val since = System.currentTimeMillis() - WINDOW_MS
        val events = dao.getSince(since)
        if (events.isEmpty()) return
        val digest = ruleEngine.digest(events)
        val result = classifier.classify(digest)
        val prevState = _state.value?.state
        _state.value = result
        // 情绪判定发生变化时记一条时间线(含感知依据),证明"判断了什么"可追溯;并发一条变化事件供自动关怀协调器消费。
        if (result.state != prevState) {
            timeline?.logState(result)
            _changes.tryEmit(result)
        }
        Logger.i(
            TAG,
            "perceived state=${result.state.code} conf=${"%.2f".format(result.confidence)} " +
                "src=${result.source} evidence=${result.evidence}",
        )
    }

    companion object {
        private const val TAG = "GuardianInference"

        /** 推理回看窗口:最近 3 天。 */
        private const val WINDOW_MS = 3L * 24 * 60 * 60 * 1000
    }
}
