package dev.gsprasada.nexoracloud

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/** Pembaca preferensi kustomisasi untuk kode non-Compose (worker, TdCloud, mesin transfer). */
object NxPrefs {
    const val NAME = "nexora_custom"
    fun prefs(context: Context) = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    fun wifiOnly(context: Context) = prefs(context).getBoolean("wifi_only", false)
    /** Batas ukuran satu file (MB); 0 = tanpa batas. */
    fun maxUploadMb(context: Context) = prefs(context).getInt("max_upload_mb", 0).coerceAtLeast(0)
    /** Ukuran satu bagian chunk (MB), 50..1900. */
    fun chunkMb(context: Context) = prefs(context).getInt("chunk_mb", 1900).coerceIn(50, 1900)
    fun bridgeUrl(context: Context) = prefs(context).getString("bridge_url", "").orEmpty().trim().trimEnd('/')
    fun githubApiBase(context: Context) = prefs(context).getString("github_api_base", "https://api.github.com").orEmpty().trim().trimEnd('/').ifBlank { "https://api.github.com" }
}

/**
 * Pengaturan kustomisasi UI & perilaku yang dapat diamati Compose. Setiap perubahan langsung disimpan.
 * Nilai rahasia (kunci bridge, token) TIDAK disimpan di sini, tetapi di [SecretStore].
 */
class NxSettings(context: Context) {
    private val prefs = NxPrefs.prefs(context)

    var density by mutableStateOf(prefs.getString("density", "normal") ?: "normal"); private set
    var filesView by mutableStateOf(prefs.getString("files_view", "list") ?: "list"); private set
    var galleryColumns by mutableIntStateOf(prefs.getInt("gallery_columns", 3).coerceIn(2, 5)); private set
    var showThumbs by mutableStateOf(prefs.getBoolean("show_thumbs", true)); private set
    var showTags by mutableStateOf(prefs.getBoolean("show_tags", true)); private set
    var startTab by mutableStateOf(prefs.getString("start_tab", "files") ?: "files"); private set
    var navLabels by mutableStateOf(prefs.getBoolean("nav_labels", true)); private set
    var showGooglePill by mutableStateOf(prefs.getBoolean("pill_google", true)); private set
    var showTdPill by mutableStateOf(prefs.getBoolean("pill_td", true)); private set

    var autoRefreshStart by mutableStateOf(prefs.getBoolean("auto_refresh_start", true)); private set
    var autoRefreshMinutes by mutableIntStateOf(prefs.getInt("auto_refresh_minutes", 0)); private set
    var wifiOnly by mutableStateOf(prefs.getBoolean("wifi_only", false)); private set
    var maxUploadMb by mutableIntStateOf(prefs.getInt("max_upload_mb", 0)); private set
    var chunkMb by mutableIntStateOf(prefs.getInt("chunk_mb", 1900).coerceIn(50, 1900)); private set

    var fabVisible by mutableStateOf(prefs.getBoolean("fab_visible", true)); private set
    var fabLocked by mutableStateOf(prefs.getBoolean("fab_locked", false)); private set
    /** Posisi FAB sebagai pecahan area layar (0..1). Nilai negatif = posisi bawaan (kanan-bawah). */
    var fabX by mutableFloatStateOf(prefs.getFloat("fab_x", -1f)); private set
    var fabY by mutableFloatStateOf(prefs.getFloat("fab_y", -1f)); private set

    var bridgeUrl by mutableStateOf(prefs.getString("bridge_url", "") ?: ""); private set
    var githubApiBase by mutableStateOf(prefs.getString("github_api_base", "https://api.github.com") ?: "https://api.github.com"); private set

    private fun putString(key: String, value: String) = prefs.edit().putString(key, value).apply()
    private fun putBoolean(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()
    private fun putInt(key: String, value: Int) = prefs.edit().putInt(key, value).apply()

    fun updateDensity(v: String) { density = v; putString("density", v) }
    fun updateFilesView(v: String) { filesView = v; putString("files_view", v) }
    fun updateGalleryColumns(v: Int) { galleryColumns = v.coerceIn(2, 5); putInt("gallery_columns", galleryColumns) }
    fun updateShowThumbs(v: Boolean) { showThumbs = v; putBoolean("show_thumbs", v) }
    fun updateShowTags(v: Boolean) { showTags = v; putBoolean("show_tags", v) }
    fun updateStartTab(v: String) { startTab = v; putString("start_tab", v) }
    fun updateNavLabels(v: Boolean) { navLabels = v; putBoolean("nav_labels", v) }
    fun updateShowGooglePill(v: Boolean) { showGooglePill = v; putBoolean("pill_google", v) }
    fun updateShowTdPill(v: Boolean) { showTdPill = v; putBoolean("pill_td", v) }
    fun updateAutoRefreshStart(v: Boolean) { autoRefreshStart = v; putBoolean("auto_refresh_start", v) }
    fun updateAutoRefreshMinutes(v: Int) { autoRefreshMinutes = v.coerceIn(0, 120); putInt("auto_refresh_minutes", autoRefreshMinutes) }
    fun updateWifiOnly(v: Boolean) { wifiOnly = v; putBoolean("wifi_only", v) }
    fun updateMaxUploadMb(v: Int) { maxUploadMb = v.coerceAtLeast(0); putInt("max_upload_mb", maxUploadMb) }
    fun updateChunkMb(v: Int) { chunkMb = v.coerceIn(50, 1900); putInt("chunk_mb", chunkMb) }
    fun updateFabVisible(v: Boolean) { fabVisible = v; putBoolean("fab_visible", v) }
    fun updateFabLocked(v: Boolean) { fabLocked = v; putBoolean("fab_locked", v) }
    fun setFabPosition(x: Float, y: Float) { fabX = x; fabY = y; prefs.edit().putFloat("fab_x", x).putFloat("fab_y", y).apply() }
    fun resetFab() { setFabPosition(-1f, -1f); updateFabVisible(true); updateFabLocked(false) }
    fun updateBridgeUrl(v: String) { bridgeUrl = v.trim().trimEnd('/'); putString("bridge_url", bridgeUrl) }
    fun updateGithubApiBase(v: String) { githubApiBase = v.trim().trimEnd('/').ifBlank { "https://api.github.com" }; putString("github_api_base", githubApiBase) }

    /** Mengembalikan semua pengaturan tampilan/perilaku ke bawaan (tidak menyentuh URL endpoint maupun rahasia). */
    fun resetAppearanceAndBehavior() {
        updateDensity("normal"); updateFilesView("list"); updateGalleryColumns(3); updateShowThumbs(true); updateShowTags(true)
        updateStartTab("files"); updateNavLabels(true); updateShowGooglePill(true); updateShowTdPill(true)
        updateAutoRefreshStart(true); updateAutoRefreshMinutes(0); updateWifiOnly(false); updateMaxUploadMb(0); updateChunkMb(1900); resetFab()
    }
}

val LocalNxSettings = staticCompositionLocalOf<NxSettings?> { null }
