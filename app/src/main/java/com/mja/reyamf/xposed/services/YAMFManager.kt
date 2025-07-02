package com.mja.reyamf.xposed.services

import android.annotation.SuppressLint
import android.app.AndroidAppHelper
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.IPackageManagerHidden
import android.content.pm.PackageManagerHidden
import android.content.pm.UserInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import com.github.kyuubiran.ezxhelper.utils.argTypes
import com.github.kyuubiran.ezxhelper.utils.args
import com.github.kyuubiran.ezxhelper.utils.invokeMethod
import com.github.kyuubiran.ezxhelper.utils.invokeMethodAs
import com.mja.reyamf.BuildConfig
import com.mja.reyamf.common.gson
import com.mja.reyamf.common.model.AppInfo
import com.mja.reyamf.common.model.Config
import com.mja.reyamf.common.model.StartCmd
import com.mja.reyamf.common.runMain
import com.mja.reyamf.xposed.IAppIconCallback
import com.mja.reyamf.xposed.IAppListCallback
import com.mja.reyamf.xposed.IOpenCountListener
import com.mja.reyamf.xposed.IYAMFManager
import com.mja.reyamf.xposed.hook.HookLauncher
import com.mja.reyamf.xposed.ui.window.AppWindow
import com.mja.reyamf.xposed.utils.Instances
import com.mja.reyamf.xposed.utils.Instances.systemContext
import com.mja.reyamf.xposed.utils.Instances.systemUiContext
import com.mja.reyamf.xposed.utils.componentName
import com.mja.reyamf.xposed.utils.createContext
import com.mja.reyamf.xposed.utils.getActivityInfoCompat
import com.mja.reyamf.xposed.utils.getTopRootTask
import com.mja.reyamf.xposed.utils.log
import com.mja.reyamf.xposed.utils.registerReceiver
import com.mja.reyamf.xposed.utils.startAuto
import com.qauxv.ui.CommonContextWrapper
import rikka.hidden.compat.ActivityManagerApis
import java.io.ByteArrayOutputStream
import java.io.File


object YAMFManager : IYAMFManager.Stub() {
    private const val TAG = "reYAMFManager"

    const val ACTION_GET_LAUNCHER_CONFIG = "com.mja.reyamf.ACTION_GET_LAUNCHER_CONFIG"
    const val ACTION_OPEN_APP = "com.mja.reyamf.action.OPEN_APP"
    private const val ACTION_CURRENT_TO_WINDOW = "com.mja.reyamf.action.CURRENT_TO_WINDOW"
    private const val ACTION_OPEN_APP_LIST = "com.mja.reyamf.action.OPEN_APP_LIST"
    const val ACTION_OPEN_IN_YAMF = "com.mja.reyamf.ACTION_OPEN_IN_YAMF"

    const val EXTRA_COMPONENT_NAME = "componentName"
    const val EXTRA_USER_ID = "userId"
    const val EXTRA_TASK_ID = "taskId"
    const val EXTRA_SOURCE = "source"

    private const val SOURCE_UNSPECIFIED = 0
    const val SOURCE_RECENT = 1
    const val SOURCE_TASKBAR = 2
    const val SOURCE_POPUP = 3

