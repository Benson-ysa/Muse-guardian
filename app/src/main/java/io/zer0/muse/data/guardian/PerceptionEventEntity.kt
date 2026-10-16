package io.zer0.muse.data.guardian

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.serialization.Serializable

/**
 * Muse Guardian · 感知事件快照(每次轮询聚合一条)。
 *
 * 由 GuardianPerceptionRunner 周期性从 UsageStatsManager.queryEvents 聚合而来,
 * 是"感知 → 推理"链路的输入:Phase 2 的情绪分类器读取 [summary] 做端侧判定,
 * Phase 5 的 Guardian 时间线按 [windowEnd] 排序展示。
 *
 * 一条记录覆盖 [windowStart, windowEnd] 这段时间窗内的使用行为聚合,
 * 而非单个原始事件 —— 原始事件量大且无直接语义,聚合快照才是下游要的。
 */
@Serializable
@Entity(tableName = "perception_events")
data class PerceptionEventEntity(
    @PrimaryKey val id: String,
    /** 快照生成时间戳。 */
    val timestamp: Long,
    /** 本快照覆盖的使用窗口起点。 */
    val windowStart: Long,
    /** 本快照覆盖的使用窗口终点。 */
    val windowEnd: Long,
    /** 窗口内前台应用切换次数(ACTIVITY_RESUMED 计数)。 */
    val foregroundSwitches: Int,
    /** 窗口内解锁次数(KEYGUARD_HIDDEN 计数,API 28+ 才有)。 */
    val unlockCount: Int,
    /** 窗口内前台使用总时长(毫秒,所有应用前台时间之和,近似亮屏交互时长)。 */
    val totalForegroundMs: Long,
    /** 窗口内落在夜间时段(22:00–06:00)的使用时长(毫秒)。 */
    val nightActiveMs: Long,
    /** 使用时长最高的前几个应用,格式 "包名:毫秒,包名:毫秒"。 */
    val topPackages: String,
    /** 人类/模型可读的一行摘要(Phase 2 情绪分类器的输入文本)。 */
    val summary: String,
)

/** Muse Guardian · 感知事件 DAO。 */
@Dao
interface PerceptionEventDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: PerceptionEventEntity)

    @Query("SELECT * FROM perception_events ORDER BY windowEnd DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<PerceptionEventEntity>

    @Query("SELECT * FROM perception_events WHERE windowEnd >= :since ORDER BY windowEnd ASC")
    suspend fun getSince(since: Long): List<PerceptionEventEntity>

    /** 最近一条记录的窗口终点 —— 用于续接采集窗口,进程重启后自动补齐缺口。 */
    @Query("SELECT MAX(windowEnd) FROM perception_events")
    suspend fun latestWindowEnd(): Long?

    @Query("DELETE FROM perception_events WHERE windowEnd < :before")
    suspend fun deleteBefore(before: Long)
}
