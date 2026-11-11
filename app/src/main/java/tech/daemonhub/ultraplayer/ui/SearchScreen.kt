package tech.daemonhub.ultraplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import tech.daemonhub.ultraplayer.data.Catalog
import tech.daemonhub.ultraplayer.data.Item
import tech.daemonhub.ultraplayer.data.Kind
import tech.daemonhub.ultraplayer.data.Prefs
import tech.daemonhub.ultraplayer.data.Xtream
import tech.daemonhub.ultraplayer.player.PlayRequest
import tech.daemonhub.ultraplayer.player.PlayerActivity

/** Searches every list that has been loaded. Live channels play directly; films/series open their tab's detail. */
@Composable
fun SearchScreen() {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<Map<Kind, List<Item>>>(emptyMap()) }
    var opened by remember { mutableStateOf<Item?>(null) }

    // Make sure all three lists are available (Live first, it's the quickest)
    LaunchedEffect(Unit) {
        Catalog.ensure(Kind.LIVE)
        Catalog.ensure(Kind.MOVIE)
        Catalog.ensure(Kind.SERIES)
    }
    LaunchedEffect(query) {
        delay(350)
        results = Catalog.search(query)
    }

    val o = opened
    if (o != null) {
        androidx.activity.compose.BackHandler { opened = null }
        SearchDetail(o)
        return
    }

    Column(Modifier.fillMaxSize().padding(24.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search channels, films and series") },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().dpadVertical()
        )
        Spacer(Modifier.size(12.dp))
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((kind, list) in results) {
                if (list.isEmpty()) continue
                item {
                    Text(
                        when (kind) { Kind.LIVE -> "Channels"; Kind.MOVIE -> "Films"; Kind.SERIES -> "Series" },
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                    )
                }
                items(list) { res ->
                    FocusBox(
                        onClick = {
                            if (res.kind == Kind.LIVE) {
                                val login = Prefs.login ?: return@FocusBox
                                PlayerActivity.start(context, PlayRequest(Xtream.liveUrl(login, res), res.name, PlayRequest.TYPE_LIVE,
                                    liveCategoryId = res.categoryId, liveItemId = res.id))
                            } else opened = res
                        },
                        modifier = Modifier.fillMaxWidth(),
                        focusedScale = 1.01f,
                        color = Panel
                    ) {
                        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            AsyncImage(
                                res.icon, null, contentScale = ContentScale.Fit,
                                modifier = Modifier.size(width = 48.dp, height = 48.dp).clip(RoundedCornerShape(6.dp)).background(Tile)
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(res.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            if (query.length >= 2 && results.values.all { it.isEmpty() }) {
                item { Text("No matches", color = Muted, modifier = Modifier.padding(16.dp)) }
            }
        }
    }
}
