package app.splitaudio

import android.content.Context

/** 音乐 App 清单（在 MusicActivity 多选）：这些 App 的声音留在蓝牙音箱，其他 App 走手机喇叭。
 *  存 SharedPreferences "music"；未选过（或全无效）使用默认清单（含 QQ 音乐及常见播放器，未安装的会自动忽略）。
 *  启动前经 [Backends] 写入 /data/local/tmp/approute.music，由 approute.sh start 转换为 uid。 */
object Music {
    /** 合法包名校验正则（会写入供 shell 读取的配置文件） */
    val PKG = Regex("^[A-Za-z0-9_.]+$")

    /** 默认音乐 App（需与 approute.sh 的 DEF_MUSIC 保持一致，有单元测试校验） */
    val DEFAULT: Set<String> = linkedSetOf(
        "com.tencent.qqmusic", "com.netease.cloudmusic", "com.kugou.android", "cn.kuwo.player", "com.luna.music",
        "com.google.android.apps.youtube.music", "app.morphe.android.apps.youtube.music",
        "com.spotify.music", "com.apple.android.music", "deezer.android.app", "com.aspiro.tidal",
        "com.amazon.mp3", "com.soundcloud.android", "com.pandora.android", "com.maxmpz.audioplayer"
    )
    private const val KEY = "pkgs"
    private fun sp(c: Context) = c.getSharedPreferences("music", Context.MODE_PRIVATE)

    /** 当前生效的音乐 App（包名集合；永远非空） */
    fun set(c: Context): Set<String> = effective(sp(c).getStringSet(KEY, null))

    fun save(c: Context, pkgs: Set<String>) {
        sp(c).edit().putStringSet(KEY, HashSet(valid(pkgs))).apply()
    }

    /** 纯逻辑（可单元测试）：去空白、校验包名合法性、去重并保留顺序 */
    fun valid(raw: Collection<String?>?): Set<String> {
        val out = LinkedHashSet<String>()
        for (r in raw.orEmpty()) {
            val p = r?.trim() ?: continue
            if (PKG.matches(p)) out.add(p)
        }
        return out
    }

    /** 纯逻辑：未保存或全无效时回退默认列表 */
    fun effective(raw: Collection<String?>?): Set<String> = valid(raw).ifEmpty { DEFAULT }

    /** 纯逻辑：approute.music 文件内容，一行一个包名 */
    fun text(s: Collection<String>): String = s.joinToString("") { "$it\n" }

    /** 纯逻辑：切换勾选状态；至少保留一个 */
    fun toggled(cur: Set<String>, pkg: String): Set<String> {
        val next = LinkedHashSet(cur)
        if (!next.remove(pkg)) next.add(pkg)
        return if (next.isEmpty()) cur else next
    }
}
