package io.zer0.muse.guardian

import android.content.Context
import io.zer0.common.Logger
import io.zer0.muse.rag.OnnxEmbeddingProvider
import java.io.File
import kotlin.math.exp
import kotlin.math.sqrt
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Muse Guardian · 端侧情绪分类器。输入规则引擎的行为摘要,输出情绪状态标签。 */
interface EmotionClassifier {
    suspend fun classify(digest: PerceptionDigest): EmotionResult
}

/**
 * 端侧 embedding 分类器(B 路线,复用工程既有 ONNX Runtime 基建)。
 *
 * 流程:对每个情绪类预置若干"种子句",用 [OnnxEmbeddingProvider] 在设备上 embed 成质心(首次惰性计算并缓存);
 * 分类时 embed 行为摘要 → 与各质心求余弦 → softmax → 取最高者。
 *
 * 这一步是整个作品"端侧不可替代性"的所在:模型(embedding.onnx)跑在设备上,
 * 把使用数据翻译成情绪标签,推理全程不离开设备,云端拿不到也做不到。
 *
 * 优雅降级:模型文件缺失([OnnxEmbeddingProvider.isAvailable] 为 false)或置信度低于阈值时,
 * 回退到 [PerceptionDigest.fallbackState] 规则标签([EmotionResult.source] 标记为 rule)。
 */
class OnnxEmotionClassifier(private val context: Context) : EmotionClassifier {

    private val provider: OnnxEmbeddingProvider? by lazy { tryCreateProvider() }

    @Volatile private var centroids: Map<EmotionState, FloatArray>? = null
    private val mutex = Mutex()

