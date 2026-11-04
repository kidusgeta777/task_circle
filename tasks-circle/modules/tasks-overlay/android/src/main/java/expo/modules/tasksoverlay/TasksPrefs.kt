package expo.modules.tasksoverlay

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class Task(val id: String, val text: String, val date: String)

/** The to-do list (kept on the phone), the on/off choice and the circle's position. */
object TasksPrefs {
  private const val NAME = "tasks_prefs"

  private fun sp(c: Context): SharedPreferences =
    c.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

  fun tasksJson(c: Context): String = sp(c).getString("tasks", "[]") ?: "[]"

  private fun readArray(c: Context): JSONArray =
    try { JSONArray(tasksJson(c)) } catch (e: Exception) { JSONArray() }

  fun tasks(c: Context): List<Task> {
    val arr = readArray(c)
    val out = ArrayList<Task>()
    for (i in 0 until arr.length()) {
      val o = arr.optJSONObject(i) ?: continue
      out.add(Task(o.optString("id"), o.optString("text"), o.optString("date")))
    }
    // the order is the user's own: they move tasks up and down to prioritise them
    return out
  }

  // ---- dates (day only, no time) ----
  private fun fmt() = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)

  /** The date `days` days from today as yyyy-MM-dd (0 = today). */
  fun dateOffset(days: Int): String {
    val cal = java.util.Calendar.getInstance()
    cal.add(java.util.Calendar.DAY_OF_YEAR, days)
    return fmt().format(cal.time)
  }

  /** The last date that can be picked: 3 months from today (Calendar clamps e.g. 31 Nov to the month's end). */
  fun maxDate(): String {
    val cal = java.util.Calendar.getInstance()
    cal.add(java.util.Calendar.MONTH, 3)
    return fmt().format(cal.time)
  }

  /** How many days from today (negative = in the past), or null if the date can't be read. */
  fun dayDiff(date: String): Int? {
    return try {
      val d = fmt().parse(date) ?: return null
      val t = fmt().parse(fmt().format(java.util.Date())) ?: return null
      Math.round((d.time - t.time) / 86400000.0).toInt()
    } catch (e: Exception) {
      null
    }
  }

  /** "Today", "Tomorrow", "Yesterday" or e.g. "3 Nov". */
  fun dateLabel(date: String): String {
    if (date.isEmpty()) return ""
    val n = dayDiff(date) ?: return date
    return when (n) {
      0 -> "Today"
      1 -> "Tomorrow"
      -1 -> "Yesterday"
      else -> try {
        java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).format(fmt().parse(date)!!)
      } catch (e: Exception) {
        date
      }
    }
  }

  /** New tasks go to the end of the list. */
  @Synchronized
  fun addTask(c: Context, text: String, date: String) {
    val arr = readArray(c)
    val now = System.currentTimeMillis()
    arr.put(
      JSONObject()
        .put("id", now.toString() + "-" + (Math.random() * 100000).toInt())
        .put("text", text)
        .put("date", date)
        .put("ts", now)
    )
    sp(c).edit().putString("tasks", arr.toString()).commit()
  }

  @Synchronized
  fun deleteTask(c: Context, id: String) {
    val old = readArray(c)
    val out = JSONArray()
    for (i in 0 until old.length()) {
      val o = old.optJSONObject(i) ?: continue
      if (o.optString("id") != id) out.put(o)
    }
    sp(c).edit().putString("tasks", out.toString()).commit()
  }

  /** Swaps the positions of two tasks in the list. */
  @Synchronized
  fun swapTasks(c: Context, idA: String, idB: String) {
    val arr = readArray(c)
    var ia = -1
    var ib = -1
    for (i in 0 until arr.length()) {
      val id = arr.optJSONObject(i)?.optString("id")
      if (id == idA) ia = i else if (id == idB) ib = i
    }
    if (ia < 0 || ib < 0 || ia == ib) return
    val a = arr.get(ia)
    val b = arr.get(ib)
    arr.put(ia, b)
    arr.put(ib, a)
    sp(c).edit().putString("tasks", arr.toString()).commit()
  }

  @Synchronized
  fun clearTasks(c: Context) {
    sp(c).edit().putString("tasks", "[]").commit()
  }

  fun enabled(c: Context): Boolean = sp(c).getBoolean("enabled", false)
  fun setEnabled(c: Context, on: Boolean) {
    sp(c).edit().putBoolean("enabled", on).apply()
  }

  fun posX(c: Context, def: Int): Int = sp(c).getInt("x", def)
  fun posY(c: Context, def: Int): Int = sp(c).getInt("y", def)
  fun savePos(c: Context, x: Int, y: Int) {
    sp(c).edit().putInt("x", x).putInt("y", y).apply()
  }
}
