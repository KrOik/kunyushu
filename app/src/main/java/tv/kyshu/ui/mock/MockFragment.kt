package tv.kyshu.ui.mock

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckedTextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.kyshu.R
import tv.kyshu.android.root.ShellUtils
import tv.kyshu.android.widget.RockerView
import tv.kyshu.android.window.OverlayUtils
import tv.kyshu.databinding.FragmentMockBinding
import tv.kyshu.ext.altitude
import tv.kyshu.ext.drawOverOtherAppsEnabled
import tv.kyshu.ext.historicalLocations
import tv.kyshu.ext.hookSensor
import tv.kyshu.ext.rawHistoricalLocations
import tv.kyshu.ext.selectLocation
import tv.kyshu.ext.speed
import tv.kyshu.service.MockServiceHelper
import tv.kyshu.xposed.utils.Log
import tv.kyshu.ui.viewmodel.MockServiceViewModel
import tv.kyshu.ui.viewmodel.MockViewModel
import tv.kyshu.xposed.utils.FakeLoc

class MockFragment : Fragment() {
    private var _binding: FragmentMockBinding? = null
    private val binding get() = _binding!!

    private val mockViewModel by lazy { ViewModelProvider(this)[MockViewModel::class.java] }
    private val mockServiceViewModel by activityViewModels<MockServiceViewModel>()

    @SuppressLint("SetTextI18n")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMockBinding.inflate(inflater, container, false)

