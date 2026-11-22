package com.win11.launcher

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.tv.TvContract
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AppInfo(val pkg: String, val label: String)

class MainActivity : Activity() {

    companion object {
        const val DESKTOP = 0
        const val TASKBAR = 1
        const val TV = 2
        const val PICK_LAUNCH = 0
        const val PICK_DESKTOP = 1
        const val PICK_PIN = 2
        const val REQ_WP = 42
    }

    private lateinit var store: Store
    private lateinit var root: FrameLayout
    private lateinit var wallpaper: ImageView
    private lateinit var holder: FrameLayout
    private lateinit var cursor: CursorView
    private val handler = Handler(Looper.getMainLooper())

    private var apps: Map<String, AppInfo> = emptyMap()
    private var desktop: MutableList<String> = ArrayList()
    private var pinned: MutableList<String> = ArrayList()
    private val tiles = ArrayList<Tile>()
    private val tickers = ArrayList<() -> Unit>()
    private val dialogs = ArrayList<Dialog>()
    private var deskScroll: ScrollView? = null
    private var tvScroll: HorizontalScrollView? = null
    private var taskbar: View? = null
    private var deskCols = 6

    // режим перемещения
    private var moving = false
    private var moveWhich = DESKTOP
    private var moveIdx = -1
    private var moveBackup: List<String> = emptyList()

    private var lastPkg: String? = null
    private var lastWhere = -1

    // виртуальный курсор
    private var cx = 960f
    private var cy = 540f
    private var dirMask = 0
    private var holdStart = 0L
    private var lastT = 0L
    private var downTime = 0L

