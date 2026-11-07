package io.github.codemastervy.hatv

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Front-door pop-up settings. The pop-up (and its background service) is off when no sensor is set. */
data class PopupConfig(
    val personEntity: String,
    val cameraEntity: String,
    val title: String,
    val autoCloseSeconds: Int,
    val quietSeconds: Int,
) {
    val enabled: Boolean get() = personEntity.isNotBlank()

    companion object {
        const val DEFAULT_TITLE = "Someone at the door"
        const val DEFAULT_AUTO_CLOSE = 30
        const val DEFAULT_QUIET = 60
        val AUTO_CLOSE_RANGE = 5..600
        val QUIET_RANGE = 0..3600
    }
}

/** Everything the user sets, stored in EncryptedSharedPreferences (Android Keystore backed). Nothing is hard-coded. */
class HaSettings(context: Context) {
    private val prefs: SharedPreferences = open(context.applicationContext)

    val baseUrl: String get() = Urls.normalizeBase(prefs.getString(K_URL, null) ?: "")
    val dashboardPath: String get() = prefs.getString(K_PATH, null) ?: ""
    val dashboardUrl: String get() = Urls.join(baseUrl, dashboardPath)
    val token: String? get() = prefs.getString(K_TOKEN, null)?.takeIf { it.isNotBlank() }
    val hasToken: Boolean get() = token != null
    /** Ready to show the dashboard. */
    val isConfigured: Boolean get() = hasToken && Urls.isValidBase(baseUrl)

    val popup: PopupConfig get() = PopupConfig(
        personEntity = prefs.getString(K_PERSON, null) ?: "",
        cameraEntity = prefs.getString(K_CAMERA, null) ?: "",
        title = prefs.getString(K_TITLE, null)?.takeIf { it.isNotBlank() } ?: PopupConfig.DEFAULT_TITLE,
        autoCloseSeconds = prefs.getInt(K_AUTO_CLOSE, PopupConfig.DEFAULT_AUTO_CLOSE),
        quietSeconds = prefs.getInt(K_QUIET, PopupConfig.DEFAULT_QUIET),
    )

    /** Bumped on every save/reset so the dashboard knows to reload. */
    val version: Int get() = prefs.getInt(K_VERSION, 0)

    /** [newToken] null/blank keeps the saved token. */
    fun save(url: String, path: String, newToken: String?, popup: PopupConfig) {
        prefs.edit().apply {
            putString(K_URL, Urls.normalizeBase(url))
            putString(K_PATH, path.trim())
            if (!newToken.isNullOrBlank()) putString(K_TOKEN, newToken.trim())
            putString(K_PERSON, popup.personEntity.trim())
            putString(K_CAMERA, popup.cameraEntity.trim())
            putString(K_TITLE, popup.title.trim())
            putInt(K_AUTO_CLOSE, popup.autoCloseSeconds.coerceIn(PopupConfig.AUTO_CLOSE_RANGE))
            putInt(K_QUIET, popup.quietSeconds.coerceIn(PopupConfig.QUIET_RANGE))
            putInt(K_VERSION, version + 1)
        }.commit()
    }

    fun clearToken() {
        prefs.edit().remove(K_TOKEN).putInt(K_VERSION, version + 1).commit()
    }

    companion object {
        private const val FILE = "ha_tv_secure"
        private const val K_URL = "url"
        private const val K_PATH = "path"
        private const val K_TOKEN = "token"
        private const val K_PERSON = "person_entity"
        private const val K_CAMERA = "camera_entity"
        private const val K_TITLE = "popup_title"
        private const val K_AUTO_CLOSE = "popup_auto_close_s"
        private const val K_QUIET = "popup_quiet_s"
        private const val K_VERSION = "version"

        private fun create(ctx: Context): SharedPreferences {
            val key = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            return EncryptedSharedPreferences.create(
                ctx, FILE, key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }

        private fun open(ctx: Context): SharedPreferences = try {
            create(ctx)
        } catch (e: Exception) {
            // Keystore key lost (e.g. after a restore). Start fresh rather than crash; user re-enters settings.
            L.w("Encrypted settings unreadable, resetting", e)
            ctx.deleteSharedPreferences(FILE)
            create(ctx)
        }
    }
}

/** Shared between the dashboard and the service. */
object AppState {
    /** True while the dashboard Activity is started (visible). The pop-up is skipped then. */
    @Volatile var dashboardVisible = false
}
