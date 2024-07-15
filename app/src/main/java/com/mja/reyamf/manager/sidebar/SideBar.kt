package com.mja.reyamf.manager.sidebar

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.IPackageManagerHidden
import android.content.pm.PackageManagerHidden
import android.content.pm.UserInfo
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.WindowManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.kyuubiran.ezxhelper.utils.argTypes
import com.github.kyuubiran.ezxhelper.utils.args
import com.github.kyuubiran.ezxhelper.utils.invokeMethodAs
import com.mja.reyamf.common.model.AppInfo
import com.mja.reyamf.databinding.SidebarLayoutBinding
import com.mja.reyamf.common.gson
import com.mja.reyamf.common.model.StartCmd
import com.mja.reyamf.common.onException
import com.mja.reyamf.common.resetAdapter
import com.mja.reyamf.common.runIO
import com.mja.reyamf.common.runMain
import com.mja.reyamf.manager.adapter.SideBarAdapter
import com.mja.reyamf.xposed.ui.window.AppListWindow
import com.mja.reyamf.xposed.utils.AppInfoCache
import com.mja.reyamf.xposed.utils.Instances
import com.mja.reyamf.xposed.utils.Instances.systemUiContext
import com.mja.reyamf.xposed.utils.componentName
import com.mja.reyamf.xposed.utils.createContext
import com.mja.reyamf.xposed.utils.getActivityInfoCompat
import com.mja.reyamf.xposed.utils.log
import com.mja.reyamf.xposed.utils.startActivity
import com.mja.reyamf.xposed.utils.vibratePhone
import com.qauxv.ui.CommonContextWrapper
import com.mja.reyamf.manager.utils.TipUtil
import com.mja.reyamf.xposed.services.YAMFManager
import com.mja.reyamf.xposed.services.YAMFManager.config
import com.mja.reyamf.xposed.services.YAMFManager.isSideBarRun
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

@SuppressLint("ClickableViewAccessibility")
class SideBar(val context: Context, private val displayId: Int? = null) {
    companion object {
        const val TAG = "reYAMF_SideBar"
    }

    private var windowManager = Instances.windowManager
    private var initialX: Int = 0
    private var initialY: Int = 0
    private var initialTouchX: Float = 0.toFloat()
    private var initialTouchY: Float = 0.toFloat()
    private lateinit var params : WindowManager.LayoutParams
    private var job: Job? = null
    private var movable = false
    private var swipeX = 0

    private lateinit var binding: SidebarLayoutBinding
    private val users = mutableMapOf<Int, String>()
    var userId = 0
    private var apps = emptyList<ActivityInfo>()
    private var showApps: MutableList<AppInfo> = mutableListOf()
    private var filteredShowApps: MutableList<AppInfo> = mutableListOf()
    private lateinit var rvAdapter: SideBarAdapter

    init {
        if (!isSideBarRun) {
            runCatching {
                binding = SidebarLayoutBinding.inflate(LayoutInflater.from(context))
            }.onException { e ->
                Log.e(AppListWindow.TAG, "new app list failed: ", e)
                TipUtil.showToast("new app list failed\nmay you forget reboot")
            }.onSuccess {
                doInit()
            }
        }
    }

