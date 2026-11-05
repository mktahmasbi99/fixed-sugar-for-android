package app.fixedsugar

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.icu.util.Calendar
import android.icu.util.TimeZone
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.text.NumberFormat

private const val IMPORT = 1
private const val EXPORT = 2
private const val NOTIFY = 3
private const val WHOLE_NUMBER = InputType.TYPE_CLASS_NUMBER
private const val DECIMAL_NUMBER = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
private const val TEAL = 0xff006b60.toInt()
private const val INK = 0xff142b26.toInt()
private const val PAPER = 0xfff5faf8.toInt()

class MainActivity : Activity() {
    private lateinit var diary: Diary
    private lateinit var reminders: Reminders
    private lateinit var body: LinearLayout
    private var screen = R.string.today
    private var historyDay = dayKey()
    private var chartDays = 7
    private var selectedPoint: String? = null

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(localContext(newBase))
    private val locale get() = resources.configuration.locales[0]
    private val prefs get() = getSharedPreferences("settings", MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        diary = Diary(this)
        reminders = Reminders(this).also { it.channel() }
        screen = savedInstanceState?.getInt("screen") ?: R.string.today
        historyDay = savedInstanceState?.getString("day") ?: dayKey()
        chartDays = savedInstanceState?.getInt("range") ?: 7
        render()
        if (savedInstanceState == null) openReminder(intent)
        Backups.schedule(this)
    }

    override fun onResume() {
        super.onResume()
        reminders.reconcile(diary)
        val context = applicationContext
        Backups.worker.execute {
            val backup = Backups(context)
            val before = backup.files().firstOrNull()
            runCatching { Diary(context).use { backup.save(it) } }
            if (backup.files().firstOrNull() != before) {
                runOnUiThread { if (!isDestroyed && screen == R.string.settings) render() }
            }
        }
        if (screen == R.string.today || screen == R.string.settings) render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("screen", screen)
        outState.putString("day", historyDay)
        outState.putInt("range", chartDays)
    }

    override fun onDestroy() {
        diary.close()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openReminder(intent)
    }

    private fun openReminder(intent: Intent) {
        if (intent.action != OPEN_READING) return
        val id = intent.data?.lastPathSegment ?: return
        diary.row("intakes", id)?.let { glucoseForm(reminder = it) }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun number(value: Number) = NumberFormat.getNumberInstance(locale).apply {
        isGroupingUsed = false
        maximumFractionDigits = 340
    }.format(value)
    private fun dayLabel(day: String) = displayDate(dayMillis(day), "UTC", locale, false)
    private fun stamp(at: Long, zone: String) = displayDate(at, zone, locale, true)
    private fun stamp(row: JSONObject) = stamp(row.getLong("at"), row.getString("zone"))
    private fun value(row: JSONObject) = getString(R.string.glucose_value, number(row.getInt("value")))
    private fun reading(row: JSONObject) =
        getString(R.string.reading_line, getString(R.string.blood_sugar), value(row))

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun line() = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }

    private fun label(parent: LinearLayout, text: CharSequence, size: Float = 16f, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(INK)
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            setPadding(0, dp(5), 0, dp(5))
            if (bold) setTypeface(null, Typeface.BOLD)
            parent.addView(this)
        }

    private fun heading(parent: LinearLayout, id: Int) = label(parent, getString(id), 20f, true)

    private fun button(parent: LinearLayout, id: Int, primary: Boolean = false, action: () -> Unit) =
        button(parent, getString(id), primary, action)

    private fun button(parent: LinearLayout, text: CharSequence, primary: Boolean = false, action: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            minimumHeight = dp(48)
            minHeight = dp(48)
            setSingleLine(false)
            if (primary) {
                backgroundTintList = android.content.res.ColorStateList.valueOf(TEAL)
                setTextColor(Color.WHITE)
            }
            setOnClickListener { action() }
            val params = if (parent.orientation == LinearLayout.HORIZONTAL) {
                LinearLayout.LayoutParams(0, -2, 1f)
            } else LinearLayout.LayoutParams(-1, -2)
            parent.addView(this, params)
        }

