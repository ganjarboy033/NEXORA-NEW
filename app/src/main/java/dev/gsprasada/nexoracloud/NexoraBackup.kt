package dev.gsprasada.nexoracloud

import android.content.Context
import android.util.Base64
import java.io.File
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

class BackupException(message: String) : Exception(message)

/** Snapshot indeks file Telegram di penyimpanan lokal: tampil instan saat startup dan ikut dibackup. */
object NxSnapshot {
    private fun file(context: Context) = File(context.filesDir, "nexora_index_snapshot.json")

    fun toJson(items: List<NxItem>): JSONArray {
        val array = JSONArray()
        items.filter { it.source == "tdlib" && !it.isFolder }.forEach {
            array.put(JSONObject().put("id", it.cloudId).put("name", it.name).put("folder", it.folder).put("size", it.sizeBytes)
                .put("mime", it.mime).put("date", it.dateText).put("sha", it.sha256))
        }
        return array
    }

    fun fromJson(array: JSONArray): List<NxItem> = (0 until array.length()).mapNotNull { i ->
        val o = array.optJSONObject(i) ?: return@mapNotNull null
        val id = o.optLong("id", 0L)
        if (id == 0L) return@mapNotNull null
        NxItem("tdlib:$id", false, "tdlib", o.optString("name"), o.optString("folder"), o.optLong("size"), o.optString("mime", "application/octet-stream"),
            o.optString("date"), o.optString("sha"), id, null)
    }

    fun write(context: Context, items: List<NxItem>) {
        runCatching { file(context).writeText(toJson(items).toString()) }
    }

    fun read(context: Context): List<NxItem> = runCatching {
        val f = file(context)
        if (f.isFile) fromJson(JSONArray(f.readText())) else emptyList()
    }.getOrDefault(emptyList())
}

/** Jembatan data antara launcher Activity (SAF) dan halaman Backup. */
object NxBackupBridge {
    class PendingImport(val bytes: ByteArray, val encrypted: Boolean)
    val pendingImport = MutableStateFlow<PendingImport?>(null)
    /** Naik setiap kali restore selesai agar objek pengaturan (NxSettings) dibuat ulang dari preferensi baru. */
    val restoreTick = MutableStateFlow(0)
    @Volatile var pendingExport: ByteArray? = null
}

class NxBackupSummary(
    val createdAt: String, val appVersion: String, val storageTitle: String, val storageChatId: Long,
    val indexCount: Int, val folderCount: Int, val settingsCount: Int, val hasSecrets: Boolean
)

/**
 * Backup/restore data aplikasi dalam satu berkas `.nexora` (JSON, opsional terenkripsi): chat penyimpanan, folder,
 * indeks file, favorit/arsip/label, dan seluruh pengaturan. ID pesan Telegram bersifat permanen, sehingga data tetap
 * valid setelah instal ulang dengan tanda tangan APK yang berbeda (akun Telegram harus sama).
 */
object NexoraBackup {
    const val FORMAT = "nexora-backup"
    const val FORMAT_ENCRYPTED = "nexora-backup-enc"
    const val VERSION = 1
    private const val ITERATIONS = 210_000
    private const val MAX_BYTES = 50L * 1024 * 1024

    /** Preferensi yang boleh dibackup/dipulihkan (allowlist; berkas dari luar tidak dapat menyentuh yang lain). */
    private val PREF_NAMES = listOf("nexora_ui_flags", NxPrefs.NAME, "nexora_accounts", "nexora_proxy", "nexora_tdlib_cloud", TelegramAppConfiguration.PREFS)
    private val SECRET_KEYS = listOf("telegram_app_api_hash", "github_pat", "proxy_secret", "proxy_password", "link_key", "link_ticket_google", "link_ticket_github", "backend_api_key")

    // ------------------------------------------------------------------ susun & baca

    private fun dumpPrefs(context: Context, name: String): JSONObject {
        val out = JSONObject()
        context.getSharedPreferences(name, Context.MODE_PRIVATE).all.forEach { (key, value) ->
            val entry = JSONObject()
            when (value) {
                is Boolean -> entry.put("t", "b").put("v", value)
                is Int -> entry.put("t", "i").put("v", value)
                is Long -> entry.put("t", "l").put("v", value)
                is Float -> entry.put("t", "f").put("v", value.toDouble())
                is String -> entry.put("t", "s").put("v", value)
                is Set<*> -> entry.put("t", "set").put("v", JSONArray(value.map { it.toString() }))
                else -> return@forEach
            }
            out.put(key, entry)
        }
        return out
    }

