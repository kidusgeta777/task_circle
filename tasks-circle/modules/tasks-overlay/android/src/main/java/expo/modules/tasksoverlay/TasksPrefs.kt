package expo.modules.tasksoverlay

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class Task(val id: String, val text: String)

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
      out.add(Task(o.optString("id"), o.optString("text")))
    }
    return out
  }

  /** New tasks go to the end of the list. */
  @Synchronized
  fun addTask(c: Context, text: String) {
    val arr = readArray(c)
    val now = System.currentTimeMillis()
    arr.put(
      JSONObject()
        .put("id", now.toString() + "-" + (Math.random() * 100000).toInt())
        .put("text", text)
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
