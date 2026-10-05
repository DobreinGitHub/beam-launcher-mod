package com.home.tiles

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AllAppsScreen(
    repo: AppRepository,
    apps: List<AppEntry>,
    onBack: () -> Unit,
    onOptions: (AppEntry) -> Unit,
) {
    val context = LocalContext.current
    val sorted = remember(apps) { apps.sortedBy { it.label.lowercase() } }
    var focusedKey by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }

    BackHandler(onBack = onBack)

    LaunchedEffect(Unit) {
        withFrameNanos {}
        runCatching { first.requestFocus() }
    }

    Column(Modifier.fillMaxSize().padding(start = 64.dp, end = 64.dp, top = 36.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            T(tr(R.string.all_apps), 36.sp, weight = FontWeight.Light)
            Spacer(Modifier.width(20.dp))
            T("${apps.size}", 24.sp, color = Colors.TextDim)
            Spacer(Modifier.weight(1f))
            Clock(30.sp)
        }
        Spacer(Modifier.height(18.dp))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            contentPadding = PaddingValues(12.dp, 14.dp, 12.dp, 40.dp),
            horizontalArrangement = Arrangement.spacedBy(30.dp),
            verticalArrangement = Arrangement.spacedBy(30.dp),
        ) {
            itemsIndexed(sorted, key = { _, e -> e.pkg }) { i, entry ->
                val art by rememberArt(repo, entry)
                val focused = focusedKey == entry.pkg
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Tile(
                        size = 150.dp,
                        highlighted = focused,
                        dimmed = entry.hidden,
                        modifier = Modifier
                            .then(if (i == 0) Modifier.focusRequester(first) else Modifier)
                            .onFocusChanged { if (it.isFocused) focusedKey = entry.pkg },
                        onClick = { context.launchApp(entry) },
                        onLongClick = { onOptions(entry) },
                    ) { AppArt(art, entry.label) }
                    Spacer(Modifier.height(12.dp))
                    T(
                        entry.label,
                        17.sp,
                        Modifier.fillMaxWidth(),
                        color = if (focused) Colors.Accent else Colors.Text,
                        align = TextAlign.Center,
                    )
                }
            }
        }
    }
}