    private fun loadPrefs(context: Context, name: String, json: JSONObject): Int {
        val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
        var count = 0
        for (key in json.keys()) {
            val entry = json.optJSONObject(key) ?: continue
            when (entry.optString("t")) {
                "b" -> editor.putBoolean(key, entry.optBoolean("v"))
                "i" -> editor.putInt(key, entry.optInt("v"))
                "l" -> editor.putLong(key, entry.optLong("v"))
                "f" -> editor.putFloat(key, entry.optDouble("v").toFloat())
                "s" -> editor.putString(key, entry.optString("v"))
                "set" -> {
                    val array = entry.optJSONArray("v") ?: JSONArray()
                    editor.putStringSet(key, (0 until array.length()).map { array.getString(it) }.toSet())
                }
                else -> continue
            }
            count++
        }
        editor.apply()
        return count
    }

    /** Menyusun isi backup. Rahasia (token, API hash, kunci) hanya disertakan bila [includeSecrets] DAN backup dienkripsi. */
    fun build(context: Context, snapshot: List<NxItem>, includeSecrets: Boolean): JSONObject {
        val cloudPrefs = context.getSharedPreferences("nexora_tdlib_cloud", Context.MODE_PRIVATE)
        val chatId = cloudPrefs.getLong("storage_chat_id", 0L)
        val known = runCatching { JSONArray(TdCloud.get(context).knownChannelsJson()) }.getOrDefault(JSONArray())
        val title = (0 until known.length()).map { known.getJSONObject(it) }.firstOrNull { it.optLong("id") == chatId }?.optString("title").orEmpty()
        val prefs = JSONObject()
        PREF_NAMES.forEach { prefs.put(it, dumpPrefs(context, it)) }
        val settings = JSONObject()
        LocalFileDatabase(context).allSettings().forEach { (k, v) -> settings.put(k, v) }
        val root = JSONObject()
            .put("format", FORMAT).put("version", VERSION)
            .put("created_at", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date()))
            .put("app_version", BuildConfig.VERSION_NAME)
            .put("storage", JSONObject().put("chat_id", chatId).put("saved_messages", chatId == 0L).put("title", title))
            .put("known_channels", known)
            .put("folders", JSONArray(cloudPrefs.getStringSet("empty_folders", emptySet())?.sorted() ?: emptyList<String>()))
            .put("index", NxSnapshot.toJson(snapshot))
            .put("prefs", prefs)
            .put("db_settings", settings)
        if (includeSecrets) {
            val store = SecretStore(context)
            val secrets = JSONObject()
            SECRET_KEYS.forEach { key -> store.get(key).takeIf { it.isNotBlank() }?.let { secrets.put(key, it) } }
            root.put("secrets", secrets)
        }
        return root
    }

    // ------------------------------------------------------------------ enkripsi

    private fun deriveKey(passphrase: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val bytes = factory.generateSecret(PBEKeySpec(passphrase.toCharArray(), salt, iterations, 256)).encoded
        return SecretKeySpec(bytes, "AES")
    }

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun unb64(text: String) = Base64.decode(text, Base64.NO_WRAP)

    /** Mengubah JSON menjadi byte berkas. Kata sandi tidak kosong = terenkripsi AES-256-GCM (kunci dari PBKDF2). */
    fun encode(json: JSONObject, passphrase: String?): ByteArray {
        val plain = json.toString(2).toByteArray(Charsets.UTF_8)
        if (passphrase.isNullOrEmpty()) return plain
        val random = SecureRandom()
        val salt = ByteArray(16).also { random.nextBytes(it) }
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt, ITERATIONS), GCMParameterSpec(128, iv))
        cipher.updateAAD(FORMAT_ENCRYPTED.toByteArray())
        val wrapper = JSONObject().put("format", FORMAT_ENCRYPTED).put("version", VERSION).put("kdf", "PBKDF2WithHmacSHA256")
            .put("iterations", ITERATIONS).put("salt", b64(salt)).put("iv", b64(iv)).put("data", b64(cipher.doFinal(plain)))
        return wrapper.toString(2).toByteArray(Charsets.UTF_8)
    }

    private fun parseRoot(bytes: ByteArray): JSONObject {
        if (bytes.size > MAX_BYTES) throw BackupException("Berkas backup terlalu besar (maksimal 50 MB).")
        return try { JSONObject(String(bytes, Charsets.UTF_8)) } catch (e: Exception) { throw BackupException("Berkas bukan backup Nexora yang valid.") }
    }

    fun isEncrypted(bytes: ByteArray): Boolean = runCatching { parseRoot(bytes).optString("format") == FORMAT_ENCRYPTED }.getOrDefault(false)

    /** Membaca byte berkas menjadi JSON backup (mendekripsi bila perlu). */
    fun decode(bytes: ByteArray, passphrase: String?): JSONObject {
        val root = parseRoot(bytes)
        return when (root.optString("format")) {
            FORMAT -> root
            FORMAT_ENCRYPTED -> {
                if (passphrase.isNullOrEmpty()) throw BackupException("Backup ini terenkripsi. Masukkan kata sandi.")
                try {
                    val iterations = root.optInt("iterations", ITERATIONS).coerceIn(1, 2_000_000)
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, deriveKey(passphrase, unb64(root.getString("salt")), iterations), GCMParameterSpec(128, unb64(root.getString("iv"))))
                    cipher.updateAAD(FORMAT_ENCRYPTED.toByteArray())
                    JSONObject(String(cipher.doFinal(unb64(root.getString("data"))), Charsets.UTF_8))
                } catch (e: Exception) {
                    // GeneralSecurityException (tag GCM salah), Base64/JSON rusak, dsb.
                    throw BackupException("Kata sandi salah atau berkas rusak.")
                }
            }
            else -> throw BackupException("Format backup tidak dikenali.")
        }
    }

    fun summarize(json: JSONObject): NxBackupSummary {
        validate(json)
        val storage = json.optJSONObject("storage") ?: JSONObject()
        return NxBackupSummary(
            json.optString("created_at"), json.optString("app_version"),
            if (storage.optBoolean("saved_messages")) "Saved Messages" else storage.optString("title").ifBlank { "chat ${storage.optLong("chat_id")}" },
            storage.optLong("chat_id"), json.optJSONArray("index")?.length() ?: 0, json.optJSONArray("folders")?.length() ?: 0,
            (json.optJSONObject("db_settings")?.length() ?: 0) + (json.optJSONObject("prefs")?.length() ?: 0), json.has("secrets")
        )
    }

    private fun validate(json: JSONObject) {
        if (json.optString("format") != FORMAT) throw BackupException("Format backup tidak dikenali.")
        if (json.optInt("version") > VERSION) throw BackupException("Backup dibuat oleh versi aplikasi yang lebih baru.")
    }

    // ------------------------------------------------------------------ pulihkan

    /** Memulihkan semua data dari backup. Mengembalikan ringkasan baris untuk ditampilkan. */
    fun restore(context: Context, json: JSONObject): List<String> {
        validate(json)
        val lines = ArrayList<String>()
        val prefs = json.optJSONObject("prefs") ?: JSONObject()
        var prefCount = 0
        for (name in PREF_NAMES) prefs.optJSONObject(name)?.let { prefCount += loadPrefs(context, name, it) }
        lines.add("$prefCount preferensi dipulihkan (tampilan, kustomisasi, favorit/arsip/label, folder, chat penyimpanan).")
        val db = LocalFileDatabase(context)
        val settings = json.optJSONObject("db_settings")
        var settingCount = 0
        if (settings != null) for (key in settings.keys()) { db.setSetting(key, settings.optString(key)); settingCount++ }
        lines.add("$settingCount pengaturan aplikasi dipulihkan.")
        json.optJSONObject("secrets")?.let { secrets ->
            val store = SecretStore(context)
            var restored = 0
            for (key in SECRET_KEYS) if (secrets.has(key)) { store.put(key, secrets.optString(key)); restored++ }
            lines.add("$restored kredensial dipulihkan (API hash, token, kunci).")
        }
        val items = NxSnapshot.fromJson(json.optJSONArray("index") ?: JSONArray())
        if (items.isNotEmpty()) NxSnapshot.write(context, items)
        lines.add("${items.size} entri indeks file dimuat; daftar tersinkron ulang dari Telegram setelah login.")
        TdCloud.get(context).invalidate()
        val storage = json.optJSONObject("storage")
        if (storage != null && !storage.optBoolean("saved_messages")) lines.add("Chat penyimpanan: ${storage.optString("title").ifBlank { storage.optLong("chat_id").toString() }}.")
        return lines
    }
}
