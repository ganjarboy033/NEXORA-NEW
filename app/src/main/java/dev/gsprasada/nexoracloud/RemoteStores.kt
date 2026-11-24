package dev.gsprasada.nexoracloud

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.webkit.MimeTypeMap
import java.io.File
import java.io.IOException
import java.io.OutputStream
import org.json.JSONArray
import org.json.JSONObject

/** Entri datar dari penyimpanan jarak jauh. [folder] = jalur induk ("" = akar). [extra] = data khusus penyedia. */
data class RemoteEntry(
    val id: String,
    val name: String,
    val folder: String,
    val isDir: Boolean,
    val size: Long,
    val mime: String,
    val modified: String,
    val sha: String = "",
    val extra: String = ""
)

interface RemoteStore {
    val id: String
    val label: String
    fun isConnected(): Boolean
    fun loadAll(): List<RemoteEntry>
    fun download(entry: RemoteEntry, out: OutputStream, onProgress: (Long, Long) -> Unit): Long
    fun upload(folder: String, file: File, name: String, onProgress: (Long, Long) -> Unit): String
    fun mkdir(path: String)
    fun delete(entry: RemoteEntry)
    fun rename(entry: RemoteEntry, newName: String)
    fun move(entry: RemoteEntry, newFolder: String)
    fun copy(entry: RemoteEntry, newFolder: String)
}

internal fun nxMimeOf(name: String): String =
    MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"

private fun joinPath(vararg parts: String): String = parts.flatMap { it.split('/') }.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("/")

// ====================================================================== GitHub

/** GitHub lewat REST API: repo, pohon file, baca/tulis/hapus. Token = PAT (Keystore) atau login Nexora Link. */
class GitHubStore(private val context: Context) : RemoteStore {
    override val id = "github"
    override val label = "GitHub"
    private val prefs = context.getSharedPreferences("nexora_accounts", Context.MODE_PRIVATE)

    val owner: String get() = prefs.getString("github_owner", "").orEmpty()
    val repo: String get() = prefs.getString("github_repo", "").orEmpty()
    val branch: String get() = prefs.getString("github_branch", "").orEmpty()

    fun configure(owner: String, repo: String, branch: String) {
        prefs.edit().putString("github_owner", owner.trim()).putString("github_repo", repo.trim()).putString("github_branch", branch.trim()).apply()
    }

    private fun token(): String {
        val pat = SecretStore(context).get("github_pat")
        if (pat.isNotBlank()) return pat
        return if (NexoraLink.hasTicket(context, "github")) NexoraLink.accessToken(context, "github") else ""
    }

    private fun headers(raw: Boolean = false) = mapOf(
        "Accept" to (if (raw) "application/vnd.github.raw+json" else "application/vnd.github+json"),
        "Authorization" to "Bearer ${token()}", "X-GitHub-Api-Version" to "2022-11-28", "User-Agent" to "NexoraCloud"
    )

    private fun api(path: String) = NxPrefs.githubApiBase(context) + path
    private fun enc(path: String) = path.split('/').filter { it.isNotEmpty() }.joinToString("/") { Uri.encode(it) }
    private fun repoPath() = "/repos/${Uri.encode(owner)}/${Uri.encode(repo)}"

    override fun isConnected(): Boolean =
        owner.isNotBlank() && repo.isNotBlank() && (SecretStore(context).get("github_pat").isNotBlank() || NexoraLink.hasTicket(context, "github"))

    /** Daftar repo yang dapat diakses: (nama lengkap, privat, branch bawaan). */
    fun listRepos(): List<Triple<String, Boolean, String>> {
        val out = ArrayList<Triple<String, Boolean, String>>()
        for (page in 1..5) {
            val result = NxHttp.check(NxHttp.request("GET", api("/user/repos?per_page=100&sort=updated&page=$page"), headers()), "Membaca daftar repo")
            val array = JSONArray(result.body)
            for (i in 0 until array.length()) {
                val r = array.getJSONObject(i)
                out.add(Triple(r.getString("full_name"), r.optBoolean("private"), r.optString("default_branch", "main")))
            }
            if (array.length() < 100) break
        }
        return out
    }

