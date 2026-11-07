package io.github.codemastervy.hatv

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.security.NetworkSecurityPolicy
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/** First-run / later setup. Plain views, D-pad navigable, focus drawn by the backgrounds. Nothing pre-filled. */
class SetupActivity : ComponentActivity() {
    private lateinit var settings: HaSettings
    private lateinit var url: EditText
    private lateinit var path: EditText
    private lateinit var token: EditText
    private lateinit var person: EditText
    private lateinit var camera: EditText
    private lateinit var title: EditText
    private lateinit var autoClose: EditText
    private lateinit var quiet: EditText
    private lateinit var tokenState: TextView
    private lateinit var status: TextView
    private lateinit var overlayWarning: TextView
    private var resetArmedAt = 0L

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        settings = HaSettings(this)
        url = findViewById(R.id.url)
        path = findViewById(R.id.path)
        token = findViewById(R.id.token)
        person = findViewById(R.id.person)
        camera = findViewById(R.id.camera)
        title = findViewById(R.id.title)
        autoClose = findViewById(R.id.auto_close)
        quiet = findViewById(R.id.quiet)
        tokenState = findViewById(R.id.token_state)
        status = findViewById(R.id.status)
        overlayWarning = findViewById(R.id.overlay_warning)
        token.typeface = Typeface.DEFAULT   // password inputType defaults to monospace

        url.setText(settings.baseUrl)
        path.setText(settings.dashboardPath)
        settings.popup.let {
            person.setText(it.personEntity)
            camera.setText(it.cameraEntity)
            title.setText(it.title)
            autoClose.setText(it.autoCloseSeconds.toString())
            quiet.setText(it.quietSeconds.toString())
        }
        refreshTokenState()

        findViewById<Button>(R.id.test).setOnClickListener { testConnection() }
        findViewById<Button>(R.id.save).setOnClickListener { save() }
        findViewById<Button>(R.id.reset).setOnClickListener { reset() }
        findViewById<Button>(R.id.pick_person).setOnClickListener { pick(person, "binary_sensor.", "Choose the person / doorbell sensor") }
        findViewById<Button>(R.id.pick_camera).setOnClickListener { pick(camera, "camera.", "Choose the camera") }

