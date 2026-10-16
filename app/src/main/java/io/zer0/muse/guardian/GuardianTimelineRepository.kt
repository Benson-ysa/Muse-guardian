package io.zer0.muse.guardian

import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.data.guardian.GuardianTimelineDao
import io.zer0.muse.data.guardian.GuardianTimelineEntity
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/**
 * Muse Guardian · 时间线仓库。
 *
 * 统一写入两类条目,供 Guardian 时间线页展示,证明"感知→判断→行动"全链路可追溯:
 *  - [logState]:端侧情绪判定发生变化时(由 [GuardianInferenceEngine] 调用)。
 *  - [logAction]:替用户执行关怀动作时(由 adjust_wake_reminder 等工具调用)。
 */
class GuardianTimelineRepository(private val dao: GuardianTimelineDao) {

    /** 记录一次情绪判定(含感知依据)。 */
    suspend fun logState(result: EmotionResult) {
        insert(KIND_STATE, result.state.zh, result.evidence, result.state.code)
    }

    /** 记录一次关怀行动。 */
    suspend fun logAction(title: String, detail: String) {
        insert(KIND_ACTION, title, detail, "")
    }

    fun recentFlow(limit: Int = DEFAULT_LIMIT): Flow<List<GuardianTimelineEntity>> = dao.recentFlow(limit)

    /** 一次性读取最近条目(供 Skill / 非 UI 场景)。 */
    suspend fun recent(limit: Int = DEFAULT_LIMIT): List<GuardianTimelineEntity> = dao.recent(limit)

    private suspend fun insert(kind: String, title: String, detail: String, stateCode: String) {
        resultOf {
            dao.insert(
                GuardianTimelineEntity(
                    id = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    kind = kind,
                    title = title,
                    detail = detail,
                    stateCode = stateCode,
                ),
            )
            dao.deleteBefore(System.currentTimeMillis() - RETENTION_MS)
        }.onError { msg, _ -> Logger.w(TAG, "timeline insert failed: $msg") }
    }

    companion object {
        private const val TAG = "GuardianTimeline"
        private const val DEFAULT_LIMIT = 100
        private const val RETENTION_MS = 30L * 24 * 60 * 60 * 1000

        const val KIND_STATE = "state"
        const val KIND_ACTION = "action"
    }
}
