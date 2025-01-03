package com.mja.reyamf.xposed.services

import android.app.ActivityManagerHidden
import android.content.AttributionSource
import android.content.pm.IPackageManager
import android.os.Bundle
import android.os.ServiceManager
import com.mja.reyamf.BuildConfig
import com.mja.reyamf.xposed.utils.log
import rikka.hidden.compat.ActivityManagerApis
import rikka.hidden.compat.adapter.UidObserverAdapter

object UserService {
    const val TAG = "reYAMFUserService"
    const val PROVIDER_AUTHORITY = "com.mja.reyamf.ServiceProvider"

    private var appUid = -1

    private val uidObserver = object : UidObserverAdapter() {
        override fun onUidActive(uid: Int) {
            if (uid != appUid) return
            try {
                val provider = ActivityManagerApis.getContentProviderExternal(
                    PROVIDER_AUTHORITY,
                    0,
                    null,
                    null
                )
                if (provider == null) {
                    log(TAG, "Failed to get content provider")
                    return
                }
                val extras = Bundle()
                extras.putBinder("binder", YAMFManager)
                val attr = AttributionSource.Builder(1000).setPackageName("android").build()
                val reply = provider.call(attr, PROVIDER_AUTHORITY, "", null, extras)
                if (reply == null) {
                    log(TAG, "Failed to send binder to app")
                    return
                }
                log(TAG, "Send binder to app")
            } catch (e: Throwable) {
                log(TAG, "Failed to send binder to app", e)
            }
        }
    }

    fun register(pms: IPackageManager) {
        log(TAG, "Init YAMFService")
        appUid =
            pms.getPackageUid(BuildConfig.APPLICATION_ID, 0L, 0)
        log(TAG, "App uid: $appUid")
        log(TAG, "Register uid observer")

        waitSystemService("activity")
        ActivityManagerApis.registerUidObserver(
            uidObserver,
            ActivityManagerHidden.UID_OBSERVER_ACTIVE,
            ActivityManagerHidden.PROCESS_STATE_UNKNOWN,
            null
        )
    }

    private fun waitSystemService(name: String) {
        while (ServiceManager.getService(name) == null) {
            Thread.sleep(1000)
        }
    }
}