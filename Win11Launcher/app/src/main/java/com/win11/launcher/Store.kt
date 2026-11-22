package com.win11.launcher

import android.content.Context
import android.content.SharedPreferences

/** Все настройки лаунчера хранятся в SharedPreferences. */
class Store(ctx: Context) {
    private val p: SharedPreferences = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    /** "tv" (по умолчанию при первом запуске) или "win" */
    var mode: String
        get() = p.getString("mode", "tv") ?: "tv"
        set(v) { p.edit().putString("mode", v).apply() }

    /** 0 - крупные, 1 - средние, 2 - мелкие */
    var iconSize: Int
        get() = p.getInt("iconSize", 1)
        set(v) { p.edit().putInt("iconSize", v).apply() }

    var locked: Boolean
        get() = p.getBoolean("locked", false)
        set(v) { p.edit().putBoolean("locked", v).apply() }

    var cursorMode: Boolean
        get() = p.getBoolean("cursor", false)
        set(v) { p.edit().putBoolean("cursor", v).apply() }

    /** default / night / sunset / blue / custom */
    var wpType: String
        get() = p.getString("wpType", "default") ?: "default"
        set(v) { p.edit().putString("wpType", v).apply() }

    var wpUri: String?
        get() = p.getString("wpUri", null)
        set(v) { p.edit().putString("wpUri", v).apply() }

    fun list(key: String): MutableList<String>? {
        val s = p.getString(key, null) ?: return null
        return if (s.isEmpty()) ArrayList() else s.split("|").toMutableList()
    }

    fun saveList(key: String, l: List<String>) {
        p.edit().putString(key, l.joinToString("|")).apply()
    }

    fun label(pkg: String): String? = p.getString("label_$pkg", null)

    fun setLabel(pkg: String, v: String?) {
        val e = p.edit()
        if (v.isNullOrBlank()) e.remove("label_$pkg") else e.putString("label_$pkg", v)
        e.apply()
    }
}