    // долгие нажатия
    private var okLongFired = false
    private var menuLongFired = false

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFmt = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
    private val dateLongFmt = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault())

    private val tick = object : Runnable {
        override fun run() {
            for (t in tickers) t()
            handler.postDelayed(this, 10000)
        }
    }
    private val okLongRun = Runnable {
        okLongFired = true
        currentFocus?.performLongClick()
    }
    private val menuLongRun = Runnable {
        menuLongFired = true
        toggleCursor()
    }
    private val cursorRun = object : Runnable {
        override fun run() {
            if (dirMask == 0) return
            val now = SystemClock.uptimeMillis()
            val dt = (now - lastT) / 1000f
            lastT = now
            val held = (now - holdStart) / 1000f
            val speed = minOf(3000f, 220f + held * 1800f) // пикс/сек, разгон при удержании
            var dx = 0f
            var dy = 0f
            if (dirMask and 1 != 0) dx -= 1f
            if (dirMask and 2 != 0) dx += 1f
            if (dirMask and 4 != 0) dy -= 1f
            if (dirMask and 8 != 0) dy += 1f
            moveCursor(dx * speed * dt, dy * speed * dt)
            handler.postDelayed(this, 16)
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        setContentView(R.layout.activity_main)
        root = findViewById(R.id.root)
        wallpaper = findViewById(R.id.wallpaper)
        holder = FrameLayout(this)
        root.addView(holder, 1, FrameLayout.LayoutParams(MATCH, MATCH))
        cursor = CursorView(this)
        cursor.visibility = View.GONE
        root.addView(cursor, FrameLayout.LayoutParams(dp(28), dp(28)))
        val dm = resources.displayMetrics
        cx = dm.widthPixels / 2f
        cy = dm.heightPixels / 2f
        applyWallpaper()
    }

    override fun onResume() {
        super.onResume()
        taskbar?.visibility = View.VISIBLE
        loadApps()
        initLists()
        rebuild()
        handler.removeCallbacks(tick)
        handler.post(tick)
        applyCursor()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
        handler.removeCallbacks(okLongRun)
        handler.removeCallbacks(menuLongRun)
        dirMask = 0
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // нажатие кнопки Домой: закрыть окна, сбросить режим перемещения, вернуть панель задач
        for (d in ArrayList(dialogs)) d.dismiss()
        if (moving) {
            val l = curMoveList()
            l.clear()
            l.addAll(moveBackup)
            moving = false
        }
        lastPkg = null
        taskbar?.visibility = View.VISIBLE
        if (apps.isNotEmpty()) rebuild()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // лаунчер не закрывается кнопкой Назад
    }

    // ---------------------------------------------------------------- data

    private fun loadApps() {
        val pm = packageManager
        val map = LinkedHashMap<String, AppInfo>()
        for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            try {
                val i = Intent(Intent.ACTION_MAIN).addCategory(cat)
                for (ri in pm.queryIntentActivities(i, 0)) {
                    val pkg = ri.activityInfo.packageName
                    if (pkg == packageName || map.containsKey(pkg)) continue
                    map[pkg] = AppInfo(pkg, ri.loadLabel(pm).toString())
                }
            } catch (e: Exception) {
            }
        }
        apps = map
    }

    private fun initLists() {
        if (apps.isNotEmpty()) {
            val sorted = apps.values.sortedBy { it.label.lowercase() }.map { it.pkg }
            if (store.list("desktop") == null) store.saveList("desktop", sorted.take(30))
            if (store.list("pinned") == null) store.saveList("pinned", sorted.take(8))
        }
        desktop = (store.list("desktop") ?: ArrayList()).filter { apps.containsKey(it) }.toMutableList()
        pinned = (store.list("pinned") ?: ArrayList()).filter { apps.containsKey(it) }.toMutableList()
    }

    private fun saveLists() {
        store.saveList("desktop", desktop)
        store.saveList("pinned", pinned)
    }

    private fun nameOf(pkg: String): String = store.label(pkg) ?: apps[pkg]?.label ?: pkg

       private fun icon(pkg: String): Drawable {
        val pm = packageManager
        try {
            val tvIntent = pm.getLeanbackLaunchIntentForPackage(pkg)
            if (tvIntent != null) {
                val banner = pm.getActivityBanner(tvIntent) ?: pm.getApplicationBanner(pkg)
                if (banner != null) return banner
            }
            val launchIntent = tvIntent ?: pm.getLaunchIntentForPackage(pkg)
            if (launchIntent != null) {
                return pm.getActivityIcon(launchIntent)
            }
            return pm.getApplicationIcon(pkg)
        } catch (e: Exception) {
            return pm.defaultActivityIcon
        }
    }


    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun startSafe(i: Intent): Boolean {
        return try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun rememberFocus() {
        (currentFocus as? Tile)?.let {
            lastPkg = it.pkg
            lastWhere = it.where
        }
    }

    private fun launch(pkg: String) {
        if (moving) {
            finishMove(false)
            return
        }
        rememberFocus()
        val pm = packageManager
        val i = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
        if (i == null) {
            toast("Не удалось открыть: " + nameOf(pkg))
            return
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        taskbar?.visibility = View.GONE // панель задач скрывается при запуске стороннего приложения
        try {
            startActivity(i)
        } catch (e: Exception) {
            taskbar?.visibility = View.VISIBLE
            toast("Не удалось открыть: " + nameOf(pkg))
        }
    }

    // ---------------------------------------------------------------- memory / network / clock

    private fun mem(): ActivityManager.MemoryInfo {
        val mi = ActivityManager.MemoryInfo()
        (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        return mi
    }

    private fun ramText(): String {
        val mi = mem()
        return "ОЗУ: свободно " + (mi.availMem / 1048576L) + " из " + (mi.totalMem / 1048576L) + " МБ"
    }

    private fun ramColor(dark: Boolean): Int {
        val mi = mem()
        val ratio = mi.availMem.toFloat() / mi.totalMem.toFloat()
        return if (ratio < 0.15f) Color.parseColor(if (dark) "#FF6B6B" else "#C62828")
        else if (ratio < 0.30f) Color.parseColor(if (dark) "#FFD54F" else "#B26A00")
        else Color.parseColor(if (dark) "#7CFC9A" else "#1B7F3A")
    }

    private fun freeMemory(done: (() -> Unit)? = null) {
        val before = mem().availMem
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val pm = packageManager
        toast("Освобождаю память...")
        Thread {
            try {
                for (ai in pm.getInstalledApplications(0)) {
                    if (ai.packageName != packageName) {
                        try {
                            am.killBackgroundProcesses(ai.packageName)
                        } catch (e: Exception) {
                        }
                    }
                }
                Thread.sleep(700)
            } catch (e: Exception) {
            }
            runOnUiThread {
                val freed = (mem().availMem - before) / 1048576L
                toast("Готово. Освобождено: " + (if (freed > 0) freed else 0) + " МБ")
                for (t in tickers) t()
                done?.invoke()
            }
        }.start()
    }

    @Suppress("DEPRECATION")
    private fun netType(): Int {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val ni = cm.activeNetworkInfo
            if (ni == null || !ni.isConnected) 0
            else if (ni.type == ConnectivityManager.TYPE_WIFI) 1
            else 2
        } catch (e: Exception) {
            0
        }
    }

    // ---------------------------------------------------------------- UI building

    private fun tvIconDp(): Int = intArrayOf(96, 76, 56)[store.iconSize.coerceIn(0, 2)]
    private fun deskIconDp(): Int = intArrayOf(72, 56, 42)[store.iconSize.coerceIn(0, 2)]

    private fun lbl(text: String, sp: Float, color: Int = Color.WHITE): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = sp
        t.setTextColor(color)
        t.gravity = Gravity.CENTER_HORIZONTAL
        t.maxLines = 2
        t.ellipsize = TextUtils.TruncateAt.END
        t.setShadowLayer(4f, 0f, 2f, Color.BLACK)
        return t
    }

    private fun rebuild() {
        rememberFocus()
        tiles.clear()
        tickers.clear()
        holder.removeAllViews()
        deskScroll = null
        tvScroll = null
        taskbar = null
        if (store.mode == "win") buildWin() else buildTv()
        for (t in tickers) t()
        val target = tiles.firstOrNull { it.pkg == lastPkg && it.where == lastWhere } ?: tiles.firstOrNull()
        target?.requestFocus()
    }

    // ---- Режим ТВ

    private fun pill(key: String, text: String, click: () -> Unit): Tile {
        val t = Tile(this)
        t.pkg = key
        t.where = TV
        t.background = focusBg(this, 22, Color.WHITE, 0x55FFFFFF, 0x66000000)
        t.setPadding(dp(18), dp(10), dp(18), dp(10))
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 16f
        tv.setTextColor(Color.WHITE)
        t.addView(tv)
        t.setOnClickListener { click() }
        tiles.add(t)
        return t
    }

    private fun tvCard(key: String, label: String, visual: View, vw: Int, vh: Int, accent: Boolean): Tile {
        val ic = dp(tvIconDp())
        val t = Tile(this)
        t.pkg = key
        t.where = TV
        t.background = focusBg(
            this, 16, Color.WHITE, 0x55FFFFFF,
            Color.parseColor(if (accent) "#E60B5BD3" else "#CC17202E")
        )
        t.setPadding(dp(8), dp(8), dp(8), dp(8))
        t.addView(visual, LinearLayout.LayoutParams(vw, vh))
        val l = lbl(label, 16f)
        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.topMargin = dp(8)
        t.addView(l, lp)
        tiles.add(t)
        return t
    }

    private fun buildTv() {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(48), dp(28), dp(48), dp(28))

        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL

        val clock = lbl("", 46f)
        clock.gravity = Gravity.START
        clock.setTypeface(clock.typeface, android.graphics.Typeface.BOLD)
        val date = lbl("", 16f, Color.parseColor("#DDFFFFFF"))
        date.gravity = Gravity.START
        val cc = LinearLayout(this)
        cc.orientation = LinearLayout.VERTICAL
        cc.addView(clock)
        cc.addView(date)
        top.addView(cc, LinearLayout.LayoutParams(0, WRAP, 1f))

        val ram = lbl("", 16f)
        val ramLp = LinearLayout.LayoutParams(WRAP, WRAP)
        ramLp.rightMargin = dp(20)
        top.addView(ram, ramLp)

        val freeBtn = pill("#free", "Освободить ОЗУ") { freeMemory() }
        val setBtn = pill("#settings", "Настройки лаунчера") { showSettings() }
        val bl = LinearLayout.LayoutParams(WRAP, WRAP)
        bl.rightMargin = dp(12)
        top.addView(freeBtn, bl)
        top.addView(setBtn, LinearLayout.LayoutParams(WRAP, WRAP))
        col.addView(top, LinearLayout.LayoutParams(MATCH, WRAP))

        val frame = FrameLayout(this)
        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        hs.clipToPadding = false
        hs.setPadding(dp(8), dp(24), dp(8), dp(24))
        val ribbon = LinearLayout(this)
        ribbon.orientation = LinearLayout.HORIZONTAL
        ribbon.gravity = Gravity.CENTER_VERTICAL

        val ic = dp(tvIconDp())
        val cw = ic + dp(120)
        val ch = ic + dp(84)
        fun addCard(t: Tile) {
            val lp = LinearLayout.LayoutParams(cw, ch)
            lp.rightMargin = dp(16)
            ribbon.addView(t, lp)
        }

        // 1) Входы (HDMI)
        val hd = GlyphView(this, GlyphView.HDMI)
        hd.color = Color.WHITE
        hd.textColor = Color.parseColor("#0B5BD3")
        val hdmiCard = tvCard("#hdmi", "Входы (HDMI)", hd, ic * 3 / 2, ic * 3 / 4 + dp(8), true)
        hdmiCard.where = TV
        hdmiCard.setOnClickListener { showHdmi() }
        addCard(hdmiCard)

        // 2) закрепленные приложения
        for (pkg in pinned) {
            val iv = ImageView(this)
            iv.setImageDrawable(icon(pkg))
            val c = tvCard(pkg, nameOf(pkg), iv, ic, ic, false)
            if (isMovingTile(pkg, TV)) c.background = focusBg(this, 16, Color.parseColor("#FFC107"), 0x55FFC107, 0x44FFC107)
            c.setOnClickListener { launch(pkg) }
            c.setOnLongClickListener { showTileMenu(pkg, TV); true }
            addCard(c)
        }

        // 3) все приложения / добавить
        val g = GlyphView(this, GlyphView.GRID)
        val allCard = tvCard("#all", "Все приложения", g, ic, ic, false)
        allCard.setOnClickListener { showAppPicker(PICK_LAUNCH) }
        addCard(allCard)
        val pl = GlyphView(this, GlyphView.PLUS)
        val pinCard = tvCard("#add", "Закрепить", pl, ic, ic, false)
        pinCard.setOnClickListener { showAppPicker(PICK_PIN) }
        addCard(pinCard)

        hs.addView(ribbon, FrameLayout.LayoutParams(WRAP, WRAP))
        frame.addView(hs, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER_VERTICAL))
        col.addView(frame, LinearLayout.LayoutParams(MATCH, 0, 1f))
        holder.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))
        tvScroll = hs

        tickers.add {
            val now = Date()
            clock.text = timeFmt.format(now)
            date.text = dateLongFmt.format(now)
            ram.text = ramText()
            ram.setTextColor(ramColor(true))
        }
    }

    // ---- Режим Windows 11

    private fun buildWin() {
        val tbH = dp(52)
        val sw = resources.displayMetrics.widthPixels
        val sizePx = dp(deskIconDp())
        val tileW = sizePx + dp(40)
        val tileH = sizePx + dp(62)
        deskCols = maxOf(3, (sw - dp(16)) / tileW)

        val scroll = ScrollView(this)
        scroll.isVerticalScrollBarEnabled = false
        scroll.isFillViewport = true
        scroll.setPadding(dp(8), dp(10), dp(8), tbH)
        val grid = GridLayout(this)
        grid.columnCount = deskCols
        grid.isLongClickable = true
        grid.setOnLongClickListener { showAppPicker(PICK_DESKTOP); true } // долгое OK на пустом месте
        val ts = floatArrayOf(15f, 13f, 12f)[store.iconSize.coerceIn(0, 2)]

        for (pkg in desktop) {
            val t = Tile(this)
            t.pkg = pkg
            t.where = DESKTOP
            t.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            t.setPadding(dp(4), dp(6), dp(4), dp(4))
            t.background = if (isMovingTile(pkg, DESKTOP))
                focusBg(this, 10, Color.parseColor("#FFC107"), 0x55FFC107, 0x44FFC107)
            else focusBg(this, 10)
            val iv = ImageView(this)
            iv.setImageDrawable(icon(pkg))
            t.addView(iv, LinearLayout.LayoutParams(sizePx, sizePx))
            val l = lbl(nameOf(pkg), ts)
            val lp = LinearLayout.LayoutParams(MATCH, WRAP)
            lp.topMargin = dp(4)
            t.addView(l, lp)
            t.setOnClickListener { launch(pkg) }
            t.setOnLongClickListener { showTileMenu(pkg, DESKTOP); true }
            tiles.add(t)
            val glp = GridLayout.LayoutParams()
            glp.width = tileW
            glp.height = tileH
            grid.addView(t, glp)
        }
        // плитка "Добавить" - "пустое место", на котором можно нажать/удержать OK
        val addT = Tile(this)
        addT.pkg = "#addDesktop"
        addT.where = DESKTOP
        addT.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        addT.setPadding(dp(4), dp(6), dp(4), dp(4))
        addT.background = focusBg(this, 10)
        val plus = GlyphView(this, GlyphView.PLUS)
        addT.addView(plus, LinearLayout.LayoutParams(sizePx, sizePx))
        val al = lbl("Добавить", ts)
        val alp = LinearLayout.LayoutParams(MATCH, WRAP)
        alp.topMargin = dp(4)
        addT.addView(al, alp)
        addT.setOnClickListener { showAppPicker(PICK_DESKTOP) }
        addT.setOnLongClickListener { showAppPicker(PICK_DESKTOP); true }
        tiles.add(addT)
        val glp2 = GridLayout.LayoutParams()
        glp2.width = tileW
        glp2.height = tileH
        grid.addView(addT, glp2)

        scroll.addView(grid, FrameLayout.LayoutParams(MATCH, WRAP))
        holder.addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))
        deskScroll = scroll

        // ----- панель задач
        val bar = FrameLayout(this)
        bar.setBackgroundColor(Color.parseColor("#EBEFF3F8"))
        val center = LinearLayout(this)
        center.orientation = LinearLayout.HORIZONTAL
        center.gravity = Gravity.CENTER_VERTICAL

        fun barBtn(key: String, content: View, w: Int, h: Int, click: () -> Unit): Tile {
            val t = Tile(this)
            t.pkg = key
            t.where = TASKBAR
            t.background = focusBg(this, 8, Color.parseColor("#0067C0"), 0x330067C0)
            t.addView(content, LinearLayout.LayoutParams(w, h))
            t.setOnClickListener { click() }
            tiles.add(t)
            return t
        }
        fun addBar(t: Tile) {
            val lp = LinearLayout.LayoutParams(dp(46), dp(44))
            lp.leftMargin = dp(2)
            lp.rightMargin = dp(2)
            center.addView(t, lp)
        }

        val winG = GlyphView(this, GlyphView.WIN)
        winG.color = Color.parseColor("#1A8CFF")
        addBar(barBtn("#start", winG, dp(30), dp(30)) { showStartMenu() })

        val hg = GlyphView(this, GlyphView.HDMI)
        hg.color = Color.parseColor("#1F2937")
        addBar(barBtn("#hdmi", hg, dp(34), dp(26)) { showHdmi() })

        for (pkg in pinned) {
            val iv = ImageView(this)
            iv.setImageDrawable(icon(pkg))
            val t = barBtn(pkg, iv, dp(30), dp(30)) { launch(pkg) }
            t.setOnLongClickListener { showTileMenu(pkg, TASKBAR); true }
            if (isMovingTile(pkg, TASKBAR)) t.background = focusBg(this, 8, Color.parseColor("#FFC107"), 0x55FFC107, 0x55FFC107)
            addBar(t)
        }
        bar.addView(center, FrameLayout.LayoutParams(WRAP, MATCH, Gravity.CENTER))

        val right = LinearLayout(this)
        right.orientation = LinearLayout.HORIZONTAL
        right.gravity = Gravity.CENTER_VERTICAL
        right.setPadding(0, 0, dp(12), 0)

        val lan = TextView(this)
        lan.text = "LAN"
        lan.textSize = 12f
        lan.setTextColor(Color.parseColor("#0A2A5C"))
        lan.visibility = View.GONE
        right.addView(lan)
        val wifi = GlyphView(this, GlyphView.WIFI)
        val wlp = LinearLayout.LayoutParams(dp(22), dp(22))
        wlp.leftMargin = dp(6)
        wlp.rightMargin = dp(10)
        right.addView(wifi, wlp)

        val ramTv = TextView(this)
        ramTv.textSize = 13f
        val ramBtn = Tile(this)
        ramBtn.pkg = "#ram"
        ramBtn.where = TASKBAR
        ramBtn.background = focusBg(this, 8, Color.parseColor("#0067C0"), 0x330067C0)
        ramBtn.setPadding(dp(8), dp(4), dp(8), dp(4))
        ramBtn.addView(ramTv)
        ramBtn.setOnClickListener { freeMemory() }
        tiles.add(ramBtn)
        right.addView(ramBtn)

        val tcol = LinearLayout(this)
        tcol.orientation = LinearLayout.VERTICAL
        tcol.gravity = Gravity.END
        val timeTv = TextView(this)
        timeTv.textSize = 13f
        timeTv.setTextColor(Color.parseColor("#1B1F24"))
        timeTv.gravity = Gravity.END
        val dateTv = TextView(this)
        dateTv.textSize = 12f
        dateTv.setTextColor(Color.parseColor("#1B1F24"))
        dateTv.gravity = Gravity.END
        tcol.addView(timeTv)
        tcol.addView(dateTv)
        val tlp = LinearLayout.LayoutParams(WRAP, WRAP)
        tlp.leftMargin = dp(10)
        right.addView(tcol, tlp)
        bar.addView(right, FrameLayout.LayoutParams(WRAP, MATCH, Gravity.END))

        holder.addView(bar, FrameLayout.LayoutParams(MATCH, tbH, Gravity.BOTTOM))
        taskbar = bar

        tickers.add {
            val now = Date()
            timeTv.text = timeFmt.format(now)
            dateTv.text = dateFmt.format(now)
            val nt = netType()
            wifi.color = Color.parseColor(if (nt == 1) "#0A2A5C" else "#9AA5B1")
            lan.visibility = if (nt == 2) View.VISIBLE else View.GONE
            val mi = mem()
            ramTv.text = "ОЗУ " + (mi.availMem / 1048576L) + " МБ"
            ramTv.setTextColor(ramColor(false))
        }
    }

    // ---------------------------------------------------------------- dialogs

    private fun column(): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        return c
    }

    private fun rowLp(): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.bottomMargin = dp(4)
        return lp
    }

    private fun note(text: String): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = 16f
        t.setTextColor(Color.parseColor("#CCFFFFFF"))
        t.setPadding(dp(8), dp(8), dp(8), dp(12))
        return t
    }

    private fun showDlg(title: String?, content: View, widthDp: Int = 480, bottomOffsetDp: Int? = null): Dialog {
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(16), dp(14), dp(16), dp(14))
        val bg = GradientDrawable()
        bg.setColor(Color.parseColor("#F01B2330"))
        bg.cornerRadius = dp(16).toFloat()
        box.background = bg
        if (title != null) {
            val tt = TextView(this)
            tt.text = title
            tt.textSize = 22f
            tt.setTextColor(Color.WHITE)
            tt.setPadding(dp(8), 0, dp(8), dp(10))
            box.addView(tt)
        }
        val sv = MaxHeightScrollView(this, (resources.displayMetrics.heightPixels * 0.78f).toInt())
        sv.addView(content)
        box.addView(sv, LinearLayout.LayoutParams(MATCH, WRAP))
        d.setContentView(box)
        val w = d.window
        if (w != null) {
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setLayout(dp(widthDp), WRAP)
            if (bottomOffsetDp != null) {
                w.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                w.attributes.y = dp(bottomOffsetDp)
            }
        }
        dialogs.add(d)
        d.setOnDismissListener { dialogs.remove(d) }
        d.show()
        return d
    }

    // ---- контекстное меню иконки

    private fun showTileMenu(pkg: String, where: Int) {
        if (moving) return
        rememberFocus()
        val col = column()
        var dlg: Dialog? = null
        fun item(text: String, a: () -> Unit) {
            val r = Row(this, null, text)
            r.setOnClickListener { dlg?.dismiss(); a() }
            col.addView(r, rowLp())
        }
        item("Открыть") { launch(pkg) }
        if (!(where == DESKTOP && store.locked)) item("Передвинуть") { startMove(where, pkg) }
        if (where == TV) item("Открепить") { togglePin(pkg) }
        else if (pinned.contains(pkg)) item("Открепить от панели задач") { togglePin(pkg) }
        else item("Закрепить на панели задач") { togglePin(pkg) }
        item("Переименовать") { renameDialog(pkg) }
        if (where == DESKTOP && !store.locked) item("Убрать с рабочего стола") {
            desktop.remove(pkg)
            saveLists()
            lastPkg = null
            rebuild()
        }
        item("О приложении / Удалить") {
            val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
            if (!startSafe(i)) toast("Не удалось открыть настройки приложения")
        }
        dlg = showDlg(nameOf(pkg), col, 440)
    }

    private fun togglePin(pkg: String) {
        if (pinned.contains(pkg)) pinned.remove(pkg) else pinned.add(pkg)
        saveLists()
        lastPkg = null
        rebuild()
    }

    private fun renameDialog(pkg: String) {
        val et = EditText(this)
        et.setText(nameOf(pkg))
        et.setSingleLine(true)
        et.inputType = InputType.TYPE_CLASS_TEXT
        et.setSelectAllOnFocus(true)
        val b = AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("Переименовать ярлык")
            .setView(et)
            .setPositiveButton("OK") { _, _ ->
                store.setLabel(pkg, et.text.toString().trim())
                rebuild()
            }
            .setNeutralButton("Сбросить") { _, _ ->
                store.setLabel(pkg, null)
                rebuild()
            }
            .setNegativeButton("Отмена", null)
        val d = b.create()
        dialogs.add(d)
        d.setOnDismissListener { dialogs.remove(d) }
        d.show()
    }

    // ---- выбор приложений

    private fun showAppPicker(mode: Int) {
        if (moving) return
        rememberFocus()
        val col = column()
        var dlg: Dialog? = null
        if (mode == PICK_DESKTOP) {
            val r = Row(this, null, "Упорядочить по алфавиту", true)
            r.setOnClickListener {
                desktop.sortBy { nameOf(it).lowercase() }
                saveLists()
                rebuild()
                toast("Ярлыки упорядочены по алфавиту")
            }
            col.addView(r, rowLp())
        }
        val sorted = apps.values.map { it.pkg }.sortedBy { nameOf(it).lowercase() }
        for (pkg in sorted) {
            fun mark(): String {
                val on = (mode == PICK_DESKTOP && desktop.contains(pkg)) || (mode == PICK_PIN && pinned.contains(pkg))
                return if (on) "  — добавлено" else ""
            }
            val r = Row(this, icon(pkg), nameOf(pkg) + mark())
            r.setOnClickListener {
                when (mode) {
                    PICK_LAUNCH -> {
                        dlg?.dismiss()
                        launch(pkg)
                    }
                    PICK_DESKTOP -> {
                        if (!desktop.contains(pkg)) desktop.add(pkg)
                        saveLists()
                        r.tv.text = nameOf(pkg) + mark()
                        rebuild()
                    }
                    else -> {
                        if (!pinned.contains(pkg)) pinned.add(pkg)
                        saveLists()
                        r.tv.text = nameOf(pkg) + mark()
                        rebuild()
                    }
                }
            }
            col.addView(r, rowLp())
        }
        val title = when (mode) {
            PICK_DESKTOP -> "Добавить ярлык на рабочий стол"
            PICK_PIN -> "Закрепить приложение"
            else -> "Все приложения"
        }
        dlg = showDlg(title, col, 560)
    }

    // ---- HDMI

    private fun showHdmi() {
        if (moving) return
        rememberFocus()
        val col = column()
        var dlg: Dialog? = null
        val inputs = Hdmi.list(this)
        if (inputs.isEmpty()) {
            col.addView(note("Система не вернула список входов. Нажмите «Системный выбор входа» ниже."))
        }
        for (inp in inputs) {
            val r = Row(this, null, inp.label + (if (inp.connected) "  — подключено" else ""), inp.connected)
            r.setOnClickListener {
                dlg?.dismiss()
                switchInput(inp)
            }
            col.addView(r, rowLp())
        }
        val sys = Row(this, null, "Системный выбор входа", true)
        sys.setOnClickListener {
            dlg?.dismiss()
            openSystemInputPicker()
        }
        col.addView(sys, rowLp())
        dlg = showDlg("Входы (HDMI)", col, 480)
    }

    private fun switchInput(inp: InputItem) {
        try {
            val uri = TvContract.buildChannelUriForPassthroughInput(inp.id)
            val i = Intent(Intent.ACTION_VIEW, uri)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            taskbar?.visibility = View.GONE
            startActivity(i)
        } catch (e: Exception) {
            taskbar?.visibility = View.VISIBLE
            toast("Прошивка не дала переключить вход напрямую. Используйте «Системный выбор входа».")
        }
    }

    private fun openSystemInputPicker() {
        // 1. приложение с «вход/источник/input/source» в названии
        val keys = listOf("input", "вход", "источник", "source", "hdmi")
        for (a in apps.values) {
            val l = nameOf(a.pkg).lowercase()
            if (keys.any { l.contains(it) }) {
                launch(a.pkg)
                return
            }
        }
        // 2. системные ТВ-приложения / экран настройки входов / общие настройки
        val tries = listOf(
            Intent(Intent.ACTION_MAIN).setClassName("com.android.tv", "com.android.tv.TvActivity"),
            Intent(Intent.ACTION_MAIN).setClassName("com.google.android.tv", "com.android.tv.TvActivity"),
            Intent("android.media.tv.action.SETUP_INPUTS"),
            Intent(Settings.ACTION_SETTINGS)
        )
        for (i in tries) if (startSafe(i)) return
        toast("Системный выбор входа недоступен. Нажмите кнопку INPUT/SOURCE на пульте.")
    }

    // ---- настройки и меню Пуск

    private fun openMainMenu() {
        if (store.mode == "win") showStartMenu() else showSettings()
    }

    private fun showStartMenu() {
        if (moving) return
        rememberFocus()
        val col = column()
        var dlg: Dialog? = null
        val hd = Row(this, null, "Входы (HDMI)", true)
        hd.setOnClickListener { dlg?.dismiss(); showHdmi() }
        col.addView(hd, rowLp())
        val all = Row(this, null, "Все приложения")
        all.setOnClickListener { dlg?.dismiss(); showAppPicker(PICK_LAUNCH) }
        col.addView(all, rowLp())
        val add = Row(this, null, "Добавить ярлык на рабочий стол")
        add.setOnClickListener { dlg?.dismiss(); showAppPicker(PICK_DESKTOP) }
        col.addView(add, rowLp())
        addSettingRows(col) { dlg?.dismiss() }
        dlg = showDlg("Пуск", col, 520, 60)
    }

    private fun showSettings() {
        if (moving) return
        rememberFocus()
        val col = column()
        var dlg: Dialog? = null
        val hd = Row(this, null, "Входы (HDMI)", true)
        hd.setOnClickListener { dlg?.dismiss(); showHdmi() }
        col.addView(hd, rowLp())
        addSettingRows(col) { dlg?.dismiss() }
        dlg = showDlg("Настройки лаунчера", col, 520)
    }

    private fun addSettingRows(col: LinearLayout, close: () -> Unit) {
        fun add(r: Row) = col.addView(r, rowLp())
        fun toggle(text: () -> String, act: () -> Unit) {
            val r = Row(this, null, text())
            r.setOnClickListener { act(); r.tv.text = text() }
            add(r)
        }
        fun plain(text: String, accent: Boolean = false, act: () -> Unit) {
            val r = Row(this, null, text, accent)
            r.setOnClickListener { act() }
            add(r)
        }

        // индикатор ОЗУ + освободить
        val ramRow = Row(this, null, ramText() + "  (нажмите: освободить)")
        ramRow.setOnClickListener {
            freeMemory { ramRow.tv.text = ramText() + "  (нажмите: освободить)" }
        }
        add(ramRow)

        // интерфейс
        plain("Стиль ТВ (по умолчанию)" + (if (store.mode == "tv") "  — выбран" else "")) {
            store.mode = "tv"
            lastPkg = null
            close()
            rebuild()
        }
        plain("Стиль Windows 11" + (if (store.mode == "win") "  — выбран" else "")) {
            store.mode = "win"
            lastPkg = null
            close()
            rebuild()
        }
        val sizes = listOf("Крупные", "Средние", "Мелкие")
        toggle({ "Размер иконок: " + sizes[store.iconSize.coerceIn(0, 2)] }) {
            store.iconSize = (store.iconSize + 1) % 3
            rebuild()
        }
        toggle({ "Заблокировать рабочий стол: " + (if (store.locked) "Вкл" else "Выкл") }) {
            store.locked = !store.locked
        }
        toggle({ "Виртуальный курсор: " + (if (store.cursorMode) "Вкл" else "Выкл") + " (долго Меню)" }) {
            store.cursorMode = !store.cursorMode
            applyCursor()
        }
        plain("Выбор обоев") { showWallpapers() }

        // системные
        plain("Настройки системы", true) { openSystemSettings() }
        plain("Все приложения (Удаление)") {
            if (!startSafe(Intent(Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS)) &&
                !startSafe(Intent(Settings.ACTION_APPLICATION_SETTINGS))
            ) openSystemSettings()
        }
        plain("Wi-Fi") {
            if (!startSafe(Intent(Settings.ACTION_WIFI_SETTINGS))) openSystemSettings()
        }
        plain("Bluetooth") {
            if (!startSafe(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))) openSystemSettings()
        }
    }

    private fun openSystemSettings() {
        val a = Intent().setClassName("com.android.tv.settings", "com.android.tv.settings.MainSettings")
        if (startSafe(a)) return
        if (!startSafe(Intent(Settings.ACTION_SETTINGS))) toast("Не удалось открыть настройки системы")
    }

    // ---------------------------------------------------------------- wallpapers

    private fun showWallpapers() {
        val col = column()
        var dlg: Dialog? = null
        val opts = listOf(
            "default" to "Windows 11 Bloom (по умолчанию)",
            "night" to "Тёмная ночь",
            "sunset" to "Неоновый закат",
            "blue" to "Классический синий"
        )
        val rows = ArrayList<Pair<String, Row>>()
        val custom = Row(this, null, "", true)
        fun refresh() {
            for ((k, r) in rows) {
                val name = opts.first { it.first == k }.second
                r.tv.text = name + (if (store.wpType == k) "  — выбран" else "")
            }
            custom.tv.text = "Выбрать свое фото из памяти ТВ" + (if (store.wpType == "custom") "  — выбрано" else "")
        }
        for ((k, name) in opts) {
            val r = Row(this, null, name)
            r.setOnClickListener {
                store.wpType = k
                applyWallpaper()
                refresh()
            }
            rows.add(k to r)
            col.addView(r, rowLp())
        }
        custom.setOnClickListener {
            dlg?.dismiss()
            pickPhoto()
        }
        col.addView(custom, rowLp())
        refresh()
        dlg = showDlg("Выбор обоев", col, 520)
    }

    private fun pickPhoto() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "image/*"
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        try {
            startActivityForResult(i, REQ_WP)
        } catch (e: Exception) {
            try {
                val g = Intent(Intent.ACTION_GET_CONTENT)
                g.addCategory(Intent.CATEGORY_OPENABLE)
                g.type = "image/*"
                startActivityForResult(Intent.createChooser(g, "Выбор фото"), REQ_WP)
            } catch (e2: Exception) {
                toast("На этом ТВ нет проводника для выбора файлов")
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_WP && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
            }
            store.wpUri = uri.toString()
            store.wpType = "custom"
            applyWallpaper()
        }
    }

    private fun grad(o: GradientDrawable.Orientation, vararg c: String) {
        wallpaper.setImageDrawable(GradientDrawable(o, c.map { Color.parseColor(it) }.toIntArray()))
    }

    private fun applyWallpaper() {
        when (store.wpType) {
            "night" -> grad(GradientDrawable.Orientation.TOP_BOTTOM, "#02030A", "#0A1330", "#16254F")
            "sunset" -> grad(GradientDrawable.Orientation.TL_BR, "#12062E", "#6A1B9A", "#FF2D95", "#FF8A3D")
            "blue" -> grad(GradientDrawable.Orientation.TL_BR, "#0B3C91", "#1565C0", "#42A5F5", "#90CAF9")
            "custom" -> loadCustomWallpaper()
            else -> loadDefaultWallpaper()
        }
    }

    private fun loadDefaultWallpaper() {
        val o = BitmapFactory.Options()
        o.inPreferredConfig = Bitmap.Config.RGB_565
        try {
            wallpaper.setImageBitmap(BitmapFactory.decodeResource(resources, R.drawable.wallpaper_default, o))
        } catch (e: Throwable) {
            grad(GradientDrawable.Orientation.TOP_BOTTOM, "#050A1E", "#0A2A9A")
        }
    }

    private fun loadCustomWallpaper() {
        val s = store.wpUri
        if (s == null) {
            loadDefaultWallpaper()
            return
        }
        val uri = Uri.parse(s)
        Thread {
            var bmp: Bitmap? = null
            try {
                // 1) читаем только размеры
                val bounds = BitmapFactory.Options()
                bounds.inJustDecodeBounds = true
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                // 2) подбираем inSampleSize под 1920x1080
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= 1920 && bounds.outHeight / (sample * 2) >= 1080) sample *= 2
                // 3) декодируем в RGB_565 (в 2 раза меньше памяти)
                var tries = 0
                while (bmp == null && tries < 3) {
                    try {
                        val o = BitmapFactory.Options()
                        o.inSampleSize = sample
                        o.inPreferredConfig = Bitmap.Config.RGB_565
                        bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
                        break
                    } catch (oom: OutOfMemoryError) {
                        sample *= 2
                        tries++
                    }
                }
            } catch (e: Throwable) {
                bmp = null
            }
            runOnUiThread {
                if (bmp != null) {
                    wallpaper.setImageBitmap(bmp)
                } else {
                    toast("Не удалось загрузить фото, возвращены обои по умолчанию")
                    store.wpType = "default"
                    loadDefaultWallpaper()
                }
            }
        }.start()
    }

    // ---------------------------------------------------------------- move mode

    private fun curMoveList(): MutableList<String> = if (moveWhich == DESKTOP) desktop else pinned

    private fun isMovingTile(pkg: String, where: Int): Boolean =
        moving && moveWhich == where && curMoveList().getOrNull(moveIdx) == pkg

    private fun startMove(where: Int, pkg: String) {
        moveWhich = if (where == DESKTOP) DESKTOP else where
        val l = curMoveList()
        moveIdx = l.indexOf(pkg)
        if (moveIdx < 0) return
        moveBackup = l.toList()
        moving = true
        lastPkg = pkg
        lastWhere = where
        rebuild()
        toast("Стрелки — переместить, OK — зафиксировать, Назад — отмена")
    }

    private fun finishMove(cancel: Boolean) {
        if (!moving) return
        if (cancel) {
            val l = curMoveList()
            l.clear()
            l.addAll(moveBackup)
        } else {
            saveLists()
        }
        moving = false
        rebuild()
    }

    private fun handleMoveKey(code: Int) {
        val l = curMoveList()
        val i = moveIdx
        var ni = i
        when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> ni = i - 1
            KeyEvent.KEYCODE_DPAD_RIGHT -> ni = i + 1
            KeyEvent.KEYCODE_DPAD_UP -> if (moveWhich == DESKTOP) ni = i - deskCols
            KeyEvent.KEYCODE_DPAD_DOWN -> if (moveWhich == DESKTOP) ni = i + deskCols
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                finishMove(false)
                return
            }
            KeyEvent.KEYCODE_BACK -> {
                finishMove(true)
                return
            }
        }
        if (ni != i && ni >= 0 && ni < l.size) {
            val t = l[i]
            l[i] = l[ni]
            l[ni] = t
            moveIdx = ni
            lastPkg = l[ni]
            lastWhere = moveWhich
            rebuild()
        }
    }

    // ---------------------------------------------------------------- cursor

    private fun applyCursor() {
        dirMask = 0
        if (store.cursorMode) {
            cursor.visibility = View.VISIBLE
            positionCursor()
        } else {
            cursor.visibility = View.GONE
        }
    }

    private fun toggleCursor() {
        store.cursorMode = !store.cursorMode
        applyCursor()
        toast("Виртуальный курсор: " + (if (store.cursorMode) "включён" else "выключен"))
    }

    private fun positionCursor() {
        cursor.translationX = cx
        cursor.translationY = cy
    }

    private fun moveCursor(dx: Float, dy: Float) {
        val dm = resources.displayMetrics
        cx = (cx + dx).coerceIn(0f, dm.widthPixels - 1f)
        cy = (cy + dy).coerceIn(0f, dm.heightPixels - 1f)
        positionCursor()
        // у края экрана прокручиваем рабочий стол / ленту
        if (cy > dm.heightPixels - dp(70)) deskScroll?.scrollBy(0, dp(8))
        if (cy < dp(40)) deskScroll?.scrollBy(0, -dp(8))
        if (cx > dm.widthPixels - dp(60)) tvScroll?.scrollBy(dp(12), 0)
        if (cx < dp(60)) tvScroll?.scrollBy(-dp(12), 0)
    }

    private fun sendTouch(action: Int) {
        val t = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) downTime = t
        val ev = MotionEvent.obtain(downTime, t, action, cx, cy, 0)
        ev.source = InputDevice.SOURCE_TOUCHSCREEN
        window.decorView.dispatchTouchEvent(ev)
        ev.recycle()
    }

    // ---------------------------------------------------------------- keys

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val code = e.keyCode
        val down = e.action == KeyEvent.ACTION_DOWN

        if (moving) {
            if (down) handleMoveKey(code)
            return true
        }

        when (code) {
            KeyEvent.KEYCODE_TV_INPUT -> {
                if (down && e.repeatCount == 0) showHdmi()
                return true
            }
            KeyEvent.KEYCODE_SETTINGS -> {
                if (down && e.repeatCount == 0) openMainMenu()
                return true
            }
            KeyEvent.KEYCODE_MENU -> {
                if (down) {
                    if (e.repeatCount == 0) {
                        menuLongFired = false
                        handler.postDelayed(menuLongRun, 800)
                    }
                } else {
                    handler.removeCallbacks(menuLongRun)
                    if (!menuLongFired) openMainMenu()
                    menuLongFired = false
                }
                return true
            }
            KeyEvent.KEYCODE_BACK -> return true
        }

        if (store.cursorMode) {
            val bit = when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> 1
                KeyEvent.KEYCODE_DPAD_RIGHT -> 2
                KeyEvent.KEYCODE_DPAD_UP -> 4
                KeyEvent.KEYCODE_DPAD_DOWN -> 8
                else -> 0
            }
            if (bit != 0) {
                if (down) {
                    if (dirMask == 0) {
                        val now = SystemClock.uptimeMillis()
                        holdStart = now
                        lastT = now
                        dirMask = bit
                        handler.removeCallbacks(cursorRun)
                        handler.post(cursorRun)
                    } else {
                        dirMask = dirMask or bit
                    }
                } else {
                    dirMask = dirMask and bit.inv()
                }
                return true
            }
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER ||
                code == KeyEvent.KEYCODE_NUMPAD_ENTER
            ) {
                if (down) {
                    if (e.repeatCount == 0) sendTouch(MotionEvent.ACTION_DOWN)
                } else {
                    sendTouch(MotionEvent.ACTION_UP)
                }
                return true
            }
            return super.dispatchKeyEvent(e)
        }

        // обычный режим: короткое OK = клик, долгое OK = контекстное меню
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER ||
            code == KeyEvent.KEYCODE_NUMPAD_ENTER
        ) {
            if (down) {
                if (e.repeatCount == 0) {
                    okLongFired = false
                    handler.postDelayed(okLongRun, 650)
                }
            } else {
                handler.removeCallbacks(okLongRun)
                if (!okLongFired) currentFocus?.performClick()
                okLongFired = false
            }
            return true
        }
        return super.dispatchKeyEvent(e)
    }
}
