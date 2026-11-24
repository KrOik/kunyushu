package tv.kyshu.ui.settings

import dagger.hilt.android.AndroidEntryPoint
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.kyshu.service.MockDomainConfigPrefs
import tv.kyshu.service.MockServiceHelper
import tv.kyshu.ui.compose.components.PortalSection
import tv.kyshu.ui.compose.theme.PortalTheme
import tv.kyshu.xposed.config.CoordinateSystem
import tv.kyshu.xposed.config.MockDomainConfigJson

@AndroidEntryPoint
class AppCoordinateFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { PortalTheme { AppCoordinateScreen() } }
        }

    @Composable
    private fun AppCoordinateScreen() {
        val context = requireContext()
        val scope = rememberCoroutineScope()
        var query by remember { mutableStateOf("") }
        var apps by remember { mutableStateOf<List<AppItem>>(emptyList()) }
        var overrides by remember { mutableStateOf<Map<String, CoordinateSystem>>(emptyMap()) }
        var dialogApp by remember { mutableStateOf<AppItem?>(null) }

        LaunchedEffect(Unit) {
            apps = withContext(Dispatchers.IO) {
                val pm = context.packageManager
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                pm.queryIntentActivities(intent, 0).map {
                    AppItem(it.activityInfo.packageName, it.loadLabel(pm).toString())
                }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
            }
            overrides = readConfig(context).coordinateOverrides
        }

        val visibleApps = remember(apps, overrides, query) {
            val normalized = query.trim().lowercase()
            (apps + overrides.keys.map { AppItem(it, it) })
                .distinctBy { it.packageName }
                .filter {
                    normalized.isBlank() ||
                        it.label.lowercase().contains(normalized) ||
                        it.packageName.lowercase().contains(normalized)
                }
                .take(200)
        }

        Column(modifier = Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("应用坐标系", style = MaterialTheme.typography.headlineSmall)
            Text(
                "为不同应用指定位置坐标系。未配置的应用默认使用 WGS84。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("搜索应用") },
                singleLine = true,
            )
            Button(
                onClick = {
                    val packageName = query.trim()
                    if (PACKAGE_NAME.matches(packageName)) dialogApp = AppItem(packageName, packageName)
                    else Toast.makeText(context, "请输入有效包名", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("添加包名规则") }

            PortalSection("已安装应用") {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp).padding(vertical = 4.dp)) {
                    items(visibleApps, key = { it.packageName }) { app ->
                        CoordinateRow(
                            app = app,
                            selected = overrides[app.packageName] ?: CoordinateSystem.WGS84,
                            onClick = { dialogApp = app },
                        )
                    }
                }
            }
        }

        dialogApp?.let { app ->
            CoordinateDialog(
                app = app,
                initial = overrides[app.packageName] ?: CoordinateSystem.WGS84,
                onDismiss = { dialogApp = null },
                onSave = { selected ->
                    dialogApp = null
                    scope.launch {
                        val updated = persistOverride(context, app.packageName, selected)
                        overrides = updated
                        Toast.makeText(context, "坐标策略已更新", Toast.LENGTH_SHORT).show()
                    }
                },
                onReset = {
                    dialogApp = null
                    scope.launch {
                        val updated = persistOverride(context, app.packageName, null)
                        overrides = updated
                        Toast.makeText(context, "已恢复默认坐标系", Toast.LENGTH_SHORT).show()
                    }
                },
            )
        }
    }

    @Composable
    private fun CoordinateRow(app: AppItem, selected: CoordinateSystem, onClick: () -> Unit) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(app.label, style = MaterialTheme.typography.bodyLarge)
                Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onClick) { Text(selected.name) }
        }
    }

    @Composable
    private fun CoordinateDialog(
        app: AppItem,
        initial: CoordinateSystem,
        onDismiss: () -> Unit,
        onSave: (CoordinateSystem) -> Unit,
        onReset: () -> Unit,
    ) {
        var expanded by remember { mutableStateOf(false) }
        var selected by remember { mutableStateOf(initial) }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("${app.label} 坐标系") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(app.packageName, style = MaterialTheme.typography.bodySmall)
                    DropdownMenuItem(
                        text = { Text("当前：${selected.name}") },
                        onClick = { expanded = true },
                    )
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        CoordinateSystem.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.name) },
                                onClick = { selected = option; expanded = false },
                            )
                        }
                    }
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = onReset) { Text("恢复默认") }
                    TextButton(onClick = onDismiss) { Text("取消") }
                }
            },
            confirmButton = { TextButton(onClick = { onSave(selected) }) { Text("保存") } },
        )
    }

    private suspend fun persistOverride(context: Context, packageName: String, system: CoordinateSystem?): Map<String, CoordinateSystem> {
        val current = readConfig(context)
        val updated = current.coordinateOverrides.toMutableMap().apply {
            if (system == null || system == CoordinateSystem.WGS84) remove(packageName) else put(packageName, system)
        }
        val config = current.copy(coordinateOverrides = updated).legalize()
        MockDomainConfigPrefs.importJson(context, MockDomainConfigJson.exportJson(config))
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        runCatching { MockServiceHelper.putConfig(manager, context) }
        return updated
    }

    private fun readConfig(context: Context) = MockDomainConfigJson.importJson(MockDomainConfigPrefs.exportJson(context), true)

    private data class AppItem(val packageName: String, val label: String)

    companion object {
        private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+")
    }
}

