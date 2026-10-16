package io.zer0.muse.guardian

import android.content.Context
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.data.guardian.GuardianTimelineDao
import io.zer0.muse.data.guardian.GuardianTimelineEntity
import io.zer0.muse.data.guardian.PerceptionEventDao
import io.zer0.muse.data.guardian.PerceptionEventEntity
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Muse Guardian · 演示数据播种器(Phase 7)。
 *
 * 一键种入可复现 90 秒脚本所需的数据,避免真机等 3 天。支持三种情绪场景([DemoScenario]),
 * 每种都构造能触发对应规则/端侧判定的夜间使用快照 + 叫醒提醒,并跑一次真实端侧推理:
 *  - [DemoScenario.AVOIDANCE] 夜间长时间刷抖音(娱乐占比高)→ 回避
 *  - [DemoScenario.ANXIETY]  凌晨 1–4 点高频切换、非娱乐 → 焦虑
 *  - [DemoScenario.FATIGUE]  连续多晚长时间熬夜、低切换 → 疲劳
 *
 * 时间线由"真实闭环"自行填充,不写死回溯条目:推理变化 → 记"感知到·X" + [GuardianActionCoordinator]
 * 自动执行对症动作并记"替你做了·Y"(仅 Debug)。幂等:每次先清空两表,保证可重复 / 可场景间切换。
 *
 * 另有 [seedExamStory]:把 90 秒"守夜人·备考"脚本的 7 天故事种成可现场演示的时间线(最新一晚跑真实推理,
 * 其余为叙事回溯条目)。
 */
