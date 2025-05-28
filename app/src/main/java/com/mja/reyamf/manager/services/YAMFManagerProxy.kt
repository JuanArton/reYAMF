package com.mja.reyamf.manager.services

import android.os.IBinder
import android.os.IBinder.DeathRecipient
import android.util.Log
import com.mja.reyamf.common.model.AppInfo
import com.mja.reyamf.xposed.IAppIconCallback
import com.mja.reyamf.xposed.IAppListCallback
import com.mja.reyamf.xposed.IOpenCountListener
import com.mja.reyamf.xposed.IYAMFManager
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

object YAMFManagerProxy : IYAMFManager, DeathRecipient {
    private const val TAG = "reYAMFManagerProxy"

    private class ServiceProxy(private val obj: IYAMFManager) : InvocationHandler {
        override fun invoke(proxy: Any?, method: Method, args: Array<out Any?>?): Any? {
            val result = method.invoke(obj, *args.orEmpty())
            if (result == null) Log.i(TAG, "Call service method ${method.name}")
            else Log.i(
                TAG,
                "Call service method ${method.name} with result " + result.toString().take(20)
            )
            return result
        }
    }

    @Volatile
    private var service: IYAMFManager? = null

    fun linkService(binder: IBinder) {
        service = Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(IYAMFManager::class.java),
            ServiceProxy(IYAMFManager.Stub.asInterface(binder))
        ) as IYAMFManager
        binder.linkToDeath(this, 0)
    }

    override fun binderDied() {
        service = null
        Log.e(TAG, "Binder died")
    }

    override fun asBinder() = service?.asBinder()

    override fun getVersionName(): String? {
        return service?.versionName
    }

    override fun getVersionCode() = service?.versionCode ?: 0

    override fun getUid() = service?.uid ?: -1

    override fun createWindow() {
        service?.createWindow()
    }

    override fun getBuildTime(): Long {
        return service?.buildTime ?: 0
    }

    override fun getConfigJson(): String {
        return service?.configJson ?: "{}"
    }

    override fun updateConfig(newConfig: String) {
        service?.updateConfig(newConfig)
    }

    override fun registerOpenCountListener(iOpenCountListener: IOpenCountListener) {
        service?.registerOpenCountListener(iOpenCountListener)
    }

    override fun unregisterOpenCountListener(iOpenCountListener: IOpenCountListener) {
        service?.unregisterOpenCountListener(iOpenCountListener)
    }

    override fun currentToWindow() {
        service?.currentToWindow()
    }

    override fun resetAllWindow() {
        service?.resetAllWindow()
    }

    override fun getAppList(): List<AppInfo?>? {
        return service?.getAppList()
    }

    override fun createWindowUserspace(appInfo: AppInfo?) {
        service?.createWindowUserspace(appInfo)
    }

    override fun getAppListAsync(callback: IAppListCallback) {
        service?.getAppListAsync(callback)
    }

    override fun getAppIcon(callback: IAppIconCallback, appInfo: AppInfo) {
        service?.getAppIcon(callback, appInfo)
    }
}