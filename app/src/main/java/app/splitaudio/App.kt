package app.splitaudio

import android.app.Application
import app.splitaudio.adb.Adb
import app.splitaudio.adb.Pairing

/** 进程级初始化：初始化内置无线 ADB 管理器并清理旧版弹窗提醒渠道（纯离线，无任何崩溃/统计上报） */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Adb.init(this)
        Pairing.clearAlerts(this)
    }
}
