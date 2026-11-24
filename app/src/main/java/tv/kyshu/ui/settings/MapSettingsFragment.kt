package tv.kyshu.ui.settings

import dagger.hilt.android.AndroidEntryPoint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import tv.kyshu.map.MapEngine
import tv.kyshu.map.MapEngineSettings
import tv.kyshu.ui.compose.components.PortalSection
import tv.kyshu.ui.compose.theme.PortalTheme

@AndroidEntryPoint
class MapSettingsFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                PortalTheme { MapSettingsScreen() }
            }
        }

    @Composable
    private fun MapSettingsScreen() {
        val context = requireContext()
        val settings = remember { MapEngineSettings(context) }
        val engines = remember { MapEngine.entries.toList() }
        var selectedEngine by remember { mutableStateOf(settings.preferredEngine) }
        var menuExpanded by remember { mutableStateOf(false) }
        var amapKey by remember { mutableStateOf("") }
        var baiduKey by remember { mutableStateOf("") }
        var googleKey by remember { mutableStateOf("") }
        var status by remember { mutableStateOf(statusText(settings)) }

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("地图与路线服务", style = MaterialTheme.typography.headlineSmall)
            Text(
                "选择显示地图并配置路线服务密钥。密钥会使用 Android Keystore 加密保存。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            PortalSection("首选地图引擎") {
                Box(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    OutlinedTextField(
                        value = engineLabel(selectedEngine),
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("显示引擎") },
                    )
                    Box(modifier = Modifier.matchParentSize()) {
                        Modifier.matchParentSize().clickable { menuExpanded = true }
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        engines.forEach { engine ->
                            DropdownMenuItem(
                                text = { Text(engineLabel(engine)) },
                                onClick = {
                                    selectedEngine = engine
                                    menuExpanded = false
                                },
                            )
                        }
                    }
                }
            }

            PortalSection("路线服务密钥") {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    KeyInput("高德 Web 服务 Key", amapKey, { amapKey = it }, masked(settings.key(MapEngine.AMAP)))
                    KeyInput("百度地图 AK", baiduKey, { baiduKey = it }, masked(settings.key(MapEngine.BAIDU)))
                    KeyInput("Google Maps API Key", googleKey, { googleKey = it }, masked(settings.key(MapEngine.GOOGLE)))
                    Button(
                        onClick = {
                            settings.preferredEngine = selectedEngine
                            amapKey.takeIf(String::isNotBlank)?.let { settings.setKey(MapEngine.AMAP, it) }
                            baiduKey.takeIf(String::isNotBlank)?.let { settings.setKey(MapEngine.BAIDU, it) }
                            googleKey.takeIf(String::isNotBlank)?.let { settings.setKey(MapEngine.GOOGLE, it) }
                            amapKey = ""; baiduKey = ""; googleKey = ""
                            status = statusText(settings)
                            Toast.makeText(context, "地图配置已加密保存", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("保存地图配置") }
                }
            }

            PortalSection("当前状态") {
                Text(
                    status,
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }

    @Composable
    private fun KeyInput(label: String, value: String, onValueChange: (String) -> Unit, hint: String) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(label) },
            placeholder = { Text(hint) },
            singleLine = true,
        )
    }

    private fun statusText(settings: MapEngineSettings): String {
        val routeProviders = settings.routeEngines().joinToString { it.name }.ifBlank { "无" }
        return "显示引擎：${settings.effectiveDisplayEngine().name}；路线服务：$routeProviders"
    }

    private fun engineLabel(engine: MapEngine): String = when (engine) {
        MapEngine.AUTO -> "自动"
        MapEngine.BAIDU -> "百度"
        MapEngine.AMAP -> "高德"
        MapEngine.GOOGLE -> "Google"
    }

    private fun masked(value: String?) = if (value.isNullOrBlank()) "未配置" else "已配置（••••${value.takeLast(4)}）"
}