    fun createRepo(name: String, private: Boolean): String {
        require(name.matches(Regex("[A-Za-z0-9_.-]{1,100}"))) { "Nama repo hanya boleh huruf, angka, titik, minus, dan garis bawah." }
        val result = NxHttp.check(
            NxHttp.json("POST", api("/user/repos"), headers(), JSONObject().put("name", name).put("private", private).put("auto_init", true).put("description", "Penyimpanan Nexora Cloud")),
            "Membuat repo"
        )
        return JSONObject(result.body).getString("full_name")
    }

    /** Memverifikasi token dan akses repo yang dipilih; mengembalikan ringkasan. */
    fun verify(): String {
        val result = NxHttp.check(NxHttp.request("GET", api(repoPath()), headers()), "Memeriksa repo")
        val json = JSONObject(result.body)
        val push = json.optJSONObject("permissions")?.optBoolean("push", false) == true
        if (branch.isBlank()) prefs.edit().putString("github_branch", json.optString("default_branch", "main")).apply()
        return "OK: ${json.optString("full_name")} • ${if (json.optBoolean("private")) "privat" else "publik"} • akses tulis: ${if (push) "ya" else "tidak"}"
    }

    private fun activeBranch(): String {
        if (branch.isNotBlank()) return branch
        verify()
        return branch.ifBlank { "main" }
    }

    override fun loadAll(): List<RemoteEntry> {
        val br = activeBranch()
        val result = NxHttp.request("GET", api("${repoPath()}/git/trees/${Uri.encode(br)}?recursive=1"), headers())
        if (result.code == 404 || result.code == 409) return emptyList() // repo kosong
        NxHttp.check(result, "Membaca pohon repo")
        val tree = JSONObject(result.body).getJSONArray("tree")
        val out = ArrayList<RemoteEntry>()
        for (i in 0 until tree.length()) {
            val node = tree.getJSONObject(i)
            val path = node.getString("path")
            val name = path.substringAfterLast('/')
            val parent = path.substringBeforeLast('/', "")
            when (node.optString("type")) {
                "tree" -> out.add(RemoteEntry(path, name, parent, true, 0L, "", "", node.optString("sha")))
                "blob" -> if (name != ".gitkeep") out.add(RemoteEntry(path, name, parent, false, node.optLong("size"), nxMimeOf(name), "", node.optString("sha")))
            }
        }
        return out
    }

    override fun download(entry: RemoteEntry, out: OutputStream, onProgress: (Long, Long) -> Unit): Long =
        NxHttp.download(api("${repoPath()}/contents/${enc(entry.id)}?ref=${Uri.encode(activeBranch())}"), headers(raw = true), out, onProgress)

    private fun existingSha(path: String): String? {
        val result = NxHttp.request("GET", api("${repoPath()}/contents/${enc(path)}?ref=${Uri.encode(activeBranch())}"), headers())
        if (result.code == 404) return null
        NxHttp.check(result, "Memeriksa berkas")
        return runCatching { JSONObject(result.body).optString("sha").takeIf { it.isNotBlank() } }.getOrNull()
    }

    private fun put(path: String, content: ByteArray, message: String) {
        val body = JSONObject().put("message", message).put("content", Base64.encodeToString(content, Base64.NO_WRAP)).put("branch", activeBranch())
        existingSha(path)?.let { body.put("sha", it) }
        NxHttp.check(NxHttp.json("PUT", api("${repoPath()}/contents/${enc(path)}"), headers(), body), "Menulis berkas")
    }

    override fun upload(folder: String, file: File, name: String, onProgress: (Long, Long) -> Unit): String {
        require(file.length() <= MAX_UPLOAD) { "GitHub: ukuran maksimal ${MAX_UPLOAD / (1024 * 1024)} MB per berkas lewat aplikasi ini (batas API 100 MB)." }
        val path = joinPath(folder, name)
        put(path, file.readBytes(), "Nexora Cloud: unggah $name")
        onProgress(file.length(), file.length())
        return path
    }

