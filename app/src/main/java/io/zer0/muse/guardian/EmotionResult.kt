package io.zer0.muse.guardian

/**
 * Muse Guardian · 端侧情绪状态标签。
 *
 * 由端侧推理([EmotionClassifier])产出,注入 Mood 的 Vibe 维度(Phase 3)。
 *
 * @param code 稳定机读标识(写入 prompt 的 <perceived_state> / 持久化用)。
 * @param zh 中文展示名。
 */
enum class EmotionState(val code: String, val zh: String) {
    AVOIDANCE("avoidance", "回避"),
    ANXIETY("anxiety", "焦虑"),
    FATIGUE("fatigue", "疲劳"),
    NORMAL("normal", "正常"),
    ;

    companion object {
        fun fromCode(code: String): EmotionState =
            entries.firstOrNull { it.code == code } ?: NORMAL
    }
}

/**
 * 一次端侧情绪推理结果。
 *
 * @param source 推理来源:[SOURCE_ONNX] = 端侧 embedding 分类(模型在设备上跑);
 *   [SOURCE_RULE] = 规则兜底(模型不可用或置信不足时)。这是"不可替代性"叙事里要展示的字段。
 */
data class EmotionResult(
    val state: EmotionState,
    val confidence: Float,
    val evidence: String,
    val source: String,
    val timestamp: Long = System.currentTimeMillis(),
) {
    companion object {
        const val SOURCE_ONNX = "onnx"
        const val SOURCE_RULE = "rule"
    }
}
