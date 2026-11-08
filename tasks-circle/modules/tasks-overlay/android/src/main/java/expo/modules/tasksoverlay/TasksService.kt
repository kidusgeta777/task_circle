package expo.modules.tasksoverlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Calendar
import java.util.Locale

/**
 * The floating circle. Collapsed it is a small draggable circle. Tapping it opens the to-do list:
 * every task starts with a minus (removes it) and the last row has a plus (adds a new task).
 */
class TasksService : Service() {

  companion object {
    const val ACTION_STOP = "expo.modules.tasksoverlay.STOP"
    private const val CHANNEL = "tasks_overlay"
    private const val NOTIF_ID = 4108

    @Volatile
    var running = false
  }

  private lateinit var wm: WindowManager
  private val main = Handler(Looper.getMainLooper())
  private var dp = 1f

  private var bubble: FrameLayout? = null
  private var bubbleLp: WindowManager.LayoutParams? = null

  private var panel: PanelLayout? = null
  private var panelLp: WindowManager.LayoutParams? = null
  private var scroll: ScrollView? = null
  private var listBox: LinearLayout? = null
  private var input: EditText? = null

  private var open = false
  private var draft = ""
  private val MAX_SHOWN = 3
  private val AUTO_CLOSE_MS = 6000L   // popup collapses after 6 seconds without touching it or typing
  private var addDate = ""     // date for the next task as yyyy-MM-dd, "" = no date
  private var dateChip: TextView? = null
  private var calBox: LinearLayout? = null   // the month calendar (today up to 3 months ahead)
  private var calOpen = false
  private var calMonth = 0       // 0 = this month ... 3 = three months ahead
  private var restoreY = -1