    override fun mkdir(path: String) { put(joinPath(path, ".gitkeep"), "Nexora folder\n".toByteArray(), "Nexora Cloud: buat folder $path") }

    override fun delete(entry: RemoteEntry) {
        require(!entry.isDir) { "Hapus berkas di dalam folder satu per satu (Git tidak menyimpan folder kosong)." }
        val body = JSONObject().put("message", "Nexora Cloud: hapus ${entry.name}").put("sha", entry.sha).put("branch", activeBranch())
        NxHttp.check(NxHttp.json("DELETE", api("${repoPath()}/contents/${enc(entry.id)}"), headers(), body), "Menghapus berkas")
    }

    private fun viaTemp(entry: RemoteEntry, targetFolder: String, targetName: String, deleteSource: Boolean) {
        require(!entry.isDir) { "Folder diproses per berkas." }
        if (joinPath(targetFolder, targetName) == entry.id) return
        val dir = File(context.cacheDir, "remote_tmp").apply { mkdirs() }
        val tmp = File.createTempFile("gh_", ".bin", dir)
        try {
            tmp.outputStream().use { download(entry, it) { _, _ -> } }
            upload(targetFolder, tmp, targetName) { _, _ -> }
            if (deleteSource) delete(entry)
        } finally {
            tmp.delete()
        }
    }

    override fun rename(entry: RemoteEntry, newName: String) = viaTemp(entry, entry.folder, newName, true)
    override fun move(entry: RemoteEntry, newFolder: String) = viaTemp(entry, newFolder, entry.name, true)
    override fun copy(entry: RemoteEntry, newFolder: String) = viaTemp(entry, newFolder, entry.name, false)

    companion object { const val MAX_UPLOAD = 25L * 1024 * 1024 }
}

// ====================================================================== Google Drive

class GoogleDriveStore(private val context: Context) : RemoteStore {
    override val id = "gdrive"
    override val label = "Google Drive"
    private val folderMime = "application/vnd.google-apps.folder"
    private val base = "https://www.googleapis.com/drive/v3"
    private val folderIds = HashMap<String, String>()
    private var rootId = "root"
    private var loaded = false

    override fun isConnected(): Boolean = NexoraLink.hasTicket(context, "google")
    private fun auth() = mapOf("Authorization" to "Bearer ${NexoraLink.accessToken(context, "google")}")
    private fun q(value: String) = Uri.encode(value)

    override fun loadAll(): List<RemoteEntry> {
        rootId = JSONObject(NxHttp.check(NxHttp.request("GET", "$base/files/root?fields=id", auth()), "Membaca akar Drive").body).getString("id")
        val nodes = ArrayList<JSONObject>()
        var pageToken: String? = null
        do {
            val url = "$base/files?pageSize=1000&q=${q("trashed=false")}&fields=${q("nextPageToken,files(id,name,mimeType,parents,size,modifiedTime,md5Checksum)")}" +
                (pageToken?.let { "&pageToken=${q(it)}" } ?: "")
            val json = JSONObject(NxHttp.check(NxHttp.request("GET", url, auth()), "Membaca daftar Drive").body)
            val files = json.optJSONArray("files") ?: JSONArray()
            for (i in 0 until files.length()) nodes.add(files.getJSONObject(i))
            pageToken = json.optString("nextPageToken").takeIf { it.isNotBlank() }
        } while (pageToken != null && nodes.size < 20000)
        val byId = nodes.associateBy { it.getString("id") }
        val memo = HashMap<String, String>()
        fun parentPath(node: JSONObject): String {
            val parts = ArrayList<String>()
            var current = node.optJSONArray("parents")?.optString(0)
            var depth = 0
            while (current != null && current != rootId && depth++ < 30) {
                memo[current]?.let { cached -> return (listOf(cached) + parts).filter { it.isNotEmpty() }.joinToString("/") }
                val parent = byId[current] ?: break
                parts.add(0, parent.getString("name"))
                current = parent.optJSONArray("parents")?.optString(0)
            }
            return parts.joinToString("/")
        }
        folderIds.clear()
        val out = ArrayList<RemoteEntry>()
        for (node in nodes) {
            val isDir = node.optString("mimeType") == folderMime
            val folder = parentPath(node)
            val name = node.getString("name")
            if (isDir) { val full = joinPath(folder, name); folderIds[full] = node.getString("id"); memo[node.getString("id")] = full }
            out.add(
                RemoteEntry(
                    node.getString("id"), name, folder, isDir, node.optString("size").toLongOrNull() ?: 0L, node.optString("mimeType"),
                    node.optString("modifiedTime"), node.optString("md5Checksum"), node.optJSONArray("parents")?.optString(0).orEmpty()
                )
            )
        }
        loaded = true
        return out
    }

