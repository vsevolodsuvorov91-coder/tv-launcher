package com.win11.launcher

import android.content.Context
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.os.Build

data class InputItem(val id: String, val label: String, val type: Int, val connected: Boolean)

object Hdmi {
    fun typeName(t: Int): String = when (t) {
        TvInputInfo.TYPE_HDMI -> "HDMI"
        TvInputInfo.TYPE_COMPOSITE -> "AV"
        TvInputInfo.TYPE_COMPONENT -> "Component"
        TvInputInfo.TYPE_SVIDEO -> "S-Video"
        TvInputInfo.TYPE_SCART -> "SCART"
        TvInputInfo.TYPE_VGA -> "VGA"
        TvInputInfo.TYPE_DVI -> "DVI"
        TvInputInfo.TYPE_DISPLAY_PORT -> "DisplayPort"
        TvInputInfo.TYPE_TUNER -> "ТВ-тюнер"
        else -> "Вход"
    }

    /** Физические (passthrough) входы телевизора через TvInputManager. */
    fun list(ctx: Context): List<InputItem> {
        val out = ArrayList<InputItem>()
        try {
            val m = ctx.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager ?: return out
            for (info in m.tvInputList) {
                if (!info.isPassthroughInput) continue
                var label = ""
                try {
                    label = info.loadLabel(ctx)?.toString() ?: ""
                } catch (e: Exception) {
                }
                if (Build.VERSION.SDK_INT >= 24) {
                    try {
                        val c = info.loadCustomLabel(ctx)?.toString()
                        if (!c.isNullOrBlank()) label = c
                    } catch (e: Exception) {
                    }
                }
                if (label.isBlank()) label = typeName(info.type)
                val connected = try {
                    m.getInputState(info.id) == TvInputManager.INPUT_STATE_CONNECTED
                } catch (e: Exception) {
                    false
                }
                out.add(InputItem(info.id, label, info.type, connected))
            }
        } catch (e: Throwable) {
        }
        return out.sortedWith(compareBy<InputItem>({ it.type }, { it.label }))
    }
}