        // Keyboard "Done" (✓ / Enter) closes the keyboard and moves on, so nobody gets stuck in a field.
        val doneMovesTo = mapOf(token to R.id.test, person to R.id.pick_person, camera to R.id.pick_camera,
            title to R.id.auto_close, autoClose to R.id.quiet, quiet to R.id.save)
        for ((field, next) in doneMovesTo) field.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(v.windowToken, 0)
                findViewById<View>(next).requestFocus()
                true
            } else false
        }

        // TV quirk: a focused EditText eats D-pad Left/Right (text cursor) and sometimes Up/Down, so the
        // remote gets stuck in it. While the on-screen keyboard is closed nobody is editing, so arrows
        // move between controls (honouring the nextFocus* order in the layout). The keyboard has its
        // own cursor keys for editing.
        for (field in listOf(url, path, token, person, camera, title, autoClose, quiet)) {
            field.setOnKeyListener { v, code, e ->
                val dir = when (code) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
                    KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
                    KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
                    KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
                    else -> return@setOnKeyListener false
                }
                if (e.action == KeyEvent.ACTION_DOWN) v.focusSearch(dir)?.requestFocus()
                true
            }
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)

        when {
            url.text.isBlank() -> url
            !settings.hasToken -> token
            else -> findViewById<View>(R.id.save)
        }.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        overlayWarning.text = "Overlay permission missing, so the door pop-up can't show. Run once from a computer (see README):\n" +
            "adb shell appops set $packageName SYSTEM_ALERT_WINDOW allow"
        overlayWarning.visibility =
            if (Settings.canDrawOverlays(this) || person.text.isBlank()) View.GONE else View.VISIBLE
    }

    private fun refreshTokenState() {
        tokenState.text = if (settings.hasToken)
            "Saved token: ${Redact.mask(settings.token)}  (leave empty to keep it)"
        else "No token saved yet."
    }

    private fun baseUrl() = Urls.normalizeBase(url.text.toString())
    private fun typedOrSavedToken(): String? = token.text.toString().trim().ifEmpty { settings.token }

    /** Returns an error message, or null if the URL can be used by this build. */
    private fun checkUrl(u: String): String? {
        if (!Urls.isValidBase(u)) return "Enter the Home Assistant URL, e.g. http://homeassistant.local:8123"
        val host = Urls.host(u)!!
        if (Urls.isCleartext(u) && !NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(host)) {
            return "This build only allows plain http to: ${BuildConfig.CLEARTEXT_HOSTS}. " +
                "Rebuild with -Pha.cleartextHosts=$host or use https (see README)."
        }
        return null
    }

    private fun testConnection() {
        val base = baseUrl()
        checkUrl(base)?.let { return showStatus(false, it) }
        val t = typedOrSavedToken() ?: return showStatus(false, "Type a long-lived access token first")
        showStatus(null, "Testing…")
        HaOneShot.run(base, t, wantStates = false) { ok, msg, _ -> showStatus(ok, msg) }
    }

    /** Lists matching entities from HA so nobody has to type entity IDs with a remote. */
    private fun pick(target: EditText, prefix: String, dialogTitle: String) {
        val base = baseUrl()
        checkUrl(base)?.let { return showStatus(false, it) }
        val t = typedOrSavedToken() ?: return showStatus(false, "Enter the URL and token first")
        showStatus(null, "Loading entities from Home Assistant…")
        HaOneShot.run(base, t, wantStates = true) { ok, msg, states ->
            if (isFinishing) return@run
            if (!ok || states == null) return@run showStatus(false, msg)
            val list = states.filter { it.entityId.startsWith(prefix) }
                .sortedWith(compareBy<EntityState>({ !it.looksLikeDoorSensor }, { (it.friendlyName ?: it.entityId).lowercase() }))
            if (list.isEmpty()) return@run showStatus(false, "No $prefix* entities found in Home Assistant")
            showStatus(null, "")
            val labels = list.map { "${it.friendlyName ?: it.entityId}\n${it.entityId}  ·  ${it.state}" }.toTypedArray()
            AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(dialogTitle)
                .setItems(labels) { _, i -> target.setText(list[i].entityId); showStatus(true, "Selected ${list[i].entityId}") }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun intField(f: EditText, default: Int, range: IntRange, name: String): Int? {
        val v = f.text.toString().trim().ifEmpty { return default }.toIntOrNull()
        if (v == null || v !in range) { showStatus(false, "$name must be ${range.first}-${range.last}"); f.requestFocus(); return null }
        return v
    }

    private fun save() {
        val base = baseUrl()
        checkUrl(base)?.let { url.requestFocus(); return showStatus(false, it) }
        val newToken = token.text.toString().trim()
        if (newToken.isEmpty() && !settings.hasToken) { token.requestFocus(); return showStatus(false, "Type a long-lived access token first") }
        val personId = person.text.toString().trim()
        val cameraId = camera.text.toString().trim()
        if (personId.isNotEmpty() && !personId.contains('.')) return showStatus(false, "Sensor must be an entity ID like binary_sensor.front_door_person")
        if (cameraId.isNotEmpty() && !cameraId.startsWith("camera.")) return showStatus(false, "Camera must be an entity ID starting with camera.")
        val close = intField(autoClose, PopupConfig.DEFAULT_AUTO_CLOSE, PopupConfig.AUTO_CLOSE_RANGE, "Auto-close") ?: return
        val quietS = intField(quiet, PopupConfig.DEFAULT_QUIET, PopupConfig.QUIET_RANGE, "Quiet time") ?: return

        settings.save(base, path.text.toString(), newToken.ifEmpty { null },
            PopupConfig(personId, cameraId, title.text.toString(), close, quietS))
        token.setText("")
        refreshTokenState()
        if (settings.popup.enabled) DoorbellService.restart(this) else DoorbellService.stop(this)
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        finish()
    }

    /** Needs a second press within 3 s. Wipes only the SAVED token, never what is typed in the box. */
    private fun reset() {
        val now = SystemClock.uptimeMillis()
        if (now - resetArmedAt > 3_000) {
            resetArmedAt = now
            return showStatus(false, "Press Reset token again to wipe the saved token")
        }
        resetArmedAt = 0
        settings.clearToken()
        DoorbellService.stop(this)
        refreshTokenState()
        showStatus(true, "Saved token wiped. The door pop-up is stopped.")
    }

    private fun showStatus(ok: Boolean?, text: String) {
        status.text = text
        status.setTextColor(when (ok) { true -> Color.parseColor("#66BB6A"); false -> Color.parseColor("#EF5350"); null -> Color.LTGRAY })
    }
}
