package app.fixedsugar

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.FilterReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.Executors

internal const val CHANNEL = "follow_up"
private const val ALARM = "app.fixedsugar.FOLLOW_UP"
internal const val OPEN_READING = "app.fixedsugar.OPEN_READING"

internal class Reminders(private val context: Context) {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    val exact: Boolean get() = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()

    fun channel() {
        if (Build.VERSION.SDK_INT >= 26) {
            notifications.createNotificationChannel(NotificationChannel(
                CHANNEL, context.getString(R.string.follow_ups), NotificationManager.IMPORTANCE_DEFAULT
            ))
        }
    }

    fun enabled(): Boolean = notifications.areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 26 ||
            notifications.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE)

    private fun alarm(id: String, due: Long, flags: Int): PendingIntent? =
        PendingIntent.getBroadcast(context, 0, Intent(context, ReminderReceiver::class.java)
            .setAction(ALARM).setData(Uri.parse("fixed-sugar://intake/$id"))
            .putExtra("due", due), PendingIntent.FLAG_IMMUTABLE or flags)

    fun schedule(id: String, due: Long) {
        val operation = alarm(id, due, PendingIntent.FLAG_UPDATE_CURRENT)!!
        if (exact) {
            try {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, operation)
                return
            } catch (_: SecurityException) {
                // Access can be revoked between checking and scheduling.
            }
        }
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, operation)
    }

    fun reconcile(diary: Diary) {
        val now = System.currentTimeMillis()
        diary.pending().filter { it.getLong("due") > now }.forEach {
            schedule(it.getString("id"), it.getLong("due"))
        }
    }

    fun cancel(id: String) {
        alarm(id, 0L, PendingIntent.FLAG_NO_CREATE)?.let {
            alarms.cancel(it)
            it.cancel()
        }
        notifications.cancel(id, 1)
    }

    fun post(id: String) {
        channel()
        if (!enabled()) return
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java)
            .setAction(OPEN_READING).setData(Uri.parse("fixed-sugar://intake/$id"))
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, CHANNEL)
            else @Suppress("DEPRECATION") Notification.Builder(context)
        val notification = builder.setSmallIcon(R.drawable.ic_reminder)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.reminder_text))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setAutoCancel(true).setContentIntent(open).build()
        try {
            notifications.notify(id, 1, notification)
        } catch (_: SecurityException) {
            // The due time remains visible if permission changed.
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reminders = Reminders(localContext(context))
        Diary(context).use { diary ->
            if (intent.action == ALARM) {
                val id = intent.data?.lastPathSegment ?: return
                val due = intent.getLongExtra("due", 0)
                if (due in 1..System.currentTimeMillis() && diary.needsReminder(id, due)) reminders.post(id)
            } else {
                reminders.reconcile(diary)
                Backups.schedule(context)
            }
        }
    }
}

// shortcut: transfers are limited to 16 million characters to bound parsing memory, use streaming for larger diaries.
private const val MAX_TRANSFER = 16 * 1024 * 1024

internal fun readSnapshot(stream: InputStream): JSONObject {
    val bounded = object : FilterReader(InputStreamReader(stream, Charsets.UTF_8.newDecoder())) {
        var remaining = MAX_TRANSFER
        override fun read(buffer: CharArray, offset: Int, length: Int): Int {
            val count = super.read(buffer, offset, minOf(length, remaining + 1))
            require(count <= remaining)
            if (count > 0) remaining -= count
            return count
        }
    }
    return JsonReader(bounded).use { reader ->
        val result = readJson(reader, 0) as? JSONObject ?: error("Expected an object")
        require(reader.peek() == JsonToken.END_DOCUMENT)
        result
    }
}

private fun readJson(reader: JsonReader, depth: Int): Any {
    require(depth <= 8)
    return when (reader.peek()) {
        JsonToken.BEGIN_OBJECT -> JSONObject().apply {
            reader.beginObject()
            while (reader.hasNext()) {
                val key = reader.nextName()
                require(!has(key))
                put(key, readJson(reader, depth + 1))
            }
            reader.endObject()
        }
        JsonToken.BEGIN_ARRAY -> JSONArray().apply {
            reader.beginArray()
            while (reader.hasNext()) put(readJson(reader, depth + 1))
            reader.endArray()
        }
        JsonToken.NUMBER -> reader.nextString().let { it.toLongOrNull() ?: it.toDouble() }
        JsonToken.STRING -> reader.nextString()
        JsonToken.BOOLEAN -> reader.nextBoolean()
        JsonToken.NULL -> {
            reader.nextNull()
            JSONObject.NULL
        }
        else -> error("Invalid JSON")
    }
}

internal fun writeSnapshot(stream: OutputStream, snapshot: JSONObject) {
    val content = snapshot.toString()
    require(content.length <= MAX_TRANSFER)
    stream.write(content.toByteArray(Charsets.UTF_8))
}

internal class Backups(context: Context) {
    private val directory = File(context.noBackupFilesDir, "snapshots")
    fun files(): List<File> = directory.listFiles().orEmpty()
        .filter { it.isFile && it.name.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}\\.json")) }
        .sortedByDescending { it.name }

    fun save(diary: Diary, day: String = dayKey(),
             write: (OutputStream, JSONObject) -> Unit = ::writeSnapshot) = synchronized(lock) {
        dayMillis(day)
        val file = File(directory, "$day.json")
        if (file.exists() || diary.empty()) return@synchronized
        check(directory.isDirectory || directory.mkdirs())
        val staging = File(directory, "snapshot.tmp")
        try {
            FileOutputStream(staging).use { stream ->
                write(stream, diary.snapshot())
                stream.fd.sync()
            }
            check(staging.renameTo(file))
        } finally {
            staging.delete()
        }
        files().drop(3).forEach { it.delete() }
    }

    companion object {
        private val lock = Any()
        private const val JOB = 1
        val worker = Executors.newSingleThreadExecutor()
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (scheduler.getPendingJob(JOB) == null) {
                scheduler.schedule(JobInfo.Builder(JOB, ComponentName(context, BackupJob::class.java))
                    .setPersisted(true).setPeriodic(6 * 60 * 60_000L).build())
            }
        }
    }
}

class BackupJob : JobService() {
    private var active: JobParameters? = null

    override fun onStartJob(params: JobParameters): Boolean {
        active = params
        Backups.worker.execute {
            val retry = runCatching { Diary(this).use { Backups(this).save(it) } }.isFailure
            android.os.Handler(mainLooper).post {
                if (active === params) {
                    active = null
                    jobFinished(params, retry)
                }
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        active = null
        return true
    }
}
