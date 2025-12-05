package com.mja.reyamf.manager.services

import android.content.Intent
import android.service.quicksettings.TileService
import androidx.preference.PreferenceManager
import com.mja.reyamf.manager.applist.AppListWindow

class QSNewWindowService : TileService() {
    override fun onClick() {
        super.onClick()
        if (PreferenceManager.getDefaultSharedPreferences(this).getBoolean("useAppList", true)) {
            YAMFManagerProxy.collapseStatusBarPanel()
            startService(Intent(this, AppListWindow::class.java))
        }
        else YAMFManagerProxy.createWindow()
    }
}