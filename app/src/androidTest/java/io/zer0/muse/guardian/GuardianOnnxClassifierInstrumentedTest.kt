package io.zer0.muse.guardian

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Muse Guardian · 端侧 ONNX 情绪分类器真机验证。
 *
 * 目的:证明端侧模型(bge-small-zh-v1.5)真能"区分不同困扰",而非只会判一类。
 * 方法:直接喂分类器若干"留出(held-out)"的自然语言行为摘要——措辞刻意不同于
 * [OnnxEmotionClassifier.SEED_PHRASES],但贴近 [PerceptionRuleEngine] 真实产出的 summary——
 * 断言:① 判出的 state 命中预期类;② source=onnx(真走了模型,非规则兜底);③ 同输入可复现。
 *
 * 前置:设备 filesDir/muse_onnx/ 已放 embedding.onnx + vocab.txt;
 * 未放则 [assumeTrue] 跳过(判为 skipped 而非 failed),便于无模型的 CI/他机。
 *
 * 运行(仅本类):
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=io.zer0.muse.guardian.GuardianOnnxClassifierInstrumentedTest
 */
@RunWith(AndroidJUnit4::class)
class GuardianOnnxClassifierInstrumentedTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun requireModel() {
        val model = File(ctx.filesDir, "muse_onnx/embedding.onnx")
        val vocab = File(ctx.filesDir, "muse_onnx/vocab.txt")
        assumeTrue(
            "端侧模型未部署(filesDir/muse_onnx/embedding.onnx + vocab.txt),跳过真机 ONNX 验证",
            model.exists() && vocab.exists(),
        )
    }

    private fun digest(summary: String, fallback: EmotionState) =
        PerceptionDigest(summary = summary, fallbackState = fallback, evidence = "test", hasSignal = true)

    /**
     * 留出测试用例:(预期类, 行为摘要)。措辞不同于种子句,贴近规则引擎真实 summary,
     * 用以检验"泛化判别"而非"背诵种子句"。
     */
    private val cases: List<Pair<EmotionState, String>> = listOf(
        EmotionState.AVOIDANCE to
            "最近 3 晚,用户在夜间长时间刷抖音等短视频娱乐应用,累计约 160 分钟,一直不肯睡。",
        EmotionState.FATIGUE to
            "最近 4 晚,用户每晚都熬夜到凌晨才睡,夜间累计活跃约 210 分钟,白天明显精神不振、很累。",
        EmotionState.ANXIETY to
            "凌晨 1 点后用户仍然醒着,频繁拿起手机、在多个应用之间反复来回切换,坐立不安、难以安定。",
    )

    @Test
    fun onnx_classifies_each_distress_state_correctly() = runBlocking {
        requireModel()
        val clf = OnnxEmotionClassifier(ctx)
        val failures = mutableListOf<String>()
        val report = StringBuilder("端侧 ONNX 判别矩阵:\n")
        for ((expected, summary) in cases) {
            val r = clf.classify(digest(summary, expected))
            report.append(
                "  expect=${expected.code} got=${r.state.code} " +
                    "conf=${"%.3f".format(r.confidence)} src=${r.source}\n",
            )
            if (r.source != EmotionResult.SOURCE_ONNX) {
                failures += "${expected.code}: 未走 ONNX(src=${r.source}) — 模型未生效或置信不足被迫回退"
            }
            if (r.state != expected) {
                failures += "${expected.code}: 误判为 ${r.state.code}"
            }
        }
        assertTrue("${report}失败项:\n${failures.joinToString("\n")}", failures.isEmpty())
    }

    @Test
    fun onnx_result_is_deterministic() = runBlocking {
        requireModel()
        val clf = OnnxEmotionClassifier(ctx)
        val d = digest(cases.first().second, EmotionState.AVOIDANCE)
        val a = clf.classify(d)
        val b = clf.classify(d)
        assertEquals("同输入两次 state 不一致", a.state, b.state)
        assertEquals("同输入两次 source 不一致", a.source, b.source)
        assertEquals("同输入两次置信度不一致", a.confidence, b.confidence, 1e-4f)
        assertEquals("验证样本应判为回避", EmotionState.AVOIDANCE, a.state)
        assertEquals("验证样本应走 ONNX", EmotionResult.SOURCE_ONNX, a.source)
    }

    @Test
    fun normal_gate_short_circuits_without_model() = runBlocking {
        // hasSignal=false → 直接 NORMAL + 规则(不调模型);不依赖模型文件,始终可跑。
        val clf = OnnxEmotionClassifier(ctx)
        val r = clf.classify(
            PerceptionDigest(
                summary = "最近作息平稳,夜间几乎没使用手机。",
                fallbackState = EmotionState.NORMAL,
                evidence = "test",
                hasSignal = false,
            ),
        )
        assertEquals(EmotionState.NORMAL, r.state)
        assertEquals(EmotionResult.SOURCE_RULE, r.source)
    }
}