        binding.fabMockLocation.setOnClickListener {
            Log.buttonOp("Mock", "FAB位置选择")
            if (!OverlayUtils.hasOverlayPermissions(requireContext())) {
                Toast.makeText(requireContext(), "请授权悬浮窗权限", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
        }

        binding.fabMockLocation.setOnLongClickListener {
            Log.buttonOp("Mock", "FAB长按(糸守町)")
            Toast.makeText(requireContext(), "糸守町", Toast.LENGTH_SHORT).show()
            true
        }

        if (mockServiceViewModel.isServiceStart()) {
            binding.switchMock.text = "停止模拟"
            ContextCompat.getDrawable(requireContext(), R.drawable.rounded_play_disabled_24)?.let {
                binding.switchMock.icon = it
            }
        }

        binding.switchMock.setOnClickListener {
            val isStarting = !mockServiceViewModel.isServiceStart()
            Log.switchOp("Mock", "模拟启停", isStarting)
            if (mockServiceViewModel.isServiceStart()) {
                showToast("正在停止位置模拟")
                tryCloseService(it as MaterialButton)
            } else {
                showToast("正在启动位置模拟")
                tryOpenService(it as MaterialButton)
            }
        }

        with(mockServiceViewModel) {
            if (rocker.isStart) {
                binding.rocker.toggle()
            }
            binding.rocker.setOnClickListener {
                if (locationManager == null) {
                    Toast.makeText(requireContext(), "定位服务加载异常", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (!isServiceStart()) {
                    Toast.makeText(requireContext(), "请先启动模拟", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val checkedTextView = it as CheckedTextView
                checkedTextView.toggle()
                Log.switchOp("Mock", "摇杆显示", checkedTextView.isChecked)

                if (!requireContext().drawOverOtherAppsEnabled()) {
                    Toast.makeText(requireContext(), "请授权悬浮窗权限", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                lifecycleScope.launch(Dispatchers.Main) {
                    if (checkedTextView.isChecked) {
                        rocker.show()
                        Toast.makeText(requireContext(), "摇杆已显示", Toast.LENGTH_SHORT).show()
                    } else {
                        rocker.hide()
                        rockerCoroutineController.pause()
                        Toast.makeText(requireContext(), "摇杆已隐藏", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            rocker.setRockerListener(object: RockerView.Companion.OnMoveListener {
                override fun onAngle(angle: Double) {
                    MockServiceHelper.setBearing(locationManager ?: return@onAngle, angle)
                    FakeLoc.bearing = angle
                    FakeLoc.hasBearings = true
                }

                override fun onLockChanged(isLocked: Boolean) {
                    isRockerLocked = isLocked
                }

                override fun onFinished() {
                    if (!isRockerLocked) {
                        rockerCoroutineController.pause()
                    }
                }

                override fun onStarted() {
                    rockerCoroutineController.resume()
                }
            })
        }

        requireContext().selectLocation?.let {
            binding.mockLocationName.text = it.name
            binding.mockLocationAddress.text = it.address
            binding.mockLocationLatlon.text = it.lat.toString().take(8) + ", " + it.lon.toString().take(8)
            mockServiceViewModel.selectedLocation = it
        }

        val locations = requireContext().historicalLocations

        binding.mockLocationCard.setOnClickListener {
            Log.buttonOp("Mock", "查看位置信息")
            mockServiceViewModel.locationManager?.let { mgr ->
                val location = MockServiceHelper.getLocation(mgr)
                Toast.makeText(requireContext(), "Location$location, ListenerSize: ${MockServiceHelper.getLocationListenerSize(mgr)}", Toast.LENGTH_SHORT).show()
            }
        }

        // 2024.10.10: sort historical locations
        val historicalLocationAdapter = HistoricalLocationAdapter(locations.sortedBy { it.name }.toMutableList()) { loc, isLongClick ->
            if (isLongClick) {
                Log.buttonOp("Mock", "历史位置长按")
                Toast.makeText(requireContext(), "长按", Toast.LENGTH_SHORT).show()
            } else {
                Log.dialogOp("Mock", "选择位置", "${loc.name}(${loc.lat},${loc.lon})")
                binding.mockLocationName.text = loc.name
                binding.mockLocationAddress.text = loc.address
                binding.mockLocationLatlon.text = loc.lat.toString().take(8) + ", " + loc.lon.toString().take(8)
                mockServiceViewModel.selectedLocation = loc
                requireContext().selectLocation = loc

                mockServiceViewModel.locationManager?.let { mgr ->
                    if (MockServiceHelper.isMockStart(mgr)) {
                        if (MockServiceHelper.setLocation(mgr, loc.lat, loc.lon)) {
                            Toast.makeText(requireContext(), "位置更新成功", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(requireContext(), "更新位置失败", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
        val recyclerView = binding.historicalLocationList
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = historicalLocationAdapter
        ItemTouchHelper(object: ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                return false
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.adapterPosition
                val location = historicalLocationAdapter[position]
                with(requireContext()) {
                    MaterialAlertDialogBuilder(this)
                        .setTitle("删除位置")
                        .setMessage("确定要删除位置(${location.name})吗？")
                        .setPositiveButton("删除") { _, _ ->
                            Log.dialogOp("Mock", "删除位置", location.name)
                            historicalLocationAdapter.removeItem(position)
                            rawHistoricalLocations = rawHistoricalLocations.toMutableSet().apply {
                                removeIf { it.split(",")[0] == location.name }
                            }
                            showToast("已删除位置")
                        }
                }
            }
        }).attachToRecyclerView(recyclerView)

        return binding.root
    }

    private fun tryOpenService(button: MaterialButton) {
        if (!OverlayUtils.hasOverlayPermissions(requireContext())) {
            showToast("请授权悬浮窗权限")
            return
        }

        val selectedLocation = mockServiceViewModel.selectedLocation ?: run {
            showToast("请选择一个位置")
            return
        }

        if (mockServiceViewModel.locationManager == null) {
            showToast("定位服务加载异常")
            return
        }

        if (!MockServiceHelper.isServiceInit()) {
            showToast("系统服务注入失败")
            return
        }

        lifecycleScope.launch {
            val context = requireContext()
            val speed = context.speed
            val altitude = context.altitude
            val accuracy = FakeLoc.accuracy

            button.isClickable = false
            try {
                withContext(Dispatchers.IO) {
                    val mgr = mockServiceViewModel.locationManager ?: run {
                        showToast("定位服务加载异常")
                        return@withContext
                    }
                    if (MockServiceHelper.tryOpenMock(mgr, speed, altitude, accuracy)) {
                        updateMockButtonState(button, "停止模拟", R.drawable.rounded_play_disabled_24)
                    } else {
                        showToast("模拟服务启动失败")
                        return@withContext
                    }

                    if (!MockServiceHelper.setLocation(mgr, selectedLocation.lat, selectedLocation.lon)) {
                        showToast("更新位置失败")
                        return@withContext
                    }

                    if (MockServiceHelper.broadcastLocation(mgr)) {
                        showToast("更新位置成功")
                    } else {
                        showToast("更新位置失败")
                    }
                }
            } finally {
                button.isClickable = true
            }
        }


    }

    private fun tryCloseService(button: MaterialButton) {
        if (mockServiceViewModel.locationManager == null) {
            showToast("定位服务加载异常")
            return
        }

        if (!MockServiceHelper.isServiceInit()) {
            showToast("系统服务注入失败")
            return
        }

        lifecycleScope.launch {
            button.isClickable = false
            try {
                val isClosed = withContext(Dispatchers.IO) {
                    val mgr = mockServiceViewModel.locationManager ?: return@withContext false
                    if (!MockServiceHelper.isMockStart(mgr)) {
                        showToast("模拟服务未启动")
                        return@withContext false
                    }

                    if (MockServiceHelper.tryCloseMock(mgr)) {
                        updateMockButtonState(button, "开始模拟", R.drawable.rounded_play_arrow_24)
                        return@withContext true
                    } else {
                        showToast("模拟服务停止失败")
                        return@withContext false
                    }
                }
                if (isClosed && mockServiceViewModel.rocker.isStart) {
                    binding.rocker.isClickable = false
                    binding.rocker.toggle()
                    mockServiceViewModel.rocker.hide()
                    mockServiceViewModel.rockerCoroutineController.pause()
                    binding.rocker.isClickable = true
                }
                if (isClosed) showToast("模拟服务已停止")
            } finally {
                button.isClickable = true
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun showToast(message: String) {
        val ctx = requireContext().applicationContext
        lifecycleScope.launch(Dispatchers.Main) {
            Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateMockButtonState(button: MaterialButton, text: String, iconRes: Int) {
        val ctx = requireContext().applicationContext
        lifecycleScope.launch(Dispatchers.Main) {
            button.text = text
            ContextCompat.getDrawable(ctx, iconRes)?.let {
                button.icon = it
            }
        }
    }
} //
