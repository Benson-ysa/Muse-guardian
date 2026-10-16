package io.zer0.muse.guardian

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.schedule.scheduleWorkEnabledOrFalse
import kotlinx.coroutines.flow.first
import org.koin.core.context.GlobalContext

/**
 * Muse Guardian · [GuardianPerceptionRunner] 的 WorkManager 兜底。
 *
 * 进程内协程轮询仅在 App 存活时有效,App 被杀后感知停止。本 Worker 每 15 分钟(Android 周期下限)
 * 由系统拉起补采一次;采集窗口由 Runner 按 DB 最后窗口终点续接,因此 15 分钟兜底也不丢数据。
 *
 * 取舍与 [io.zer0.muse.schedule.ProactiveMessageWorker] 一致:不 setExpedited、不加网络约束、
 * 恒返回 success(避免 30s 内反复重试)。Koin 未就绪(Safe Mode)时跳过本次。
 */
class GuardianPerceptionWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val koin = resultOf { GlobalContext.get() }.getOrNull()
        if (koin == null) {
            Logger.w(TAG, "Koin 未初始化(Safe Mode?),跳过本次 Worker 执行")
            return Result.success()
        }
        val workEnabled =
            scheduleWorkEnabledOrFalse(
                resultOf { koin.get<io.zer0.muse.data.SettingsRepository>().scheduleWorkEnabledFlow?.first() }
                    .getOrNull(),
            )
        if (!workEnabled) {
            Logger.i(TAG, "后台调度总控已关闭,跳过本次执行")
            return Result.success()
        }
        val runner = resultOf { koin.get<GuardianPerceptionRunner>() }.getOrNull()
        if (runner == null) {
            Logger.w(TAG, "GuardianPerceptionRunner 解析失败,跳过本次 Worker 执行")
            return Result.success()
        }
        resultOf { runner.tickOnceForWorker() }
            .onError { msg, t -> Logger.w(TAG, "tickOnceForWorker failed: ${t?.message ?: msg}") }
        return Result.success()
    }

    companion object {
        private const val TAG = "GuardianPerceptionWorker"
        const val UNIQUE_WORK_NAME = "muse_guardian_perception_worker"
    }
}
