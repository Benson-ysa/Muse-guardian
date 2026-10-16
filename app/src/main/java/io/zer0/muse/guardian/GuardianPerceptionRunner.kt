package io.zer0.muse.guardian

import io.zer0.common.Logger
import io.zer0.muse.data.guardian.PerceptionEventDao
import io.zer0.muse.data.guardian.PerceptionEventEntity
import io.zer0.muse.util.GlobalCoroutineExceptionHandler
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Muse Guardian · 感知轮询器(感知层入口)。
 *
 * 对齐工程既有调度器([io.zer0.muse.schedule.ProactiveMessageRunner] /
 * [io.zer0.muse.schedule.ScheduledTaskRunner])的"appScope 协程轮询 + WorkManager 兜底"模式:
 *  - 进程存活时:[start] 在 appScope 内每 [POLL_INTERVAL_MS] 采集一次。
 *  - 进程被杀后:[GuardianPerceptionWorker] 每 15 分钟由系统拉起,调 [tickOnceForWorker] 补采。
 *
 * 不新引入前台服务:从 Application.onCreate 起常驻前台服务在 Android 12+ 有后台启动限制,
 * 且本工程的其它周期任务均走"协程轮询 + Worker 兜底"而非前台服务,这里保持一致。
 *
 * 采集窗口无状态续接:每次用 DB 里最后一条记录的 windowEnd 作为本次窗口起点(上限回看
 * [MAX_LOOKBACK_MS]),因此进程重启后能自动补齐停机期间的使用事件(UsageStatsManager 保留历史)。
 */
class GuardianPerceptionRunner(
    private val collector: UsageStatsCollector,
    private val dao: PerceptionEventDao,
    private val inferenceEngine: GuardianInferenceEngine,
    private val appScope: CoroutineScope,
) {
    private var job: Job? = null

    fun start() {
        job?.cancel()
        job = appScope.launch(GlobalCoroutineExceptionHandler) {
            Logger.i(TAG, "GuardianPerceptionRunner started")
            while (isActive) {
                tickOnce()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** WorkManager 兜底路径:进程被杀后由 [GuardianPerceptionWorker] 调一次。 */
    suspend fun tickOnceForWorker() = tickOnce()

    private suspend fun tickOnce() {
        try {
            val now = System.currentTimeMillis()
            val lastEnd = dao.latestWindowEnd() ?: (now - POLL_INTERVAL_MS)
            val windowStart = lastEnd.coerceAtLeast(now - MAX_LOOKBACK_MS)
            if (windowStart >= now) return

            val snapshot = collector.collect(windowStart, now)
            if (snapshot.isEmpty()) {
                // 未授权使用访问 / 窗口内无使用 —— 不写空行,仅推进不写库。
                return
            }
            dao.insert(snapshot.toEntity())
            dao.deleteBefore(now - RETENTION_MS)
            Logger.i(
                TAG,
                "perception tick: switches=${snapshot.foregroundSwitches} " +
                    "unlocks=${snapshot.unlockCount} fg=${snapshot.totalForegroundMs}ms " +
                    "night=${snapshot.nightActiveMs}ms",
            )
            // 采集到新数据后立即重新推理端侧情绪状态(复用本轮询,不另起调度)。
            inferenceEngine.refresh()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            Logger.w(TAG, "perception tick failed: ${t.message}")
        }
    }

    private fun PerceptionSnapshot.toEntity(): PerceptionEventEntity = PerceptionEventEntity(
        id = UUID.randomUUID().toString(),
        timestamp = System.currentTimeMillis(),
        windowStart = windowStart,
        windowEnd = windowEnd,
        foregroundSwitches = foregroundSwitches,
        unlockCount = unlockCount,
        totalForegroundMs = totalForegroundMs,
        nightActiveMs = nightActiveMs,
        topPackages = topPackagesString(),
        summary = buildSummary(this),
    )

    /** 一行可读摘要,既给人看也作为 Phase 2 情绪分类器的输入文本。 */
    private fun buildSummary(s: PerceptionSnapshot): String {
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val span = "${fmt.format(s.windowStart)}–${fmt.format(s.windowEnd)}"
        val fgMin = s.totalForegroundMs / 60000.0
        val nightMin = s.nightActiveMs / 60000.0
        val topPkg = s.topPackages.firstOrNull()?.first ?: "无"
        return buildString {
            append("窗口 $span")
            append(" · 前台切换 ${s.foregroundSwitches} 次")
            append(" · 解锁 ${s.unlockCount} 次")
            append(" · 使用 ${"%.1f".format(fgMin)} 分钟")
            if (s.nightActiveMs > 0) append("(夜间 ${"%.1f".format(nightMin)} 分钟)")
            append(" · 主要应用 $topPkg")
        }
    }

    companion object {
        private const val TAG = "GuardianPerception"

        /** 轮询间隔:进程内 5 分钟一次。 */
        private const val POLL_INTERVAL_MS = 5L * 60 * 1000

        /** 单次采集窗口回看上限,避免进程长时间停机后一次处理超大窗口。 */
        private const val MAX_LOOKBACK_MS = 6L * 60 * 60 * 1000

        /** 感知事件保留 30 天,滚动清理。 */
        private const val RETENTION_MS = 30L * 24 * 60 * 60 * 1000
    }
}