    private val windowList = mutableListOf<Int>()
    lateinit var config: Config
    val configFile = File("/data/system/reYAMF.json")
    private var openWindowCount = 0
    private val iOpenCountListenerSet = mutableSetOf<IOpenCountListener>()
    lateinit var activityManagerService: Any
    private val listeners = mutableListOf<TopDisplayId>()
    var currentDisplayId = 0

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun systemReady() {
        Instances.init(activityManagerService)
        systemContext.registerReceiver(ACTION_OPEN_IN_YAMF, OpenInYAMFBroadcastReceiver)
        systemContext.registerReceiver(ACTION_CURRENT_TO_WINDOW) { _, _ ->
            currentToWindow()
        }
        systemContext.registerReceiver(ACTION_OPEN_APP_LIST) { _, _ ->
            val componentName = ComponentName("com.mja.reyamf", "com.mja.reyamf.manager.applist.AppListWindow")
            val intent = Intent().setComponent(componentName)
            AndroidAppHelper.currentApplication().startService(intent)
        }
        systemContext.registerReceiver(ACTION_OPEN_APP) { _, intent ->
            val componentName = intent.getParcelableExtra<ComponentName>(EXTRA_COMPONENT_NAME)
                ?: return@registerReceiver
            val userId = intent.getIntExtra(EXTRA_USER_ID, 0)
            createWindow(StartCmd(componentName = componentName, userId = userId))
        }
        systemContext.registerReceiver(ACTION_GET_LAUNCHER_CONFIG) { _, intent ->
            ActivityManagerApis.broadcastIntent(Intent(HookLauncher.ACTION_RECEIVE_LAUNCHER_CONFIG).apply {
                log(TAG, "send config: ${config.hookLauncher}")
                putExtra(HookLauncher.EXTRA_HOOK_RECENT, config.hookLauncher.hookRecents)
                putExtra(HookLauncher.EXTRA_HOOK_TASKBAR, config.hookLauncher.hookTaskbar)
                putExtra(HookLauncher.EXTRA_HOOK_POPUP, config.hookLauncher.hookPopup)
                putExtra(HookLauncher.EXTRA_HOOK_TRANSIENT_TASKBAR, config.hookLauncher.hookTransientTaskbar)
                `package` = intent.getStringExtra("sender")
            }, 0)
        }

        configFile.createNewFile()
        config = runCatching {
            gson.fromJson(configFile.readText(), Config::class.java)
        }.getOrNull() ?: Config()
        log(TAG, "config: $config")
    }

    private fun addWindow(id: Int) {
        windowList.add(0, id)
        openWindowCount++
        val toRemove = mutableSetOf<IOpenCountListener>()
        iOpenCountListenerSet.forEach {
            runCatching {
                it.onUpdate(openWindowCount)
            }.onFailure { _ ->
                toRemove.add(it)
            }
        }
        iOpenCountListenerSet.removeAll(toRemove)
    }

    fun removeWindow(id: Int) {
        windowList.remove(id)
    }

    fun isTop(id: Int) = if (windowList.isNotEmpty()) windowList[0] == id else true

    fun moveToTop(id: Int) {
        windowList.remove(id)
        windowList.add(0, id)
    }

    fun createWindow(startCmd: StartCmd?) {
        Instances.iStatusBarService.collapsePanels()
        AppWindow(
            CommonContextWrapper.createAppCompatContext(systemUiContext.createContext()),
            config.flags
        ) { displayId ->
            addWindow(displayId)
            startCmd?.startAuto(displayId)
        }
    }

    init {
        log(TAG, "reYAMF service initialized")
    }

    override fun getVersionName(): String {
        return BuildConfig.VERSION_NAME
    }

    override fun getVersionCode(): Int {
        return BuildConfig.VERSION_CODE
    }

    override fun getUid(): Int {
        return Process.myUid()
    }

    override fun createWindow() {
        runMain {
            createWindow(null)
        }
    }

    override fun getBuildTime(): Long {
        return BuildConfig.BUILD_TIME
    }

    override fun getConfigJson(): String {
        return gson.toJson(config)
    }

    override fun updateConfig(newConfig: String) {
        config = gson.fromJson(newConfig, Config::class.java)
        runMain {
            configFile.writeText(newConfig)
            Log.d(TAG, "updateConfig: $config")
        }
    }

    override fun registerOpenCountListener(iOpenCountListener: IOpenCountListener) {
        iOpenCountListenerSet.add(iOpenCountListener)
        iOpenCountListener.onUpdate(openWindowCount)
    }

    override fun unregisterOpenCountListener(iOpenCountListener: IOpenCountListener?) {
        iOpenCountListenerSet.remove(iOpenCountListener)
    }

    override fun currentToWindow() {
        runMain {
            val task = getTopRootTask(0) ?: return@runMain

            if (task.baseActivity?.packageName != "com.android.launcher3") {
                createWindow(StartCmd(taskId = task.taskId))
            }
        }
    }

    override fun resetAllWindow() {
        runMain {
            Instances.iStatusBarService.collapsePanels()
            systemContext.sendBroadcast(Intent(AppWindow.ACTION_RESET_ALL_WINDOW))
        }
    }

