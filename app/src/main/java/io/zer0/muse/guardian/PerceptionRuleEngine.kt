package io.zer0.muse.guardian

import io.zer0.muse.data.guardian.PerceptionEventEntity
import java.util.Calendar

/**
 * Muse Guardian · 感知规则引擎。
 *
 * 职责:把一段时间内的 [PerceptionEventEntity] 序列翻译成
 *  ① 一段自然语言行为摘要([PerceptionDigest.summary]),供 [EmotionClassifier] 做 embedding;
 *  ② 一个确定性的规则兜底标签([PerceptionDigest.fallbackState]),模型不可用时直接用;
 *  ③ 人类可读的证据([PerceptionDigest.evidence]),进时间线 / prompt。
 *
 * 分工:embedding 擅长"语言相似度"而不擅长读遥测数字,所以先由规则引擎把
 * "凌晨2点刷了50分钟抖音"这类遥测翻译成自然语言,再交给分类器做"语言→情绪"的映射。
 */
class PerceptionRuleEngine {

    fun digest(events: List<PerceptionEventEntity>): PerceptionDigest {
        if (events.isEmpty()) return EMPTY

        var totalFgMs = 0L
        var totalNightMs = 0L
        var lateNightMs = 0L
        var totalSwitches = 0
        val nightDayKeys = HashSet<Int>()
        val nightPkgMs = HashMap<String, Long>()

        for (e in events) {
            totalFgMs += e.totalForegroundMs
            totalSwitches += e.foregroundSwitches
            if (e.nightActiveMs > 0) {
                totalNightMs += e.nightActiveMs
                // 夜间按"以 06:00 为界"归属到某一晚:凌晨 00–06 点归前一晚。
                nightDayKeys.add(dayKey(e.windowEnd - NIGHT_SHIFT_MS))
                if (localHour(e.windowEnd) in 1..4) lateNightMs += e.nightActiveMs
                for ((pkg, ms) in parseTop(e.topPackages)) {
                    nightPkgMs.merge(pkg, ms) { a, b -> a + b }
                }
            }
        }

        val nightMin = totalNightMs / 60000
        val lateMin = lateNightMs / 60000
        val nights = nightDayKeys.size
        val nightTotalPkgMs = nightPkgMs.values.sum().coerceAtLeast(1L)
        val distractMs = nightPkgMs.entries
            .filter { isDistracting(categoryOf(it.key)) }
            .sumOf { it.value }
        val distractShare = distractMs.toFloat() / nightTotalPkgMs
        val topPkg = nightPkgMs.maxByOrNull { it.value }?.key
        val topLabel = topPkg?.let { labelOf(it) } ?: "手机"
        val switchesPerHour = if (totalFgMs > 0) totalSwitches / (totalFgMs / 3_600_000.0) else 0.0

        val hasSignal = totalNightMs >= NIGHT_SIGNAL_MIN_MS
        if (!hasSignal) {
            return PerceptionDigest(
                summary = "最近作息平稳,夜间(22:00–06:00)几乎没有使用手机。",
                fallbackState = EmotionState.NORMAL,
                evidence = "夜间活跃 ${nightMin} 分钟,无明显异常。",
                hasSignal = false,
            )
        }

        val lateNight = lateNightMs >= LATE_NIGHT_MIN_MS
        val highSwitch = switchesPerHour >= HIGH_SWITCH_PER_HOUR
        val distract = distractShare >= DISTRACT_SHARE
        val multiNight = nights >= 2 || totalNightMs >= FATIGUE_TOTAL_MIN_MS

        // 兜底标签优先级:焦虑(凌晨高频切换) > 回避(夜间沉迷娱乐) > 疲劳(多晚熬夜) > 正常。
        val fallback = when {
            lateNight && highSwitch -> EmotionState.ANXIETY
            distract -> EmotionState.AVOIDANCE
            multiNight -> EmotionState.FATIGUE
            else -> EmotionState.FATIGUE
        }

        val summary = buildString {
            append("最近 ${nights} 晚,用户在夜间(22:00–06:00)使用手机,夜间累计活跃约 ${nightMin} 分钟")
            if (lateNight) append(",其中有凌晨 1 点后仍在活跃的情况")
            if (distract) append(",主要时间花在刷短视频 / 社交娱乐应用($topLabel)上")
            if (highSwitch) append(",期间频繁地在多个应用之间反复来回切换、不停拿起手机查看,难以专注、迟迟无法入睡")
            if (fallback == EmotionState.FATIGUE) append(",连续多晚熬夜、睡眠不足,白天容易没精神")
            append("。")
        }
        val evidence = buildString {
            append("夜间活跃 ${nightMin} 分钟 / ${nights} 晚")
            if (lateMin > 0) append(" · 凌晨后 ${lateMin} 分钟")
            if (distractShare > 0) append(" · 娱乐占比 ${(distractShare * 100).toInt()}%")
            append(" · 主要应用 $topLabel")
        }

        return PerceptionDigest(summary, fallback, evidence, hasSignal = true)
    }