  private fun px(v: Int): Int = (v * dp + 0.5f).toInt()
  private val circleSize: Int get() = px(36)

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    dp = resources.displayMetrics.density
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_STOP) {
      TasksPrefs.setEnabled(this, false)
      stopSelf()
      return START_NOT_STICKY
    }
    // A foreground service must always call startForeground right after being started.
    startFg()
    if (!TasksPrefs.enabled(this) || !Settings.canDrawOverlays(this)) {
      stopSelf()
      return START_NOT_STICKY
    }
    if (bubble == null) addBubble()
    running = true
    return START_STICKY
  }

  override fun onDestroy() {
    running = false
    closePanel()
    bubble?.let { runCatching { wm.removeView(it) } }
    bubble = null
    super.onDestroy()
  }

  // ------------------------------------------------------------ notification --
  @Suppress("DEPRECATION")
  private fun startFg() {
    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (Build.VERSION.SDK_INT >= 26) {
      nm.createNotificationChannel(
        NotificationChannel(CHANNEL, "Floating circle", NotificationManager.IMPORTANCE_LOW)
      )
    }
    val piFlags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    val stopPi = PendingIntent.getService(
      this, 1, Intent(this, TasksService::class.java).setAction(ACTION_STOP), piFlags
    )
    val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
    b.setContentTitle("Tasks is on")
      .setContentText("The floating circle is showing")
      .setSmallIcon(android.R.drawable.ic_menu_agenda)
      .setOngoing(true)
    packageManager.getLaunchIntentForPackage(packageName)?.let {
      b.setContentIntent(PendingIntent.getActivity(this, 0, it, piFlags))
    }
    b.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Turn off", stopPi)
    val n = b.build()
    if (Build.VERSION.SDK_INT >= 34) {
      startForeground(NOTIF_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    } else {
      startForeground(NOTIF_ID, n)
    }
  }

  // ------------------------------------------------------------------ drawing --
  @Suppress("DEPRECATION")
  private fun overlayType(): Int {
    return if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    else WindowManager.LayoutParams.TYPE_PHONE
  }

  private fun roundBg(fill: Int, radiusPx: Int, stroke: Int, oval: Boolean = false): GradientDrawable {
    val g = GradientDrawable()
    g.shape = if (oval) GradientDrawable.OVAL else GradientDrawable.RECTANGLE
    if (!oval) g.cornerRadius = radiusPx.toFloat()
    g.setColor(fill)
    g.setStroke(px(1), stroke)
    return g
  }

  private val panelFill get() = Color.argb(110, 0, 0, 0)
  private val outline get() = Color.argb(120, 255, 255, 255)

  private fun addBubble() {
    val dm = resources.displayMetrics
    val size = circleSize
    val frame = FrameLayout(this)
    frame.background = roundBg(Color.argb(245, 0, 0, 0), 0, outline, oval = true)
    frame.addView(IconView(this, IconView.LIST), FrameLayout.LayoutParams(px(16), px(16), Gravity.CENTER))

    val lp = WindowManager.LayoutParams(
      size, size, overlayType(),
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
      PixelFormat.TRANSLUCENT
    )
    lp.gravity = Gravity.TOP or Gravity.START
    lp.x = TasksPrefs.posX(this, dm.widthPixels - size - px(16))
    lp.y = TasksPrefs.posY(this, dm.heightPixels / 2)
    clampInto(lp)

    frame.setOnTouchListener(object : View.OnTouchListener {
      var sx = 0f
      var sy = 0f
      var ox = 0
      var oy = 0
      var moved = false
      override fun onTouch(v: View, e: MotionEvent): Boolean {
        when (e.action) {
          MotionEvent.ACTION_DOWN -> {
            sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y; moved = false
            return true
          }
          MotionEvent.ACTION_MOVE -> {
            val dx = e.rawX - sx
            val dy = e.rawY - sy
            if (!moved && Math.abs(dx) + Math.abs(dy) > px(6)) moved = true
            if (moved) {
              lp.x = ox + dx.toInt()
              lp.y = oy + dy.toInt()
              clampInto(lp)
              wm.updateViewLayout(frame, lp)
              if (open) layoutPanel()
            }
            return true
          }
          MotionEvent.ACTION_UP -> {
            if (!moved) togglePanel()
            else {
              if (open) restoreY = lp.y
              TasksPrefs.savePos(this@TasksService, lp.x, lp.y)
            }
            return true
          }
        }
        return false
      }
    })

    wm.addView(frame, lp)
    bubble = frame
    bubbleLp = lp
  }

  /** Keep the circle on screen. */
  private fun clampInto(lp: WindowManager.LayoutParams) {
    val dm = resources.displayMetrics
    val size = circleSize
    val maxY = dm.heightPixels - size
    lp.x = lp.x.coerceIn(0, dm.widthPixels - size)
    lp.y = lp.y.coerceIn(0, maxY.coerceAtLeast(0))
  }

  // -------------------------------------------------------------------- panel --
  private fun togglePanel() {
    if (open) closePanel() else openPanel()
  }

  private fun openPanel() {
    val blp = bubbleLp ?: return
    val bub = bubble ?: return
    if (open) return
    restoreY = blp.y
    open = true
    clampInto(blp)
    wm.updateViewLayout(bub, blp)

    val root = PanelLayout(this, { closePanel() }, { if (!noteEditing) closePanel() }, { scheduleAutoClose() })
    root.outsideFilter = { ev -> noteEditing || noteContains(ev.rawX, ev.rawY) }
    root.background = roundBg(panelFill, px(18), outline)

    // the task list (scrolls when it gets long)
    val maxH = (resources.displayMetrics.heightPixels * 0.36f).toInt()
    val sv = MaxHeightScrollView(this, maxH)
    sv.isVerticalScrollBarEnabled = false
    val box = LinearLayout(this)
    box.orientation = LinearLayout.VERTICAL
    sv.addView(box, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    root.addView(sv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

    val divider = View(this)
    divider.setBackgroundColor(Color.argb(70, 255, 255, 255))
    root.addView(divider, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(1)))

    // the calendar (hidden until the date chip is tapped)
    val cal = LinearLayout(this)
    cal.orientation = LinearLayout.VERTICAL
    cal.visibility = View.GONE
    root.addView(cal, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    calBox = cal
    calOpen = false

    // the add row: [ Add a task…            (+) ]
    val addRow = LinearLayout(this)
    addRow.orientation = LinearLayout.HORIZONTAL
    addRow.gravity = Gravity.CENTER_VERTICAL
    addRow.setPadding(px(16), px(6), px(8), px(6))

    val et = EditText(this)
    et.background = null
    et.setTextColor(Color.WHITE)
    et.setHintTextColor(Color.argb(170, 255, 255, 255))
    et.hint = "Add a task…"
    et.textSize = 15f
    et.minHeight = px(40)
    et.setPadding(0, px(6), px(8), px(6))
    et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
    et.imeOptions = EditorInfo.IME_ACTION_DONE
    et.setText(draft)
    et.setSelection(et.text.length)
    et.setOnEditorActionListener { _, actionId, _ ->
      if (actionId == EditorInfo.IME_ACTION_DONE) {
        addFromInput()
        true
      } else {
        false
      }
    }
    addRow.addView(et, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

    // date chip (optional): "+ Date" = no date. Tap it to open a calendar: any day from today up to 3 months ahead
    val chip = TextView(this)
    dateChip = chip
    refreshChip()
    chip.setTextColor(Color.WHITE)
    chip.textSize = 12f
    chip.gravity = Gravity.CENTER
    chip.setPadding(px(10), px(5), px(10), px(5))
    chip.background = roundBg(Color.argb(60, 255, 255, 255), px(12), Color.TRANSPARENT)
    chip.setOnClickListener { toggleCalendar() }
    val chipLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    chipLp.rightMargin = px(4)
    addRow.addView(chip, chipLp)

    val plus = FrameLayout(this)
    plus.addView(IconView(this, IconView.PLUS), FrameLayout.LayoutParams(px(16), px(16), Gravity.CENTER))
    plus.setOnClickListener { addFromInput() }
    addRow.addView(plus, LinearLayout.LayoutParams(px(34), px(34)))

    root.addView(addRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

    // the idea row: [ Write your idea…      (+) ]  (saved on the phone, not listed here)
    val ideaRow = LinearLayout(this)
    ideaRow.orientation = LinearLayout.HORIZONTAL
    ideaRow.gravity = Gravity.CENTER_VERTICAL
    ideaRow.setPadding(px(16), px(0), px(8), px(6))
    val ie = EditText(this)
    ie.background = null
    ie.setTextColor(Color.WHITE)
    ie.setHintTextColor(Color.argb(170, 255, 255, 255))
    ie.hint = "Write your idea…"
    ie.textSize = 15f
    ie.minHeight = px(40)
    ie.setPadding(0, px(6), px(8), px(6))
    ie.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
    ie.imeOptions = EditorInfo.IME_ACTION_DONE
    val saveIdea = {
      val t = ie.text.toString().trim()
      if (t.isNotEmpty()) {
        TasksPrefs.addIdea(this, t)
        ie.setText("")
        android.widget.Toast.makeText(this, "Saved", android.widget.Toast.LENGTH_SHORT).show()
      }
    }
    ie.setOnEditorActionListener { _, actionId, _ ->
      if (actionId == EditorInfo.IME_ACTION_DONE) { saveIdea(); true } else false
    }
    ie.addTextChangedListener(object : android.text.TextWatcher {
      override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
      override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) { scheduleAutoClose() }
      override fun afterTextChanged(s: android.text.Editable?) {}
    })
    ideaInput = ie
    ideaRow.addView(ie, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    val iplus = FrameLayout(this)
    iplus.addView(IconView(this, IconView.PLUS), FrameLayout.LayoutParams(px(16), px(16), Gravity.CENTER))
    iplus.setOnClickListener { saveIdea() }
    ideaRow.addView(iplus, LinearLayout.LayoutParams(px(34), px(34)))
    root.addView(ideaRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    showNote()

    val lp = WindowManager.LayoutParams(
      px(260), WindowManager.LayoutParams.WRAP_CONTENT, overlayType(),
      WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
      PixelFormat.TRANSLUCENT
    )
    lp.gravity = Gravity.TOP or Gravity.START
    lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN

    panel = root
    panelLp = lp
    scroll = sv
    listBox = box
    input = et
    rebuildList()
    layoutPanel()
    wm.addView(root, lp)
    scheduleAutoClose()
    // once the panel has a real height (and whenever the list grows or shrinks), keep it on screen
    var lastH = 0
    root.viewTreeObserver.addOnGlobalLayoutListener {
      if (root.height != lastH) {
        lastH = root.height
        layoutPanel()
      }
    }
  }

  // ---- auto-collapse: closes by itself when nobody is typing in it ----
  private val autoClose = Runnable {
    if (input?.hasFocus() == true || ideaInput?.hasFocus() == true || noteEditing || calOpen) scheduleAutoClose()   // still typing or picking a date: keep it open
    else closePanel()
  }

  private fun scheduleAutoClose() {
    main.removeCallbacks(autoClose)
    if (open) main.postDelayed(autoClose, AUTO_CLOSE_MS)
  }

  private fun refreshChip() {
    dateChip?.text = if (addDate.isEmpty()) "+ Date" else TasksPrefs.dateLabel(addDate)
  }

  private fun hideCalendar() {
    calOpen = false
    calBox?.visibility = View.GONE
  }

  private fun toggleCalendar() {
    val box = calBox ?: return
    if (calOpen) {
      hideCalendar()
      return
    }
    panel?.let { p ->
      runCatching {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(p.windowToken, 0)   // make room for the calendar
      }
    }
    calMonth = runCatching {
      val now = Calendar.getInstance()
      val y = addDate.substring(0, 4).toInt()
      val m = addDate.substring(5, 7).toInt() - 1
      ((y - now.get(Calendar.YEAR)) * 12 + m - now.get(Calendar.MONTH)).coerceIn(0, 3)
    }.getOrDefault(0)
    calOpen = true
    buildCalendar()
    box.visibility = View.VISIBLE
  }

  private fun calText(text: String, size: Float, alpha: Int = 255): TextView {
    val tv = TextView(this)
    tv.text = text
    tv.textSize = size
    tv.gravity = Gravity.CENTER
    tv.setTextColor(Color.argb(alpha, 255, 255, 255))
    return tv
  }

  /** Draws the month calendar. Only today up to 3 months ahead can be picked. */
  private fun buildCalendar() {
    val box = calBox ?: return
    box.removeAllViews()
    box.setPadding(px(10), px(6), px(10), px(4))
    val today = TasksPrefs.dateOffset(0)
    val max = TasksPrefs.maxDate()
    val first = Calendar.getInstance()
    first.set(Calendar.DAY_OF_MONTH, 1)
    first.add(Calendar.MONTH, calMonth)
    val year = first.get(Calendar.YEAR)
    val month = first.get(Calendar.MONTH)

    // header: [<]  November 2026  [>]  Clear
    val head = LinearLayout(this)
    head.orientation = LinearLayout.HORIZONTAL
    head.gravity = Gravity.CENTER_VERTICAL
    val prev = calText("‹", 22f, if (calMonth > 0) 255 else 60)
    prev.setPadding(px(10), 0, px(10), 0)
    if (calMonth > 0) prev.setOnClickListener { calMonth--; buildCalendar() }
    val next = calText("›", 22f, if (calMonth < 3) 255 else 60)
    next.setPadding(px(10), 0, px(10), 0)
    if (calMonth < 3) next.setOnClickListener { calMonth++; buildCalendar() }
    val title = calText(java.text.SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(first.time), 14f)
    title.setTypeface(null, android.graphics.Typeface.BOLD)
    val clear = calText("Clear", 12f, 190)
    clear.setPadding(px(8), px(4), px(4), px(4))
    clear.setOnClickListener {
      addDate = ""
      refreshChip()
      hideCalendar()
    }
    head.addView(prev, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, px(32)))
    head.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    head.addView(next, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, px(32)))
    head.addView(clear, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, px(32)))
    box.addView(head, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

    // weekday letters (Sunday first)
    val dow = LinearLayout(this)
    dow.orientation = LinearLayout.HORIZONTAL
    for (l in arrayOf("S", "M", "T", "W", "T", "F", "S")) {
      dow.addView(calText(l, 11f, 150), LinearLayout.LayoutParams(0, px(22), 1f))
    }
    box.addView(dow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

    // the days
    val lead = first.get(Calendar.DAY_OF_WEEK) - 1
    val days = first.getActualMaximum(Calendar.DAY_OF_MONTH)
    val rows = (lead + days + 6) / 7
    var day = 1 - lead
    for (r in 0 until rows) {
      val row = LinearLayout(this)
      row.orientation = LinearLayout.HORIZONTAL
      for (c in 0 until 7) {
        val cellBox = FrameLayout(this)
        if (day in 1..days) {
          val ds = String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, day)
          val ok = ds >= today && ds <= max
          val sel = ds == addDate
          val tv = calText(day.toString(), 13f, if (ok) 255 else 70)
          if (sel) {
            tv.setTextColor(Color.BLACK)
            tv.background = roundBg(Color.WHITE, 0, Color.TRANSPARENT, oval = true)
          } else if (ds == today) {
            tv.background = roundBg(Color.TRANSPARENT, 0, outline, oval = true)
          }
          cellBox.addView(tv, FrameLayout.LayoutParams(px(30), px(30), Gravity.CENTER))
          if (ok) {
            cellBox.setOnClickListener {
              addDate = ds
              refreshChip()
              hideCalendar()
            }
          }
        }
        day++
        row.addView(cellBox, LinearLayout.LayoutParams(0, px(32), 1f))
      }
      box.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }
  }

  /** Fills the list with one row per task: [-] text */
  private fun rebuildList() {
    val box = listBox ?: return
    box.removeAllViews()
    // the popup shows tasks with no date, plus tasks due today or overdue; future-dated ones appear on their day
    val today = TasksPrefs.dateOffset(0)
    val tasks = TasksPrefs.tasks(this).filter { it.date.isEmpty() || it.date <= today }
    if (tasks.isEmpty()) {
      val empty = TextView(this)
      empty.text = "No tasks for today"
      empty.setTextColor(Color.argb(170, 255, 255, 255))
      empty.textSize = 14f
      empty.setPadding(px(16), px(14), px(16), px(12))
      box.addView(empty)
    } else {
      // the popup shows only the first 3 tasks, in the user's own order (move important ones up); the rest are in the app
      tasks.take(MAX_SHOWN).forEachIndexed { i, t ->
        box.addView(taskRow(t, tasks.getOrNull(i - 1), tasks.getOrNull(i + 1)))
      }
      if (tasks.size > MAX_SHOWN) {
        val more = TextView(this)
        more.text = "+" + (tasks.size - MAX_SHOWN) + " more in the app"
        more.setTextColor(Color.argb(150, 255, 255, 255))
        more.textSize = 12f
        more.setPadding(px(16), px(4), px(16), px(8))
        box.addView(more)
      }
    }
  }

  private fun taskRow(t: Task, prev: Task?, next: Task?): View {
    val row = LinearLayout(this)
    row.orientation = LinearLayout.HORIZONTAL
    row.gravity = Gravity.CENTER_VERTICAL
    row.setPadding(px(10), px(5), px(14), px(5))

    val minus = FrameLayout(this)
    minus.addView(IconView(this, IconView.MINUS), FrameLayout.LayoutParams(px(14), px(14), Gravity.CENTER))
    minus.setOnClickListener {
      TasksPrefs.deleteTask(this, t.id)
      rebuildList()
    }
    row.addView(minus, LinearLayout.LayoutParams(px(32), px(32)))

    val col = LinearLayout(this)
    col.orientation = LinearLayout.VERTICAL

    val tv = TextView(this)
    tv.text = t.text
    tv.setTextColor(Color.WHITE)
    tv.textSize = 15f
    col.addView(tv)

    if (t.date.isNotEmpty()) {
      val overdue = (TasksPrefs.dayDiff(t.date) ?: 0) < 0
      val dv = TextView(this)
      dv.text = TasksPrefs.dateLabel(t.date)
      dv.setTextColor(if (overdue) Color.WHITE else Color.argb(170, 255, 255, 255))
      if (overdue) dv.setTypeface(null, android.graphics.Typeface.BOLD)
      dv.textSize = 12f
      col.addView(dv)
    }

    val tlp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    tlp.leftMargin = px(10)
    row.addView(col, tlp)

    // move up / down (swaps with the neighbouring task in the list)
    row.addView(arrowBtn(IconView.UP, prev) { TasksPrefs.swapTasks(this, t.id, it.id); rebuildList() }, LinearLayout.LayoutParams(px(30), px(32)))
    row.addView(arrowBtn(IconView.DOWN, next) { TasksPrefs.swapTasks(this, t.id, it.id); rebuildList() }, LinearLayout.LayoutParams(px(30), px(32)))
    return row
  }

  /** An up/down arrow; empty (but still taking space, so rows line up) when there is no neighbour to swap with. */
  private fun arrowBtn(kind: Int, other: Task?, onTap: (Task) -> Unit): View {
    val f = FrameLayout(this)
    if (other != null) {
      f.addView(IconView(this, kind), FrameLayout.LayoutParams(px(14), px(14), Gravity.CENTER))
      f.setOnClickListener { onTap(other) }
    }
    return f
  }

  private fun addFromInput() {
    val et = input ?: return
    val text = et.text.toString().trim()
    if (text.isEmpty()) {
      // Nothing typed yet: the plus just opens the keyboard on the field.
      et.requestFocus()
      val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
      imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT)
      return
    }
    TasksPrefs.addTask(this, text, addDate)
    addDate = ""
    refreshChip()
    hideCalendar()
    et.setText("")
    draft = ""
    rebuildList()
    val sv = scroll
    val box = listBox
    if (sv != null && box != null) sv.post { sv.smoothScrollTo(0, box.height) }
  }

  /**
   * The panel sits right beside the circle, on the side with more room, with its top level with the top of the
   * circle. It follows the circle when the circle is moved.
   */
  private fun layoutPanel() {
    val lp = panelLp ?: return
    val blp = bubbleLp ?: return
    val dm = resources.displayMetrics
    val gap = px(8)
    val w = minOf(px(280), dm.widthPixels - circleSize - gap - px(8))
    lp.width = w
    val circleOnRight = blp.x + circleSize / 2 > dm.widthPixels / 2
    lp.x = if (circleOnRight) blp.x - gap - w else blp.x + circleSize + gap
    lp.x = lp.x.coerceIn(0, (dm.widthPixels - w).coerceAtLeast(0))
    lp.y = blp.y   // top of the panel level with the top of the circle
    val h = panel?.height ?: 0
    if (h > 0 && lp.y + h > dm.heightPixels) lp.y = (dm.heightPixels - h).coerceAtLeast(0)  // only if it would run off the bottom
    panel?.let { if (it.isAttachedToWindow) wm.updateViewLayout(it, lp) }
  }

  // A small strip fixed at the top while the popup is open. The pencil edits its text.
  private var note: LinearLayout? = null
  private var noteEditing = false
  private var ideaInput: EditText? = null

  private fun noteContains(x: Float, y: Float): Boolean {
    val n = note ?: return false
    val loc = IntArray(2)
    n.getLocationOnScreen(loc)
    return x >= loc[0] && x <= loc[0] + n.width && y >= loc[1] && y <= loc[1] + n.height
  }

  private fun showNote() {
    if (note != null) return
    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    val row = LinearLayout(this)
    row.orientation = LinearLayout.HORIZONTAL
    row.gravity = Gravity.CENTER
    row.setPadding(px(14), px(4), px(10), px(4))
    row.background = roundBg(Color.argb(100, 0, 0, 0), px(14), Color.argb(70, 255, 255, 255))

    val tv = TextView(this)
    tv.text = TasksPrefs.noteText(this)
    tv.setTextColor(Color.argb(220, 255, 255, 255))
    tv.textSize = 12f
    tv.gravity = Gravity.CENTER
    row.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

    val et = EditText(this)
    et.background = null
    et.setTextColor(Color.WHITE)
    et.textSize = 12f
    et.gravity = Gravity.CENTER
    et.setSingleLine(true)
    et.filters = arrayOf(android.text.InputFilter.LengthFilter(40))
    et.imeOptions = EditorInfo.IME_ACTION_DONE
    et.visibility = View.GONE
    row.addView(et, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

    val pencil = TextView(this)
    pencil.text = "✎"
    pencil.setTextColor(Color.argb(220, 255, 255, 255))
    pencil.textSize = 15f
    pencil.setPadding(px(10), px(4), px(4), px(4))
    row.addView(pencil, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

    val w = (resources.displayMetrics.widthPixels * 0.9f).toInt()
    val lp = WindowManager.LayoutParams(
      w, WindowManager.LayoutParams.WRAP_CONTENT, overlayType(),
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
      PixelFormat.TRANSLUCENT
    )
    lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
    lp.y = px(24)
    lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN

    val finish = { save: Boolean ->
      val v = et.text.toString().trim()
      if (save && v.isNotEmpty()) {
        TasksPrefs.setNoteText(this, v)
        tv.text = v
      }
      et.visibility = View.GONE
      tv.visibility = View.VISIBLE
      pencil.text = "✎"
      noteEditing = false
      lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
      runCatching { wm.updateViewLayout(row, lp) }
      runCatching { imm.hideSoftInputFromWindow(row.windowToken, 0) }
      scheduleAutoClose()
    }
    et.setOnEditorActionListener { _, actionId, _ ->
      if (actionId == EditorInfo.IME_ACTION_DONE) { finish(true); true } else false
    }
    pencil.setOnClickListener {
      if (noteEditing) {
        finish(true)
      } else {
        noteEditing = true
        et.setText(tv.text)
        et.setSelection(et.text.length)
        tv.visibility = View.GONE
        et.visibility = View.VISIBLE
        pencil.text = "✓"
        lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        runCatching { wm.updateViewLayout(row, lp) }
        row.post {
          et.requestFocus()
          runCatching { imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT) }
        }
      }
    }
    runCatching { wm.addView(row, lp); note = row }
  }

  private fun hideNote() {
    note?.let { runCatching { wm.removeView(it) } }
    note = null
    noteEditing = false
  }

  private fun closePanel() {
    main.removeCallbacks(autoClose)
    val p = panel ?: return
    draft = input?.text?.toString() ?: ""
    runCatching {
      val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
      imm.hideSoftInputFromWindow(p.windowToken, 0)
    }
    runCatching { wm.removeView(p) }
    hideNote()
    ideaInput = null
    panel = null
    panelLp = null
    scroll = null
    listBox = null
    input = null
    dateChip = null
    calBox = null
    calOpen = false
    open = false
    val blp = bubbleLp
    val bub = bubble
    if (blp != null && bub != null && restoreY >= 0) {
      blp.y = restoreY
      clampInto(blp)
      runCatching { wm.updateViewLayout(bub, blp) }
    }
    restoreY = -1
  }

  // ------------------------------------------------------------------- views --
  /** Closes the list when Back is pressed, or when the user taps outside it (the panel loses focus). */
  private class PanelLayout(
    context: Context,
    private val onBack: () -> Unit,
    private val onFocusLost: () -> Unit,
    private val onActivity: () -> Unit
  ) : LinearLayout(context) {
    private var hadFocus = false
    var outsideFilter: ((MotionEvent) -> Boolean)? = null

    init {
      orientation = LinearLayout.VERTICAL
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
      super.onWindowFocusChanged(hasWindowFocus)
      if (hasWindowFocus) {
        hadFocus = true
      } else if (hadFocus) {
        post { onFocusLost() }
      }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
      if (ev.action == MotionEvent.ACTION_OUTSIDE) {
        if (outsideFilter?.invoke(ev) == true) return true
        post { onFocusLost() }   // a tap anywhere outside the popup closes it
        return true
      }
      onActivity()               // any touch inside keeps it open a bit longer
      return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
      if (event.keyCode == KeyEvent.KEYCODE_BACK) {
        if (event.action == KeyEvent.ACTION_UP) onBack()
        return true
      }
      return super.dispatchKeyEvent(event)
    }
  }

  /** A ScrollView that never grows taller than maxH, so a long list scrolls instead. */
  private class MaxHeightScrollView(context: Context, private val maxH: Int) : ScrollView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
      super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST))
    }
  }

  /** The list, plus and minus icons, drawn in code so no image resources are needed. */
  private class IconView(context: Context, private val kind: Int) : View(context) {
    companion object {
      const val LIST = 0
      const val PLUS = 1
      const val MINUS = 2
      const val UP = 3
      const val DOWN = 4
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE
      color = Color.WHITE
      strokeCap = Paint.Cap.ROUND
      strokeJoin = Paint.Join.ROUND
    }

    override fun onDraw(canvas: Canvas) {
      val s = width / 24f
      paint.strokeWidth = 2.2f * s
      when (kind) {
        LIST -> {
          val ys = floatArrayOf(6f, 12f, 18f)
          for (y in ys) {
            canvas.drawPoint(4 * s, y * s, paint)
            canvas.drawLine(9 * s, y * s, 21 * s, y * s, paint)
          }
        }
        PLUS -> {
          canvas.drawLine(12 * s, 5 * s, 12 * s, 19 * s, paint)
          canvas.drawLine(5 * s, 12 * s, 19 * s, 12 * s, paint)
        }
        UP -> {
          canvas.drawLine(5 * s, 15 * s, 12 * s, 8 * s, paint)
          canvas.drawLine(12 * s, 8 * s, 19 * s, 15 * s, paint)
        }
        DOWN -> {
          canvas.drawLine(5 * s, 9 * s, 12 * s, 16 * s, paint)
          canvas.drawLine(12 * s, 16 * s, 19 * s, 9 * s, paint)
        }
        else -> canvas.drawLine(5 * s, 12 * s, 19 * s, 12 * s, paint)
      }
    }
  }
}
