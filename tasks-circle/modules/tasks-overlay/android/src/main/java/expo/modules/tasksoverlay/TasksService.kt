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
  private var addDayOffset = -1  // date for the next task: -1 = no date, otherwise days from today
  private var dateChip: TextView? = null
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

  private val panelFill get() = Color.argb(165, 0, 0, 0)
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

  /** Keep the circle on screen; while the list is open keep it high so the keyboard never covers it. */
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

    val root = PanelLayout(this, { closePanel() }, { closePanel() })
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

    // date chip (optional): "+ Date" = no date. Tap: Today, Tomorrow, ... up to 7 days ahead, then back to no date
    val chip = TextView(this)
    dateChip = chip
    refreshChip()
    chip.setTextColor(Color.WHITE)
    chip.textSize = 12f
    chip.gravity = Gravity.CENTER
    chip.setPadding(px(10), px(5), px(10), px(5))
    chip.background = roundBg(Color.argb(60, 255, 255, 255), px(12), Color.TRANSPARENT)
    chip.setOnClickListener {
      addDayOffset = if (addDayOffset >= 7) -1 else addDayOffset + 1
      refreshChip()
    }
    val chipLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    chipLp.rightMargin = px(4)
    addRow.addView(chip, chipLp)

    val plus = FrameLayout(this)
    plus.addView(IconView(this, IconView.PLUS), FrameLayout.LayoutParams(px(16), px(16), Gravity.CENTER))
    plus.setOnClickListener { addFromInput() }
    addRow.addView(plus, LinearLayout.LayoutParams(px(34), px(34)))

    root.addView(addRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

    val lp = WindowManager.LayoutParams(
      px(260), WindowManager.LayoutParams.WRAP_CONTENT, overlayType(),
      WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
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
  }

  private fun refreshChip() {
    dateChip?.text = if (addDayOffset < 0) "+ Date" else TasksPrefs.dateLabel(TasksPrefs.dateOffset(addDayOffset))
  }

  /** Fills the list with one row per task: [-] text */
  private fun rebuildList() {
    val box = listBox ?: return
    box.removeAllViews()
    val tasks = TasksPrefs.tasks(this)
    if (tasks.isEmpty()) {
      val empty = TextView(this)
      empty.text = "No tasks yet"
      empty.setTextColor(Color.argb(170, 255, 255, 255))
      empty.textSize = 14f
      empty.setPadding(px(16), px(14), px(16), px(12))
      box.addView(empty)
    } else {
      for (t in tasks) box.addView(taskRow(t))
    }
  }

  private fun taskRow(t: Task): View {
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
    return row
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
    TasksPrefs.addTask(this, text, if (addDayOffset < 0) "" else TasksPrefs.dateOffset(addDayOffset))
    addDayOffset = -1
    refreshChip()
    et.setText("")
    draft = ""
    rebuildList()
    val sv = scroll
    val box = listBox
    if (sv != null && box != null) sv.post { sv.smoothScrollTo(0, box.height) }
  }

  /** The panel always opens in the same place: centred, near the top of the screen. It does not move with the circle. */
  private fun layoutPanel() {
    val lp = panelLp ?: return
    val dm = resources.displayMetrics
    val w = minOf(dm.widthPixels - px(24), px(320))
    lp.width = w
    lp.x = (dm.widthPixels - w) / 2
    lp.y = (dm.heightPixels * 0.12f).toInt()
    panel?.let { if (it.isAttachedToWindow) wm.updateViewLayout(it, lp) }
  }

  private fun closePanel() {
    val p = panel ?: return
    draft = input?.text?.toString() ?: ""
    runCatching {
      val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
      imm.hideSoftInputFromWindow(p.windowToken, 0)
    }
    runCatching { wm.removeView(p) }
    panel = null
    panelLp = null
    scroll = null
    listBox = null
    input = null
    dateChip = null
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
    private val onFocusLost: () -> Unit
  ) : LinearLayout(context) {
    private var hadFocus = false

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
      if (kind == LIST) {
        val ys = floatArrayOf(6f, 12f, 18f)
        for (y in ys) {
          canvas.drawPoint(4 * s, y * s, paint)
          canvas.drawLine(9 * s, y * s, 21 * s, y * s, paint)
        }
      } else if (kind == PLUS) {
        canvas.drawLine(12 * s, 5 * s, 12 * s, 19 * s, paint)
        canvas.drawLine(5 * s, 12 * s, 19 * s, 12 * s, paint)
      } else {
        canvas.drawLine(5 * s, 12 * s, 19 * s, 12 * s, paint)
      }
    }
  }
}
