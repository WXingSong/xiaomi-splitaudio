package app.splitaudio

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** 下拉控制中心快捷开关磁贴：
 *  - 短按：按需一键开启或关闭音频分流（关闭时停止服务进入 0 CPU 待命）；
 *  - 长按：由系统 QS_TILE_PREFERENCES 拉起 SpeakerPanelActivity（小米控制中心风格手机喇叭音量小窗，含「更多设置」跳转主界面）。 */
class SplitTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        val active = SplitPrefs.isActive(this)
        if (active) {
            MonitorService.stop(this)
            refreshTile()
            return
        }
        // 尚未完成 Shizuku 授权或无线 ADB 配对（且底层未休眠待命）时，打开主界面引导配置
        if (Backends.current() == null) {
            val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
            return
        }
        MonitorService.start(this)
        refreshTile()
    }

    private fun refreshTile() {
        val t = qsTile ?: return
        val active = SplitPrefs.isActive(this)
        t.label = getString(R.string.tile_label)
        if (active) {
            t.state = Tile.STATE_ACTIVE
            t.subtitle = MonitorService.currentStateText.ifBlank { getString(R.string.s_starting) }
        } else {
            t.state = Tile.STATE_INACTIVE
            t.subtitle = getString(R.string.tile_off)
        }
        t.updateTile()
    }

    companion object {
        fun requestUpdate(c: Context) {
            try {
                requestListeningState(c.applicationContext, ComponentName(c.applicationContext, SplitTileService::class.java))
            } catch (_: Throwable) {}
        }
    }
}
