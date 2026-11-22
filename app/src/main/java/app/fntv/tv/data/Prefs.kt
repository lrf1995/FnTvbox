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

    fun clearLogin() {
        prefs.edit().remove("token").apply()
    }
}