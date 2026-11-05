package app.fixedsugar

import android.app.Activity
import android.app.Instrumentation
import android.icu.util.Calendar
import android.os.Bundle
import org.json.JSONObject
import java.io.File
import java.util.Locale

class DiaryChecks : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        val context = targetContext.createDeviceProtectedStorageContext()
        val directory = File(context.noBackupFilesDir, "snapshots")
        context.deleteDatabase("diary.sqlite")
        directory.deleteRecursively()
        val outcome = runCatching {
            checkDates()
            Diary(context).use { diary -> checkDiary(diary, Backups(context)) }
        }
        context.deleteDatabase("diary.sqlite")
        directory.deleteRecursively()
        result.putString("stream", outcome.fold(
            { "\nPassed date conversion, linking, reminder state, transfer, and backup checks.\n" },
            { "\n${it.stackTraceToString()}\n" }
        ))
        finish(if (outcome.isSuccess) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }

    private fun checkDates() {
        check(digits("۱۲٣4") == "1234")
        val fa = Locale.forLanguageTag("fa")
        val esFand = calendar(fa, utc, dayMillis("2021-03-20"))
        check(esFand.get(Calendar.YEAR) == 1399)
        check(esFand.get(Calendar.MONTH) == 11)
        check(esFand.get(Calendar.DAY_OF_MONTH) == 30)
        check(esFand.getActualMaximum(Calendar.DAY_OF_MONTH) == 30)
        esFand.set(1400, 0, 1)
        check(dayKey(esFand.timeInMillis, "UTC") == "2021-03-21")
        esFand.set(1400, 11, 1)
        check(esFand.getActualMaximum(Calendar.DAY_OF_MONTH) == 29)
        check(displayDate(dayMillis("2021-03-21"), "UTC", fa, false) !=
            displayDate(dayMillis("2021-03-21"), "UTC", Locale.US, false))
        check(dayKey(eventTime("2026-11-05", "Asia/Tehran", 0, 15), "Asia/Tehran") == "2026-11-05")
        val at = eventTime("2026-11-05", "Asia/Tehran", 13, 15)
        check(displayTime(at, "Asia/Tehran", Locale.US, clock24 = true) == "13:15")
        check(digits(displayTime(at, "Asia/Tehran", fa, clock24 = true)) == "13:15")
    }

    private fun checkDiary(diary: Diary, backups: Backups) {
        diary.saveProfile(JSONObject().put("id", "patient").put("name", "آزمایش"), 62.5)
        val day = "2026-11-05"
        val at = eventTime(day, "Asia/Tehran", 0, 15)
        val before = newId()
        diary.saveGlucose(eventRow(before, at - HALF_HOUR, "Asia/Tehran")
            .put("kind", BEFORE).put("value", 101))
        check(diary.row("glucose", before)!!.getString("day") == "2026-11-04")
        val id = newId()
        val second = newId()
        val firstMeal = eventRow(id, at, "Asia/Tehran").put("name", "Snack").put("ingredients", "")
            .put("due", at + HALF_HOUR)
        check(diary.candidates(at, id).single().getString("id") == before)
        diary.saveIntake(firstMeal, before)
        diary.saveIntake(eventRow(second, at + 60_000, "Asia/Tehran").put("name", "Drink")
            .put("ingredients", "").put("due", at + HALF_HOUR + 60_000), null)
        check(diary.candidates(at, second).isEmpty())
        check(diary.needsReminder(id, at + HALF_HOUR))
        check(!diary.needsReminder(second, at + HALF_HOUR))
        diary.saveGlucose(eventRow(newId(), at + HALF_HOUR, "Asia/Tehran").put("kind", AFTER)
            .put("value", 124).put("intake", id))
        check(!diary.needsReminder(id, at + HALF_HOUR))
        check(diary.needsReminder(second, at + HALF_HOUR + 60_000))
        val standalone = newId()
        diary.saveGlucose(eventRow(standalone, at + HALF_HOUR, "Asia/Tehran")
            .put("kind", GENERAL).put("value", 119).put("intake", JSONObject.NULL))
        check(diary.needsReminder(second, at + HALF_HOUR + 60_000))
        diary.write("notes", JSONObject().put("day", day).put("body", "یادداشت"))
        val exported = readSnapshot(diary.snapshot().toString().byteInputStream())
        val nested = "[".repeat(100) + "null" + "]".repeat(100)
        check(runCatching { readSnapshot(nested.byteInputStream()) }.isFailure)
        check(runCatching { readSnapshot("{\"version\":1,\"version\":1}".byteInputStream()) }.isFailure)
        val invalid = JSONObject(exported.toString())
        invalid.getJSONArray("glucose").getJSONObject(0).put("intake", newId())
        check(runCatching { diary.load(invalid, true) }.isFailure)
        check(runCatching { diary.load(exported, false) }.exceptionOrNull() is DiaryNotEmpty)
        check(diary.linked(id).size == 2)
        check(diary.load(exported, true) == listOf(second))
        check(diary.pending().isEmpty())
        check(diary.linked(id).size == 2)
        check(diary.row("glucose", standalone)!!.let {
            it.getString("kind") == GENERAL && it.getInt("value") == 119 && it.isNull("intake")
        })
        check(diary.rows("weights").single().getDouble("kg") == 62.5)
        check(diary.rows("notes").single().getString("body") == "یادداشت")
        diary.delete("intakes", id)
        check(diary.rows("glucose").size == 3 && diary.rows("glucose").all { it.isNull("intake") })

        repeat(4) { backups.save(diary, shiftDay(day, it)) }
        val good = backups.files()
        check(good.map { it.nameWithoutExtension } == (3 downTo 1).map { shiftDay(day, it) })
        check(runCatching { backups.save(diary, shiftDay(day, 4)) { stream, _ -> stream.write(123); error("Disk write failed") } }.isFailure)
        check(backups.files() == good)
        good.forEach { file ->
            check(diary.load(readSnapshot(file.inputStream()), true).isEmpty())
        }

        listOf("profile", "weights", "intakes", "glucose", "notes").forEach { table ->
            diary.rows(table).forEach { diary.delete(table, it.getString(if (table == "notes") "day" else "id")) }
        }
        check(diary.empty())
        check(diary.load(exported, false).isEmpty())
        check(diary.linked(id).size == 2 && diary.pending().isEmpty())
    }
}
