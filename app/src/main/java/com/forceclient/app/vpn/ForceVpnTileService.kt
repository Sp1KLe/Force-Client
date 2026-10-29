package com.forceclient.app.vpn

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.forceclient.app.R

class ForceVpnTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        syncTile()
    }

    override fun onClick() {
        super.onClick()
        val prefs = getSharedPreferences("forcec_preferences", MODE_PRIVATE)
        val active = prefs.getBoolean("vpn_active", false)
        val action = if (active) ForceVpnService.ACTION_STOP else ForceVpnService.ACTION_START
        if (!active && VpnService.prepare(this) != null) {
            qsTile?.state = Tile.STATE_INACTIVE
            qsTile?.label = "ForceCLIENT"
            qsTile?.updateTile()
            startActivityAndCollapse(Intent(this, com.forceclient.app.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        val intent = Intent(this, ForceVpnService::class.java).setAction(action)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        qsTile?.state = if (active) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        qsTile?.label = if (active) "ForceCLIENT · выкл." else "ForceCLIENT · вкл."
        qsTile?.updateTile()
    }

    private fun syncTile() {
        val active = getSharedPreferences("forcec_preferences", MODE_PRIVATE).getBoolean("vpn_active", false)
        qsTile?.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        qsTile?.label = if (active) "ForceCLIENT · вкл." else "ForceCLIENT"
        qsTile?.updateTile()
    }
}
