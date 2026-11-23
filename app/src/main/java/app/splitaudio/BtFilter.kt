package app.splitaudio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import java.util.Locale

/** 特定蓝牙音箱白名单（持久化到 SharedPreferences "bt_filter"）。
 *  - 未选择任何设备（空集合）= 不限制，所有蓝牙音频输出均可触发分流；
 *  - 已选择特定蓝牙音箱 = 仅当当前活跃/已连接的蓝牙输出命中白名单时才分流，连蓝牙耳机等其他设备绝不误分流。
 *  - 同步写入 /data/local/tmp/approute.btfilter（每行 "MAC|名称"），供 AppRoute 热重载。 */
object BtFilter {
    data class Entry(val mac: String, val name: String) {
        /** 规范化后的唯一存储键："MAC|NAME" */
        fun serialize(): String = "${normalizeMac(mac)}|${cleanName(name)}"
        fun displayName(): String = name.ifBlank { mac }
    }

    sealed class Status {
        /** 当前未连接任何蓝牙媒体设备 */
        object None : Status()
        /** 当前命中白名单目标蓝牙设备（或未设白名单时连着任意蓝牙） */
        data class Matched(val label: String) : Status()
        /** 连着蓝牙设备（如蓝牙耳机），但不在白名单中 */
        data class Other(val label: String) : Status()
        /** 设定了白名单但缺少 BLUETOOTH_CONNECT 权限，无法在 App 侧识别设备，交由拥有 shell 权限的 AppRoute 终裁 */
        object NeedsRouterCheck : Status()
    }

    private const val KEY_ENTRIES = "entries"
    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("bt_filter", Context.MODE_PRIVATE)

    fun hasConnectPermission(c: Context): Boolean =
        c.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** 纯逻辑：规范化 MAC 地址（转大写，过滤无冒号及 02:00:00:00:00:00 / 00:00:00:00:00:00 占位地址） */
    fun normalizeMac(raw: String?): String {
        val m = raw?.trim()?.uppercase(Locale.ROOT).orEmpty()
        if (m.isEmpty() || ':' !in m || m == "02:00:00:00:00:00" || m == "00:00:00:00:00:00") return ""
        return m.replace("\r", "").replace("\n", "").replace("|", "")
    }

    /** 纯逻辑：清理设备显示名称（去掉换行与分隔符 |，避免破坏配置文件格式） */
    fun cleanName(raw: String?): String =
        raw?.replace('\r', ' ')?.replace('\n', ' ')?.replace('|', ' ')?.trim().orEmpty()

    /** 纯逻辑：解析单行 "MAC|NAME" 为 Entry；全空则 null */
    fun parseLine(line: String?): Entry? {
        val s = line?.trim().orEmpty()
        if (s.isEmpty()) return null
        val sep = s.indexOf('|')
        val mac = normalizeMac(if (sep >= 0) s.substring(0, sep) else s)
        val name = cleanName(if (sep >= 0) s.substring(sep + 1) else "")
        return if (mac.isEmpty() && name.isEmpty()) null else Entry(mac, name)
    }

    /** 纯逻辑：同一设备判重键（有有效 MAC 按 MAC 去重，否则按名称小写去重） */
    fun dedupKey(mac: String?, name: String?): String {
        val m = normalizeMac(mac)
        if (m.isNotEmpty()) return "M:$m"
        val n = cleanName(name).lowercase(Locale.ROOT)
        return if (n.isNotEmpty()) "N:$n" else ""
    }

    /** 纯逻辑：判断设备类型是否为蓝牙媒体输出（A2DP / BLE 耳机 / BLE 音箱） */
    fun isBtOutputType(type: Int): Boolean =
        type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
            type == AudioDeviceInfo.TYPE_BLE_SPEAKER

    /** 纯逻辑：某台蓝牙输出设备是否命中白名单（空白名单表示不限制，直接 true） */
    fun matches(entries: Collection<Entry>, devMac: String?, devName: String?): Boolean {
        if (entries.isEmpty()) return true
        val m = normalizeMac(devMac)
        val n = cleanName(devName)
        for (e in entries) {
            if (e.mac.isNotEmpty() && m.isNotEmpty()) {
                if (e.mac.equals(m, ignoreCase = true)) return true
            } else if (e.name.isNotEmpty() && n.isNotEmpty()) {
                if (e.name.equals(n, ignoreCase = true)) return true
            }
        }
        return false
    }