    private fun folderId(path: String, create: Boolean): String {
        if (!loaded) loadAll()
        val clean = joinPath(path)
        if (clean.isEmpty()) return rootId
        folderIds[clean]?.let { return it }
        require(create) { "Folder Drive tidak ditemukan: $clean" }
        var current = ""
        var parentId = rootId
        for (segment in clean.split('/')) {
            current = joinPath(current, segment)
            val existing = folderIds[current]
            if (existing != null) { parentId = existing; continue }
            val body = JSONObject().put("name", segment).put("mimeType", folderMime).put("parents", JSONArray().put(parentId))
            val created = JSONObject(NxHttp.check(NxHttp.json("POST", "$base/files?fields=id", auth(), body), "Membuat folder").body).getString("id")
            folderIds[current] = created
            parentId = created
        }
        return parentId
    }

    override fun download(entry: RemoteEntry, out: OutputStream, onProgress: (Long, Long) -> Unit): Long {
        val url = if (entry.mime.startsWith("application/vnd.google-apps.")) "$base/files/${entry.id}/export?mimeType=application/pdf" else "$base/files/${entry.id}?alt=media"
        return NxHttp.download(url, auth(), out, onProgress)
    }

    override fun upload(folder: String, file: File, name: String, onProgress: (Long, Long) -> Unit): String {
        val parent = folderId(folder, create = true)
        val mime = nxMimeOf(name)
        val meta = JSONObject().put("name", name).put("parents", JSONArray().put(parent))
        val init = NxHttp.check(
            NxHttp.json("POST", "https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&fields=id", auth() + mapOf("X-Upload-Content-Type" to mime, "X-Upload-Content-Length" to file.length().toString()), meta),
            "Memulai unggahan"
        )
        val location = init.location ?: throw IOException("Google tidak mengembalikan URL unggahan.")
        val done = NxHttp.check(NxHttp.request("PUT", location, emptyMap(), NxHttp.FileBody(file, mime, onProgress)), "Mengunggah")
        return JSONObject(done.body).optString("id")
    }

    override fun mkdir(path: String) { folderId(path, create = true) }

    override fun delete(entry: RemoteEntry) {
        NxHttp.check(NxHttp.json("PATCH", "$base/files/${entry.id}", auth(), JSONObject().put("trashed", true)), "Memindahkan ke sampah Drive")
    }

    override fun rename(entry: RemoteEntry, newName: String) {
        NxHttp.check(NxHttp.json("PATCH", "$base/files/${entry.id}", auth(), JSONObject().put("name", newName)), "Mengganti nama")
    }

    override fun move(entry: RemoteEntry, newFolder: String) {
        val target = folderId(newFolder, create = true)
        val remove = if (entry.extra.isNotBlank()) "&removeParents=${q(entry.extra)}" else ""
        NxHttp.check(NxHttp.json("PATCH", "$base/files/${entry.id}?addParents=${q(target)}$remove", auth(), JSONObject()), "Memindahkan")
    }

    override fun copy(entry: RemoteEntry, newFolder: String) {
        require(!entry.isDir) { "Drive tidak dapat menyalin folder; salin berkas satu per satu." }
        val target = folderId(newFolder, create = true)
        NxHttp.check(NxHttp.json("POST", "$base/files/${entry.id}/copy", auth(), JSONObject().put("name", entry.name).put("parents", JSONArray().put(target))), "Menyalin")
    }
}

