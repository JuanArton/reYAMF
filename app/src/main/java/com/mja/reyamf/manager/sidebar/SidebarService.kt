package com.mja.reyamf.manager.sidebar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.Surface
import android.view.WindowManager
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.app.NotificationCompat
import androidx.core.graphics.toColorInt
import com.juanarton.batterysense.batterymonitorservice.ServiceState
import com.juanarton.batterysense.batterymonitorservice.setServiceState
import com.mja.reyamf.R
import com.mja.reyamf.common.gson
import com.mja.reyamf.databinding.SidebarLayoutBinding
import com.mja.reyamf.manager.services.YAMFManagerProxy
import com.mja.reyamf.manager.sidebar.SidebarMenuService.Companion.SERVICE_NOTIFICATION_ID
import com.mja.reyamf.manager.sidebar.SidebarMenuService.Companion.SERVICE_NOTIF_CHANNEL_ID
import com.mja.reyamf.xposed.utils.vibratePhone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.mja.reyamf.common.model.Config as YAMFConfig

class SidebarService : Service() {

    lateinit var config: YAMFConfig
    private lateinit var binding: SidebarLayoutBinding
    private lateinit var params : WindowManager.LayoutParams
    private lateinit var windowManager: WindowManager
    private var isServiceRunning = false

    private var initialX: Int = 0
    private var initialY: Int = 0
    private var initialTouchX: Float = 0.toFloat()
    private var initialTouchY: Float = 0.toFloat()
    private var job: Job? = null
    private var movable = false
    private var swipeX = 0

    var userId = 0
    private var orientation = 0
    private var isShown = false

    private var colorString = "#FFFFFF"

    override fun onBind(intent: Intent): IBinder? {
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
        binding = SidebarLayoutBinding.inflate(inflater)

        config = gson.fromJson(YAMFManagerProxy.configJson, YAMFConfig::class.java)

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
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

        colorString = if (config.sidebarTransparency == 100) {
            "#AAAAAA"
        } else {
            val alpha = (config.sidebarTransparency * 255 / 100)
            val alphaHex = String.format("%02X", alpha)
            "#${alphaHex}AAAAAA"
        }

        handleSidebar()

        setServiceState(this, ServiceState.STARTED)
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
//            // Android 14 / API 34 ke atas, wajib serviceType
//            startForeground(
//                SERVICE_NOTIFICATION_ID,
//                createNotification(),
//                ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
//            )
//        } else {
//            // Android 13 ke bawah, cukup notification saja
//            startForeground(
//                SERVICE_NOTIFICATION_ID,
//                createNotification()
//            )
//        }
    }

    private fun handleSidebar() {
        binding.clickMask.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    storeTouchs(event)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    moveSidebar(event)
                    true
                }

                MotionEvent.ACTION_UP -> {
                    openApp(event)
                    v.performClick()
                    true
                }

                else -> false
            }
        }

        binding.rootSidebar.post {
            binding.cvSideBar.setCardBackgroundColor(colorString.toColorInt())
            if (config.sidebarPosition) {
                binding.rootSidebar.scaleX = -1f
            } else {
                binding.rootSidebar.scaleX = 1f
            }
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
    }

    private fun openApp(event: MotionEvent): Boolean {
        job?.cancel()
        movable = false
        if (initialTouchY == event.rawY) {
            vibratePhone(this)
            startService(Intent(this, SidebarMenuService::class.java))
            stopService()
            isShown = false
        }

        if (swipeX > 200) {
            vibratePhone(this)
            startService(Intent(this, SidebarMenuService::class.java))
            stopService()
            isShown = false
            swipeX = 0
        }
        return true
    }

    private fun moveSidebar(event: MotionEvent): Boolean {
        swipeX = event.rawX.toInt()
        params.y = initialY + (event.rawY - initialTouchY).toInt()

        if (movable) {
            windowManager.updateViewLayout(binding.root, params)

            val displayManager = getSystemService(DISPLAY_SERVICE) as DisplayManager
            val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)

            display?.let {
                when (it.rotation) {
                    Surface.ROTATION_0, Surface.ROTATION_180 -> {
                        config.portraitY = params.y
                    }
                    Surface.ROTATION_90, Surface.ROTATION_270 -> {
                        config.landscapeY = params.y
                    }
                }
            }

            YAMFManagerProxy.updateConfig(gson.toJson(config))
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
            delay(200)
            movable = true
            vibratePhone(this@SidebarService)
        }
    }

    private fun createNotification(): Notification {
        val channelId = SERVICE_NOTIF_CHANNEL_ID
        val channel = NotificationChannel(
            channelId,
            "reYAMF Sidebar Service",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Sidebar Service Running")
            .setContentText("reYAMF Sidebar Service")
            .build()
    }

    private fun stopService() {
        try {
            windowManager.removeView(binding.root)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } catch (e: Exception) {
            Log.d("reYAMF", "Sidebar killed")
        }
        setServiceState(this, ServiceState.STOPPED)
    }
}