package io.zer0.muse.guardian

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import io.zer0.common.Logger
import io.zer0.common.resultOf

/**
 * Muse Guardian · 使用统计采集器。
 *
 * 复用系统既有的 UsageStatsManager(工程已在 [io.zer0.muse.tools.SystemToolsImpl] 用过,
 * 权限 PACKAGE_USAGE_STATS 已在 AndroidManifest 声明)。本类只做只读采集与聚合,
 * 不写库、不做情绪判断 —— 判断在 Phase 2 的分类器,持久化在 Runner。
 *
 * 用 queryEvents(事件流)而非 queryUsageStats(聚合值):
 * 事件流能还原前台切换序列、解锁、每个应用的前台时长,这是判断"拖延/熬夜/焦虑"所需的。
 */
class UsageStatsCollector(private val context: Context) {

    private val usm: UsageStatsManager?
        get() = resultOf {
            context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        }.getOrNull()

    /**
     * 采集 [start, end] 窗口内的使用聚合快照。
     *
     * 未授予"使用情况访问"权限时 queryEvents 返回空 —— 此时快照各项为 0([PerceptionSnapshot.isEmpty] 为真),
     * 由调用方决定跳过写库,不抛异常。
     */
    @Suppress("DEPRECATION") // MOVE_TO_FOREGROUND/BACKGROUND(API 21)在 minSdk 26 可用;
    // ACTIVITY_RESUMED/PAUSED(API 29)值相同但会触发 NewApi,故用旧常量。
    fun collect(start: Long, end: Long): PerceptionSnapshot {
        if (end <= start) return PerceptionSnapshot.empty(start, end)
        val manager = usm ?: return PerceptionSnapshot.empty(start, end)

        var switches = 0
        var unlocks = 0
        // 每个包名当前未配对的前台起点(MOVE_TO_FOREGROUND 后等待 MOVE_TO_BACKGROUND)
        val resumeAt = HashMap<String, Long>()
        // 每个包名累计前台时长
        val fgMillis = HashMap<String, Long>()
        // 每个包名在夜间时段的前台时长
        var nightMs = 0L

        resultOf {
            val events = manager.queryEvents(start, end)
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                when (event.eventType) {
                    UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                        switches++
                        resumeAt[event.packageName] = event.timeStamp
                    }
                    UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                        val from = resumeAt.remove(event.packageName) ?: continue
                        val dur = (event.timeStamp - from).coerceAtLeast(0)
                        fgMillis[event.packageName] = (fgMillis[event.packageName] ?: 0L) + dur
                        nightMs += nightOverlapMs(from, event.timeStamp)
                    }
                    // 解锁:KEYGUARD_HIDDEN == 18(API 28+)。用字面量常量避免 minSdk 26 触发 NewApi;
                    // API 28 以下设备不产生该事件,unlockCount 自然保持 0。
                    EVENT_KEYGUARD_HIDDEN -> unlocks++
                }
            }
        }.onError { msg, _ -> Logger.w(TAG, "queryEvents 失败: $msg") }

        // 窗口结束时仍在前台的应用:按窗口终点结算,避免长时间停留的应用时长丢失。
        for ((pkg, from) in resumeAt) {
            val dur = (end - from).coerceAtLeast(0)
            fgMillis[pkg] = (fgMillis[pkg] ?: 0L) + dur
            nightMs += nightOverlapMs(from, end)
        }

        val top = fgMillis.entries
            .sortedByDescending { it.value }
            .take(TOP_PACKAGES)
            .map { it.key to it.value }
        val totalFg = fgMillis.values.sum()

        return PerceptionSnapshot(
            windowStart = start,
            windowEnd = end,
            foregroundSwitches = switches,
            unlockCount = unlocks,
            totalForegroundMs = totalFg,
            nightActiveMs = nightMs,
            topPackages = top,
        )
    }

    /** 是否已授予"使用情况访问"权限(供 UI/诊断用;采集本身不依赖它,未授权时自然返回空)。 */
    fun hasUsageAccess(): Boolean = resultOf {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        mode == AppOpsManager.MODE_ALLOWED
    }.getOrNull() ?: false

    /** [start, end] 落在夜间时段 [22:00, 06:00) 本地时间内的毫秒数,正确处理跨日。 */
    private fun nightOverlapMs(start: Long, end: Long): Long {
        if (end <= start) return 0L
        var total = 0L
        var cursor = start
        while (cursor < end) {
            val cal = java.util.Calendar.getInstance().apply {
                timeInMillis = cursor
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
            val dayStart = cal.timeInMillis
            val nextDay = dayStart + DAY_MS
            val segEnd = minOf(end, nextDay)
            // 当日两段夜间:[00:00, 06:00) 与 [22:00, 次日00:00)
            total += overlap(cursor, segEnd, dayStart, dayStart + 6 * HOUR_MS)
            total += overlap(cursor, segEnd, dayStart + 22 * HOUR_MS, nextDay)
            cursor = segEnd
        }
        return total
    }

    private fun overlap(s1: Long, e1: Long, s2: Long, e2: Long): Long =
        (minOf(e1, e2) - maxOf(s1, s2)).coerceAtLeast(0L)

    companion object {
        private const val TAG = "GuardianCollector"
        private const val TOP_PACKAGES = 3
        private const val HOUR_MS = 60L * 60 * 1000
        private const val DAY_MS = 24 * HOUR_MS

        /** UsageEvents.Event.KEYGUARD_HIDDEN 的值(API 28+),用字面量避免 minSdk 26 的 NewApi。 */
        private const val EVENT_KEYGUARD_HIDDEN = 18
    }
}

/** 一次采集窗口的使用聚合结果(不落库形态)。 */
data class PerceptionSnapshot(
    val windowStart: Long,
    val windowEnd: Long,
    val foregroundSwitches: Int,
    val unlockCount: Int,
    val totalForegroundMs: Long,
    val nightActiveMs: Long,
    val topPackages: List<Pair<String, Long>>,
) {
    /** 窗口内无任何可记录的使用行为 —— 调用方据此跳过写库,避免空行刷屏。 */
    fun isEmpty(): Boolean =
        foregroundSwitches == 0 && unlockCount == 0 && totalForegroundMs == 0L

    /** 扁平化为 "包名:毫秒,包名:毫秒" 字符串,匹配 [io.zer0.muse.data.guardian.PerceptionEventEntity.topPackages]。 */
    fun topPackagesString(): String =
        topPackages.joinToString(",") { "${it.first}:${it.second}" }

    companion object {
        fun empty(start: Long, end: Long) = PerceptionSnapshot(
            windowStart = start,
            windowEnd = end,
            foregroundSwitches = 0,
            unlockCount = 0,
            totalForegroundMs = 0L,
            nightActiveMs = 0L,
            topPackages = emptyList(),
        )
    }
}
