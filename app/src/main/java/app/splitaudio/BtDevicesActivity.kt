package app.splitaudio

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/** 指定触发分流的蓝牙音箱白名单（多选）：
 *  - 留空 = 不限制，所有蓝牙音频输出均会分流；
 *  - 勾选特定蓝牙音箱后 = 仅连着已选音箱时分流，连蓝牙耳机等其他蓝牙设备绝不误分流。
 *  - 合并展示「当前已连接的蓝牙媒体设备 + 系统已配对蓝牙设备 + 已保存的历史设备」，退出时热同步给 AppRoute。 */
class BtDevicesActivity : Activity() {
    private class Item(
        val entry: BtFilter.Entry,
        val connected: Boolean,
        val isAudio: Boolean,
        val bonded: Boolean
    )

    private var all = listOf<Item>()
    private var shown = listOf<Item>()
    private var selected = listOf<BtFilter.Entry>()
    private var query = ""
    private var changed = false
    private lateinit var permBox: LinearLayout
    private lateinit var tvEmpty: TextView
    private lateinit var adapter: BaseAdapter

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        selected = BtFilter.entries(this)
        val dp = resources.displayMetrics.density
        val pad = (12 * dp).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(MainActivity.C_BG_TOP, MainActivity.C_BG_BOTTOM))
        }
        root.addView(TextView(this).apply {
            textSize = 22f; text = "📻 " + getString(R.string.bt_title)
            setTextColor(MainActivity.C_PURPLE); setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            textSize = 13f; text = getString(R.string.bt_hint)
            setTextColor(MainActivity.C_TEXT_2); setPadding(0, pad / 2, 0, pad / 2)
        })

        // 未授予 BLUETOOTH_CONNECT 权限时的提示与一键授权卡
        permBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply { cornerRadius = 18 * dp; setColor(Color.WHITE) }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = pad
            }
            addView(TextView(this@BtDevicesActivity).apply {
                text = getString(R.string.bt_perm_hint); textSize = 13f; setTextColor(MainActivity.C_ORANGE)
                setPadding(0, 0, 0, (8 * dp).toInt())
            })
            addView(Button(this@BtDevicesActivity).apply {
                text = getString(R.string.bt_perm_btn); isAllCaps = false; textSize = 15f
                setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD); stateListAnimator = null
                background = GradientDrawable().apply { cornerRadius = 22 * dp; setColor(MainActivity.C_ORANGE) }
                setOnClickListener { requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_BT) }
            })
        }
        root.addView(permBox)

        root.addView(EditText(this).apply {
            hint = "🔍 " + getString(R.string.bt_search); isSingleLine = true; textSize = 15f
            setPadding(pad, pad * 3 / 4, pad, pad * 3 / 4)
            background = GradientDrawable().apply {
                cornerRadius = 24 * dp; setColor(Color.WHITE); setStroke((2 * dp).toInt(), Color.rgb(206, 147, 216))
            }
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { query = s?.toString().orEmpty().trim(); filter() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        })

        tvEmpty = TextView(this).apply {
            text = getString(R.string.bt_empty); textSize = 14f; setTextColor(MainActivity.C_TEXT_2)
            gravity = Gravity.CENTER; setPadding(pad, pad * 2, pad, pad * 2); visibility = View.GONE
        }
        root.addView(tvEmpty)

        adapter = object : BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(i: Int): Any = shown[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, v: View?, parent: ViewGroup?): View {
                val row = (v as? LinearLayout) ?: row(dp)
                val item = shown[i]
                val icon = row.getChildAt(0) as TextView
                val col = row.getChildAt(1) as LinearLayout
                val tvName = col.getChildAt(0) as TextView
                val tvSub = col.getChildAt(1) as TextView
                val cb = row.getChildAt(2) as CheckBox
                icon.text = if (item.connected) "🟢" else if (item.isAudio) "🔊" else "📶"
                tvName.text = item.entry.displayName()
                val tags = ArrayList<String>()
                if (item.connected) tags += getString(R.string.bt_tag_connected)
                else if (item.bonded) tags += getString(R.string.bt_tag_paired)
                else tags += getString(R.string.bt_tag_saved)
                if (item.entry.mac.isNotEmpty()) tags += item.entry.mac
                tvSub.text = tags.joinToString(" · ")
                cb.isChecked = isSelected(item.entry)
                return row
            }
        }

        val lv = ListView(this).apply {
            adapter = this@BtDevicesActivity.adapter
            divider = null; dividerHeight = (8 * dp).toInt()
            setPadding(0, pad, 0, pad); clipToPadding = false
            setOnItemClickListener { _, _, i, _ -> toggle(shown[i]) }
        }
        root.addView(lv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        Edge.apply(root, root, lv)

        if (!BtFilter.hasConnectPermission(this)) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_BT)
        }
        reload()
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onRequestPermissionsResult(req: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(req, perms, res)
        if (req == REQ_BT) reload()
    }

    private fun row(dp: Float): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        val p = (10 * dp).toInt(); setPadding(p + p / 2, p, p, p)
        background = GradientDrawable().apply { cornerRadius = 18 * dp; setColor(Color.WHITE) }
        addView(TextView(this@BtDevicesActivity).apply {
            textSize = 22f; gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams((36 * dp).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = (10 * dp).toInt() })
        addView(LinearLayout(this@BtDevicesActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@BtDevicesActivity).apply {
                textSize = 16f; setTextColor(MainActivity.C_TEXT); setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(this@BtDevicesActivity).apply {
                textSize = 11f; setTextColor(MainActivity.C_TEXT_2)
            })
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(CheckBox(this@BtDevicesActivity).apply {
            isClickable = false; isFocusable = false; isFocusableInTouchMode = false
        })
    }

    private fun isSelected(e: BtFilter.Entry): Boolean {
        val k = BtFilter.dedupKey(e.mac, e.name)
        return selected.any { BtFilter.dedupKey(it.mac, it.name) == k }
    }

    @SuppressLint("MissingPermission")
    private fun reload() {
        val hasPerm = BtFilter.hasConnectPermission(this)
        permBox.visibility = if (hasPerm) View.GONE else View.VISIBLE

        val byKey = LinkedHashMap<String, Item>()

        // 1. 当前已连接的蓝牙音频输出设备
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            for (d in am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                if (!BtFilter.isBtOutputType(d.type)) continue
                val mac = try { BtFilter.normalizeMac(d.address) } catch (_: Throwable) { "" }
                val name = BtFilter.cleanName(d.productName?.toString())
                val key = BtFilter.dedupKey(mac, name)
                if (key.isEmpty()) continue
                byKey[key] = Item(BtFilter.Entry(mac, name), connected = true, isAudio = true, bonded = true)
            }
        } catch (_: Throwable) {}

        // 2. 系统已配对蓝牙设备（需 BLUETOOTH_CONNECT 权限）
        if (hasPerm) {
            try {
                val bm = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                for (dev in bm?.adapter?.bondedDevices.orEmpty()) {
                    val mac = BtFilter.normalizeMac(dev.address)
                    val name = BtFilter.cleanName(dev.name)
                    val key = BtFilter.dedupKey(mac, name)
                    if (key.isEmpty()) continue
                    val major = try { dev.bluetoothClass?.majorDeviceClass } catch (_: Throwable) { null }
                    val audio = major == BluetoothClass.Device.Major.AUDIO_VIDEO
                    val existing = byKey[key]
                    if (existing != null) {
                        val bestName = existing.entry.name.ifBlank { name }
                        val bestMac = existing.entry.mac.ifBlank { mac }
                        byKey[key] = Item(BtFilter.Entry(bestMac, bestName), connected = existing.connected, isAudio = existing.isAudio || audio, bonded = true)
                    } else {
                        byKey[key] = Item(BtFilter.Entry(mac, name), connected = false, isAudio = audio, bonded = true)
                    }
                }
            } catch (_: Throwable) {}
        }

        // 3. 之前已勾选但当前未配对/未连接的设备（保留在列表中以便随时取消勾选）
        for (e in selected) {
            val key = BtFilter.dedupKey(e.mac, e.name)
            if (key.isNotEmpty() && !byKey.containsKey(key)) {
                byKey[key] = Item(e, connected = false, isAudio = true, bonded = false)
            }
        }

        all = byKey.values.toList()
        filter()
    }

    /** 排序：已勾选 → 当前已连接 → 音频类蓝牙设备 → 名称字母序 */
    private fun filter() {
        shown = all.filter {
            query.isEmpty() || it.entry.name.contains(query, true) || it.entry.mac.contains(query, true)
        }.sortedWith(
            compareBy<Item>(
                { !isSelected(it.entry) },
                { !it.connected },
                { !it.isAudio },
                { it.entry.displayName().lowercase() }
            )
        )
        tvEmpty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
    }

    private fun toggle(item: Item) {
        selected = BtFilter.toggled(selected, item.entry)
        BtFilter.save(this, selected)
        changed = true
        adapter.notifyDataSetChanged()
    }

    override fun onPause() {
        super.onPause()
        if (changed) {
            changed = false
            val c = applicationContext
            Thread { Route.syncConfigIfRunning(c) }.start()
        }
    }

    companion object {
        private const val REQ_BT = 1002
    }
}
