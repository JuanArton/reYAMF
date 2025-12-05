package com.mja.reyamf.manager.sidebar

import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.IBinder
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import androidx.appcompat.view.ContextThemeWrapper
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.graphics.toColorInt
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.kyuubiran.ezxhelper.utils.runOnMainThread
import com.juanarton.batterysense.batterymonitorservice.ServiceState
import com.juanarton.batterysense.batterymonitorservice.setServiceState
import com.mja.reyamf.R
import com.mja.reyamf.common.gson
import com.mja.reyamf.common.model.AppInfo
import com.mja.reyamf.common.runMain
import com.mja.reyamf.databinding.SidebarMenuLayoutBinding
import com.mja.reyamf.manager.adapter.SideBarAdapter
import com.mja.reyamf.manager.adapter.VerticalSpaceItemDecoration
import com.mja.reyamf.manager.applist.AppListWindow
import com.mja.reyamf.manager.core.domain.repository.IReyamfRepository
import com.mja.reyamf.manager.services.YAMFManagerProxy
import com.mja.reyamf.xposed.IAppListCallback
import com.mja.reyamf.xposed.utils.animateAlpha
import com.mja.reyamf.xposed.utils.animateResize
import com.mja.reyamf.xposed.utils.componentName
import com.mja.reyamf.xposed.utils.dpToPx
import com.mja.reyamf.xposed.utils.vibratePhone
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.mja.reyamf.common.model.Config as YAMFConfig

@AndroidEntryPoint
class SidebarMenuService() : LifecycleService() {
    companion object {
        const val TAG = "reYAMF_SideBar"
        const val SERVICE_NOTIFICATION_ID = 1
        const val SERVICE_NOTIF_CHANNEL_ID = "BatteryMonitorChannel"
    }

    @Inject
    lateinit var iReyamfRepository: IReyamfRepository

    lateinit var config: YAMFConfig
    private lateinit var binding: SidebarMenuLayoutBinding
    private lateinit var params : WindowManager.LayoutParams
    private lateinit var windowManager: WindowManager

    var userId = 0
    private lateinit var rvAdapter: SideBarAdapter
    private var orientation = 0
    private var isShown = false
    private var cardBgColor: ColorStateList = ColorStateList.valueOf(Color.WHITE)
    private var colorString = "#FFFFFF"

    private val _showApp: MutableLiveData<List<AppInfo>> = MutableLiveData()
    private val showApp: LiveData<List<AppInfo>> = _showApp

    private var isServiceRunning = false

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onCreate() {
        super.onCreate()

        if (!isServiceRunning) {
            isServiceRunning = true
            initSidebar()
        }
    }

    private fun initSidebar() {
        val themedContext = ContextThemeWrapper(this, R.style.Theme_Reyamf)
        val inflater = LayoutInflater.from(themedContext)
        binding = SidebarMenuLayoutBinding.inflate(inflater)

        config = gson.fromJson(YAMFManagerProxy.configJson, YAMFConfig::class.java)

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )

        params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS

        params.gravity = Gravity.NO_GRAVITY

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager.addView(binding.root, params)

        params.x = if (config.sidebarPosition) 100000 else -100000

        val displayManager = getSystemService(DISPLAY_SERVICE) as DisplayManager
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        val rotation = display?.rotation ?: Surface.ROTATION_0
        params.y = when (rotation) {
            Surface.ROTATION_0, Surface.ROTATION_180 -> {
                orientation = 0
                config.portraitY
            }
            Surface.ROTATION_90, Surface.ROTATION_270 -> {
                orientation = 1
                config.landscapeY
            }
            else -> 0
        }

        cardBgColor = binding.cvSideBarMenu.cardBackgroundColor

        showApp.observe(this) { sidebarApp ->
            val receivedApps = mutableListOf<AppInfo>()
            YAMFManagerProxy.getAppListAsync(object : IAppListCallback.Stub() {
                override fun onAppListReceived(appList: MutableList<AppInfo>) {
                    receivedApps.addAll(appList)
                }

                override fun onAppListFinished() {
                    lifecycleScope.launch(Dispatchers.Main) {
                        val commonApps = sidebarApp.filter { app1 ->
                            receivedApps.any { app2 ->
                                app1.activityInfo.componentName == app2.activityInfo.componentName
                            }
                        }.toMutableList()

                        rvAdapter.setData(commonApps)
                        rvAdapter.notifyDataSetChanged()
                        binding.rvSideBarMenu.scrollToPosition(0)
                        binding.cpiLoading.isVisible = false
                    }
                }
            })
        }