    override suspend fun classify(digest: PerceptionDigest): EmotionResult {
        // 无异常模式 → 直接正常,省去一次模型调用。
        if (!digest.hasSignal) {
            return EmotionResult(EmotionState.NORMAL, NORMAL_CONF, digest.evidence, EmotionResult.SOURCE_RULE)
        }
        val p = provider ?: return ruleResult(digest)
        return try {
            val cents = ensureCentroids(p)
            val vec = p.embed(digest.summary)
            // 只在 hasSignal 为真时到这里 → 规则引擎已判定"有异常夜间活动",此处只分辨"哪种困扰"。
            // 排除 NORMAL 质心:句向量各向异性使所有余弦都挤在 ~0.7,NORMAL 会抢走概率质量,
            // 把 top 概率压到阈值以下、端侧判定被迫长期回退规则。NORMAL 已由上方 !hasSignal 分支覆盖。
            val candidates = cents.filterKeys { it != EmotionState.NORMAL }
            val sims = candidates.mapValues { dot(vec, it.value) }
            val probs = softmax(sims)
            val top = probs.maxByOrNull { it.value } ?: return ruleResult(digest)
            Logger.i(
                TAG,
                "onnx decision: top=${top.key.code} conf=${"%.3f".format(top.value)} " +
                    "thr=$MIN_CONFIDENCE sims=[${sims.entries.sortedByDescending { it.value }
                        .joinToString { "${it.key.code}:${"%.3f".format(it.value)}" }}] " +
                    "probs=[${probs.entries.sortedByDescending { it.value }
                        .joinToString { "${it.key.code}:${"%.3f".format(it.value)}" }}]",
            )
            if (top.value < MIN_CONFIDENCE) {
                ruleResult(digest)
            } else {
                EmotionResult(
                    state = top.key,
                    confidence = top.value,
                    evidence = digest.evidence,
                    source = EmotionResult.SOURCE_ONNX,
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            Logger.w(TAG, "端侧分类失败,回退规则: ${t.message}")
            ruleResult(digest)
        }
    }

    private fun ruleResult(digest: PerceptionDigest): EmotionResult =
        EmotionResult(digest.fallbackState, RULE_CONF, digest.evidence, EmotionResult.SOURCE_RULE)

    /** 惰性计算并缓存各情绪类的 embedding 质心(L2 归一化)。 */
    private suspend fun ensureCentroids(p: OnnxEmbeddingProvider): Map<EmotionState, FloatArray> {
        centroids?.let { return it }
        return mutex.withLock {
            centroids?.let { return@withLock it }
            val computed = HashMap<EmotionState, FloatArray>()
            for ((state, seeds) in SEED_PHRASES) {
                val vectors = p.embed(seeds) // List<FloatArray>,逐条已 L2 归一化
                computed[state] = l2Normalize(averageVectors(vectors))
            }
            computed.also { centroids = it }
        }
    }

    private fun tryCreateProvider(): OnnxEmbeddingProvider? {
        val modelFile = File(context.filesDir, DEFAULT_MODEL_RELATIVE)
        if (!modelFile.exists()) {
            Logger.i(TAG, "未找到端侧模型 ${modelFile.absolutePath},情绪判定走规则兜底")
            return null
        }
        val p = OnnxEmbeddingProvider(modelPath = modelFile.absolutePath)
        return if (p.isAvailable()) p else null
    }

    private fun averageVectors(vectors: List<FloatArray>): FloatArray {
        val dim = vectors.firstOrNull()?.size ?: 0
        val sum = FloatArray(dim)
        for (v in vectors) {
            val n = minOf(dim, v.size)
            for (i in 0 until n) sum[i] += v[i]
        }
        if (vectors.isNotEmpty()) {
            for (i in 0 until dim) sum[i] /= vectors.size
        }
        return sum
    }

    private fun l2Normalize(vec: FloatArray): FloatArray {
        var s = 0f
        for (v in vec) s += v * v
        val norm = sqrt(s)
        if (norm == 0f) return vec
        return FloatArray(vec.size) { vec[it] / norm }
    }

    /** 两个 L2 归一化向量的点积即余弦相似度。 */
    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        val n = minOf(a.size, b.size)
        for (i in 0 until n) s += a[i] * b[i]
        return s
    }

    private fun softmax(sims: Map<EmotionState, Float>): Map<EmotionState, Float> {
        val maxSim = sims.values.maxOrNull() ?: 0f
        val exps = sims.mapValues { exp(((it.value - maxSim) / TEMPERATURE).toDouble()) }
        val sum = exps.values.sum().coerceAtLeast(1e-9)
        return exps.mapValues { (it.value / sum).toFloat() }
    }

    companion object {
        private const val TAG = "GuardianClassifier"
        private const val DEFAULT_MODEL_RELATIVE = "muse_onnx/embedding.onnx"

        /** softmax 温度:余弦相似度差异较小,用较小温度锐化区分度。 */
        private const val TEMPERATURE = 0.06f

        /** 端侧分类采纳阈值:最高类概率低于此值则认为区分不明确,回退规则。 */
        private const val MIN_CONFIDENCE = 0.40f

        /** 规则兜底结果的名义置信度。 */
        private const val RULE_CONF = 0.60f

        /** 无异常模式(正常作息)的名义置信度。 */
        private const val NORMAL_CONF = 0.90f

        /**
         * 各情绪类的中文种子句(描述"行为 + 推断出的状态"),用于计算 embedding 质心。
         * 黑客松可直接调这些句子来调分类边界,无需训练。
         */
        private val SEED_PHRASES: Map<EmotionState, List<String>> = mapOf(
            EmotionState.FATIGUE to listOf(
                "连续好几晚熬夜到很晚才睡,睡眠不足,身体很疲惫。",
                "最近总是深夜还不睡,白天没精神、很累。",
                "作息被打乱,夜里一直醒着,疲劳积累。",
            ),
            EmotionState.AVOIDANCE to listOf(
                "深夜停不下来地刷短视频和社交应用,在逃避该做的事,一直拖延不睡。",
                "明明该休息或学习,却沉迷娱乐应用,用刷手机回避问题。",
                "晚上把时间都花在娱乐上,逃避正事,迟迟不肯睡。",
            ),
            EmotionState.ANXIETY to listOf(
                "夜里频繁拿起手机、反复切换应用,心神不宁、焦躁难安。",
                "凌晨还醒着,坐立不安地不停看手机,内心焦虑。",
                "睡不着,一直在不同应用之间来回切换,情绪紧张。",
            ),
            EmotionState.NORMAL to listOf(
                "作息规律,夜间很少使用手机,状态平稳放松。",
                "睡前基本不看手机,使用习惯健康。",
                "晚上按时休息,手机使用不多,一切正常。",
            ),
        )
    }
}