// ====================================================================== Google Photos

/**
 * Google Photos. Sejak 2025 Library API hanya mengelola media yang DIBUAT aplikasi; membaca galeri pengguna dilakukan lewat
 * Picker API (pengguna memilih foto di aplikasi Google Photos, lalu aplikasi mengimpornya).
 */
class GooglePhotosStore(private val context: Context) : RemoteStore {
    override val id = "gphotos"
    override val label = "Google Photos"
    private val library = "https://photoslibrary.googleapis.com/v1"
    private val picker: String get() = NxPrefs.prefs(context).getString("photos_picker_base", "https://photospicker.googleapis.com/v1").orEmpty().trimEnd('/')

    override fun isConnected(): Boolean = NexoraLink.hasTicket(context, "google")
    private fun auth() = mapOf("Authorization" to "Bearer ${NexoraLink.accessToken(context, "google")}")

    /** Media yang dibuat oleh Nexora Cloud (memerlukan scope photos_appdata). */
    override fun loadAll(): List<RemoteEntry> {
        val out = ArrayList<RemoteEntry>()
        var token: String? = null
        do {
            val result = NxHttp.request("GET", "$library/mediaItems?pageSize=100" + (token?.let { "&pageToken=${Uri.encode(it)}" } ?: ""), auth())
            if (result.code == 403) throw IOException("Scope Google Photos belum diberikan. Login ulang Google dengan scope photos_appdata (lihat Nexora Link).")
            NxHttp.check(result, "Membaca media Photos")
            val json = JSONObject(result.body)
            val items = json.optJSONArray("mediaItems") ?: JSONArray()
            for (i in 0 until items.length()) {
                val m = items.getJSONObject(i)
                out.add(RemoteEntry(m.getString("id"), m.optString("filename", m.getString("id")), "", false, 0L, m.optString("mimeType"),
                    m.optJSONObject("mediaMetadata")?.optString("creationTime").orEmpty(), "", m.optString("baseUrl")))
            }
            token = json.optString("nextPageToken").takeIf { it.isNotBlank() }
        } while (token != null && out.size < 5000)
        return out
    }

    private fun suffix(mime: String) = if (mime.startsWith("video/")) "=dv" else "=d"

    override fun download(entry: RemoteEntry, out: OutputStream, onProgress: (Long, Long) -> Unit): Long {
        // baseUrl hanya berlaku ±60 menit: ambil yang segar dari API sebelum mengunduh.
        val fresh = runCatching {
            JSONObject(NxHttp.check(NxHttp.request("GET", "$library/mediaItems/${Uri.encode(entry.id)}", auth()), "Menyegarkan media").body).optString("baseUrl")
        }.getOrDefault("").ifBlank { entry.extra }
        if (fresh.isBlank()) throw IOException("URL media tidak tersedia.")
        return NxHttp.download(fresh + suffix(entry.mime), auth(), out, onProgress)
    }

    override fun upload(folder: String, file: File, name: String, onProgress: (Long, Long) -> Unit): String {
        val mime = nxMimeOf(name)
        require(mime.startsWith("image/") || mime.startsWith("video/")) { "Google Photos hanya menerima foto dan video." }
        val raw = NxHttp.check(
            NxHttp.request("POST", "$library/uploads", auth() + mapOf("X-Goog-Upload-Content-Type" to mime, "X-Goog-Upload-Protocol" to "raw"), NxHttp.FileBody(file, "application/octet-stream", onProgress)),
            "Mengunggah bytes"
        )
        val token = raw.body.trim()
        val item = JSONObject().put("description", "Nexora Cloud").put("simpleMediaItem", JSONObject().put("uploadToken", token).put("fileName", name))
        val created = NxHttp.check(NxHttp.json("POST", "$library/mediaItems:batchCreate", auth(), JSONObject().put("newMediaItems", JSONArray().put(item))), "Membuat media")
        val result = JSONObject(created.body).optJSONArray("newMediaItemResults")?.optJSONObject(0)
        val status = result?.optJSONObject("status")?.optString("message").orEmpty()
        if (status.isNotBlank() && !status.equals("Success", true) && !status.equals("OK", true)) throw IOException("Photos menolak: $status")
        return result?.optJSONObject("mediaItem")?.optString("id").orEmpty()
    }

