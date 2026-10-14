package com.prasad.textscheduler

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

enum class Repeat(val label: String, val unit: String) {
    NONE("Once", ""),
    DAILY("Daily", "day"),
    WEEKLY("Weekly", "week"),
    MONTHLY("Monthly", "month"),
    YEARLY("Yearly", "year"),
}

data class Recipient(val name: String, val phone: String) {
    val display: String get() = if (name.isBlank()) phone else name
}

data class ScheduledMessage(
    val id: Long,
    val recipients: List<Recipient>,
    val body: String,
    /** First send date + time of day (local time). */
    val start: LocalDateTime,
    val repeat: Repeat,
    /** "Every N days/weeks/months/years". */
    val interval: Int = 1,
    /** For WEEKLY: which days to send on. */
    val weekDays: Set<DayOfWeek> = emptySet(),
    /** Optional last date to send on (inclusive). */
    val endDate: LocalDate? = null,
    val enabled: Boolean = true,
    /** Epoch millis of the next planned send, or null when finished. */
    val nextAt: Long? = null,
    val lastSentAt: Long? = null,
    val sentCount: Int = 0,
) {
    val time: LocalTime get() = start.toLocalTime()

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("recipients", JSONArray().apply {
            recipients.forEach { put(JSONObject().put("name", it.name).put("phone", it.phone)) }
        })
        put("body", body)
        put("start", start.toString())
        put("repeat", repeat.name)
        put("interval", interval)
        put("weekDays", JSONArray().apply { weekDays.sorted().forEach { put(it.value) } })
        endDate?.let { put("endDate", it.toString()) }
        put("enabled", enabled)
        nextAt?.let { put("nextAt", it) }
        lastSentAt?.let { put("lastSentAt", it) }
        put("sentCount", sentCount)
    }

    companion object {
        fun fromJson(o: JSONObject): ScheduledMessage {
            val rs = o.getJSONArray("recipients")
            val days = o.optJSONArray("weekDays") ?: JSONArray()
            return ScheduledMessage(
                id = o.getLong("id"),
                recipients = (0 until rs.length()).map {
                    val r = rs.getJSONObject(it)
                    Recipient(r.optString("name"), r.getString("phone"))
                },
                body = o.getString("body"),
                start = LocalDateTime.parse(o.getString("start")),
                repeat = Repeat.valueOf(o.getString("repeat")),
                interval = o.optInt("interval", 1).coerceAtLeast(1),
                weekDays = (0 until days.length()).map { DayOfWeek.of(days.getInt(it)) }.toSet(),
                endDate = if (o.has("endDate")) LocalDate.parse(o.getString("endDate")) else null,
                enabled = o.optBoolean("enabled", true),
                nextAt = if (o.has("nextAt")) o.getLong("nextAt") else null,
                lastSentAt = if (o.has("lastSentAt")) o.getLong("lastSentAt") else null,
                sentCount = o.optInt("sentCount", 0),
            )
        }
    }
}

/** Tiny JSON-in-SharedPreferences store. All access is synchronized. */
object Store {
    private const val PREFS = "messages"
    private const val KEY = "items"

    @Synchronized
    fun all(ctx: Context): List<ScheduledMessage> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull {
                try { ScheduledMessage.fromJson(arr.getJSONObject(it)) } catch (e: Exception) { null }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun get(ctx: Context, id: Long): ScheduledMessage? = all(ctx).firstOrNull { it.id == id }

    @Synchronized
    fun save(ctx: Context, msg: ScheduledMessage) {
        val list = all(ctx).filter { it.id != msg.id } + msg
        write(ctx, list)
    }

    @Synchronized
    fun delete(ctx: Context, id: Long) {
        write(ctx, all(ctx).filter { it.id != id })
    }

    private fun write(ctx: Context, list: List<ScheduledMessage>) {
        val arr = JSONArray().apply { list.forEach { put(it.toJson()) } }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, arr.toString()).commit()
    }

    fun newId(): Long = System.currentTimeMillis()
}
