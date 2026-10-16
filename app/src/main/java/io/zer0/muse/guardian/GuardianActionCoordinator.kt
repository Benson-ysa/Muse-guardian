package io.zer0.muse.guardian

import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.schedule.ProactiveMessageRunner
import io.zer0.muse.tools.ToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Muse Guardian · 自动关怀协调器 —— 把"端侧判定"接到"真实动作"("检测到就真执行")。
 *
 * 订阅 [GuardianInferenceEngine.changes](判定变化事件流),当情绪**变化到**某个困扰态时,自动执行对症动作:
 *  - 回避 → `show_focus_overlay`:弹陪伴浮层挡一下(需悬浮窗权限)
 *  - 焦虑 → [ProactiveMessageRunner.triggerByEvent]:主动发条消息陪伴安抚(需已配对话模型)
 *  - 疲劳 → `adjust_wake_reminder`:把最近的叫醒提醒往后挪 30 分钟
 *
 * 护栏:
 *  ① **仅 Debug 构建启动**(见 MuseApp;release 不自动触发,保留工具/角色手动触发路径,避免真机误改闹钟/弹窗)。
 *  ② 只订阅"变化"事件 —— 5 分钟轮询状态不变不会重复触发。
 *  ③ 每个状态 [COOLDOWN_MS] 冷却,去抖/防误连点重复执行。
 *  ④ 动作前置(悬浮窗权限 / 精确闹钟 / 已配模型)由各动作自身优雅降级;整体 resultOf 兜底,任一失败不影响感知链路。
 */
class GuardianActionCoordinator(
    private val inferenceEngine: GuardianInferenceEngine,
    private val proactiveMessageRunner: ProactiveMessageRunner,
    private val toolRegistry: ToolRegistry,
    private val timeline: GuardianTimelineRepository,
    private val appScope: CoroutineScope,
) {
    private val lastFiredAt = HashMap<EmotionState, Long>()

    @Volatile private var startedAt = 0L

    fun start() {
        startedAt = System.currentTimeMillis()
        appScope.launch {
            inferenceEngine.changes.collect { result -> react(result.state) }
        }
        Logger.i(TAG, "GuardianActionCoordinator started —— 端侧判定变化将自动触发对症关怀动作(Debug)")
    }

    private suspend fun react(state: EmotionState) {
        if (state == EmotionState.NORMAL) return
        val now = System.currentTimeMillis()
        // 预热窗口:跳过 App 刚启动时"首次推理"触发的动作 —— 否则一打开 App 就弹浮层/发消息,
        // 既打断导航也不符合"使用过程中检测到"的语义;场景按钮(启动数秒后)照常触发。
        if (now - startedAt < WARMUP_MS) {
            Logger.d(TAG, "启动预热中,跳过 state=${state.code}")
            return
        }
        if (now - (lastFiredAt[state] ?: 0L) < COOLDOWN_MS) {
            Logger.d(TAG, "自动关怀冷却中,跳过 state=${state.code}")
            return
        }
        lastFiredAt[state] = now
        Logger.i(TAG, "检测到 ${state.code} → 自动执行对症关怀动作")
        resultOf {
            when (state) {
                EmotionState.AVOIDANCE ->
                    toolRegistry.executeFromJson("show_focus_overlay", """{"message":"$OVERLAY_MESSAGE"}""")
                EmotionState.FATIGUE ->
                    toolRegistry.executeFromJson("adjust_wake_reminder", """{"delay_minutes":"30"}""")
                EmotionState.ANXIETY -> {
                    proactiveMessageRunner.triggerByEvent(EVENT_ANXIETY)
                    timeline.logAction("主动陪你说了会儿话", "夜里你心神不宁,主动发了条消息陪你,把紧绷的情绪慢慢放平")
                }
                EmotionState.NORMAL -> Unit
            }
        }.onError { msg, t -> Logger.w(TAG, "自动关怀动作失败 state=${state.code}: ${t?.message ?: msg}") }
    }

    companion object {
        private const val TAG = "GuardianActionCoord"

        /** 同一状态的自动动作冷却:去抖 + 防误连点(仅 Debug 运行,无需长冷却)。 */
        private const val COOLDOWN_MS = 3_000L

        /** 启动预热:App 启动后这段时间内不触发自动动作,避开"首次推理"在打开瞬间弹窗挡住导航。 */
        private const val WARMUP_MS = 6_000L
        private const val EVENT_ANXIETY = "guardian_anxiety"
        private const val OVERLAY_MESSAGE = "又想刷一会儿？先陪我把该做的事做完，好不好？"
    }
}