        handleSidebar()
    }

    private fun handleSidebar() {

        binding.ivExtraTool.setOnClickListener {
            hideMenu()
        }

        binding.cvSideBarMenu.post {
            if (config.sidebarPosition) {
                binding.root.layoutDirection = View.LAYOUT_DIRECTION_RTL
                binding.rvSideBarMenu.layoutDirection = View.LAYOUT_DIRECTION_LTR
            }
        }

        binding.ivExtraTool.setOnClickListener {
            animateAlpha(binding.cvExtraTool, 0f, 1f)

            true
        }

        binding.root.setOnClickListener {
            hideMenu()
        }

        binding.ibAppList.setOnClickListener {
            runMain {
                startService(Intent(this@SidebarMenuService, AppListWindow::class.java))
            }
            hideMenu()
        }

        binding.ibCurrentToWindow.setOnClickListener {
            YAMFManagerProxy.currentToWindow()
            hideMenu()
        }

        binding.ibKillSidebar.setOnClickListener {
            try {
                windowManager.removeView(binding.root)
                stopSelf()
            } catch (e: Exception) {
                Log.d("reYAMF", "Sidebar killed")
            }
            setServiceState(this, ServiceState.STOPPED)
        }

        binding.root.addOnLayoutChangeListener {  _, _, _, _, _, _, _, _, _ ->
            var newOrientation = 0
            val displayManager = getSystemService(DISPLAY_SERVICE) as DisplayManager
            val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
            val rotation = display?.rotation ?: Surface.ROTATION_0

            when (rotation) {
                Surface.ROTATION_0, Surface.ROTATION_180 -> {
                    params.y = config.portraitY
                    windowManager.updateViewLayout(binding.root, params)
                    newOrientation = 0
                }
                Surface.ROTATION_90, Surface.ROTATION_270 -> {
                    params.y = config.landscapeY
                    windowManager.updateViewLayout(binding.root, params)
                    newOrientation = 1
                }
            }


            if (orientation != newOrientation) {
                orientation = newOrientation
            }
        }

        val longClickListener: (AppInfo) -> Unit = {
            lifecycleScope.launch(Dispatchers.IO) {
                iReyamfRepository.deleteAppInfo(it)

                runOnMainThread {
                    getSidebarApp()
                }
            }
        }

        val clickListener: (AppInfo) -> Unit = {
            hideMenu()
            lifecycleScope.launch {
                delay(200)
                YAMFManagerProxy.createWindowUserspace(it)
            }
        }

        rvAdapter = SideBarAdapter(clickListener, arrayListOf(), longClickListener)

        binding.rvSideBarMenu.layoutManager = LinearLayoutManager(this@SidebarMenuService)
        binding.rvSideBarMenu.adapter = rvAdapter
        binding.rvSideBarMenu.isVerticalScrollBarEnabled = false

        if (binding.rvSideBarMenu.itemDecorationCount == 0) {
            binding.rvSideBarMenu.addItemDecoration(VerticalSpaceItemDecoration(8.dpToPx().toInt()))
        }

        showMenu()
    }

    private fun showMenu() {
        if (!isShown) {
            isShown = true
            binding.root.elevation = 8.dpToPx()

            binding.cvSideBarMenu.strokeWidth = 1.dpToPx().toInt()
            binding.cvExtraTool.strokeWidth = 1.dpToPx().toInt()

            val layout = binding.llSidebarMenu
            val params1 = layout.layoutParams as ConstraintLayout.LayoutParams
            val position = if (orientation == 0) config.portraitY else config.landscapeY
            params1.topMargin =
                (Resources.getSystem().displayMetrics.heightPixels/2 + position) - 75.dpToPx().toInt()
            layout.layoutParams = params1

            animateResize(
                binding.cvSideBarMenu,
                3.dpToPx().toInt(), 70.dpToPx().toInt(),
                75.dpToPx().toInt(), 350.dpToPx().toInt(), this@SidebarMenuService
            ) {
                binding.sideBarMenu.visibility = View.VISIBLE
                animateAlpha(binding.rvSideBarMenu, 0F, 1F)
                getSidebarApp()
            }
        }
    }

    private fun hideMenu() {
        isShown = false
        vibratePhone(this)
        binding.root.elevation = 0.dpToPx()

        animateAlpha(binding.rvSideBarMenu, 1F, 0F)

        if (binding.cvExtraTool.isVisible) {
            animateAlpha(binding.cvExtraTool, 1f, 0f) {
                animateResize(
                    binding.cvSideBarMenu,
                    70.dpToPx().toInt(), 3.dpToPx().toInt(),
                    350.dpToPx().toInt(), 75.dpToPx().toInt(), this
                ) {
                    binding.sideBarMenu.visibility = View.GONE
                    binding.cvSideBarMenu.setCardBackgroundColor(colorString.toColorInt())
                    binding.cvSideBarMenu.strokeWidth = 0
                    binding.cvSideBarMenu.visibility = View.INVISIBLE

                    binding.cvSideBarMenu.visibility = View.VISIBLE
                    stopService()
                }
            }
        } else {
            animateResize(
                binding.cvSideBarMenu,
                70.dpToPx().toInt(), 3.dpToPx().toInt(),
                350.dpToPx().toInt(), 75.dpToPx().toInt(), this
            ) {
                binding.sideBarMenu.visibility = View.GONE
                binding.cvSideBarMenu.setCardBackgroundColor(colorString.toColorInt())
                binding.cvSideBarMenu.strokeWidth = 0
                binding.cvSideBarMenu.visibility = View.INVISIBLE

                binding.cvSideBarMenu.visibility = View.VISIBLE
                stopService()
            }
        }
    }

    private fun getSidebarApp() {
        binding.cpiLoading.isVisible = true
        lifecycleScope.launch {
            iReyamfRepository.getAppInfoList().collect {
                _showApp.value = it
            }
        }
    }

    private fun stopService() {
        try {
            windowManager.removeView(binding.root)
            stopSelf()
            startService(Intent(this, SidebarService::class.java))
        } catch (e: Exception) {
            Log.d("reYAMF", "Sidebar killed")
        }
        setServiceState(this, ServiceState.STOPPED)
    }
}