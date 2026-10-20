package com.example.glucoble

import android.Manifest
import android.annotation.SuppressLint
import android.content.*
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import java.util.Locale

/** Presentation only: BLE remains owned by the foreground service. */
class MainActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("bridge", MODE_PRIVATE) }
    private lateinit var value: TextView
    private lateinit var output: TextView
    private lateinit var status: TextView
    private lateinit var source: TextView
    private lateinit var hint: TextView
    private var selected = BridgeMode.CADENCE
    private var pending: BridgeMode? = null
    private var diagnostics = "Запустите вещание для получения журнала."
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != GlucoBleBridgeService.ACTION_UI_UPDATE) return
            diagnostics = intent.getStringExtra("diagnostics") ?: diagnostics
            val mmol = intent.getDoubleExtra("mmol", 0.0)
            value.text = if (mmol > 0) String.format(Locale.getDefault(), "%.1f", mmol) else "—"
            output.text = intent.getStringExtra("output") ?: "Ожидаем глюкозу"
            source.text = "${intent.getStringExtra("source") ?: "Нет источника"}\n${intent.getStringExtra("updated") ?: ""}"
            val mode = BridgeMode.restore(intent.getStringExtra("mode"))
            val subscribers = intent.getIntExtra("connected", 0)
            status.text = when {
                !intent.getBooleanExtra("running", false) -> "Вещание не запущено · подробности в журнале"
                subscribers > 0 -> "Подписчиков: $subscribers · ${mode.profileLabel}"
                else -> "Ожидаем подключения · ${mode.profileLabel}"
            }
            if (mode.test) status.text = "ТЕСТ 93 · ${status.text}"
        }
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun color(attr: Int) = MaterialColors.getColor(this, attr, "GlucoPower")
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun label(text: String, size: Float = 16f) = TextView(this).apply {
        this.text = text; textSize = size
        setTextColor(color(com.google.android.material.R.attr.colorOnSurface))
        setPadding(0, dp(3), 0, dp(3))
    }
    private fun button(text: String, outlined: Boolean = false, action: () -> Unit) =
        MaterialButton(this, null, if (outlined) com.google.android.material.R.attr.materialButtonOutlinedStyle
            else com.google.android.material.R.attr.materialButtonStyle).apply {
            this.text = text; isAllCaps = false; textSize = 18f
            minHeight = dp(52); cornerRadius = dp(28)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) }
            setOnClickListener { action() }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selected = BridgeMode.restore(savedInstanceState?.getString("selected") ?: prefs.getString("ui_profile", "CADENCE"))
        if (selected.test) selected = if (selected.heartProfile) BridgeMode.HEART_GLUCOSE else if (selected.powerProfile) BridgeMode.POWER else BridgeMode.CADENCE
        pending = savedInstanceState?.getString("pending")?.let { BridgeMode.restore(it) }
        val root = column().apply {
            setBackgroundColor(color(com.google.android.material.R.attr.colorSurface))
        }
        val toolbar = MaterialToolbar(this).apply {
            title = "GlucoPower"
            menu.add(0, 1, 0, "Тесты и настройки")
            menu.add(0, 2, 1, "Диагностика")
            menu.add(0, 3, 2, "Работа в фоне")
            setOnMenuItemClickListener {
                when (it.itemId) { 1 -> showSettings(); 2 -> showDiagnostics(); 3 -> batterySettings() }
                true
            }
        }
        root.addView(toolbar)
        val content = column().apply { setPadding(dp(16), dp(4), dp(16), dp(8)) }
        val card = MaterialCardView(this).apply {
            radius = dp(32).toFloat(); strokeWidth = 0
            setCardBackgroundColor(color(com.google.android.material.R.attr.colorPrimaryContainer))
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(4); bottomMargin = dp(12) }
        }
        val readings = column().apply { setPadding(dp(20), dp(12), dp(20), dp(12)) }
        readings.addView(label("ГЛЮКОЗА", 12f).apply { setTextColor(color(com.google.android.material.R.attr.colorOnPrimaryContainer)) })
        value = label("—", 80f).apply {
            setTypeface(typeface, Typeface.BOLD); maxLines = 1; gravity = Gravity.CENTER_VERTICAL
            setAutoSizeTextTypeUniformWithConfiguration(32, 104, 2, android.util.TypedValue.COMPLEX_UNIT_SP)
        }
        val readingRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        readingRow.addView(value, LinearLayout.LayoutParams(0, -1, 1.2f))
        val metadata = column()
        metadata.addView(label("ммоль/л", 14f).apply { setTextColor(color(com.google.android.material.R.attr.colorOnPrimaryContainer)) })
        source = label("Нет источника", 11f).apply { maxLines = 3 }
        metadata.addView(source)
        readingRow.addView(metadata, LinearLayout.LayoutParams(0, -2, 1f))
        readings.addView(readingRow, LinearLayout.LayoutParams(-1, 0, 1f))
        output = label("Ожидаем данные", 14f).apply { maxLines = 2 }
        readings.addView(output)
        listOf(value, source, output).forEach { it.setTextColor(color(com.google.android.material.R.attr.colorOnPrimaryContainer)) }
        card.addView(readings, android.widget.FrameLayout.LayoutParams(-1, -1)); content.addView(card)
        content.addView(label("Передавать через", 16f))
        val group = MaterialButtonToggleGroup(this).apply {
            isSingleSelection = true; isSelectionRequired = true
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
        val cadence = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            id = View.generateViewId(); text = "Каденс"; isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, dp(56), 1f)
        }
        val heart = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            id = View.generateViewId(); text = "ЧСС"; isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, dp(56), 1f)
        }
        val power = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            id = View.generateViewId(); text = "Мощность"; isAllCaps = false
            setPadding(dp(8), 0, dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(0, dp(56), 1f)
        }
        group.addView(cadence); group.addView(heart); group.addView(power)
        group.check(if (selected.heartProfile) heart.id else if (selected.powerProfile) power.id else cadence.id)
        group.addOnButtonCheckedListener { _, id, checked ->
            if (checked) {
                selected = if (id == heart.id) BridgeMode.HEART_GLUCOSE else if (id == power.id) BridgeMode.POWER else BridgeMode.CADENCE
                prefs.edit().putString("ui_profile", selected.name).apply()
                updateHint()
            }
        }
        content.addView(group)
        hint = label("", 12f); content.addView(hint); updateHint()
        status = label("Готово к запуску", 14f).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        content.addView(status)
        content.addView(button("Начать вещание") { requestStart(selected) })
        content.addView(button("Остановить", true) {
            pending = null
            stopService(Intent(this, GlucoBleBridgeService::class.java))
            status.text = "Вещание остановлено"
            output.text = "Передача выключена"
        })
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun updateHint() {
        if (!::hint.isInitialized) return
        hint.text = if (selected.heartProfile) "9,3 ммоль/л → 93 в поле ЧСС. Это глюкоза, не пульс."
            else if (selected.powerProfile) if (prefs.getBoolean("multiply", true)) "9,3 ммоль/л → 93 в поле мощности. Это глюкоза, не реальные ватты." else "Глюкоза округляется до целого в поле мощности."
            else if (prefs.getBoolean("multiply", true)) "9,3 ммоль/л → 93 об/мин. Это глюкоза, не реальный каденс."
            else "Глюкоза округляется до целого в поле каденса."

    }

    private fun showSettings() {
        val body = column().apply { setPadding(dp(24), dp(8), dp(24), dp(16)) }
        body.addView(label("Тестовые данные", 22f))
        body.addView(label("Тест заменяет входную глюкозу. На главном экране будет отметка ТЕСТ.", 14f))
        val dialog = MaterialAlertDialogBuilder(this).setTitle("Тесты и настройки")
            .setView(ScrollView(this).apply { addView(body) }).setPositiveButton("Готово") { _, _ -> updateHint() }.create()
        body.addView(button("Тест каденса · 93 об/мин") { dialog.dismiss(); requestStart(BridgeMode.CADENCE_TEST) })
        body.addView(button("Тест ЧСС · 93", true) { dialog.dismiss(); requestStart(BridgeMode.HEART_TEST) })
        body.addView(button("Тест мощности · 93 Вт", true) { dialog.dismiss(); requestStart(BridgeMode.POWER_TEST) })
        body.addView(label("Передача", 22f))
        fun toggle(key: String, title: String, default: Boolean = true): MaterialSwitch = MaterialSwitch(this).apply {
            text = title; minHeight = dp(56); isChecked = prefs.getBoolean(key, default)
            setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean(key, checked).apply() }
            body.addView(this)
        }
        toggle("multiply", "Каденс и мощность: глюкоза ×10")
        body.addView(label("Совместимость BLE", 22f))
        body.addView(label("Изменения применяются при следующем запуске вещания.", 14f))
        val variantButton = button("Пакет: ${AdvertisingVariant.restore(prefs.getString("adv_variant", null)).label}") {}
        variantButton.setOnClickListener {
            MaterialAlertDialogBuilder(this).setTitle("Тестовый пакет BLE")
                .setSingleChoiceItems(AdvertisingVariant.entries.map { it.label }.toTypedArray(),
                    AdvertisingVariant.restore(prefs.getString("adv_variant", null)).ordinal) { chooser, which ->
                    val variant = AdvertisingVariant.entries[which]
                    prefs.edit().putString("adv_variant", variant.name).apply()
                    variantButton.text = "Пакет: ${variant.label}"
                    chooser.dismiss()
                }.setNegativeButton("Отмена", null).show()
        }
        body.addView(variantButton)
        body.addView(label("Для мощности всегда используется A: только UUID. Для ЧСС и каденса A–F заменяют настройки ниже. Выберите пакет, затем запустите тест мощности 93. Ищите 60 секунд без смены профиля. Flags 06 и Appearance недоступны через Android API.", 14f))
        val vpm = toggle("vpm_advertising", "Реклама VPM (имя до 19 байт)")
        val minimal = toggle("minimal_advertising", "UUID в рекламе, имя в ответе")
        val name = toggle("advertise_name_primary", "Имя в основном пакете")
        fun enabled() { minimal.isEnabled = !vpm.isChecked; name.isEnabled = !vpm.isChecked && !minimal.isChecked }
        vpm.setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean("vpm_advertising", checked).apply(); enabled() }
        minimal.setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean("minimal_advertising", checked).apply(); enabled() }
        enabled()
        body.addView(label("Если CC600 сохраняет датчик, но не получает данные: удалите прежнее сопряжение CC600 с телефоном и добавьте датчик через поиск велокомпьютера.", 14f))
        dialog.show()
    }

    private fun showDiagnostics() {
        MaterialAlertDialogBuilder(this).setTitle("Диагностика BLE")
            .setMessage(diagnostics).setNegativeButton("Закрыть", null)
            .setPositiveButton("Скопировать") { _, _ ->
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("GlucoPower BLE", diagnostics))
                Toast.makeText(this, "Журнал скопирован", Toast.LENGTH_SHORT).show()
            }.show()
    }

    private fun requestStart(mode: BridgeMode) {
        pending = mode
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) permissions.addAll(listOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT))
        if (Build.VERSION.SDK_INT >= 33) permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 101) else launch(mode)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 101) return
        val mode = pending ?: return
        if (Build.VERSION.SDK_INT < 31 || listOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
                .all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) launch(mode)
        else { pending = null; status.text = "Для вещания разрешите доступ к устройствам поблизости" }
    }

    private fun launch(mode: BridgeMode) {
        pending = null
        val intent = Intent(this, GlucoBleBridgeService::class.java)
            .putExtra(GlucoBleBridgeService.EXTRA_ADV_VARIANT, prefs.getString("adv_variant", "EXISTING"))
            .putExtra(GlucoBleBridgeService.EXTRA_MODE, mode.name)
            .putExtra(GlucoBleBridgeService.EXTRA_MULTIPLY_BY_10, prefs.getBoolean("multiply", true))
            .putExtra(GlucoBleBridgeService.EXTRA_VPM_ADVERTISING, prefs.getBoolean("vpm_advertising", true))
            .putExtra(GlucoBleBridgeService.EXTRA_MINIMAL_ADVERTISING, prefs.getBoolean("minimal_advertising", true))
            .putExtra(GlucoBleBridgeService.EXTRA_ADVERTISE_NAME_PRIMARY, prefs.getBoolean("advertise_name_primary", true))
        startForegroundService(intent)
        status.text = if (mode.test) "ТЕСТ 93 · запуск…" else "Запускаем вещание…"
    }

    private fun batterySettings() {
        val power = getSystemService(POWER_SERVICE) as PowerManager
        if (power.isIgnoringBatteryOptimizations(packageName)) {
            MaterialAlertDialogBuilder(this).setTitle("Работа в фоне")
                .setMessage("Оптимизация батареи отключена. На vivo также разрешите автозапуск приложения в системных настройках.")
                .setPositiveButton("Понятно", null).show()
        } else {
            val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            startActivity(if (request.resolveActivity(packageManager) != null) request
                else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(GlucoBleBridgeService.ACTION_UI_UPDATE)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(receiver, filter)
    }
    override fun onPause() { unregisterReceiver(receiver); super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("selected", selected.name)
        outState.putString("pending", pending?.name)
        super.onSaveInstanceState(outState)
    }
}