    /** 纯逻辑：序列化写入 /data/local/tmp/approute.btfilter */
    fun fileText(entries: Collection<Entry>): String =
        entries.map { it.serialize() }.filter { it != "|" }.distinct().joinToString("") { "$it\n" }

    /** 纯逻辑：勾选/取消勾选一台蓝牙设备（允许全部取消，空集合即恢复不限设备） */
    fun toggled(cur: List<Entry>, target: Entry): List<Entry> {
        val key = dedupKey(target.mac, target.name)
        if (key.isEmpty()) return cur
        val out = ArrayList<Entry>()
        var removed = false
        for (e in cur) {
            if (dedupKey(e.mac, e.name) == key) removed = true else out.add(e)
        }
        if (!removed) out.add(Entry(normalizeMac(target.mac), cleanName(target.name)))
        return out
    }

    fun entries(c: Context): List<Entry> {
        val raw = sp(c).getStringSet(KEY_ENTRIES, emptySet()).orEmpty()
        val seen = LinkedHashSet<String>()
        val out = ArrayList<Entry>()
        for (line in raw.sorted()) {
            val e = parseLine(line) ?: continue
            val k = dedupKey(e.mac, e.name)
            if (k.isNotEmpty() && seen.add(k)) out.add(e)
        }
        return out
    }

    fun save(c: Context, list: Collection<Entry>) {
        val seen = LinkedHashSet<String>()
        val out = LinkedHashSet<String>()
        for (e in list) {
            val k = dedupKey(e.mac, e.name)
            if (k.isNotEmpty() && seen.add(k)) out.add(e.serialize())
        }
        sp(c).edit().putStringSet(KEY_ENTRIES, out).apply()
    }

    /** 检查当前手机的蓝牙媒体输出状态（Android 13+ 优先取当前活跃媒体路由，避免双蓝牙同时连接时误判） */
    fun inspect(c: Context, am: AudioManager): Status {
        val allBt = try {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { isBtOutputType(it.type) }
        } catch (_: Throwable) { emptyList() }
        if (allBt.isEmpty()) return Status.None

        val whitelist = entries(c)

        // Android 13+ 优先查看当前 USAGE_MEDIA 的活跃输出设备
        val activeBt: List<AudioDeviceInfo> = if (Build.VERSION.SDK_INT >= 33) {
            try {
                val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
                val active = am.getAudioDevicesForAttributes(attrs)
                if (active.isNotEmpty()) {
                    val activeBts = active.filter { isBtOutputType(it.type) }
                    if (activeBts.isEmpty()) return Status.None   // 当前活跃媒体路由不是蓝牙（如已切到手机扬声器或有线耳机）
                    activeBts
                } else allBt
            } catch (_: Throwable) { allBt }
        } else allBt

        if (whitelist.isEmpty()) {
            val label = activeBt.firstOrNull()?.productName?.toString().orEmpty()
            return Status.Matched(cleanName(label))
        }

        // 若缺少 BLUETOOTH_CONNECT 权限且设备地址全为空，则 App 侧拿不到真实 MAC/名称，交给拥有 shell 权限的 AppRoute 裁决
        val hasPerm = hasConnectPermission(c)
        var anyIdentifiable = false
        for (d in activeBt) {
            val mac = try { normalizeMac(d.address) } catch (_: Throwable) { "" }
            val name = cleanName(d.productName?.toString())
            if (mac.isNotEmpty() || (hasPerm && name.isNotEmpty())) anyIdentifiable = true
            if (matches(whitelist, mac, name)) {
                return Status.Matched(name.ifEmpty { mac })
            }
        }
        if (!hasPerm && !anyIdentifiable) return Status.NeedsRouterCheck
        val first = activeBt.firstOrNull()
        val otherLabel = cleanName(first?.productName?.toString()).ifEmpty {
            try { normalizeMac(first?.address) } catch (_: Throwable) { "" }
        }
        return Status.Other(otherLabel)
    }
}