    override fun getAppList(): List<AppInfo?>? {
        return listOf()
    }

    override fun createWindowUserspace(appInfo: AppInfo?) {
        runMain {
            appInfo?.let {
                createWindow(StartCmd(it.activityInfo.componentName, it.userId))
            }
        }
    }

    override fun getAppListAsync(callback: IAppListCallback) {
        runMain {
            var apps: List<ActivityInfo>
            val showApps: MutableList<AppInfo> = mutableListOf()
            val users = mutableMapOf<Int, String>()
            Instances.userManager.invokeMethodAs<List<UserInfo>>(
                "getUsers",
                args(true, true, true),
                argTypes(java.lang.Boolean.TYPE, java.lang.Boolean.TYPE, java.lang.Boolean.TYPE)
            )!!
                .filter { it.isProfile || it.isPrimary }
                .forEach {
                    users[it.id] = it.name
                }

            users.forEach { usr ->
                apps = (Instances.packageManager as PackageManagerHidden).queryIntentActivitiesAsUser(
                    Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_LAUNCHER)
                    }, 0, usr.key
                ).map {
                    (Instances.iPackageManager as IPackageManagerHidden).getActivityInfoCompat(
                        ComponentName(it.activityInfo.packageName, it.activityInfo.name),
                        0, usr.key
                    )
                }

                apps.forEach { activityInfo ->
                    showApps.add(
                        AppInfo(
                            activityInfo, usr.key, usr.value
                        )
                    )
                }
            }

            showApps.chunked(5).forEach { chunk ->
                callback.onAppListReceived(chunk.toMutableList())
            }

            callback.onAppListFinished()
        }
    }

    //Might be useful in the future
    override fun getAppIcon(callback: IAppIconCallback, appInfo: AppInfo) {
        runMain {
            val drawable = appInfo.activityInfo.loadIcon(Instances.packageManager)

            val bitmap = when (drawable) {
                is BitmapDrawable -> drawable.bitmap
                is AdaptiveIconDrawable -> {
                    val size = 108
                    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    drawable.setBounds(0, 0, canvas.width, canvas.height)
                    drawable.draw(canvas)
                    bitmap
                }
                else -> {
                    val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 1
                    val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 1
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    drawable.setBounds(0, 0, canvas.width, canvas.height)
                    drawable.draw(canvas)
                    bitmap
                }
            }

            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            val byteArray = stream.toByteArray()
            callback.onResult(byteArray)
        }
    }

    private val OpenInYAMFBroadcastReceiver: BroadcastReceiver.(Context, Intent) -> Unit =
        { _: Context, intent: Intent ->
            val taskId = intent.getIntExtra(EXTRA_TASK_ID, 0)
            val componentName =
                intent.getParcelableExtra(EXTRA_COMPONENT_NAME, ComponentName::class.java)
            val userId = intent.getIntExtra(EXTRA_USER_ID, 0)
            val source = intent.getIntExtra(EXTRA_SOURCE, SOURCE_UNSPECIFIED)
            createWindow(StartCmd(componentName, userId, taskId))

            // TODO: better way to close recents
            if (source == SOURCE_RECENT && config.recentBackHome) {
                val down = KeyEvent(
                    SystemClock.uptimeMillis(),
                    SystemClock.uptimeMillis(),
                    KeyEvent.ACTION_DOWN,
                    KeyEvent.KEYCODE_HOME,
                    0
                ).apply {
                    this.source = InputDevice.SOURCE_KEYBOARD
                    this.invokeMethod("setDisplayId", args(0), argTypes(Integer.TYPE))
                }
                Instances.inputManager.injectInputEvent(down, 0)
                val up = KeyEvent(
                    SystemClock.uptimeMillis(),
                    SystemClock.uptimeMillis(),
                    KeyEvent.ACTION_UP,
                    KeyEvent.KEYCODE_HOME,
                    0
                ).apply {
                    this.source = InputDevice.SOURCE_KEYBOARD
                    this.invokeMethod("setDisplayId", args(0), argTypes(Integer.TYPE))
                }
                Instances.inputManager.injectInputEvent(up, 0)
            }
        }
}