    private fun iconButton(parent: LinearLayout, label: Int, icon: Int, selected: Boolean = false,
                           action: () -> Unit) = button(parent, "", selected, action).apply {
        contentDescription = getString(label)
        isSelected = selected
        val drawable = getDrawable(icon)!!.mutate().apply { setTint(currentTextColor) }
        setCompoundDrawablesWithIntrinsicBounds(null, drawable, null, null)
    }

    private fun card(parent: LinearLayout): LinearLayout = column().apply {
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(16).toFloat()
            setStroke(dp(1), 0xffd5e6df.toInt())
        }
        setPadding(dp(14), dp(10), dp(14), dp(10))
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
    }

    private fun field(parent: LinearLayout, hint: Int, initial: String = "", numberType: Int? = null,
                      multiline: Boolean = false): EditText = EditText(this).apply {
        this.hint = getString(hint)
        inputType = when {
            multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        if (!multiline) setSingleLine()
        // Request a number pad while keeping the text key listener for all supported digit families.
        if (numberType != null) setRawInputType(numberType)
        setText(initial)
        parent.addView(this, LinearLayout.LayoutParams(-1, -2))
    }

    private fun render() {
        val root = column().apply {
            setBackgroundColor(PAPER)
            layoutDirection = resources.configuration.layoutDirection
            @Suppress("DEPRECATION")
            systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                (if (Build.VERSION.SDK_INT >= 26) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
            setOnApplyWindowInsetsListener { view, insets ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val edges = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                    view.setPadding(edges.left, edges.top, edges.right, edges.bottom)
                } else {
                    @Suppress("DEPRECATION")
                    view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                        insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                }
                insets
            }
        }
        val top = line().apply {
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setPadding(dp(16), dp(4), dp(12), dp(4))
        }
        root.addView(top)
        val title = label(top, getString(R.string.app_name), 24f, true)
        title.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        val profile = diary.row("profile", "patient")
        val day = dayKey()
        if (profile != null && screen == R.string.today) {
            val toolbar = line()
            top.addView(toolbar, LinearLayout.LayoutParams(dp(104), -2))
            iconButton(toolbar, R.string.note, R.drawable.ic_note) { noteForm(day) }
            iconButton(toolbar, R.string.profile, R.drawable.ic_profile) { navigate(R.string.profile) }
            val todayHeader = column().apply { setPadding(dp(16), 0, dp(16), 0) }
            root.addView(todayHeader)
            todayActions(todayHeader, profile, day)
        }
        val scroll = ScrollView(this).apply { isFillViewport = true }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        body = column().apply { setPadding(dp(16), dp(8), dp(16), dp(16)) }
        scroll.addView(body)
        val tabs = line().apply { layoutDirection = View.LAYOUT_DIRECTION_LTR }
        val pages = if (profile == null) listOf(R.string.today)
            else listOf(R.string.today, R.string.history, R.string.chart)
        pages.forEach { id ->
            button(tabs, id, id == screen) { navigate(id) }.isSelected = id == screen
        }
        iconButton(tabs, R.string.settings, R.drawable.ic_settings, screen == R.string.settings) {
            navigate(R.string.settings)
        }
        root.addView(tabs)
        if (Build.VERSION.SDK_INT < 26) {
            @Suppress("DEPRECATION")
            window.navigationBarColor = INK
        }
        setContentView(root)
        when {
            screen == R.string.settings -> settings(profile)
            profile == null -> {
                heading(body, R.string.setup)
                profileForm(null, body)
                button(body, R.string.import_data) { importFile() }
            }
            screen == R.string.profile -> profilePage(profile)
            screen == R.string.history -> history()
            screen == R.string.chart -> chart()
            else -> entries(day)
        }
    }

    private fun navigate(to: Int) {
        screen = to
        render()
    }

    private fun dialog(title: Int, form: LinearLayout, save: () -> Boolean) {
        form.setPadding(dp(20), dp(8), dp(20), dp(12))
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(form)
        }
        val dialog = AlertDialog.Builder(this).setTitle(title).setView(scroll)
            .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.save, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    if (save()) {
                        dialog.dismiss()
                        render()
                    }
                } catch (_: Exception) {
                    message(R.string.save_error)
                }
            }
        }
        dialog.show()
    }

    private fun confirm(text: CharSequence, actionLabel: Int, action: () -> Unit) {
        AlertDialog.Builder(this).setMessage(text).setNegativeButton(R.string.cancel, null)
            .setPositiveButton(actionLabel) { _, _ -> action() }.show()
    }

    private fun message(id: Int) = Toast.makeText(this, id, Toast.LENGTH_LONG).show()

    private fun EditText.positive(): Double? = digits(text.toString().trim()).replace('٫', '.')
        .takeIf { Regex("[0-9]+(\\.[0-9]+)?").matches(it) }
        ?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }

    private fun profileForm(existing: JSONObject?, parent: LinearLayout? = null) {
        val form = if (parent == null) column() else card(parent)
        val persianOffset = if (locale.language == "fa") 621 else 0
        val year = dayKey().take(4).toInt()
        val displayYear = calendar(locale, TimeZone.getTimeZone(systemZone), System.currentTimeMillis()).get(Calendar.YEAR)
        val name = field(form, R.string.name, existing?.getString("name").orEmpty())
        val height = field(form, R.string.height, existing?.optional("height")?.toDouble()?.let(::number).orEmpty(), numberType = DECIMAL_NUMBER)
        val birth = field(form, R.string.birth,
            existing?.optional("birth")?.toInt()?.let { number(it - persianOffset) }.orEmpty(), numberType = WHOLE_NUMBER)
        label(form, getString(R.string.birth_calendar, number(displayYear)), 14f)
        val weight = if (existing == null) field(form, R.string.weight_optional, numberType = DECIMAL_NUMBER) else null
        fun saveProfile(): Boolean {
            val enteredName = name.text.toString()
            val heightValue = height.positive()
            val birthValue = digits(birth.text.toString().trim()).toIntOrNull()
                ?.takeIf { it in 1..displayYear }?.plus(persianOffset)?.takeIf { it <= year }
            val weightValue = weight?.positive()
            when {
                enteredName.isBlank() -> name.error = getString(R.string.required_name)
                height.text.isNotBlank() && heightValue == null -> height.error = getString(R.string.invalid_number)
                birth.text.isNotBlank() && birthValue == null -> birth.error = getString(R.string.invalid_number)
                weight != null && weight.text.isNotBlank() && weightValue == null -> weight.error = getString(R.string.invalid_number)
                else -> {
                    diary.saveProfile(JSONObject().put("id", "patient").put("name", enteredName)
                        .put("height", heightValue ?: JSONObject.NULL).put("birth", birthValue ?: JSONObject.NULL), weightValue)
                    return true
                }
            }
            return false
        }
        if (parent == null) dialog(R.string.profile, form, ::saveProfile)
        else button(form, R.string.save, true) {
            try {
                if (saveProfile()) render()
            } catch (_: Exception) {
                message(R.string.save_error)
            }
        }
    }

    private fun pickDay(day: String, onPick: (String) -> Unit) {
        val selected = calendar(locale, utc, dayMillis(day))
        val year = selected.get(Calendar.YEAR)
        val controls = line()
        fun picker(title: Int, min: Int, max: Int, value: Int): NumberPicker {
            val group = column()
            controls.addView(group, LinearLayout.LayoutParams(0, -2, 1f))
            label(group, getString(title), 14f).gravity = Gravity.CENTER
            return NumberPicker(this).apply {
                minValue = min
                maxValue = max
                this.value = value
                wrapSelectorWheel = false
                descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
                contentDescription = getString(title)
                setFormatter { number(it) }
                group.addView(this, LinearLayout.LayoutParams(-1, -2))
            }
        }
        val y = picker(R.string.year, 1, if (locale.language == "fa") 9378 else 9999, year)
        val m = picker(R.string.month, 1, 12, selected.get(Calendar.MONTH) + 1)
        val d = picker(R.string.day, 1, selected.getActualMaximum(Calendar.DAY_OF_MONTH), selected.get(Calendar.DAY_OF_MONTH))
        fun updateDays() {
            selected.set(y.value, m.value - 1, 1)
            d.maxValue = selected.getActualMaximum(Calendar.DAY_OF_MONTH)
        }
        y.setOnValueChangedListener { _, _, _ -> updateDays() }
        m.setOnValueChangedListener { _, _, _ -> updateDays() }
        AlertDialog.Builder(this).setTitle(R.string.choose_date).setView(controls)
            .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.ok) { _, _ ->
                selected.set(y.value, m.value - 1, d.value)
                val picked = dayKey(selected.timeInMillis, "UTC")
                if (runCatching { dayMillis(picked) }.isSuccess) onPick(picked) else message(R.string.invalid_date)
            }.show()
    }

    private inner class EventEditor(form: LinearLayout, original: JSONObject?) {
        var now = original == null
            private set
        var zone: String = original?.getString("zone") ?: systemZone
            private set
        var at: Long = original?.getLong("at") ?: System.currentTimeMillis()
            private set
        private val clock24 = android.text.format.DateFormat.is24HourFormat(this@MainActivity)
        private val dateButton = button(form, "") {
            val cal = calendar(locale, TimeZone.getTimeZone(zone), at)
            pickDay(dayKey(at, zone)) { day ->
                change(day, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
            }
        }
        private val timeButton = button(form, "") {
            val cal = calendar(locale, TimeZone.getTimeZone(zone), at)
            TimePickerDialog(this@MainActivity, { _, hour, minute ->
                change(dayKey(at, zone), hour, minute)
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), clock24).show()
        }
        private val nowButton = button(form, R.string.now) {
            now = true
            zone = systemZone
            at = System.currentTimeMillis()
            update()
        }

        init {
            update()
        }

        private fun change(day: String, hour: Int, minute: Int) {
            val picked = runCatching { eventTime(day, zone, hour, minute) }.getOrElse {
                message(R.string.invalid_date)
                return
            }
            now = false
            at = picked
            update()
        }

        private fun update() {
            dateButton.text = displayDate(at, zone, locale, false)
            timeButton.text = displayTime(at, zone, locale, clock24)
            nowButton.visibility = if (now) View.GONE else View.VISIBLE
        }

        fun preview() = if (now) System.currentTimeMillis() else at
    }

    private fun glucoseForm(existing: JSONObject? = null, reminder: JSONObject? = null) {
        val intake = reminder?.getString("id") ?: existing?.optional("intake")
        val old = existing ?: reminder?.let {
            diary.linked(it.getString("id")).firstOrNull { row -> row.getString("kind") == AFTER }
        }
        val kind = old?.getString("kind") ?: if (reminder == null) GENERAL else AFTER
        val form = column()
        val glucose = field(form, R.string.reading, old?.let { number(it.getInt("value")) }.orEmpty(), numberType = WHOLE_NUMBER)
        val time = EventEditor(form, old)
        dialog(R.string.blood_sugar, form) {
            val entered = digits(glucose.text.toString().trim())
            val amount = entered.takeIf { it.matches(Regex("[0-9]+")) }?.toIntOrNull()
            if (amount == null || amount <= 0) {
                glucose.error = getString(R.string.invalid_reading)
                return@dialog false
            }
            val row = eventRow(old?.getString("id") ?: newId(), time.preview(), time.zone)
                .put("value", amount).put("kind", kind).put("intake", intake ?: JSONObject.NULL)
            diary.saveGlucose(row)
            if (intake != null && kind == AFTER) reminders.cancel(intake)
            true
        }
    }

    private fun intakeForm(existing: JSONObject? = null) {
        val id = existing?.getString("id") ?: newId()
        val form = column()
        val name = field(form, R.string.food_name, existing?.getString("name").orEmpty())
        val ingredients = field(form, R.string.ingredients, existing?.getString("ingredients").orEmpty(), multiline = true)
        val time = EventEditor(form, existing)
        val before = diary.linked(id).firstOrNull { it.getString("kind") == BEFORE }?.getString("id")
        dialog(if (existing == null) R.string.intake else R.string.edit_intake, form) {
            if (name.text.isBlank()) {
                name.error = getString(R.string.required_name)
                return@dialog false
            }
            val at = time.preview()
            val timestampChanged = existing != null &&
                (existing.getLong("at") != at || existing.getString("zone") != time.zone)
            val due = when {
                existing == null && time.now -> at + HALF_HOUR
                existing != null && !timestampChanged && !existing.isNull("due") -> existing.getLong("due")
                else -> null
            }
            diary.saveIntake(eventRow(id, at, time.zone).put("name", name.text.toString())
                .put("ingredients", ingredients.text.toString()).put("due", due ?: JSONObject.NULL), before)
            if (existing == null && due != null) {
                reminders.schedule(id, due)
                requestNotifications()
            } else if (timestampChanged) reminders.cancel(id)
            true
        }
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !prefs.getBoolean("asked_notifications", false)
        ) {
            prefs.edit().putBoolean("asked_notifications", true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFY)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIFY) render()
    }

    private fun todayActions(parent: LinearLayout, profile: JSONObject, day: String) {
        label(parent, profile.getString("name"), 28f, true)
        label(parent, dayLabel(day))
        val actions = line()
        card(parent).addView(actions)
        button(actions, R.string.blood_sugar, true) { glucoseForm() }
            .setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.ic_add_glucose, 0, 0)
        button(actions, R.string.food_or_drink, true) { intakeForm() }
            .setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.ic_add_food, 0, 0)
    }

    private fun reminderSettings(parent: LinearLayout) {
        if (!reminders.enabled()) {
            label(parent, getString(R.string.notifications_disabled))
            button(parent, R.string.notification_settings) {
                if (Build.VERSION.SDK_INT >= 26) {
                    startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                } else {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 31 && !reminders.exact) {
            label(parent, getString(R.string.notifications_approximate))
            button(parent, R.string.alarm_settings) {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            }
        }
    }

    private fun history() {
        heading(body, R.string.history)
        button(body, dayLabel(historyDay), true) { pickDay(historyDay) { historyDay = it; render() } }
        val controls = line()
        body.addView(controls)
        button(controls, R.string.previous_day) { historyDay = shiftDay(historyDay, -1); render() }
            .isEnabled = historyDay > "0001-01-01"
        button(controls, R.string.next_day) { historyDay = shiftDay(historyDay, 1); render() }
            .isEnabled = historyDay < "9999-12-31"
        note(historyDay)
        entries(historyDay)
    }

    private fun note(day: String) {
        val section = card(body)
        heading(section, R.string.note)
        val previous = diary.rows("notes", "day=?", arrayOf(day)).firstOrNull()?.getString("body").orEmpty()
        if (previous.isNotEmpty()) label(section, previous)
        button(section, if (previous.isEmpty()) R.string.note_empty else R.string.edit) { noteForm(day) }
    }

    private fun noteForm(day: String) {
        val previous = diary.rows("notes", "day=?", arrayOf(day)).firstOrNull()?.getString("body").orEmpty()
        val form = column()
        val text = field(form, R.string.note, previous, multiline = true)
        dialog(R.string.note, form) {
            if (text.text.isEmpty()) diary.delete("notes", day)
            else diary.write("notes", JSONObject().put("day", day).put("body", text.text.toString()))
            true
        }
    }

    private fun entries(day: String) {
        heading(body, R.string.entries)
        val args = arrayOf(day)
        val intake = diary.rows("intakes", "day=?", args).map { "intakes" to it }
        val glucose = diary.rows("glucose", "day=?", args).map { "glucose" to it }
        val entries = (intake + glucose).sortedByDescending { it.second.getLong("at") }
        if (entries.isEmpty()) label(body, getString(R.string.empty_day))
        entries.forEach { (table, row) ->
            val section = card(body)
            val id = row.getString("id")
            val isIntake = table == "intakes"
            label(section, if (isIntake) row.getString("name") else reading(row), bold = true)
            label(section, stamp(row), 14f)
            if (isIntake) {
                row.getString("ingredients").takeIf { it.isNotEmpty() }?.let { label(section, it) }
            }
            val actions = line()
            section.addView(actions)
            button(actions, R.string.edit) {
                if (isIntake) intakeForm(row) else glucoseForm(existing = row)
            }
            button(actions, R.string.delete) { deleteEntry(table, id) }
        }
    }

    private fun deleteEntry(table: String, id: String) {
        val isIntake = table == "intakes"
        confirm(getString(if (isIntake) R.string.delete_intake else R.string.delete_entry), R.string.delete) {
            try {
                diary.delete(table, id)
                if (isIntake) reminders.cancel(id)
                render()
            } catch (_: Exception) {
                message(R.string.save_error)
            }
        }
    }

    private fun chart() {
        heading(body, R.string.chart_title)
        val controls = line()
        body.addView(controls)
        listOf(7 to R.string.chart_7, 30 to R.string.chart_30).forEach { (days, title) ->
            button(controls, title, chartDays == days) {
                chartDays = days
                selectedPoint = null
                render()
            }
        }
        val zone = systemZone
        val end = dayKey(zone = zone)
        val start = shiftDay(end, 1 - chartDays)
        label(body, getString(R.string.chart_range, dayLabel(start), dayLabel(end)))
        val startAt = dayStart(start, zone)
        val endAt = dayStart(shiftDay(end, 1), zone)
        val points = diary.rows("glucose", "at>=? AND at<?",
            arrayOf(startAt.toString(), endAt.toString()), "at ASC")
        if (points.isEmpty()) {
            label(body, getString(R.string.chart_empty))
            return
        }
        val panel = card(body)
        val details = label(panel, getString(R.string.chart_select)).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        fun detail(row: JSONObject) = "${reading(row)}\n${stamp(row)}"
        val plot = GlucosePlot(this, points.map { it.getLong("at") to it.getInt("value") },
            startAt, endAt, ::number)
        fun select(index: Int) {
            val row = points[index]
            selectedPoint = row.getString("id")
            details.text = detail(row)
            plot.selected = index
            plot.invalidate()
        }
        plot.onSelection = ::select
        panel.addView(plot, LinearLayout.LayoutParams(-1, dp(220)))
        val index = points.indexOfFirst { it.getString("id") == selectedPoint }
        if (index >= 0) select(index)
        heading(body, R.string.chart_readings)
        points.indices.reversed().forEach { i ->
            button(body, detail(points[i])) {
                select(i)
                panel.requestRectangleOnScreen(android.graphics.Rect(0, 0, panel.width, panel.height))
            }
        }
    }

    private fun profilePage(profile: JSONObject) {
        val identity = card(body)
        heading(identity, R.string.profile)
        label(identity, profile.getString("name"), bold = true)
        profile.optional("birth")?.toInt()?.let { birth ->
            val year = dayKey().take(4).toInt()
            if (birth <= year) label(identity, getString(R.string.age, number(year - birth)))
        }
        button(identity, R.string.edit) { profileForm(profile) }

        val weight = card(body)
        heading(weight, R.string.weight_history)
        button(weight, R.string.add_weight, true) { weightForm() }
        val weights = diary.rows("weights", order = "day DESC, rowid DESC")
        if (weights.isEmpty()) label(weight, getString(R.string.empty_weights))
        weights.forEach { weightEntry(weight, it) }
    }

    private fun settings(profile: JSONObject?) {
        heading(body, R.string.settings)
        val language = card(body)
        heading(language, R.string.language)
        val english = locale.language == "fa"
        button(language, if (english) R.string.language_english else R.string.language_persian) {
            prefs.edit().putString("language", if (english) "en" else "fa").apply()
            recreate()
        }
        if (profile == null) return
        if (!reminders.enabled() || !reminders.exact) {
            val notifications = card(body)
            heading(notifications, R.string.follow_ups)
            reminderSettings(notifications)
        }
        val backups = card(body)
        heading(backups, R.string.backup_transfer)
        val files = Backups(this).files()
        label(backups, getString(R.string.backup_explain))
        label(backups, files.firstOrNull()?.let {
            getString(R.string.last_backup, dayLabel(it.nameWithoutExtension))
        } ?: getString(R.string.no_backup))
        button(backups, R.string.restore) {
            val dates = files.map { dayLabel(it.nameWithoutExtension) }.toTypedArray()
            AlertDialog.Builder(this).setTitle(R.string.restore).setItems(dates) { _, index ->
                confirm(getString(R.string.restore_confirm, dates[index]), R.string.replace) {
                    try {
                        val ids = diary.load(readSnapshot(files[index].inputStream()), replace = true)
                        ids.forEach(reminders::cancel)
                        message(R.string.restore_success)
                        render()
                    } catch (_: Exception) {
                        message(R.string.file_error)
                    }
                }
            }.setNegativeButton(R.string.cancel, null).show()
        }.isEnabled = files.isNotEmpty()
        button(backups, R.string.export_data) {
            confirm(getString(R.string.export_warning), R.string.export_data) {
                launchPicker(Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json")
                    .putExtra(Intent.EXTRA_TITLE, "fixed-sugar-${dayKey()}.json"), EXPORT)
            }
        }
        button(backups, R.string.import_data) { importFile() }
    }

    private fun weightEntry(parent: LinearLayout, row: JSONObject) {
        val value = getString(R.string.weight_line, number(row.getDouble("kg")))
        val day = dayLabel(row.getString("day"))
        label(parent, "$day · $value")
        val actions = line()
        parent.addView(actions)
        button(actions, R.string.edit) { weightForm(row) }
        button(actions, R.string.delete) { deleteEntry("weights", row.getString("id")) }
    }

    private fun weightForm(existing: JSONObject? = null) {
        val form = column()
        var day = existing?.getString("day") ?: dayKey()
        val weight = field(form, R.string.weight, existing?.let { number(it.getDouble("kg")) }.orEmpty(), numberType = DECIMAL_NUMBER)
        lateinit var date: Button
        date = button(form, dayLabel(day)) { pickDay(day) { day = it; date.text = dayLabel(it) } }
        dialog(R.string.add_weight, form) {
            val kg = weight.positive()
            if (kg == null) {
                weight.error = getString(R.string.invalid_number)
                return@dialog false
            }
            diary.write("weights", JSONObject().put("id", existing?.getString("id") ?: newId())
                .put("day", day).put("kg", kg))
            true
        }
    }

    private fun importFile() {
        if (!diary.empty()) {
            AlertDialog.Builder(this).setMessage(R.string.import_empty).setPositiveButton(R.string.ok, null).show()
            return
        }
        launchPicker(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*"), IMPORT)
    }

    @Suppress("DEPRECATION")
    private fun launchPicker(intent: Intent, request: Int) {
        try {
            startActivityForResult(intent.addCategory(Intent.CATEGORY_OPENABLE), request)
        } catch (_: Exception) {
            message(R.string.file_error)
        }
    }

    @Deprecated("Platform Activity result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            when (requestCode) {
                IMPORT -> {
                    val snapshot = readSnapshot(checkNotNull(contentResolver.openInputStream(uri)))
                    diary.load(snapshot, replace = false)
                    screen = R.string.today
                    message(R.string.import_success)
                    render()
                }
                EXPORT -> {
                    val snapshot = diary.snapshot()
                    checkNotNull(contentResolver.openOutputStream(uri, "wt")).use { writeSnapshot(it, snapshot) }
                    message(R.string.export_success)
                }
            }
        } catch (_: DiaryNotEmpty) {
            message(R.string.import_empty)
        } catch (_: Exception) {
            message(R.string.file_error)
        }
    }
}
