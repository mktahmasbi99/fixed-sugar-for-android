@file:Suppress("SimpleDateFormat") // The ISO key and the explicit display calendar need separate formats.


package app.fixedsugar

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.icu.text.DateFormat
import android.icu.text.SimpleDateFormat
import android.icu.util.Calendar
import android.icu.util.TimeZone
import android.icu.util.ULocale
import java.util.Date
import java.util.Locale

internal val utc: TimeZone get() = TimeZone.getTimeZone("UTC")
internal val zones by lazy { TimeZone.getAvailableIDs().toHashSet() }
internal val systemZone: String get() = java.util.TimeZone.getDefault().id

internal fun digits(text: String) = text.map {
    val digit = Character.digit(it, 10)
    if (digit >= 0) '0' + digit else it
}.joinToString("")

internal fun localContext(context: Context): Context {
    val choice = context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("language", null)
    val systemLanguage = Resources.getSystem().configuration.locales[0].language
    val locale = Locale.forLanguageTag(choice ?: if (systemLanguage == "fa") "fa" else "en")
    val config = Configuration(context.resources.configuration)
    config.setLocale(locale)
    return context.createConfigurationContext(config)
}

private fun calendarLocale(locale: Locale): ULocale =
    if (locale.language == "fa") ULocale("fa_IR@calendar=persian") else ULocale.US

internal fun calendar(locale: Locale, zone: TimeZone, instant: Long): Calendar =
    Calendar.getInstance(zone, calendarLocale(locale)).apply { timeInMillis = instant }

internal fun dayKey(instant: Long = System.currentTimeMillis(), zone: String = systemZone): String =
    SimpleDateFormat("yyyy-MM-dd", ULocale.US).apply {
        timeZone = TimeZone.getTimeZone(zone)
    }.format(Date(instant))

internal fun dayMillis(day: String): Long {
    require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(day))
    return SimpleDateFormat("yyyy-MM-dd", ULocale.US).apply {
        timeZone = utc
        isLenient = false
    }.parse(day)!!.time.also { require(dayKey(it, "UTC") == day) }
}

internal fun shiftDay(day: String, amount: Int): String =
    dayKey(dayMillis(day) + amount * 86_400_000L, "UTC")

internal fun displayDate(instant: Long, zone: String, locale: Locale, withTime: Boolean): String {
    val date = if (locale.language == "fa") "d MMMM y" else "MMM d, y"
    return SimpleDateFormat(if (withTime) "$date • HH:mm" else date, calendarLocale(locale)).apply {
        timeZone = TimeZone.getTimeZone(zone)
    }.format(Date(instant))
}

internal fun displayTime(instant: Long, zone: String, locale: Locale, clock24: Boolean): String =
    DateFormat.getInstanceForSkeleton(if (clock24) "Hm" else "hm", calendarLocale(locale)).apply {
        timeZone = TimeZone.getTimeZone(zone)
    }.format(Date(instant))

internal fun eventTime(day: String, zone: String, hour: Int, minute: Int): Long {
    val date = calendar(Locale.US, utc, dayMillis(day))
    return calendar(Locale.US, TimeZone.getTimeZone(zone), 0).apply {
        clear()
        isLenient = false
        set(date.get(Calendar.YEAR), date.get(Calendar.MONTH), date.get(Calendar.DAY_OF_MONTH), hour, minute)
    }.timeInMillis
}

internal fun dayStart(day: String, zone: String): Long {
    val date = calendar(Locale.US, utc, dayMillis(day))
    return calendar(Locale.US, TimeZone.getTimeZone(zone), 0).apply {
        clear()
        set(date.get(Calendar.YEAR), date.get(Calendar.MONTH), date.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis
}
