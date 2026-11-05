package app.fixedsugar

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal const val GENERAL = "general"
internal const val WAKING = "waking"
internal const val BEFORE = "before"
internal const val AFTER = "after"
internal const val HALF_HOUR = 30 * 60_000L
internal fun newId(): String = UUID.randomUUID().toString()
internal fun JSONObject.optional(key: String): String? = if (isNull(key)) null else getString(key)

internal class Diary(context: Context) : SQLiteOpenHelper(context, "diary.sqlite", null, 1), java.io.Closeable {
    override fun onConfigure(db: SQLiteDatabase) = db.setForeignKeyConstraintsEnabled(true)
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE profile (id TEXT PRIMARY KEY CHECK(id='patient'), name TEXT NOT NULL, height REAL, birth INTEGER)")
        db.execSQL("CREATE TABLE weights (id TEXT PRIMARY KEY, day TEXT NOT NULL, kg REAL NOT NULL)")
        db.execSQL("CREATE TABLE intakes (id TEXT PRIMARY KEY, name TEXT NOT NULL, ingredients TEXT NOT NULL, at INTEGER NOT NULL, zone TEXT NOT NULL, day TEXT NOT NULL, due INTEGER)")
        db.execSQL("CREATE TABLE glucose (id TEXT PRIMARY KEY, value INTEGER NOT NULL, at INTEGER NOT NULL, zone TEXT NOT NULL, day TEXT NOT NULL, kind TEXT NOT NULL, intake TEXT REFERENCES intakes(id) ON DELETE SET NULL, UNIQUE(intake,kind))")
        db.execSQL("CREATE TABLE notes (day TEXT PRIMARY KEY, body TEXT NOT NULL)")
        db.execSQL("CREATE INDEX intake_day ON intakes(day,at)")
        db.execSQL("CREATE INDEX glucose_day ON glucose(day,at)")
        db.execSQL("CREATE INDEX glucose_time ON glucose(at)")
        db.execSQL("CREATE INDEX intake_due ON intakes(due)")
    }
    override fun onUpgrade(db: SQLiteDatabase, from: Int, to: Int) = error("Unsupported database version")

    private fun <T> transaction(block: () -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val result = block()
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    fun rows(table: String, where: String? = null, args: Array<String>? = null,
             order: String? = null, limit: String? = null): List<JSONObject> =
        readableDatabase.query(table, null, where, args, null, null, order, limit).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val row = JSONObject()
                    cursor.columnNames.forEachIndexed { index, key ->
                        val value = when (cursor.getType(index)) {
                            Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                            Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                            Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
                            else -> cursor.getString(index)
                        }
                        row.put(key, value)
                    }
                    add(row)
                }
            }
        }

    fun row(table: String, id: String): JSONObject? =
        rows(table, "id=?", arrayOf(id)).firstOrNull()

    fun write(table: String, row: JSONObject) {
        val key = if (table == "notes") "day" else "id"
        val values = ContentValues()
        row.keys().forEach { name ->
            when (val value = row.get(name)) {
                JSONObject.NULL -> values.putNull(name)
                is Int -> values.put(name, value)
                is Long -> values.put(name, value)
                is Double -> values.put(name, value)
                is String -> values.put(name, value)
                else -> error("Unsupported value")
            }
        }
        val db = writableDatabase
        if (db.update(table, values, "$key=?", arrayOf(row.getString(key))) == 0) {
            db.insertOrThrow(table, null, values)
        }
    }

    fun delete(table: String, id: String) {
        val key = if (table == "notes") "day" else "id"
        writableDatabase.delete(table, "$key=?", arrayOf(id))
    }

    fun candidates(at: Long, intake: String): List<JSONObject> =
        rows("glucose", "kind=? AND ((intake IS NULL AND at BETWEEN ? AND ?) OR intake=?)",
            arrayOf(BEFORE, (at - 2 * HALF_HOUR).toString(), at.toString(), intake), "at DESC")

    fun linked(id: String): List<JSONObject> = rows("glucose", "intake=?", arrayOf(id))
    fun pending(): List<JSONObject> = rows("intakes", "due IS NOT NULL", order = "due")
    fun needsReminder(id: String, due: Long): Boolean =
        row("intakes", id)?.let { !it.isNull("due") && it.getLong("due") == due } == true &&
            linked(id).none { it.getString("kind") == AFTER }

    fun saveProfile(profile: JSONObject, weight: Double?) = transaction {
        write("profile", profile)
        if (weight != null) write("weights", JSONObject().put("id", newId())
            .put("day", dayKey()).put("kg", weight))
    }

    fun saveIntake(row: JSONObject, before: String?) = transaction {
        val id = row.getString("id")
        require(before == null || candidates(row.getLong("at"), id).any { it.getString("id") == before })
        write("intakes", row)
        linked(id).filter { it.getString("kind") == BEFORE && it.getString("id") != before }
            .forEach { write("glucose", it.put("intake", JSONObject.NULL)) }
        if (before != null) write("glucose", row("glucose", before)!!.put("intake", id))
    }

    fun saveGlucose(row: JSONObject) = transaction {
        write("glucose", row)
        val intake = row.optional("intake")
        if (intake != null && row.getString("kind") == AFTER) {
            write("intakes", row("intakes", intake)!!.put("due", JSONObject.NULL))
        }
    }

    fun empty(): Boolean = tables.none {
        readableDatabase.rawQuery("SELECT 1 FROM $it LIMIT 1", null).use { cursor -> cursor.moveToFirst() }
    }

    fun snapshot(): JSONObject = transaction {
        JSONObject().put("version", 1).also { data ->
            tables.forEach { table ->
                data.put(table, JSONArray(rows(table).onEach { it.remove("due") }))
            }
        }
    }

    fun load(source: JSONObject, replace: Boolean): List<String> {
        val data = validated(source)
        return transaction {
            if (!replace && !empty()) throw DiaryNotEmpty()
            val pendingIds = if (replace) pending().map { it.getString("id") } else emptyList()
            if (replace) tables.reversed().forEach { writableDatabase.delete(it, null, null) }
            data.forEach { (table, records) -> records.forEach { write(table, it) } }
            pendingIds
        }
    }

    companion object {
        private val tables = listOf("profile", "weights", "intakes", "glucose", "notes")

        private fun validated(source: JSONObject): Map<String, List<JSONObject>> {
            val version = source.get("version")
            require((version is Int || version is Long) && (version as Number).toLong() == 1L)
            val hasProfile = source.getJSONArray("profile").length() == 1
            val intakes = mutableSetOf<String>()
            val links = mutableSetOf<Pair<String, String>>()
            return tables.associateWith { table ->
                val items = source.getJSONArray(table)
                val keys = mutableSetOf<String>()
                require(items.length() == 0 || hasProfile)
                List(items.length()) { index ->
                    val item = items.getJSONObject(index)
                    val clean = JSONObject()
                    fun string(key: String, allowBlank: Boolean = true): String {
                        val value = item.get(key) as? String ?: error("Expected string")
                        require(allowBlank || value.isNotBlank())
                        clean.put(key, value)
                        return value
                    }
                    fun integer(key: String): Long {
                        val value = item.get(key)
                        require(value is Int || value is Long)
                        return (value as Number).toLong().also { clean.put(key, it) }
                    }
                    fun positive(key: String) {
                        val value = (item.get(key) as? Number)?.toDouble() ?: error("Expected number")
                        require(value.isFinite() && value > 0)
                        clean.put(key, value)
                    }
                    fun event() {
                        val at = integer("at")
                        require(at in -62_135_596_800_000L..253_402_214_399_999L)
                        val zone = string("zone")
                        require(zone in zones)
                        dayMillis(string("day"))
                    }
                    val key = if (table == "notes") string("day") else string("id", false)
                    require(keys.add(key))
                    if (table !in listOf("profile", "notes")) require(UUID.fromString(key).toString() == key)
                    when (table) {
                        "profile" -> {
                            require(key == "patient")
                            string("name", false)
                            if (!item.isNull("height")) positive("height")
                            if (!item.isNull("birth")) require(integer("birth") in 1..9999)
                        }
                        "weights" -> {
                            dayMillis(string("day"))
                            positive("kg")
                        }
                        "intakes" -> {
                            intakes.add(key)
                            string("name", false)
                            string("ingredients")
                            event()
                        }
                        "glucose" -> {
                            require(integer("value") in 1..Int.MAX_VALUE.toLong())
                            event()
                            val kind = string("kind")
                            require(kind in listOf(GENERAL, WAKING, BEFORE, AFTER))
                            if (!item.isNull("intake")) {
                                val intake = string("intake")
                                require((kind == BEFORE || kind == AFTER) && intake in intakes && links.add(intake to kind))
                            }
                        }
                        "notes" -> {
                            dayMillis(key)
                            string("body")
                        }
                    }
                    clean
                }
            }
        }
    }
}

internal class DiaryNotEmpty : IllegalStateException()

internal fun eventRow(id: String, at: Long, zone: String): JSONObject =
    JSONObject().put("id", id).put("at", at).put("zone", zone).put("day", dayKey(at, zone))
