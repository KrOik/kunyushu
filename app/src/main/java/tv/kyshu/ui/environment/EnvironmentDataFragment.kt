package tv.kyshu.ui.environment

import dagger.hilt.android.AndroidEntryPoint
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.Fragment
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.baidu.mapapi.map.MapStatusUpdateFactory
import com.baidu.mapapi.map.MapView
import com.baidu.mapapi.map.MarkerOptions
import com.baidu.mapapi.model.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.kyshu.environment.CloudEnvironmentClient
import tv.kyshu.environment.CompleteEnvironmentSample
import tv.kyshu.environment.EnvironmentDatabase
import tv.kyshu.environment.EnvironmentJsonCodec
import tv.kyshu.environment.EnvironmentRepository
import tv.kyshu.ext.gcj02
import tv.kyshu.ext.wgs84
import tv.kyshu.security.SecretStore
import tv.kyshu.ui.compose.portalComposeView

@AndroidEntryPoint
class EnvironmentDataFragment : Fragment() {
    private lateinit var repository: EnvironmentRepository
    private lateinit var secrets: SecretStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = EnvironmentRepository(EnvironmentDatabase.get(requireContext()))
        secrets = SecretStore(requireContext())
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        portalComposeView { EnvironmentDataScreen() }

    @Composable
    private fun EnvironmentDataScreen() {
        val context = requireContext()
        val scope = rememberCoroutineScope()
        val samples by repository.observeAll().collectAsStateWithLifecycle(emptyList())
        var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
        var editItem by remember { mutableStateOf<CompleteEnvironmentSample?>(null) }
        var confirmExport by remember { mutableStateOf(false) }
        var confirmDelete by remember { mutableStateOf(false) }
        var confirmFetch by remember { mutableStateOf(false) }
        var mapView by remember { mutableStateOf<MapView?>(null) }
        var wigleToken by remember { mutableStateOf(if (secrets.contains(SecretStore.WIGLE_TOKEN)) MASK else "") }
        var cellToken by remember { mutableStateOf(if (secrets.contains(SecretStore.OPENCELLID_TOKEN)) MASK else "") }

        val exportLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json"),
        ) { uri ->
            if (uri != null) scope.launch(Dispatchers.IO) {
                val json = EnvironmentJsonCodec.encode(repository.getAll())
                context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                withContext(Dispatchers.Main) { toast("环境数据已导出") }
            }
        }
        val importLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri != null) scope.launch(Dispatchers.IO) { importData(uri, context) }
        }

        LaunchedEffect(samples, mapView) {
            selectedIds = selectedIds.intersect(samples.map { it.sample.id }.toSet())
            val map = mapView?.map ?: return@LaunchedEffect
            map.clear()
            samples.forEach { item ->
                val point = (item.sample.latitude to item.sample.longitude).gcj02
                map.addOverlay(
                    MarkerOptions()
                        .position(point)
                        .title(item.sample.label.ifBlank { item.sample.source }),
                )
            }
            samples.firstOrNull()?.let { item ->
                val point = (item.sample.latitude to item.sample.longitude).gcj02
                map.setMapStatus(MapStatusUpdateFactory.newLatLngZoom(point, 15f))
            }
        }

        Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("环境数据", style = MaterialTheme.typography.headlineSmall)
            Text(
                "管理本地采集样本。敏感标识仅用于已授权的环境回放。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/json")) }, modifier = Modifier.weight(1f)) { Text("导入") }
                OutlinedButton(onClick = { confirmExport = true }, modifier = Modifier.weight(1f)) { Text("导出") }
                Button(onClick = { confirmDelete = true }, enabled = selectedIds.isNotEmpty(), modifier = Modifier.weight(1f)) { Text("删除所选") }
            }

            AndroidView(
                factory = { viewContext ->
                    MapView(viewContext).also {
                        it.onCreate(viewContext, null)
                        mapView = it
                    }
                },
                modifier = Modifier.fillMaxWidth().height(180.dp),
            )

            if (samples.isEmpty()) {
                Text("暂无环境样本", modifier = Modifier.fillMaxWidth().padding(12.dp), style = MaterialTheme.typography.bodyLarge)
            } else {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    LazyColumn(modifier = Modifier.fillMaxWidth().height(260.dp)) {
                        items(samples, key = { it.sample.id }) { item ->
                            EnvironmentSampleRow(
                                item = item,
                                selected = item.sample.id in selectedIds,
                                onSelectedChange = { checked ->
                                    selectedIds = if (checked) selectedIds + item.sample.id else selectedIds - item.sample.id
                                },
                                onEdit = { editItem = item },
                            )
                        }
                    }
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("云端数据源", style = MaterialTheme.typography.titleMedium)
                    Text("Token 仅保存在 Android Keystore。", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = wigleToken,
                        onValueChange = { wigleToken = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("WiGLE Token") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = cellToken,
                        onValueChange = { cellToken = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("OpenCellID Token") },
                        singleLine = true,
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { saveTokens(wigleToken, cellToken, scope) }, modifier = Modifier.weight(1f)) { Text("保存并验证") }
                        Button(onClick = { confirmFetch = true }, modifier = Modifier.weight(1f)) { Text("补全地图中心") }
                    }
                }
            }
        }

        if (confirmExport) {
            AlertDialog(
                onDismissRequest = { confirmExport = false },
                title = { Text("导出敏感环境数据") },
                text = { Text("导出文件包含真实 BSSID、基站和 BLE 标识，请仅在授权环境中保存和共享。") },
                dismissButton = { TextButton(onClick = { confirmExport = false }) { Text("取消") } },
                confirmButton = {
                    TextButton(onClick = { confirmExport = false; exportLauncher.launch("portal-environment.json") }) { Text("继续") }
                },
            )
        }
        if (confirmDelete) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text("删除 ${selectedIds.size} 个样本？") },
                text = { Text("删除后将无法从本地数据库恢复。") },
                dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
                confirmButton = {
                    TextButton(onClick = {
                        confirmDelete = false
                        val ids = selectedIds.toList()
                        selectedIds = emptySet()
                        scope.launch(Dispatchers.IO) { repository.delete(ids) }
                    }) { Text("删除") }
                },
            )
        }
        if (confirmFetch) {
            AlertDialog(
                onDismissRequest = { confirmFetch = false },
                title = { Text("补全无线环境？") },
                text = { Text("将查询地图中心附近的 WiGLE 与 OpenCellID 数据，并缓存到本地。") },
                dismissButton = { TextButton(onClick = { confirmFetch = false }) { Text("取消") } },
                confirmButton = {
                    TextButton(onClick = {
                        confirmFetch = false
                        val target = mapView?.map?.mapStatus?.target?.wgs84 ?: return@TextButton
                        fetchCloud(target.first, target.second, scope)
                    }) { Text("查询") }
                },
            )
        }
        editItem?.let { item ->
            EditSampleDialog(item, onDismiss = { editItem = null }) { updated ->
                editItem = null
                scope.launch(Dispatchers.IO) { repository.update(updated) }
            }
        }
    }

    @Composable
    private fun EnvironmentSampleRow(
        item: CompleteEnvironmentSample,
        selected: Boolean,
        onSelectedChange: (Boolean) -> Unit,
        onEdit: () -> Unit,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = selected, onCheckedChange = onSelectedChange)
            Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(item.sample.label.ifBlank { item.sample.source }, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "%.6f, %.6f\nWi-Fi ${item.wifi.size} · Cell ${item.cells.size} · BLE ${item.ble.size}".format(item.sample.latitude, item.sample.longitude),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onEdit) { Text("编辑") }
        }
    }

    @Composable
    private fun EditSampleDialog(
        item: CompleteEnvironmentSample,
        onDismiss: () -> Unit,
        onSave: (tv.kyshu.environment.EnvironmentSample) -> Unit,
    ) {
        var label by remember(item.sample.id) { mutableStateOf(item.sample.label) }
        var note by remember(item.sample.id) { mutableStateOf(item.sample.note) }
        var preferredWifi by remember(item.sample.id) { mutableStateOf(item.sample.preferredWifiBssid) }
        var preferredCell by remember(item.sample.id) { mutableStateOf(item.sample.preferredCellKey) }
        var preferredBle by remember(item.sample.id) { mutableStateOf(item.sample.preferredBleAddress) }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("编辑环境样本") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(label, { label = it }, label = { Text("名称") }, singleLine = true)
                    OutlinedTextField(note, { note = it }, label = { Text("备注") }, minLines = 2)
                    PreferenceMenu("首选连接 Wi-Fi", preferredWifi, listOf(null) + item.wifi.map { it.bssid }) { preferredWifi = it }
                    PreferenceMenu("首选注册小区", preferredCell, listOf(null) + item.cells.map { it.cellKey }) { preferredCell = it }
                    PreferenceMenu("首选 BLE 设备", preferredBle, listOf(null) + item.ble.map { it.address }) { preferredBle = it }
                }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
            confirmButton = {
                TextButton(onClick = {
                    onSave(item.sample.copy(
                        label = label.take(200), note = note.take(2_000),
                        preferredWifiBssid = preferredWifi,
                        preferredCellKey = preferredCell,
                        preferredBleAddress = preferredBle,
                    ))
                }) { Text("保存") }
            },
        )
    }

    @Composable
    private fun PreferenceMenu(title: String, selected: String?, options: List<String?>, onSelected: (String?) -> Unit) {
        var expanded by remember(title, selected) { mutableStateOf(false) }
        Column {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text("$title：${selected ?: "不指定"}")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.distinct().forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option ?: "不指定") },
                        onClick = { onSelected(option); expanded = false },
                    )
                }
            }
        }
    }

    private suspend fun importData(uri: Uri, context: android.content.Context) {
        runCatching {
            val json = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: error("无法读取文件")
            EnvironmentJsonCodec.decode(json).forEach { (capture, metadata) -> repository.import(capture, metadata) }
        }.onSuccess { withContext(Dispatchers.Main) { toast("环境数据已导入") } }
            .onFailure { error -> withContext(Dispatchers.Main) { toast("导入失败：${error.message}") } }
    }

    private fun saveTokens(wigle: String, cell: String, scope: kotlinx.coroutines.CoroutineScope) {
        if (wigle != MASK) secrets.put(SecretStore.WIGLE_TOKEN, wigle)
        if (cell != MASK) secrets.put(SecretStore.OPENCELLID_TOKEN, cell)
        scope.launch {
            val client = CloudEnvironmentClient()
            val wigleOk = secrets.get(SecretStore.WIGLE_TOKEN)?.let { client.validateWigle(it) } ?: false
            val cellOk = secrets.get(SecretStore.OPENCELLID_TOKEN)?.let { client.validateOpenCellId(it) } ?: false
            toast("WiGLE ${if (wigleOk) "有效" else "未配置/无效"} · OpenCellID ${if (cellOk) "有效" else "未配置/无效"}")
        }
    }

    private fun fetchCloud(lat: Double, lon: Double, scope: kotlinx.coroutines.CoroutineScope) {
        scope.launch {
            val client = CloudEnvironmentClient()
            val messages = mutableListOf<String>()
            secrets.get(SecretStore.WIGLE_TOKEN)?.let { token ->
                runCatching { client.fetchWigle(lat, lon, token) }
                    .onSuccess { if (it.wifi.isNotEmpty()) { repository.save(it); messages += "Wi-Fi ${it.wifi.size}" } }
                    .onFailure { messages += "WiGLE 失败" }
            }
            secrets.get(SecretStore.OPENCELLID_TOKEN)?.let { token ->
                runCatching { client.fetchOpenCellId(lat, lon, token) }
                    .onSuccess { if (it.cells.isNotEmpty()) { repository.save(it); messages += "基站 ${it.cells.size}" } }
                    .onFailure { messages += "OpenCellID 失败" }
            }
            toast(messages.joinToString(" · ").ifBlank { "没有可用 Token 或返回数据" })
        }
    }

    private fun toast(message: String) = Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()

    companion object { private const val MASK = "••••••" }
}

