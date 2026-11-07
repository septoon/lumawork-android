package ru.lumastack.lumawork.ui.analytics

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import java.io.File
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import ru.lumastack.lumawork.data.analytics.*
import ru.lumastack.lumawork.domain.analytics.*
import ru.lumastack.lumawork.ui.adaptive.appContentWidthClass
import ru.lumastack.lumawork.ui.shell.AppHeaderState
import ru.lumastack.lumawork.ui.shell.LocalAppHeader
import ru.lumastack.lumawork.ui.simpleone.SimpleOneUiState
import ru.lumastack.lumawork.ui.theme.LumaWorkTheme

/** Synthetic preview uses the real repository, route, header content and adaptive layout. */
@Composable
internal fun rememberPreviewLeaderboard(context: Context): LeaderboardRepository {
    val scope = rememberCoroutineScope()
    return remember(context) {
        val api = object : LeaderboardApi {
            override suspend fun capability(token: String) = LeaderboardCapability(true, null)
            override suspend fun leaderboard(token: String, filter: LeaderboardFilter, offset: Int): LeaderboardResponse {
                delay(1200)
                val now = Instant.now().toString()
                val cities = listOf(LeaderboardCity.All, LeaderboardCity("simf", "Симферополь"), LeaderboardCity("msk", "Москва"))
                fun entry(id: String, name: String, rank: Int?, count: Int?, own: Boolean = false) = LeaderboardEntry(id, name, null, rank, count, count,
                    workCategories.associateWith { if (it == "portable") count ?: 0 else 0 }, count != null, if (count != null) now else null, if (count != null) now else null, own)
                val me = entry("me", "Тигран Михайлович Дарчинян", 24, 954, true)
                val rows = listOf(entry("1", "Александр Константинович Александровский", 1, 123456), entry("2", "Михаил Иванов", 1, 123456),
                    entry("3", "Екатерина Петрова", 3, 9980), entry("4", "Дмитрий Сергеевич Волков", 4, 1854), me,
                    entry("zero", "Анна Смирнова", null, 0), entry("none", "Иван Без данных", null, null))
                return LeaderboardResponse(filter.period, filter.category, cities.first { it.key == filter.city }, cities, true, "Europe/Moscow", now,
                    filter.period.key(), null, null, now, 1, 7, 6, 7, rows, me, null)
            }
        }
        val access = LeaderboardAccessRepository(api, { "preview" to "fixture" })
        LeaderboardRepository(api, FileLeaderboardCache(File(context.noBackupFilesDir, "leaderboard-fixture"), Json { encodeDefaults = true }),
            access, { StatisticsIdentity("preview", "fixture", "fixture-so") }, scope)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LeaderboardPreview(context: Context, dark: Boolean) {
    LumaWorkTheme(darkTheme = dark) {
        val repository = rememberPreviewLeaderboard(context)
        val header = remember { AppHeaderState() }
        val config = LocalConfiguration.current
        val width = appContentWidthClass(config.screenWidthDp, config.screenHeightDp, LocalDensity.current.fontScale)
        CompositionLocalProvider(LocalAppHeader provides header) {
            Scaffold(topBar = { CenterAlignedTopAppBar(title = { header.content?.title?.invoke() ?: Text("Аналитика") },
                actions = { header.content?.actions?.invoke(this) }, navigationIcon = { TextButton(onClick = {}) { Text("☰") } }) }) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    AnalyticsRoute(repository, null, SimpleOneUiState(isAuthorized = true, isInitializing = false), width, {})
                }
            }
        }
    }
}
