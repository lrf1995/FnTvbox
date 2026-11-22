package app.fntv.tv.data

import android.content.Context
import android.content.SharedPreferences

/** 本机配置：只保存 NAS 地址 / 账号 / token 与少量播放偏好，密码不落盘。 */
object Prefs {
    private var sp: SharedPreferences? = null

    fun init(context: Context) {
        if (sp == null) sp = context.getSharedPreferences("fntv", Context.MODE_PRIVATE)
    }

    private val prefs: SharedPreferences get() = sp ?: error("Prefs 未初始化")

    var server: String
        get() = prefs.getString("server", "") ?: ""
        set(v) = prefs.edit().putString("server", v).apply()

    var token: String
        get() = prefs.getString("token", "") ?: ""
        set(v) = prefs.edit().putString("token", v).apply()

    var username: String
        get() = prefs.getString("username", "") ?: ""
        set(v) = prefs.edit().putString("username", v).apply()

    var lastLib: String
        get() = prefs.getString("last_lib", "") ?: ""
        set(v) = prefs.edit().putString("last_lib", v).apply()

    /** 起播时自动跳过片头（飞牛 play/info 的 play_config.skip_opening）。 */
    var autoSkipOpening: Boolean
        get() = prefs.getBoolean("skip_opening", false)
        set(v) = prefs.edit().putBoolean("skip_opening", v).apply()

    var autoNextEpisode: Boolean
        get() = prefs.getBoolean("auto_next", true)
        set(v) = prefs.edit().putBoolean("auto_next", v).apply()

    /** TMDB API Key（在 themoviedb.org 免费申请）；为空时详情页只用 Bangumi。 */
    var tmdbKey: String
        get() = prefs.getString("tmdb_key", "") ?: ""
        set(v) = prefs.edit().putString("tmdb_key", v).apply()

    /** 弹幕接口基址（自建弹幕容器的地址），留给下一版弹幕功能用。 */
    var danmuApiBase: String
        get() = prefs.getString("danmu_api_base", "") ?: ""
        set(v) = prefs.edit().putString("danmu_api_base", v).apply()

    /** 播放时是否加载并显示弹幕。 */
    var danmakuEnabled: Boolean
        get() = prefs.getBoolean("danmaku_enabled", true)
        set(v) = prefs.edit().putBoolean("danmaku_enabled", v).apply()

    fun clearLogin() {
        prefs.edit().remove("token").apply()
    }
}