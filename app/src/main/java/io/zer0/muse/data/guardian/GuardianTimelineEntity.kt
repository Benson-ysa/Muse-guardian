package io.zer0.muse.data.guardian

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * Muse Guardian · 时间线条目(情绪判定变化 / 关怀行动)。
 *
 * 这是演示"闭环可追溯"的证据表:
 *  - kind=state:端侧判定出的情绪状态变化,detail 带感知依据(看到了什么 + 判断了什么)。
 *  - kind=action:替用户执行的关怀动作(做了什么)。
 * 由 [io.zer0.muse.guardian.GuardianTimelineRepository] 写入,Guardian 时间线页按时间倒序展示。
 */
@Serializable
@Entity(tableName = "guardian_timeline")
data class GuardianTimelineEntity(
    @PrimaryKey val id: String,
    val timestamp: Long,
    /** 条目类型: state(情绪判定变化) / action(关怀行动)。 */
    val kind: String,
    /** 一行标题(状态中文名 / 行动名)。 */
    val title: String,
    /** 详情(感知依据 / 行动结果)。 */
    val detail: String,
    /** 情绪状态码(kind=state 时为状态码,否则空串)。 */
    val stateCode: String,
)

@Dao
interface GuardianTimelineDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: GuardianTimelineEntity)

    /** 响应式最近条目流,供时间线页 collectAsState 实时刷新。 */
    @Query("SELECT * FROM guardian_timeline ORDER BY timestamp DESC LIMIT :limit")
    fun recentFlow(limit: Int): Flow<List<GuardianTimelineEntity>>

    @Query("SELECT * FROM guardian_timeline ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<GuardianTimelineEntity>

    @Query("DELETE FROM guardian_timeline WHERE timestamp < :before")
    suspend fun deleteBefore(before: Long)
}