    override fun mkdir(path: String) = throw UnsupportedOperationException("Google Photos tidak memakai folder.")
    override fun delete(entry: RemoteEntry) = throw UnsupportedOperationException("Google Photos API tidak mengizinkan menghapus media; hapus lewat aplikasi Google Photos.")
    override fun rename(entry: RemoteEntry, newName: String) = throw UnsupportedOperationException("Google Photos API tidak mendukung ganti nama.")
    override fun move(entry: RemoteEntry, newFolder: String) = throw UnsupportedOperationException("Google Photos tidak memakai folder.")
    override fun copy(entry: RemoteEntry, newFolder: String) = throw UnsupportedOperationException("Gunakan Unduh atau Impor untuk menyalin media.")

    // ---- Picker API: pengguna memilih foto/video miliknya, aplikasi mengimpor
    class PickerSession(val id: String, val pickerUri: String, val pollMillis: Long)

    fun createPickerSession(): PickerSession {
        val result = NxHttp.check(NxHttp.json("POST", "$picker/sessions", auth(), JSONObject()), "Membuat sesi Picker")
        val json = JSONObject(result.body)
        val poll = json.optJSONObject("pollingConfig")?.optString("pollInterval").orEmpty().removeSuffix("s").toDoubleOrNull() ?: 5.0
        return PickerSession(json.getString("id"), json.getString("pickerUri"), (poll * 1000).toLong().coerceIn(2000L, 30000L))
    }

    /** True bila pengguna sudah selesai memilih. */
    fun pickerReady(sessionId: String): Boolean {
        val result = NxHttp.check(NxHttp.request("GET", "$picker/sessions/${Uri.encode(sessionId)}", auth()), "Memeriksa sesi Picker")
        return JSONObject(result.body).optBoolean("mediaItemsSet")
    }

    fun listPicked(sessionId: String): List<RemoteEntry> {
        val out = ArrayList<RemoteEntry>()
        var token: String? = null
        do {
            val url = "$picker/mediaItems?sessionId=${Uri.encode(sessionId)}&pageSize=100" + (token?.let { "&pageToken=${Uri.encode(it)}" } ?: "")
            val json = JSONObject(NxHttp.check(NxHttp.request("GET", url, auth()), "Membaca pilihan").body)
            val items = json.optJSONArray("mediaItems") ?: JSONArray()
            for (i in 0 until items.length()) {
                val m = items.getJSONObject(i)
                val file = m.optJSONObject("mediaFile") ?: continue
                out.add(RemoteEntry(m.getString("id"), file.optString("filename", m.getString("id")), "", false, 0L, file.optString("mimeType"), m.optString("createTime"), "", file.optString("baseUrl")))
            }
            token = json.optString("nextPageToken").takeIf { it.isNotBlank() }
        } while (token != null && out.size < 2000)
        return out
    }

    fun downloadPicked(entry: RemoteEntry, out: OutputStream, onProgress: (Long, Long) -> Unit): Long =
        NxHttp.download(entry.extra + suffix(entry.mime), auth(), out, onProgress)

    fun deletePickerSession(sessionId: String) { runCatching { NxHttp.request("DELETE", "$picker/sessions/${Uri.encode(sessionId)}", auth()) } }
}

/** Akses terpusat ke penyimpanan jarak jauh. */
object NxRemotes {
    fun github(context: Context) = GitHubStore(context)
    fun drive(context: Context) = GoogleDriveStore(context)
    fun photos(context: Context) = GooglePhotosStore(context)
    fun byId(context: Context, id: String): RemoteStore? = when (id) { "github" -> github(context); "gdrive" -> drive(context); "gphotos" -> photos(context); else -> null }
    fun connected(context: Context): List<RemoteStore> = listOf<RemoteStore>(github(context), drive(context), photos(context)).filter { it.isConnected() }
}
