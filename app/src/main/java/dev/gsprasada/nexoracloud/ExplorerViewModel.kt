package dev.gsprasada.nexoracloud

import android.app.Application
import android.content.Context
import android.webkit.MimeTypeMap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Satu entri di penjelajah terpadu: folder virtual Telegram atau berkas (Telegram/lokal). */
data class NxItem(
    val id: String,
    val isFolder: Boolean,
    val source: String,
    val name: String,
    val folder: String,
    val sizeBytes: Long,
    val mime: String,
    val dateText: String,
    val sha256: String,
    val cloudId: Long,
    val localPath: String?,
    val fileCount: Int = 0,
    val remoteId: String = "",
    val remoteSha: String = "",
    val remoteExtra: String = ""
) {
    val kind: String get() = if (isFolder) "folder" else nxKind(mime, name)
    val fullPath: String get() = if (folder.isEmpty()) name else "$folder/$name"
}

/** Tujuan salin/pindah: [source] = tdlib | github | gdrive | gphotos | lokal; [chatId] hanya untuk tdlib (0 = chat aktif). */
data class NxTarget(val source: String, val folder: String, val chatId: Long = 0L)

sealed interface NxViewerState {
    data object Idle : NxViewerState
    data object Loading : NxViewerState
    data class Ready(val file: File) : NxViewerState
    data object Cancelled : NxViewerState
    data class Failed(val message: String) : NxViewerState
}

/**
 * Penjelajah terpadu: menggabungkan berkas cloud (Telegram lewat [CloudGateway]) dan pustaka lokal.
 * Seluruh operasi cloud memakai gateway/TdCloud yang sudah ada; ViewModel ini tidak mengubah logika autentikasi atau penyimpanan.
 */
class ExplorerViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Context = app.applicationContext
    private val prefs = ctx.getSharedPreferences("nexora_ui_flags", Context.MODE_PRIVATE)
    val db = LocalFileDatabase(ctx)
    val gateway = CloudGateway(ctx, BackendClient(ctx))
    val library: File = File(ctx.filesDir, "berkas").apply { mkdirs() }
    private val trashDir = File(ctx.filesDir, "sampah")
    val sessionCacheDir: File = File(ctx.cacheDir, "session_preview").apply { mkdirs() }

    // ------------------------------------------------------------------ state data

    var cloudItems by mutableStateOf<List<NxItem>>(emptyList()); private set
    var localItems by mutableStateOf<List<NxItem>>(emptyList()); private set
    var emptyFolders by mutableStateOf<List<String>>(emptyList()); private set
    var loading by mutableStateOf(false); private set
    var cloudNote by mutableStateOf<String?>(null); private set
    var toast by mutableStateOf<String?>(null); private set
    var cacheTick by mutableIntStateOf(0); private set
    var remoteItems by mutableStateOf<List<NxItem>>(emptyList()); private set
    var remoteDirs by mutableStateOf<Map<String, List<String>>>(emptyMap()); private set
    var remoteNotes by mutableStateOf<Map<String, String>>(emptyMap()); private set
    var channels by mutableStateOf<List<TdChannelInfo>>(emptyList()); private set
    var activeTarget by mutableStateOf<TdStorageTarget?>(null); private set
    var channelBusy by mutableStateOf(false); private set
    private var localHashes: Map<String, String> = emptyMap()
    private var toastJob: Job? = null

    var favTd by mutableStateOf<Set<String>>(prefs.getStringSet("fav", emptySet())?.toSet() ?: emptySet()); private set
    var archivedIds by mutableStateOf<Set<String>>(prefs.getStringSet("archive", emptySet())?.toSet() ?: emptySet()); private set
    var localFavs by mutableStateOf<Set<String>>(emptySet()); private set
    var labelMap by mutableStateOf<Map<String, List<String>>>(loadLabels()); private set

    // ------------------------------------------------------------------ state navigasi

    var filter by mutableStateOf("all")
    var query by mutableStateOf("")
    var filesFolder by mutableStateOf("")
    var galleryFolder by mutableStateOf("")
    var batchMode by mutableStateOf(false); private set
    var selected by mutableStateOf<Set<String>>(emptySet()); private set

    var viewerItem by mutableStateOf<NxItem?>(null); private set
    var viewerState by mutableStateOf<NxViewerState>(NxViewerState.Idle); private set
    private var viewerJob: Job? = null

    init {
        // Indeks terakhir langsung tampil (mis. setelah instal ulang + impor backup) sebelum sinkron dengan Telegram selesai.
        val snapshot = NxSnapshot.read(ctx)
        if (snapshot.isNotEmpty()) { cloudItems = snapshot; cloudNote = "Menampilkan indeks tersimpan; menyinkronkan dengan Telegram…" }
    }

    fun showToast(message: String) {
        toast = message
        toastJob?.cancel()
        toastJob = viewModelScope.launch { delay(2600); toast = null }
    }

    // ------------------------------------------------------------------ muat data

    /** Memuat ulang katalog. [includeCloud] = false hanya memuat pustaka lokal (indeks Telegram tersimpan tetap dipakai). */
    fun refresh(includeCloud: Boolean = true) {
        viewModelScope.launch {
            loading = true
            if (includeCloud) {
                val cloud = withContext(Dispatchers.IO) { runCatching { loadCloud() } }
                cloud.onSuccess { cloudItems = it.first; emptyFolders = it.second; cloudNote = null }
                    .onFailure { cloudNote = it.message ?: "Cloud tidak dapat dimuat." }
                val remote = withContext(Dispatchers.IO) { runCatching { loadRemote() } }
                remote.onSuccess { remoteItems = it.first; remoteDirs = it.second; remoteNotes = it.third }
            }
            val local = withContext(Dispatchers.IO) {
                Triple(db.listFiles().map(::toLocalItem), db.favoritePaths(), db.listHashes())
            }
            localItems = local.first; localFavs = local.second; localHashes = local.third
            loading = false
        }
    }

    private fun loadCloud(): Pair<List<NxItem>, List<String>> {
        val out = ArrayList<NxItem>()
        var offset = 0
        while (offset < 20000) {
            val page = gateway.listCloudFiles(100, offset, "", "all", "date", "desc")
            out.addAll(page.map(::toCloudItem))
            if (page.size < 100) break
            offset += page.size
        }
        NxSnapshot.write(ctx, out)
        return out to gateway.emptyFolders()
    }

    private fun toCloudItem(f: CloudFileItem) = NxItem(
        id = "tdlib:${f.id}", isFolder = false, source = "tdlib", name = f.name,
        folder = f.archivePath.trim('/'), sizeBytes = f.byteSize, mime = f.mimeType,
        dateText = f.createdAt, sha256 = f.sha256, cloudId = f.id, localPath = null
    )

    private fun joinPath(vararg parts: String): String =
        parts.flatMap { it.split('/') }.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("/")

    private fun loadRemote(): Triple<List<NxItem>, Map<String, List<String>>, Map<String, String>> {
        val items = ArrayList<NxItem>()
        val dirs = HashMap<String, List<String>>()
        val notes = HashMap<String, String>()
        for (store in NxRemotes.connected(ctx)) {
            try {
                val entries = store.loadAll()
                dirs[store.id] = entries.filter { it.isDir }.map { joinPath(it.folder, it.name) }
                entries.filter { !it.isDir }.mapTo(items) { toRemoteItem(store.id, it) }
            } catch (e: Exception) {
                notes[store.id] = "${store.label}: ${e.message ?: e.javaClass.simpleName}"
                AppLog.err("Memuat ${store.label} gagal: ${e.message}")
            }
        }
        return Triple(items, dirs, notes)
    }

    private fun toRemoteItem(source: String, e: RemoteEntry) = NxItem(
        id = "$source:${e.id}", isFolder = false, source = source, name = e.name, folder = e.folder, sizeBytes = e.size,
        mime = e.mime.ifBlank { nxMimeOf(e.name) }, dateText = e.modified, sha256 = e.sha.takeIf { it.length == 64 }.orEmpty(),
        cloudId = 0L, localPath = null, remoteId = e.id, remoteSha = e.sha, remoteExtra = e.extra
    )

    private fun toRemoteEntry(item: NxItem) =
        RemoteEntry(item.remoteId, item.name, item.folder, false, item.sizeBytes, item.mime, item.dateText, item.remoteSha, item.remoteExtra)

    private fun remoteStore(item: NxItem): RemoteStore = remoteStoreById(item.source)
    private fun remoteStoreById(id: String): RemoteStore = NxRemotes.byId(ctx, id) ?: error("Sumber $id tidak didukung.")

    private fun toLocalItem(f: LocalItem): NxItem {
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.ext) ?: "application/octet-stream"
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(f.time))
        return NxItem("lokal:${f.path}", false, "lokal", f.name, "", f.size, mime, date, "", 0L, f.path)
    }

    // ------------------------------------------------------------------ flag, label, cache

    private fun loadLabels(): Map<String, List<String>> = runCatching {
        val obj = JSONObject(prefs.getString("labels", "{}") ?: "{}")
        obj.keys().asSequence().associateWith { key ->
            val arr = obj.getJSONArray(key)
            (0 until arr.length()).map { arr.getString(it) }
        }
    }.getOrDefault(emptyMap())

    private fun persistFlags() {
        val labels = JSONObject()
        labelMap.forEach { (k, v) -> if (v.isNotEmpty()) labels.put(k, JSONArray(v)) }
        prefs.edit().putStringSet("fav", HashSet(favTd)).putStringSet("archive", HashSet(archivedIds))
            .putString("labels", labels.toString()).apply()
    }

    fun isFav(item: NxItem): Boolean = if (item.source == "lokal") item.localPath in localFavs else item.id in favTd
    fun isArchived(item: NxItem): Boolean = item.id in archivedIds
    fun labelsOf(item: NxItem): List<String> = labelMap[item.id].orEmpty()

    fun cacheFile(item: NxItem): File? {
        if (item.isFolder || item.source == "lokal") return null
        // Satu folder per ID agar nama berkas tetap asli saat disalin ke folder unduhan.
        val dirName = if (item.source == "tdlib") item.cloudId.toString() else "r_" + Integer.toHexString(item.id.hashCode())
        return File(File(sessionCacheDir, dirName), item.name.replace(Regex("[^A-Za-z0-9._ -]"), "_").ifBlank { "berkas" })
    }

    /** Berkas lokal selalu tersedia offline; berkas Telegram tersedia bila sudah ada di cache sesi. */
    fun isCached(item: NxItem): Boolean {
        if (cacheTick < 0) return false
        if (item.isFolder) return false
        if (item.source == "lokal") return true
        return cacheFile(item)?.let { it.isFile && it.length() > 0 } == true
    }

    fun toggleFavorite(item: NxItem) {
        if (item.source == "lokal") {
            val path = item.localPath ?: return
            viewModelScope.launch {
                val now = withContext(Dispatchers.IO) { db.toggleFavorite(path, library); db.favoritePaths() }
                localFavs = now
                showToast(if (path in now) "Ditambahkan ke Favorit." else "Dihapus dari Favorit.")
            }
        } else {
            val on = item.id !in favTd
            favTd = if (on) favTd + item.id else favTd - item.id
            persistFlags()
            showToast(if (on) "Ditambahkan ke Favorit." else "Dihapus dari Favorit.")
        }
    }

    fun toggleArchive(item: NxItem) {
        val on = item.id !in archivedIds
        archivedIds = if (on) archivedIds + item.id else archivedIds - item.id
        persistFlags()
        showToast(if (on) "Dipindahkan ke Arsip Tersembunyi." else "Dikembalikan dari Arsip.")
    }

    fun setLabels(item: NxItem, labels: List<String>) {
        labelMap = labelMap + (item.id to labels.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(12))
        persistFlags()
    }

    fun sessionCacheFiles(): List<File> {
        if (cacheTick < 0) return emptyList()
        return sessionCacheDir.walkTopDown().filter { it.isFile && !it.name.endsWith(".part") }.sortedByDescending { it.lastModified() }.toList()
    }

    fun clearSessionCache() {
        viewModelScope.launch {
            val removed = withContext(Dispatchers.IO) {
                val count = sessionCacheFiles().size
                sessionCacheDir.listFiles()?.forEach { it.deleteRecursively() }
                count
            }
            cacheTick++
            showToast("$removed file cache sesi dibersihkan.")
        }
    }

    // ------------------------------------------------------------------ daftar terlihat

    fun allFiles(): List<NxItem> = cloudItems + localItems + remoteItems

    /** Pasangan (folder, berkas) yang tampil untuk tab Files ([gallery] = false) atau Gallery. */
    private val folderSources = listOf("tdlib", "github", "gdrive", "gphotos")

    private fun pathsOf(source: String): List<String> =
        if (source == "tdlib") cloudItems.map { it.folder } + emptyFolders
        else remoteItems.filter { it.source == source }.map { it.folder } + remoteDirs[source].orEmpty()

    fun visible(gallery: Boolean): Pair<List<NxItem>, List<NxItem>> {
        val folderPath = if (gallery) galleryFolder else filesFolder
        val q = query.trim().lowercase()
        val files = allFiles().filter { f ->
            val archived = isArchived(f)
            if (!gallery && filter == "all" && archived) return@filter false
            when (filter) {
                "fav" -> if (!isFav(f)) return@filter false
                "archive" -> if (!archived) return@filter false
                "offline" -> if (!isCached(f)) return@filter false
                "tdlib", "gdrive", "github", "gphotos", "lokal" -> if (f.source != filter) return@filter false
            }
            if (q.isEmpty()) {
                if ((gallery || filter == "all") && f.folder != folderPath) return@filter false
            } else if (!f.name.lowercase().contains(q)) {
                return@filter false
            }
            true
        }
        val showFolders = q.isEmpty() && (filter == "all" || filter in folderSources)
        val folders: List<NxItem> = if (!showFolders) emptyList() else {
            val prefix = if (folderPath.isEmpty()) "" else "$folderPath/"
            val bySource = LinkedHashMap<String, MutableSet<String>>()
            for (src in folderSources) {
                if (filter != "all" && filter != src) continue
                pathsOf(src).filter { it.startsWith(prefix) && it.length > prefix.length }
                    .map { it.removePrefix(prefix).substringBefore('/') }
                    .forEach { name -> bySource.getOrPut(name) { linkedSetOf() }.add(src) }
            }
            bySource.entries.sortedByDescending { it.key }.map { entry ->
                val path = prefix + entry.key
                val src = if (entry.value.size == 1) entry.value.first() else "mixed"
                NxItem(
                    "folder:$src|$path", true, src, entry.key, folderPath, 0L, "", "", "", 0L, null,
                    fileCount = allFiles().count { f -> f.source != "lokal" && (src == "mixed" || f.source == src) && (f.folder == path || f.folder.startsWith("$path/")) }
                )
            }
        }
        return folders to files
    }

    /** Seluruh jalur folder Telegram (beserta induknya) untuk pilihan tujuan. Root = "". */
    /** Seluruh jalur folder (beserta induknya) pada sumber [source] untuk pilihan tujuan. Root = "". */
    fun folderChoices(source: String = "tdlib"): List<String> {
        val all = HashSet<String>()
        pathsOf(source).forEach { path ->
            var current = ""
            path.split('/').filter { it.isNotEmpty() }.forEach { seg ->
                current = if (current.isEmpty()) seg else "$current/$seg"
                all.add(current)
            }
        }
        return listOf("") + all.sorted()
    }

    fun bytesBySource(): Map<String, Long> = mapOf(
        "tdlib" to cloudItems.sumOf { it.sizeBytes }, "lokal" to localItems.sumOf { it.sizeBytes },
        "github" to remoteItems.filter { it.source == "github" }.sumOf { it.sizeBytes },
        "gdrive" to remoteItems.filter { it.source == "gdrive" }.sumOf { it.sizeBytes }
    )

    // ------------------------------------------------------------------ pilih / batch

    fun setBatch(on: Boolean) { batchMode = on; if (!on) selected = emptySet() }
    fun toggleSelect(id: String) { selected = if (id in selected) selected - id else selected + id }

    fun selectAll(gallery: Boolean) {
        val (folders, files) = visible(gallery)
        val ids = (folders + files).map { it.id }.toSet()
        selected = if (ids.isNotEmpty() && selected.containsAll(ids)) emptySet() else ids
    }

    fun selectedItems(): List<NxItem> = filesOf(selected)

    /** Target operasi dari seleksi batch: folder tetap sebagai folder (untuk pindah/ganti nama), berkas sebagai berkas. */
    private fun parseFolderId(id: String): Pair<String, String> {
        val body = id.removePrefix("folder:")
        val bar = body.indexOf('|')
        return if (bar < 0) "tdlib" to body else body.substring(0, bar) to body.substring(bar + 1)
    }

    fun selectedTargets(): List<NxItem> = selected.mapNotNull { id ->
        if (id.startsWith("folder:")) {
            val (src, path) = parseFolderId(id)
            NxItem(id, true, src, path.substringAfterLast('/'), path.substringBeforeLast('/', ""), 0L, "", "", "", 0L, null)
        } else {
            allFiles().firstOrNull { it.id == id }
        }
    }

    private fun filesOf(ids: Set<String>): List<NxItem> {
        val out = LinkedHashMap<String, NxItem>()
        for (id in ids) {
            if (id.startsWith("folder:")) {
                val (src, path) = parseFolderId(id)
                (cloudItems + remoteItems).filter { (src == "mixed" || it.source == src) && (it.folder == path || it.folder.startsWith("$path/")) }.forEach { out[it.id] = it }
            } else {
                allFiles().firstOrNull { it.id == id }?.let { out[it.id] = it }
            }
        }
        return out.values.toList()
    }

    private fun filesOf(items: List<NxItem>): List<NxItem> = filesOf(items.map { it.id }.toSet())

    // ------------------------------------------------------------------ operasi

    private fun operate(block: () -> String) {
        viewModelScope.launch {
            try {
                val message = withContext(Dispatchers.IO) { block() }
                showToast(message)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showToast("Gagal: ${e.message ?: e.javaClass.simpleName}")
                AppLog.err("Operasi gagal: ${e.message}")
            }
            setBatch(false)
            refresh()
        }
    }

    fun rename(item: NxItem, newName: String) = operate {
        val clean = newName.trim()
        require(clean.isNotEmpty()) { "Nama tidak boleh kosong." }
        when {
            item.isFolder -> renameFolderAny(item, clean)
            item.source == "tdlib" -> gateway.renameCloudFile(item.cloudId, clean)
            item.source == "lokal" -> {
                check(db.renameLocalFile(item.localPath.orEmpty(), clean, library)) { "Nama sudah dipakai atau tidak valid." }
                "Nama berkas diubah menjadi $clean."
            }
            else -> {
                val store = remoteStore(item)
                store.rename(toRemoteEntry(item), clean)
                "Nama diubah menjadi $clean di ${store.label}."
            }
        }
    }

    /** Ganti nama folder: Telegram memakai ganti-nama-rekursif; sumber lain memindahkan setiap berkas ke jalur baru. */
    private fun renameFolderAny(item: NxItem, clean: String): String {
        val newPath = joinPath(item.folder, clean)
        if (item.source == "tdlib") return gateway.renameFolder(item.fullPath, newPath)
        val files = filesOf(listOf(item))
        require(files.isNotEmpty()) { "Folder kosong tidak dapat diganti namanya di sumber ini." }
        for (f in files) {
            val target = newPath + f.folder.removePrefix(item.fullPath)
            if (f.source == "tdlib") gateway.moveCloudFile(f.cloudId, target) else remoteStore(f).move(toRemoteEntry(f), target)
        }
        return "Folder diganti nama menjadi $newPath (${files.size} berkas diperbarui)."
    }

    fun trash(targets: List<NxItem>) = operate {
        var ok = 0
        var failed = 0
        for (t in filesOf(targets)) {
            try {
                when (t.source) {
                    "tdlib" -> gateway.softDeleteCloudFile(t.cloudId)
                    "lokal" -> check(db.moveToTrash(t.localPath.orEmpty(), library, trashDir)) { "Pemindahan gagal." }
                    else -> remoteStore(t).delete(toRemoteEntry(t))
                }
                ok++
            } catch (e: Exception) {
                failed++
                AppLog.err("Hapus ${t.name} gagal: ${e.message}")
            }
        }
        val remoteNote = if (targets.any { it.source == "github" }) " (GitHub: berkas dihapus dari repo, riwayat Git tetap ada)" else ""
        (if (failed == 0) "$ok item dipindahkan ke Sampah." else "$ok item ke Sampah, $failed gagal.") + remoteNote
    }

    fun purge(targets: List<NxItem>) = operate {
        var ok = 0
        var failed = 0
        for (t in filesOf(targets)) {
            try {
                when (t.source) {
                    "tdlib" -> gateway.purgeCloudFile(t.cloudId)
                    "lokal" -> {
                        check(File(t.localPath.orEmpty()).delete()) { "Berkas lokal tidak dapat dihapus." }
                        db.reconcile(library)
                    }
                    else -> remoteStore(t).delete(toRemoteEntry(t))
                }
                cacheFile(t)?.delete()
                ok++
            } catch (e: Exception) {
                failed++
                AppLog.err("Hapus permanen ${t.name} gagal: ${e.message}")
            }
        }
        cacheTick++
        if (failed == 0) "$ok item dihapus permanen." else "$ok item dihapus, $failed gagal."
    }

    /** Kompatibilitas: pindah ke folder pada sumber yang sama (Telegram, chat aktif). */
    fun moveToFolder(targets: List<NxItem>, folder: String) = transferItems(targets, NxTarget("tdlib", folder), true)

    /**
     * Salin/pindah file atau folder ke [dest]. Telegram→Telegram (termasuk antar channel) diproses server-side tanpa unduh/unggah ulang;
     * sumber lain memakai staging. Memindahkan ke Telegram dari sumber lain hanya menyalin (upload berjalan di antrean) agar tidak
     * menghapus sumber sebelum unggahan terbukti berhasil.
     */
    fun transferItems(targets: List<NxItem>, dest: NxTarget, move: Boolean) = operate {
        var ok = 0
        var failed = 0
        var deferred = 0
        val firstError = ArrayList<String>()
        val base = joinPath(dest.folder)
        for (t in targets) {
            val files = if (t.isFolder) filesOf(listOf(t)) else listOf(t)
            for (f in files) {
                try {
                    val folder = if (t.isFolder) joinPath(base, t.name, f.folder.removePrefix(t.fullPath)) else base
                    if (transferOne(f, NxTarget(dest.source, folder, dest.chatId), move)) deferred++
                    ok++
                } catch (e: Exception) {
                    failed++
                    if (firstError.isEmpty()) firstError.add(e.message ?: e.javaClass.simpleName)
                    AppLog.err("${if (move) "Pindah" else "Salin"} ${f.name} gagal: ${e.message}")
                }
            }
        }
        val verb = if (move) "dipindahkan" else "disalin"
        var message = "$ok item $verb" + if (failed > 0) ", $failed gagal (${firstError.firstOrNull().orEmpty()})" else "."
        if (deferred > 0) message += " $deferred item diantre ke Telegram; sumbernya TIDAK dihapus—hapus manual setelah unggahan selesai."
        message
    }

    /** Mengembalikan true bila penghapusan sumber ditunda (upload Telegram berjalan di latar belakang). */
    private fun transferOne(f: NxItem, dest: NxTarget, move: Boolean): Boolean {
        val remoteSource = f.source != "tdlib" && f.source != "lokal"
        when {
            f.source == "tdlib" && dest.source == "tdlib" -> { gateway.transferCloudFile(f.cloudId, dest.folder, dest.chatId, move); return false }
            remoteSource && f.source == dest.source -> {
                val store = remoteStore(f)
                if (move) store.move(toRemoteEntry(f), dest.folder) else store.copy(toRemoteEntry(f), dest.folder)
                return false
            }
            f.source == "lokal" && dest.source == "tdlib" -> {
                check(TdClient.isReady) { "Login akun Telegram diperlukan." }
                NxTransferEngine.enqueueUpload(ctx, db, f.localPath.orEmpty(), f.name, dest.folder, "Salin ke Telegram")
                return move
            }
            f.source == "lokal" && dest.source == "lokal" -> error("Berkas lokal sudah berada di pustaka lokal.")
            f.source == "lokal" -> {
                remoteStoreById(dest.source).upload(dest.folder, File(f.localPath.orEmpty()), f.name) { _, _ -> }
                if (move) removeSource(f)
                return false
            }
        }
        // Sumber Telegram/jarak jauh -> tujuan lain: unduh ke staging lalu unggah.
        val dir = File(File(ctx.cacheDir, "upload_stage"), System.nanoTime().toString()).apply { mkdirs() }
        val staged = File(dir, f.name.replace('/', '_'))
        try {
            staged.outputStream().use { out ->
                if (f.source == "tdlib") gateway.downloadCloudFile(f.cloudId, out) { _, _ -> } else remoteStore(f).download(toRemoteEntry(f), out) { _, _ -> }
            }
            when (dest.source) {
                "tdlib" -> {
                    check(TdClient.isReady) { "Login akun Telegram diperlukan." }
                    NxTransferEngine.enqueueUpload(ctx, db, staged.absolutePath, f.name, dest.folder, "Salin lintas cloud")
                    return move // berkas staging dipertahankan untuk worker
                }
                "lokal" -> {
                    var out = File(library, staged.name)
                    if (out.exists()) out = File(library, "${System.currentTimeMillis()}_${staged.name}")
                    staged.copyTo(out)
                    db.index(out)
                }
                else -> remoteStoreById(dest.source).upload(dest.folder, staged, f.name) { _, _ -> }
            }
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw e
        }
        dir.deleteRecursively()
        if (move) removeSource(f)
        return false
    }

    private fun removeSource(f: NxItem) {
        when (f.source) {
            "tdlib" -> gateway.softDeleteCloudFile(f.cloudId)
            "lokal" -> check(db.moveToTrash(f.localPath.orEmpty(), library, trashDir)) { "Sumber lokal tidak dapat dipindahkan ke sampah." }
            else -> remoteStore(f).delete(toRemoteEntry(f))
        }
    }

    /** Folder baru dibuat di sumber yang sedang difilter (GitHub/Drive); selain itu di Telegram. */
    private fun createSource(): String = if (filter == "github" || filter == "gdrive") filter else "tdlib"

    fun createFolder(parent: String, name: String) = operate {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "Nama folder tidak boleh kosong." }
        val path = if (parent.isEmpty()) clean else "$parent/$clean"
        val source = createSource()
        if (source == "tdlib") {
            gateway.createFolder(path)
        } else {
            val store = remoteStoreById(source)
            store.mkdir(path)
            "Folder $path dibuat di ${store.label}."
        }
    }

    /** Membuat berkas teks baru lalu mengunggahnya ke folder Telegram. */
    fun createTextFile(parent: String, name: String, body: String) = operate {
        val safe = name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
        require(safe.isNotEmpty()) { "Nama berkas tidak boleh kosong." }
        val dir = File(File(ctx.cacheDir, "new_files"), System.currentTimeMillis().toString()).apply { mkdirs() }
        val file = File(dir, safe)
        file.writeText(body)
        try {
            val source = createSource()
            if (source == "tdlib") gateway.uploadFileToFolder(file, parent) else remoteStoreById(source).upload(parent, file, safe) { _, _ -> }
        } finally {
            dir.deleteRecursively()
        }
        "Berkas $safe dibuat dan diunggah."
    }

    fun saveInlineText(item: NxItem, text: String) = operate {
        val path = item.localPath ?: error("Hanya berkas lokal yang dapat disimpan langsung.")
        val file = File(path)
        file.writeText(text)
        db.index(file)
        "Perubahan teks disimpan."
    }

    /**
     * Mengekstrak ZIP: berkas Telegram diunduh ke cache lalu hasilnya diunggah ke subfolder bernama ZIP di folder yang sama;
     * ZIP lokal diekstrak ke pustaka lokal. Entri berbahaya (zip-slip) diabaikan dan ukuran total dibatasi.
     */
    fun extractZipHere(item: NxItem) = operate {
        val zipFile: File = if (item.source == "lokal") {
            File(item.localPath.orEmpty())
        } else {
            val cache = cacheFile(item) ?: error("Sumber berkas tidak didukung.")
            if (!(cache.isFile && cache.length() > 0)) downloadToCache(item, cache)
            cache
        }
        val stage = File(File(ctx.cacheDir, "upload_stage"), System.currentTimeMillis().toString())
        val entries = NxTransferEngine.extractZip(zipFile, stage)
        require(entries.isNotEmpty()) { "ZIP kosong atau tidak berisi berkas yang aman diekstrak." }
        val base = item.name.substringBeforeLast('.')
        if (item.source == "tdlib") {
            check(TdClient.isReady) { "Login akun Telegram diperlukan untuk mengunggah hasil ekstrak." }
            for ((file, relative) in entries) {
                val target = listOf(item.folder, base, relative).flatMap { it.split('/') }.filter { it.isNotBlank() }.joinToString("/")
                NxTransferEngine.enqueueUpload(ctx, db, file.absolutePath, file.name, target)
            }
            "${entries.size} berkas hasil ekstrak masuk antrean upload ke folder $base."
        } else {
            for ((file, _) in entries) {
                var out = File(library, file.name)
                if (out.exists()) out = File(library, "${System.currentTimeMillis()}_${file.name}")
                file.copyTo(out, overwrite = false)
                db.index(out)
            }
            stage.deleteRecursively()
            "${entries.size} berkas diekstrak ke pustaka lokal."
        }
    }

    /** Mengantre unduhan ke folder SAF pilihan. [useCache] = salin dari cache sesi bila sudah ada (tanpa unduh ulang). */
    fun enqueueDownload(item: NxItem, useCache: Boolean = false) {
        viewModelScope.launch {
            val message = withContext(Dispatchers.IO) {
                val tree = NxTransferEngine.downloadTreeUri(ctx)
                val cached = if (useCache) cacheFile(item)?.takeIf { it.isFile && it.length() > 0 } else null
                when {
                    tree == null -> "Pilih folder unduhan terlebih dahulu."
                    cached != null -> {
                        NxTransferEngine.enqueueLocalSave(ctx, db, cached.absolutePath, item.name, tree)
                        "${item.name} disalin dari cache sesi ke folder unduhan."
                    }
                    item.source == "tdlib" -> {
                        NxTransferEngine.enqueueCloudDownload(ctx, db, item.cloudId, item.name, item.mime, tree)
                        "Unduhan ${item.name} masuk antrean. Pantau di halaman Transfer."
                    }
                    item.source != "lokal" -> {
                        val cache = cacheFile(item) ?: error("Sumber berkas tidak didukung.")
                        if (!(cache.isFile && cache.length() > 0)) downloadToCache(item, cache)
                        NxTransferEngine.enqueueLocalSave(ctx, db, cache.absolutePath, item.name, tree)
                        "${item.name} diunduh dan masuk antrean penyalinan ke folder unduhan."
                    }
                    else -> {
                        NxTransferEngine.enqueueLocalSave(ctx, db, item.localPath.orEmpty(), item.name, tree)
                        "Penyalinan ${item.name} masuk antrean."
                    }
                }
            }
            showToast(message)
        }
    }

    /** Menyalin berkas lokal ke Telegram ([folder] tujuan). */
    fun uploadLocalToCloud(item: NxItem, folder: String) {
        viewModelScope.launch {
            val message = withContext(Dispatchers.IO) {
                if (!TdClient.isReady) "Login akun Telegram diperlukan."
                else {
                    NxTransferEngine.enqueueUpload(ctx, db, item.localPath.orEmpty(), item.name, folder)
                    "Upload ${item.name} masuk antrean."
                }
            }
            showToast(message)
        }
    }

    // ------------------------------------------------------------------ viewer

    fun openViewer(item: NxItem) {
        viewerItem = item
        loadViewer(item)
    }

    fun switchViewer(item: NxItem) = openViewer(item)

    fun closeViewer() {
        viewerJob?.cancel()
        viewerItem = null
        viewerState = NxViewerState.Idle
    }

    fun cancelViewerLoad() { viewerJob?.cancel() }

    fun retryViewer() { viewerItem?.let { loadViewer(it) } }

    private fun loadViewer(item: NxItem) {
        viewerJob?.cancel()
        if (item.source == "lokal") {
            val file = File(item.localPath.orEmpty())
            viewerState = if (file.isFile) NxViewerState.Ready(file) else NxViewerState.Failed("Berkas lokal tidak ditemukan.")
            return
        }
        val cache = cacheFile(item) ?: run { viewerState = NxViewerState.Failed("Sumber berkas tidak didukung."); return }
        if (cache.isFile && cache.length() > 0) { viewerState = NxViewerState.Ready(cache); return }
        viewerState = NxViewerState.Loading
        viewerJob = viewModelScope.launch {
            try {
                runInterruptible(Dispatchers.IO) { downloadToCache(item, cache) }
                cacheTick++
                if (viewerItem?.id == item.id) viewerState = NxViewerState.Ready(cache)
            } catch (e: CancellationException) {
                if (viewerItem?.id == item.id) viewerState = NxViewerState.Cancelled
                throw e
            } catch (e: Exception) {
                if (viewerItem?.id == item.id) viewerState = NxViewerState.Failed(e.message ?: "Pratinjau gagal dimuat.")
            }
        }
    }

    private fun downloadToCache(item: NxItem, cache: File) {
        cache.parentFile?.mkdirs()
        val part = File(cache.parentFile, cache.name + ".part")
        try {
            part.outputStream().use { out ->
                if (item.source == "tdlib") gateway.downloadCloudFile(item.cloudId, out) { _, _ -> }
                else remoteStore(item).download(toRemoteEntry(item), out) { _, _ -> }
            }
            if (!part.renameTo(cache)) throw IOException("Cache sesi tidak dapat disimpan.")
        } finally {
            if (part.exists()) part.delete()
        }
    }

    /** Menyimpan berkas ke cache sesi tanpa membuka viewer (untuk akses offline). */
    fun pinOffline(item: NxItem) {
        if (item.source == "lokal") { showToast("Berkas lokal sudah tersedia offline."); return }
        val cache = cacheFile(item) ?: return
        if (cache.isFile && cache.length() > 0) { showToast("Sudah ada di cache sesi."); return }
        viewModelScope.launch {
            showToast("Menyimpan ke cache sesi…")
            try {
                runInterruptible(Dispatchers.IO) { downloadToCache(item, cache) }
                cacheTick++
                showToast("${item.name} tersimpan di cache sesi (offline).")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showToast("Gagal menyimpan cache: ${e.message}")
            }
        }
    }

    // ------------------------------------------------------------------ duplikat

    /** Kelompok duplikat: "sha256", "name", "cross". Jenis "media" membutuhkan analisis visual dan belum tersedia. */
    fun duplicateGroups(type: String): List<List<NxItem>> {
        val files = allFiles()
        fun shaOf(f: NxItem): String = if (f.source == "lokal") localHashes[f.localPath].orEmpty() else f.sha256
        return when (type) {
            "sha256" -> files.filter { shaOf(it).isNotEmpty() }.groupBy { shaOf(it) }.values.filter { it.size > 1 }
            "name" -> files.groupBy { it.name.lowercase(Locale.ROOT) + "|" + it.sizeBytes }.values.filter { it.size > 1 }
            "cross" -> files.groupBy { if (shaOf(it).isNotEmpty()) shaOf(it) else it.name.lowercase(Locale.ROOT) + "|" + it.sizeBytes }
                .values.filter { g -> g.map { it.source }.distinct().size > 1 }
            else -> emptyList()
        }
    }

    // ------------------------------------------------------------------ channel penyimpanan

    fun loadChannels() {
        viewModelScope.launch {
            channelBusy = true
            try {
                val (list, target) = withContext(Dispatchers.IO) { gateway.discoverChannels() to gateway.currentTarget() }
                channels = list
                activeTarget = target
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showToast("Gagal mendeteksi channel: ${e.message ?: "login Telegram diperlukan"}")
            }
            channelBusy = false
        }
    }

    private fun changeStorage(block: () -> TdStorageTarget) {
        viewModelScope.launch {
            channelBusy = true
            try {
                val target = withContext(Dispatchers.IO) { block() }
                activeTarget = target
                showToast("Chat penyimpanan: ${target.title}")
                filesFolder = ""; galleryFolder = ""
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showToast("Gagal: ${e.message ?: e.javaClass.simpleName}")
            }
            channelBusy = false
        }
    }

    fun useSavedMessages() = changeStorage { gateway.useSavedMessages() }
    /** Menghubungkan chat berdasarkan ID numerik atau @username. */
    fun useChatReference(reference: String) = changeStorage { TdCloud.get(ctx).useChat(reference) }
    fun useChannel(info: TdChannelInfo) = changeStorage { gateway.useChannel(info.chatId) }
    fun createChannel(reuseExisting: Boolean) = changeStorage { gateway.createChannel(reuseExisting) }

    /** Menyalin nama topik forum menjadi folder virtual (struktur lama langsung dapat dipilih). */
    fun importTopics(info: TdChannelInfo) = operate {
        val count = gateway.importForumTopics(info.chatId)
        if (count == 0) "Channel ini tidak memiliki topik forum." else "$count topik forum diimpor sebagai folder."
    }

    /** Untuk dialog tujuan salin/pindah: Saved Messages + channel yang dapat diposting. */
    fun storageChoices(): List<Pair<Long, String>> =
        listOf(0L to "Chat penyimpanan aktif") + channels.filter { it.canPost && it.chatId != activeTarget?.chatId }.map { it.chatId to it.title }

    // ------------------------------------------------------------------ backup

    /** Menyusun backup di thread latar; [onReady] dipanggil dengan byte siap-tulis (atau pesan galat lewat toast). */
    fun prepareBackup(passphrase: String, includeSecrets: Boolean, onReady: (ByteArray) -> Unit) {
        viewModelScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    NexoraBackup.encode(NexoraBackup.build(ctx, cloudItems, includeSecrets && passphrase.isNotEmpty()), passphrase.ifEmpty { null })
                }
                onReady(bytes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showToast("Backup gagal: ${e.message}")
            }
        }
    }

    /** Memulihkan backup lalu memuat ulang flag dan katalog. [onDone] menerima ringkasan baris. */
    fun restoreBackup(json: org.json.JSONObject, onDone: (List<String>) -> Unit) {
        viewModelScope.launch {
            try {
                val lines = withContext(Dispatchers.IO) { NexoraBackup.restore(ctx, json) }
                favTd = prefs.getStringSet("fav", emptySet())?.toSet() ?: emptySet()
                archivedIds = prefs.getStringSet("archive", emptySet())?.toSet() ?: emptySet()
                labelMap = loadLabels()
                val snapshot = NxSnapshot.read(ctx)
                if (snapshot.isNotEmpty()) cloudItems = snapshot
                NxBackupBridge.restoreTick.value = NxBackupBridge.restoreTick.value + 1
                refresh()
                onDone(lines)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showToast("Pemulihan gagal: ${e.message}")
            }
        }
    }
}
