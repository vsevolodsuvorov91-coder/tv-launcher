package com.win11.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

/** Рамка фокуса: белая обводка при фокусе/нажатии, иначе прозрачный (или цветной) фон. */
fun focusBg(
    ctx: Context,
    radius: Int,
    stroke: Int = Color.WHITE,
    fill: Int = 0x40FFFFFF,
    normal: Int = Color.TRANSPARENT
): StateListDrawable {
    fun shape(c: Int, st: Int?): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(c)
        g.cornerRadius = ctx.dp(radius).toFloat()
        if (st != null) g.setStroke(ctx.dp(3), st)
        return g
    }
    val s = StateListDrawable()
    s.addState(intArrayOf(android.R.attr.state_focused), shape(fill, stroke))
    s.addState(intArrayOf(android.R.attr.state_pressed), shape(fill, stroke))
    s.addState(intArrayOf(), shape(normal, null))
    return s
}

/** Фокусируемая плитка (иконка рабочего стола, карточка ТВ, кнопка панели задач). */
class Tile(ctx: Context) : LinearLayout(ctx) {
    var pkg: String = ""
    var where: Int = 0

    init {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        isFocusable = true
        isClickable = true
        setOnFocusChangeListener { v, has ->
            val s = if (has) 1.06f else 1f
            v.animate().scaleX(s).scaleY(s).setDuration(90).start()
        }
    }
}

/** Строка меню/диалога. */
class Row(ctx: Context, icon: Drawable?, text: String, accent: Boolean = false) : LinearLayout(ctx) {
    val tv = TextView(ctx)

    init {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = true
        isClickable = true
        setPadding(ctx.dp(12), ctx.dp(10), ctx.dp(12), ctx.dp(10))
        background = focusBg(
            ctx, 10, Color.parseColor("#4CC2FF"), Color.parseColor("#404CC2FF"),
            if (accent) Color.parseColor("#44007ACC") else Color.TRANSPARENT
        )
        if (icon != null) {
            val iv = ImageView(ctx)
            iv.setImageDrawable(icon)
            val lp = LinearLayout.LayoutParams(ctx.dp(40), ctx.dp(40))
            lp.rightMargin = ctx.dp(12)
            addView(iv, lp)
        }
        tv.text = text
        tv.setTextColor(Color.WHITE)
        tv.textSize = 18f
        addView(tv, LinearLayout.LayoutParams(0, WRAP, 1f))
    }
}

/** Простые векторные значки, нарисованные кодом (никаких картинок). */
class GlyphView(ctx: Context, private val kind: Int) : View(ctx) {
    companion object {
        const val WIN = 0
        const val HDMI = 1
        const val WIFI = 2
        const val PLUS = 3
        const val GRID = 4
    }

    var color: Int = Color.WHITE
        set(v) { field = v; invalidate() }
    var textColor: Int = Color.WHITE
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        p.isFakeBoldText = false
        p.style = Paint.Style.FILL
        p.color = color
        when (kind) {
            WIN -> {
                val m = w * 0.14f
                val g = w * 0.06f
                val s = (w - 2 * m - g) / 2f
                for (i in 0..1) for (j in 0..1) {
                    val l = m + i * (s + g)
                    val t = m + j * (s + g)
                    c.drawRect(l, t, l + s, t + s, p)
                }
            }
            HDMI -> {
                path.reset()
                path.moveTo(w * 0.06f, h * 0.14f)
                path.lineTo(w * 0.94f, h * 0.14f)
                path.lineTo(w * 0.94f, h * 0.64f)
                path.lineTo(w * 0.80f, h * 0.90f)
                path.lineTo(w * 0.20f, h * 0.90f)
                path.lineTo(w * 0.06f, h * 0.64f)
                path.close()
                c.drawPath(path, p)
                p.color = textColor
                p.textAlign = Paint.Align.CENTER
                p.isFakeBoldText = true
                p.textSize = h * 0.30f
                c.drawText("HDMI", w / 2f, h * 0.60f, p)
            }
            WIFI -> {
                p.style = Paint.Style.STROKE
                p.strokeWidth = w * 0.09f
                p.strokeCap = Paint.Cap.ROUND
                val cx = w / 2f
                val cy = h * 0.86f
                for (r in floatArrayOf(0.22f, 0.38f, 0.54f)) {
                    val rr = w * r
                    c.drawArc(RectF(cx - rr, cy - rr, cx + rr, cy + rr), 225f, 90f, false, p)
                }
                p.style = Paint.Style.FILL
                c.drawCircle(cx, cy, w * 0.06f, p)
            }
            PLUS -> {
                p.style = Paint.Style.STROKE
                p.strokeWidth = w * 0.10f
                p.strokeCap = Paint.Cap.ROUND
                c.drawLine(w * 0.25f, h * 0.5f, w * 0.75f, h * 0.5f, p)
                c.drawLine(w * 0.5f, h * 0.25f, w * 0.5f, h * 0.75f, p)
            }
            GRID -> {
                val r = w * 0.07f
                for (i in 0..2) for (j in 0..2) {
                    c.drawCircle(w * (0.25f + 0.25f * i), h * (0.25f + 0.25f * j), r, p)
                }
            }
        }
    }
}

/** Виртуальный курсор мыши. Горячая точка - левый верхний угол. */
class CursorView(ctx: Context) : View(ctx) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    init {
        fill.color = Color.WHITE
        fill.style = Paint.Style.FILL
        line.color = Color.BLACK
        line.style = Paint.Style.STROKE
        line.strokeWidth = 2f * ctx.resources.displayMetrics.density
        line.strokeJoin = Paint.Join.ROUND
    }

    override fun onDraw(c: Canvas) {
        val u = width / 28f
        path.reset()
        path.moveTo(1 * u, 1 * u)
        path.lineTo(1 * u, 22 * u)
        path.lineTo(7 * u, 17 * u)
        path.lineTo(11 * u, 26 * u)
        path.lineTo(15 * u, 24 * u)
        path.lineTo(11 * u, 16 * u)
        path.lineTo(18 * u, 16 * u)
        path.close()
        c.drawPath(path, fill)
        c.drawPath(path, line)
    }
}

/** ScrollView, ограниченный по высоте (для диалогов). */
class MaxHeightScrollView(ctx: Context, private val maxH: Int) : ScrollView(ctx) {
    override fun onMeasure(w: Int, h: Int) {
        super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST))
    }
}
