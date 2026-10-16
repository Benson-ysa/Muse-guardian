package io.zer0.muse.ui.guardian

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.data.guardian.GuardianTimelineEntity
import io.zer0.muse.guardian.DemoScenario
import io.zer0.muse.guardian.GuardianDemoSeeder
import io.zer0.muse.guardian.GuardianTimelineRepository
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.navigation.MuseTopBar
import io.zer0.muse.ui.common.state.MuseEmptyState
import io.zer0.muse.ui.common.surface.MusePageScaffold
import io.zer0.muse.ui.common.surface.MuseSurface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Muse Guardian · 时间线页。
 *
 * 按时间倒序展示"情绪判定变化(kind=state,含感知依据)"与"关怀行动(kind=action)",
 * 证明端侧闭环"看到了什么 → 判断了什么 → 做了什么"全程可追溯。
 *
 * 用 [koinInject] 直接取仓库 + Room 响应式 Flow,无需 ViewModel。文案用字面量(新功能页,不进 i18n 包)。
 */
@Composable
fun GuardianTimelineScreen(
    onBack: () -> Unit,
    repository: GuardianTimelineRepository = koinInject(),
    seeder: GuardianDemoSeeder = koinInject(),
) {
    val entries by repository.recentFlow(TIMELINE_LIMIT)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    // Phase 7: 仅 Debug 构建暴露"种入演示数据",一键复现三种情绪场景所需数据。
    val canSeed = io.zer0.muse.BuildConfig.DEBUG

    MusePageScaffold(
        topBar = { MuseTopBar(title = "Guardian 时间线", onBack = onBack) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        if (entries.isEmpty() && !canSeed) {
            MuseEmptyState(
                icon = MuseIcons.clock,
                title = "还没有记录",
                subtitle = "当它感知到你的状态变化、或替你做了一件关怀的事,这里会留下可追溯的一条。",
                modifier = Modifier.fillMaxSize().padding(innerPadding),
            )
            return@MusePageScaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (canSeed) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "种入演示数据（Demo）· 选一种情绪场景",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            DemoScenario.entries.forEach { scenario ->
                                MuseSurface(
                                    modifier = Modifier.weight(1f)
                                        .clickable { scope.launch { seeder.seed(scenario) } },
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                ) {
                                    Text(
                                        scenario.label,
                                        style = MaterialTheme.typography.titleSmall,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                                    )
                                }
                            }
                        }
                        MuseSurface(
                            modifier = Modifier.fillMaxWidth()
                                .clickable { scope.launch { seeder.seedExamStory() } },
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                        ) {
                            Text(
                                "守夜人 · 备考 7 天故事",
                                style = MaterialTheme.typography.titleSmall,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                            )
                        }
                    }
                }
            }
            items(items = entries, key = { it.id }) { entry ->
                TimelineCard(entry)
            }
        }
    }
}

@Composable
private fun TimelineCard(entry: GuardianTimelineEntity) {
    val isAction = entry.kind == GuardianTimelineRepository.KIND_ACTION
    MuseSurface(
        modifier = Modifier.fillMaxWidth(),
        color = if (isAction) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
    ) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (isAction) MuseIcons.circleCheck else MuseIcons.clock,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (isAction) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                val prefix = if (isAction) "替你做了 · " else "感知到 · "
                Text(prefix + entry.title, style = MaterialTheme.typography.titleSmall)
                if (entry.detail.isNotBlank()) {
                    Text(
                        entry.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                    )
                }
            }
            Text(
                SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(entry.timestamp)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private const val TIMELINE_LIMIT = 100