class GuardianDemoSeeder(
    private val perceptionEventDao: PerceptionEventDao,
    private val timelineDao: GuardianTimelineDao,
    private val inference: GuardianInferenceEngine,
    private val context: Context,
) {
    private val seedMutex = Mutex()

    /** 串行化播种:防止 Debug 下快速连点多个场景按钮时,多个 seed 协程交错 delete/insert 导致数据串场景。 */
    suspend fun seed(scenario: DemoScenario = DemoScenario.AVOIDANCE) = seedMutex.withLock {
        resultOf {
            // 清空旧数据(含未来时间戳的残留),保证可重复 / 场景间不串数据
            perceptionEventDao.deleteBefore(Long.MAX_VALUE)
            timelineDao.deleteBefore(Long.MAX_VALUE)

            // 1. 三晚夜间使用快照 —— 原始遥测字段刻意触发规则引擎对应路径(分类器由规则引擎重算 summary)
            for (spec in nightsFor(scenario)) {
                val ms = spec.nightMin * 60_000L
                val end = nightAt(spec.daysAgo, spec.hour)
                perceptionEventDao.insert(
                    PerceptionEventEntity(
                        id = UUID.randomUUID().toString(),
                        timestamp = end,
                        windowStart = end - ms,
                        windowEnd = end,
                        foregroundSwitches = spec.switches,
                        unlockCount = 3,
                        totalForegroundMs = ms,
                        nightActiveMs = ms,
                        topPackages = spec.topPackages(ms),
                        summary = "",
                    ),
                )
            }

            // 2. 叫醒提醒(明早 07:30),供"疲劳 → adjust_wake_reminder"自动动作有目标可挪。
            //    先清掉旧的同名演示提醒,避免反复种入后"叫醒"堆积(adjust 每次只挪最近一个)。
            val reminderStore = io.zer0.muse.tools.reminder.ReminderStore(context)
            reminderStore.list().filter { it.title == WAKE_TITLE }.forEach { reminderStore.remove(it.id) }
            reminderStore.add(WAKE_TITLE, "该起床啦，新的一天", tomorrowAt(7, 30))

            // 3. 重置状态 → 真实端侧推理:resetState 使这次 refresh 必然构成一次"状态变化",从而
            //    (a) 时间线记一条"感知到·X"(含依据);(b) GuardianActionCoordinator 收到变化事件 →
            //    自动执行对症动作(回避弹浮层 / 焦虑发主动消息 / 疲劳挪叫醒提醒)并记一条"替你做了·Y"。
            inference.resetState()
            inference.refresh()
            Logger.i(TAG, "demo data seeded: scenario=${scenario.name}")
        }.onError { msg, t -> Logger.w(TAG, "seed demo data failed: ${t?.message ?: msg}") }
    }

    /**
     * "守夜人 · 备考" 演示:把 90 秒脚本的 7 天故事种成可现场演示的时间线。
     * 最新一晚种入备考熬夜数据 → 跑真实端侧推理(疲劳 `src=onnx`,Debug 下协调器自动把叫醒提醒往后挪);
     * 再种入 7 天回溯条目(疲劳/焦虑/回避的感知 + 改闹钟/陪聊/浮层的行动 + "第一次主动早睡"里程碑 + "替你发朋友圈")。
     * 里程碑 / 朋友圈为叙事种入条目,非真实副作用(发朋友圈不真的写 Moments)。
     */
    suspend fun seedExamStory() = seedMutex.withLock {
        resultOf {
            perceptionEventDao.deleteBefore(Long.MAX_VALUE)
            timelineDao.deleteBefore(Long.MAX_VALUE)

            // 最新一晚:备考熬夜(疲劳)→ 最新推理走真实端侧,Debug 下自动触发"改闹钟"
            for (spec in nightsFor(DemoScenario.FATIGUE)) {
                val ms = spec.nightMin * 60_000L
                val end = nightAt(spec.daysAgo, spec.hour)
                perceptionEventDao.insert(
                    PerceptionEventEntity(
                        id = UUID.randomUUID().toString(),
                        timestamp = end,
                        windowStart = end - ms,
                        windowEnd = end,
                        foregroundSwitches = spec.switches,
                        unlockCount = 3,
                        totalForegroundMs = ms,
                        nightActiveMs = ms,
                        topPackages = spec.topPackages(ms),
                        summary = "",
                    ),
                )
            }

            // 叫醒提醒(改闹钟动作的目标);先清同名旧提醒避免堆积
            val reminderStore = io.zer0.muse.tools.reminder.ReminderStore(context)
            reminderStore.list().filter { it.title == WAKE_TITLE }.forEach { reminderStore.remove(it.id) }
            reminderStore.add(WAKE_TITLE, "该起床啦，新的一天", tomorrowAt(7, 30))

            // 7 天回溯故事(叙事条目,由新到旧;今天的"感知到/替你做了"由下方真实闭环补)
            insertEntry("state", "疲劳", "凌晨活跃 61 分钟 · 连续熬夜备考", "fatigue", 6)
            insertEntry("action", "把叫醒提醒往后挪了 30 分钟", "连熬几晚,多睡会儿比硬撑重要", "", 6)
            insertEntry("state", "焦虑", "凌晨频繁切换、反复看手机、坐立不安", "anxiety", 5)
            insertEntry("action", "主动陪你说了会儿话", "深夜陪你聊了会儿,把紧绷的情绪慢慢放平", "", 5)
            insertEntry("state", "回避", "该复习时刷起了短视频 · 娱乐占比 100%", "avoidance", 4)
            insertEntry("action", "弹了张卡片挡了一下", "你又想逃时,我说:先陪我做完这道题?", "", 4)
            insertEntry("state", "主动早睡 ✨", "昨晚 23:40 就放下手机 · 这周第一次主动早睡", "normal", 3)
            insertEntry("action", "替你发了条朋友圈", "『今天终于在十一点半前睡了』—— 替你记下这个小进步", "", 3)
            insertEntry("state", "状态在回正", "夜间使用明显减少 · 作息回到正轨", "normal", 1)

            // 跑真实端侧推理(最新一晚=疲劳):记今天的"感知到·疲劳" + (Debug)协调器自动把叫醒提醒往后挪
            inference.resetState()
            inference.refresh()
            Logger.i(TAG, "exam story seeded")
        }.onError { msg, t -> Logger.w(TAG, "seed exam story failed: ${t?.message ?: msg}") }
    }

    /** 种入一条回溯时间线条目(时间戳 = 现在往前 [daysAgo] 天)。 */
    private suspend fun insertEntry(kind: String, title: String, detail: String, stateCode: String, daysAgo: Int) {
        timelineDao.insert(
            GuardianTimelineEntity(
                id = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis() - daysAgo * DAY_MS,
                kind = kind,
                title = title,
                detail = detail,
                stateCode = stateCode,
            ),
        )
    }

    private fun nightsFor(scenario: DemoScenario): List<NightSpec> = when (scenario) {
        // 刷抖音 100% → 娱乐占比高(distract)、低切换、非凌晨 → 回避
        DemoScenario.AVOIDANCE -> listOf(
            NightSpec(0, 23, 52, 18, listOf(DOUYIN to 1.0)),
            NightSpec(1, 23, 47, 16, listOf(DOUYIN to 1.0)),
            NightSpec(2, 23, 61, 22, listOf(DOUYIN to 1.0)),
        )
        // 凌晨 2 点(lateNight)+ 高频切换(highSwitch)+ 娱乐占比低 → 焦虑
        DemoScenario.ANXIETY -> listOf(
            NightSpec(0, 2, 35, 72, NEUTRAL_MIX),
            NightSpec(1, 2, 40, 84, NEUTRAL_MIX),
            NightSpec(2, 2, 30, 60, NEUTRAL_MIX),
        )
        // 连续 3 晚长时间(multiNight)、低切换、非娱乐、非凌晨高频 → 疲劳
        DemoScenario.FATIGUE -> listOf(
            NightSpec(0, 0, 62, 10, listOf(CHROME to 0.6, CLOCK to 0.4)),
            NightSpec(1, 0, 55, 9, listOf(CHROME to 0.6, CLOCK to 0.4)),
            NightSpec(2, 0, 70, 11, listOf(CHROME to 0.6, CLOCK to 0.4)),
        )
    }

    /** "最近一次该整点"再往前 [daysAgo] 天的时间戳;强制落在过去(今天该点还没到就用昨天),
     *  否则会生成未来时间戳的夜间事件(语义错误,且 deleteBefore 清不掉 → 场景间串数据)。 */
    private fun nightAt(daysAgo: Int, hour: Int): Long =
        java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, hour)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
            if (timeInMillis >= System.currentTimeMillis()) add(java.util.Calendar.DAY_OF_YEAR, -1)
            add(java.util.Calendar.DAY_OF_YEAR, -daysAgo)
        }.timeInMillis

    private fun tomorrowAt(hour: Int, minute: Int): Long =
        java.util.Calendar.getInstance().apply {
            add(java.util.Calendar.DAY_OF_YEAR, 1)
            set(java.util.Calendar.HOUR_OF_DAY, hour)
            set(java.util.Calendar.MINUTE, minute)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis

    companion object {
        private const val TAG = "GuardianDemoSeeder"
        private const val WAKE_TITLE = "叫醒"
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val DOUYIN = "com.ss.android.ugc.aweme"
        private const val CHROME = "com.android.chrome"
        private const val SETTINGS = "com.android.settings"
        private const val CLOCK = "com.android.deskclock"
        private const val WECHAT = "com.tencent.mm"

        /** 焦虑场景的应用占比:浏览器/设置/微信混用,微信(娱乐类)占比 <50% → distract 不触发,保持"焦虑"而非"回避"。 */
        private val NEUTRAL_MIX = listOf(CHROME to 0.5, SETTINGS to 0.3, WECHAT to 0.2)
    }
}

/** 一晚夜间使用快照的参数(供 [GuardianDemoSeeder] 构造 perception 事件)。 */
private data class NightSpec(
    val daysAgo: Int,
    val hour: Int,
    val nightMin: Int,
    val switches: Int,
    val pkgWeights: List<Pair<String, Double>>,
) {
    /** 把夜间时长按权重拆到各应用,拼成 PerceptionEventEntity.topPackages 的 "pkg:ms,pkg:ms" 格式。 */
    fun topPackages(ms: Long): String =
        pkgWeights.joinToString(",") { (pkg, w) -> "$pkg:${(ms * w).toLong()}" }
}

/**
 * 演示情绪场景:每种对应一套能让端侧分类器判出该类的夜间数据(见 [GuardianDemoSeeder] 的 nightsFor)。
 * "对症的自动动作"由 [GuardianActionCoordinator] 在检测到该状态时执行,不再写死回溯条目。
 *
 * @param label 时间线场景按钮上的短标签。
 */
enum class DemoScenario(val label: String) {
    AVOIDANCE("回避"),
    ANXIETY("焦虑"),
    FATIGUE("疲劳"),
}