    private fun doInit() {
        isSideBarRun = true
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        params.gravity = Gravity.NO_GRAVITY
        params.x = -100000
        params.y = when (windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_0, Surface.ROTATION_180 -> {
                log(TAG, "portrait ${config.portraitY}")
                config.portraitY
            }
            Surface.ROTATION_90, Surface.ROTATION_270 -> {
                log(TAG, "landscape ${config.landscapeY}")
                config.landscapeY
            }
            else -> 0
        }


        binding.root.let { layout ->
            windowManager.addView(layout, params)
        }

        binding.closeClickMask.setOnClickListener {
            hideMenu()
        }
        binding.closeBarClickMask.setOnClickListener {
            isSideBarRun = false
            Instances.windowManager.removeView(binding.root)
        }

        binding.restartBarClickMask.setOnClickListener {
            isSideBarRun = false
            YAMFManager.restartSideBar(binding.root)
        }

        binding.ivAllApp.setOnClickListener {
            runMain {
                Instances.iStatusBarService.collapsePanels()
                AppListWindow(
                    CommonContextWrapper.createAppCompatContext(systemUiContext.createContext()),
                    null
                )
            }
            hideMenu()
        }

        binding.clickMask.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN ->  storeTouchs(event)

                MotionEvent.ACTION_MOVE ->  moveBubble(event)

                MotionEvent.ACTION_UP   ->  openApp(event)

                else ->  false
            }
        }

        binding.root.addOnLayoutChangeListener {  _, _, _, _, _, _, _, _, _ ->
            when (windowManager.defaultDisplay.rotation) {
                Surface.ROTATION_0, Surface.ROTATION_180 -> {
                    params.y = config.portraitY
                    windowManager.updateViewLayout(binding.root, params)
                    log(TAG, "Portrait")
                }

                Surface.ROTATION_90, Surface.ROTATION_270 -> {
                    params.y = config.landscapeY
                    windowManager.updateViewLayout(binding.root, params)
                    log(TAG, "Landscape")
                }
            }
        }

        Instances.sideBarLayout = binding.root
        val clickListener: (AppInfo) -> Unit = {
            if (displayId == null)
                YAMFManager.createWindow(StartCmd(it.componentName, it.userId))
            else
                startActivity(context, it.componentName, it.userId, displayId)
            hideMenu()
        }

        val longClickListener: (Int) -> Unit = {
            config.favApps.removeAt(it)
            YAMFManager.sideBarUpdateConfig(gson.toJson(config))

            runIO {
                filterApp()

                runMain {
                    rvAdapter.setData(filteredShowApps)
                    rvAdapter.notifyDataSetChanged()
                }
            }
        }

        rvAdapter = SideBarAdapter(clickListener, arrayListOf(), longClickListener)
        getAppList()
    }

    private fun openApp(event: MotionEvent): Boolean {
        job?.cancel()
        movable = false
        if (initialTouchY == event.rawY) {
            showMenu()
            vibratePhone(context)
        }

        if (swipeX > 200) {
            showMenu()
            swipeX = 0
            vibratePhone(context)
        }
        return true
    }

    private fun moveBubble(event: MotionEvent): Boolean {
        swipeX = event.rawX.toInt()
        params.y = initialY + (event.rawY - initialTouchY).toInt()

        if (movable) {
            windowManager.updateViewLayout(binding.root, params)

            when (windowManager.defaultDisplay.rotation) {
                Surface.ROTATION_0, Surface.ROTATION_180 -> {
                    config.portraitY = params.y
                    log(TAG, "Portrait")
                }

                Surface.ROTATION_90, Surface.ROTATION_270 -> {
                    config.landscapeY = params.y
                    log(TAG, "Landscape")
                }
            }

            log(TAG, config.toString())
            YAMFManager.sideBarUpdateConfig(gson.toJson(config))
            log(TAG, config.toString())
        }
        return true
    }


    private fun storeTouchs(event: MotionEvent): Boolean {
        startCounter()
        initialX = params.x
        initialY = params.y
        initialTouchX = (event.rawX)
        initialTouchY = (event.rawY)
        return true
    }

    private fun startCounter() {
        job = CoroutineScope(Dispatchers.IO).launch {
            delay(500)
            movable = true
            vibratePhone(context)
        }
    }

    private fun showMenu() {
        runIO {
            filterApp()

            runMain {
                rvAdapter.setData(filteredShowApps)
                rvAdapter.notifyDataSetChanged()
            }
        }

        binding.sideBarImage.visibility = View.INVISIBLE
        binding.sideBarMenu.visibility = View.VISIBLE
        binding.closeLayout.visibility = View.VISIBLE
        binding.closeSidebar.visibility = View.VISIBLE
        binding.restartSidebar.visibility = View.VISIBLE
    }

    private fun hideMenu() {
        binding.sideBarImage.visibility = View.VISIBLE
        binding.sideBarMenu.visibility = View.GONE
        binding.closeLayout.visibility = View.GONE
        binding.closeSidebar.visibility = View.GONE
        binding.restartSidebar.visibility = View.GONE
    }

    private fun getAppList() {
        runIO {
            Instances.userManager.invokeMethodAs<List<UserInfo>>(
                "getUsers",
                args(true, true, true),
                argTypes(java.lang.Boolean.TYPE, java.lang.Boolean.TYPE, java.lang.Boolean.TYPE)
            )!!
                .filter { it.isProfile || it.isPrimary }
                .forEach {
                    users[it.id] = it.name
                }

            for ((key, _) in users) {
                getAppListByID(key)
            }

            runMain {
                filterApp()
                binding.rvSideBarMenu.layoutManager = LinearLayoutManager(context)
                binding.rvSideBarMenu.adapter = rvAdapter
                rvAdapter.setData(filteredShowApps)
                rvAdapter.notifyDataSetChanged()
            }
        }
    }

    private fun getAppListByID(uId: Int): List<ActivityInfo> {
        val apps = (Instances.packageManager as PackageManagerHidden).queryIntentActivitiesAsUser(
            Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }, 0, uId
        ).map {
            (Instances.iPackageManager as IPackageManagerHidden).getActivityInfoCompat(
                ComponentName(it.activityInfo.packageName, it.activityInfo.name),
                0, uId
            )
        }
        apps.forEach { activityInfo ->
            val appInfoCache = AppInfoCache.getIconLabel(activityInfo)
            showApps.add(
                AppInfo(
                    0, appInfoCache.first, appInfoCache.second, activityInfo.componentName, uId
                )
            )
        }

        this.apps = listOf(this.apps, apps).flatten()

        return apps
    }

    private fun filterApp() {
        runMain {
            binding.cpiLoading.visibility = View.VISIBLE
        }
        filteredShowApps.clear()

        for (i in showApps.indices) {
            for (j in config.favApps.indices) {
                if (
                    showApps[i].componentName.packageName == config.favApps[j].packageName &&
                    showApps[i].userId == config.favApps[j].userId
                ) {
                    filteredShowApps.add(
                        showApps[i]
                    )
                }
            }
        }
        filteredShowApps.sortBy { it.label.toString().lowercase(Locale.ROOT) }

        runMain {
            binding.cpiLoading.visibility = View.GONE
            if (filteredShowApps.isEmpty()) {
                binding.tvEmpty.visibility = View.VISIBLE
            } else {
                binding.tvEmpty.visibility = View.INVISIBLE
            }
        }
    }
}