    // ── 应用分类 ───────────────────────────────────────────────────────────────

    private enum class AppCategory { ENTERTAINMENT, SOCIAL, GAME, OTHER }

    private fun categoryOf(pkg: String): AppCategory = PACKAGE_INFO[pkg]?.first ?: AppCategory.OTHER

    private fun labelOf(pkg: String): String = PACKAGE_INFO[pkg]?.second ?: pkg

    private fun isDistracting(c: AppCategory): Boolean =
        c == AppCategory.ENTERTAINMENT || c == AppCategory.SOCIAL || c == AppCategory.GAME

    /** 解析 "pkg:ms,pkg:ms" 为列表。 */
    private fun parseTop(raw: String): List<Pair<String, Long>> =
        raw.split(",").mapNotNull { seg ->
            val i = seg.lastIndexOf(':')
            if (i <= 0) return@mapNotNull null
            val pkg = seg.substring(0, i)
            val ms = seg.substring(i + 1).toLongOrNull() ?: return@mapNotNull null
            pkg to ms
        }

    private fun dayKey(ts: Long): Int {
        val c = Calendar.getInstance().apply { timeInMillis = ts }
        return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
    }

    private fun localHour(ts: Long): Int =
        Calendar.getInstance().apply { timeInMillis = ts }.get(Calendar.HOUR_OF_DAY)

    companion object {
        private const val NIGHT_SHIFT_MS = 6L * 60 * 60 * 1000
        private const val NIGHT_SIGNAL_MIN_MS = 20L * 60 * 1000
        private const val LATE_NIGHT_MIN_MS = 10L * 60 * 1000
        private const val FATIGUE_TOTAL_MIN_MS = 45L * 60 * 1000
        private const val DISTRACT_SHARE = 0.5f
        private const val HIGH_SWITCH_PER_HOUR = 60.0

        val EMPTY = PerceptionDigest(
            summary = "暂无足够的使用数据。",
            fallbackState = EmotionState.NORMAL,
            evidence = "无数据。",
            hasSignal = false,
        )

        /**
         * 常见应用包名 → (分类, 展示名)。黑客松用的精简表,可按需扩充。
         * 判断"夜间沉迷娱乐"时把 娱乐 / 社交 / 游戏 视为分心类。
         */
        private val PACKAGE_INFO: Map<String, Pair<AppCategory, String>> = mapOf(
            "com.ss.android.ugc.aweme" to (AppCategory.ENTERTAINMENT to "抖音"),
            "com.ss.android.ugc.aweme.lite" to (AppCategory.ENTERTAINMENT to "抖音极速版"),
            "com.smile.gifmaker" to (AppCategory.ENTERTAINMENT to "快手"),
            "tv.danmaku.bili" to (AppCategory.ENTERTAINMENT to "哔哩哔哩"),
            "com.tencent.qqlive" to (AppCategory.ENTERTAINMENT to "腾讯视频"),
            "com.qiyi.video" to (AppCategory.ENTERTAINMENT to "爱奇艺"),
            "com.tencent.weishi" to (AppCategory.ENTERTAINMENT to "微视"),
            "com.netease.cloudmusic" to (AppCategory.ENTERTAINMENT to "网易云音乐"),
            "com.tencent.mm" to (AppCategory.SOCIAL to "微信"),
            "com.tencent.mobileqq" to (AppCategory.SOCIAL to "QQ"),
            "com.sina.weibo" to (AppCategory.SOCIAL to "微博"),
            "com.xingin.xhs" to (AppCategory.SOCIAL to "小红书"),
            "com.zhiliaoapp.musically" to (AppCategory.ENTERTAINMENT to "TikTok"),
            "com.tencent.tmgp.sgame" to (AppCategory.GAME to "王者荣耀"),
            "com.tencent.tmgp.pubgmhd" to (AppCategory.GAME to "和平精英"),
            "com.miHoYo.Yuanshen" to (AppCategory.GAME to "原神"),
            // 常见非娱乐应用:显式归为 OTHER(不计入"娱乐占比")并给中文展示名,让证据文案更可读。
            "com.android.chrome" to (AppCategory.OTHER to "浏览器"),
            "com.android.settings" to (AppCategory.OTHER to "系统设置"),
            "com.android.deskclock" to (AppCategory.OTHER to "时钟"),
        )
    }
}

/**
 * 规则引擎的产出:行为的自然语言描述 + 规则兜底标签 + 证据。
 *
 * @param hasSignal 是否存在值得推理的异常模式;为 false 时分类器直接判为 NORMAL,不必调用模型。
 */
data class PerceptionDigest(
    val summary: String,
    val fallbackState: EmotionState,
    val evidence: String,
    val hasSignal: Boolean,
)
