package dev.gsprasada.nexoracloud

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
private fun H(text: String) {
    val c = LocalNexora.current
    Text(text, color = c.text, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 4.dp))
}

@Composable
private fun N(text: String, color: Color? = null) {
    val c = LocalNexora.current
    Text(text, color = color ?: c.muted, fontSize = 11.5.sp, modifier = Modifier.padding(vertical = 2.dp))
}

@Composable
private fun L(text: String) {
    val c = LocalNexora.current
    Text(text, color = c.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
}

// ====================================================================== Channel & folder penyimpanan

@Composable
fun NxChannelsPage(vm: ExplorerViewModel, onBack: () -> Unit, onOpenLogin: () -> Unit) {
    val c = LocalNexora.current
    val ready = TdClient.state.collectAsState().value is TdAuthState.Ready
    var reference by remember { mutableStateOf("") }
    LaunchedEffect(ready) { if (ready) vm.loadChannels() }
    val folderCounts = remember(vm.cloudItems, vm.emptyFolders) {
        val counts = HashMap<String, Int>()
        vm.cloudItems.forEach { item ->
            var current = ""
            item.folder.split('/').filter { it.isNotEmpty() }.forEach { seg ->
                current = if (current.isEmpty()) seg else "$current/$seg"
                counts[current] = (counts[current] ?: 0) + 1
            }
        }
        vm.emptyFolders.forEach { counts.putIfAbsent(it, 0) }
        counts.entries.sortedBy { it.key }
    }
    NxSubpage("Channel & Folder Penyimpanan", onBack, actions = { NxMiniButton("↻ Deteksi") { vm.loadChannels() } }) {
        if (!ready) {
            NxCard {
                H("Login Telegram diperlukan")
                N("Untuk mendeteksi channel yang sudah ada, masuk ke akun Telegram Anda terlebih dahulu.")
                NxButton("✈️ Login Telegram") { onOpenLogin() }
            }
            return@NxSubpage
        }
        NxCard {
            H("Penyimpanan aktif")
            val target = vm.activeTarget
            Text(target?.title ?: "Memuat…", color = c.primary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            N(if (target?.isSavedMessages == false) "File disimpan sebagai dokumen di channel/grup ini." else "File disimpan di Saved Messages akun Anda.")
            NxButton("Pakai Saved Messages", kind = NxKind.Outline, enabled = !vm.channelBusy) { vm.useSavedMessages() }
            NxButton("Hubungkan / buat channel Nexora", enabled = !vm.channelBusy) { vm.createChannel(reuseExisting = true) }
            N("Bila sudah ada channel Nexora dari instalasi sebelumnya, channel itu dipakai ulang (bukan membuat baru).")
        }
        NxCard {
            H("Channel & grup yang terdeteksi")
            N("Dibaca dari akun Telegram Anda. Channel berlabel Nexora diurutkan paling atas.")
            if (vm.channelBusy && vm.channels.isEmpty()) N("Mendeteksi…")
            if (!vm.channelBusy && vm.channels.isEmpty()) N("Belum ada channel/grup yang dapat dipakai. Buat satu lewat tombol di atas.")
            vm.channels.forEach { info ->
                val active = vm.activeTarget?.chatId == info.chatId
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(info.title, color = c.text, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 2.dp)) {
                                NxPill(if (info.kind == "channel") "Channel" else "Grup")
                                if (info.isNexora) NxPill("Nexora", bg = c.successBg, fg = c.success)
                                if (info.isForum) NxPill("Forum")
                                if (!info.canPost) NxPill("Hanya baca", bg = c.warningBg, fg = c.warning)
                            }
                        }
                        if (active) NxPill("Aktif", bg = c.successBg, fg = c.success)
                        else NxMiniButton("Pakai") { if (info.canPost) vm.useChannel(info) else vm.showToast("Anda tidak dapat memposting di chat ini.") }
                    }
                    if (info.isForum) NxMiniButton("Impor topik forum sebagai folder", Modifier.padding(top = 4.dp)) { vm.importTopics(info) }
                }
            }
            L("Hubungkan manual (ID atau @username)")
            NxField(reference, { reference = it.trim() }, "-1001234567890 atau @namachannel")
            NxButton("Hubungkan Chat Ini", kind = NxKind.Outline, enabled = reference.isNotBlank() && !vm.channelBusy) { vm.useChatReference(reference) }
        }
        NxCard {
            H("Folder yang sudah ada di penyimpanan")
            N("Struktur folder dibaca dari caption file di chat aktif, termasuk file yang dibuat di luar aplikasi (tanpa caption dikelompokkan per tanggal).")
            if (folderCounts.isEmpty()) N("Belum ada folder.")
            folderCounts.take(80).forEach { (path, count) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text("📁 $path", Modifier.weight(1f), color = c.text, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    NxPill("$count file")
                }
            }
            if (folderCounts.size > 80) N("… dan ${folderCounts.size - 80} folder lainnya")
        }
    }
}

// ====================================================================== Backup & restore

@Composable
fun NxBackupPage(vm: ExplorerViewModel, actions: NexoraActions, onBack: () -> Unit, onConfirm: NxConfirm) {
    val c = LocalNexora.current
    val scope = rememberCoroutineScope()
    val pending by NxBackupBridge.pendingImport.collectAsState()
    var passphrase by remember { mutableStateOf("") }
    var includeSecrets by remember { mutableStateOf(false) }
    var importPass by remember { mutableStateOf("") }
    var parsed by remember { mutableStateOf<JSONObject?>(null) }
    var summary by remember { mutableStateOf<NxBackupSummary?>(null) }
    var error by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(pending) { parsed = null; summary = null; error = ""; importPass = "" }

    NxSubpage("Backup, Ekspor & Impor Data", onBack) {
        NxCard {
            H("Ekspor ke satu berkas (.nexora)")
            N("Memuat: chat/channel penyimpanan, struktur folder, indeks file, favorit/arsip/label, dan seluruh pengaturan. Gunakan sebelum uninstall agar build berbeda tanda tangan dapat memulihkan semuanya.")
            L("Kata sandi enkripsi (opsional)")
            NxField(passphrase, { passphrase = it }, "Kosongkan = JSON biasa", secret = true)
            NxSwitchRow("Sertakan kredensial", "API hash, token GitHub, kunci Nexora Link & tiket login (wajib kata sandi)", includeSecrets && passphrase.isNotEmpty(), enabled = passphrase.isNotEmpty()) { includeSecrets = it }
            NxButton("💾 Ekspor Backup", enabled = !busy) {
                busy = true
                vm.prepareBackup(passphrase, includeSecrets) { bytes ->
                    NxBackupBridge.pendingExport = bytes
                    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                    actions.createBackupFile("nexora-backup-$stamp.nexora")
                    busy = false
                }
            }
            N("File backup dengan kata sandi dienkripsi AES‑256‑GCM. Simpan kata sandi: tanpa itu backup tidak dapat dibuka.", c.warning)
        }
        NxCard {
            H("Impor / pulihkan dari backup")
            NxButton("📂 Pilih Berkas Backup", kind = NxKind.Outline) { actions.openBackupFile() }
            val file = pending
            if (file != null && summary == null) {
                if (file.encrypted) {
                    L("Backup terenkripsi — masukkan kata sandi")
                    NxField(importPass, { importPass = it }, "Kata sandi backup", secret = true)
                } else N("Berkas dipilih (${nxSize(file.bytes.size.toLong())}).")
                NxButton(if (busy) "Membaca…" else "🔍 Baca & Periksa Backup", enabled = !busy && (!file.encrypted || importPass.isNotEmpty())) {
                    scope.launch {
                        busy = true; error = ""
                        try {
                            val json = withContext(Dispatchers.IO) { NexoraBackup.decode(file.bytes, importPass.ifEmpty { null }) }
                            summary = NexoraBackup.summarize(json)
                            parsed = json
                        } catch (e: BackupException) { error = e.message.orEmpty() } catch (e: Exception) { error = "Gagal membaca backup: ${e.message}" }
                        busy = false
                    }
                }
            }
            val info = summary
            if (info != null) {
                L("Isi backup")
                N("Dibuat: ${info.createdAt.ifBlank { "—" }} • versi aplikasi ${info.appVersion.ifBlank { "—" }}")
                N("Penyimpanan: ${info.storageTitle} • ${info.indexCount} entri indeks • ${info.folderCount} folder • ${info.settingsCount} pengaturan")
                N(if (info.hasSecrets) "Memuat kredensial (akan ikut dipulihkan)." else "Tidak memuat kredensial; isi ulang API hash/token bila perlu.")
                N("Akun Telegram harus sama dengan yang membuat backup agar chat penyimpanan dan ID pesan cocok.", c.warning)
                NxButton("♻️ Pulihkan Sekarang", enabled = !busy) {
                    val json = parsed
                    if (json != null) onConfirm("Pulihkan data dari backup?", "Pengaturan, favorit, folder, dan pilihan chat penyimpanan saat ini akan ditimpa oleh isi backup.", true) {
                        vm.restoreBackup(json) { lines -> result = lines; NxBackupBridge.pendingImport.value = null }
                    }
                }
            }
            if (error.isNotEmpty()) N(error, c.danger)
            if (result.isNotEmpty()) {
                L("Hasil pemulihan")
                result.forEach { N("• $it", c.success) }
                N("Sebagian pengaturan tampilan diterapkan segera; bila ada yang belum berubah, tutup dan buka kembali aplikasi.")
            }
        }
        NxCard {
            H("Cara migrasi ke APK baru (tanda tangan berbeda)")
            N("1. Ekspor backup di aplikasi lama.\n2. Uninstall aplikasi lama, pasang build baru.\n3. Login Telegram dengan akun yang sama.\n4. Impor backup. Channel dan folder lama langsung tersambung; file tidak perlu diunggah ulang karena tetap ada di Telegram.")
        }
    }
}

// ====================================================================== Kustomisasi

@Composable
fun NxCustomizePage(settings: NxSettings, onBack: () -> Unit, onOpenPage: (String) -> Unit, onConfirm: NxConfirm) {
    val c = LocalNexora.current
    var bridge by remember { mutableStateOf(settings.bridgeUrl) }
    var apiBase by remember { mutableStateOf(settings.githubApiBase) }
    var maxMb by remember { mutableStateOf(settings.maxUploadMb.toString()) }
    NxSubpage("Kustomisasi Penuh", onBack) {
        NxCard {
            H("Tema & warna")
            N("Mode terang/gelap dan warna aksen diatur di Personalisasi; tombol 🌓 di bilah atas juga mengganti mode.")
            NxButton("🎨 Buka Tema & Aksen", kind = NxKind.Outline) { onOpenPage("personalization") }
        }
        NxCard {
            H("Tata letak")
            L("Kepadatan daftar")
            NxDropdown(listOf("compact" to "Ringkas", "normal" to "Normal", "comfortable" to "Lega"), settings.density, { settings.updateDensity(it) })
            L("Tampilan tab Files")
            NxDropdown(listOf("list" to "Daftar", "grid" to "Grid kartu"), settings.filesView, { settings.updateFilesView(it) })
            L("Kolom grid: ${settings.galleryColumns}")
            Slider(
                value = settings.galleryColumns.toFloat(), onValueChange = { settings.updateGalleryColumns(it.toInt()) }, valueRange = 2f..5f, steps = 2,
                colors = SliderDefaults.colors(thumbColor = c.primary, activeTrackColor = c.primary)
            )
            L("Tab saat aplikasi dibuka")
            NxDropdown(listOf("gallery" to "Gallery", "files" to "Files", "settings" to "Setting"), settings.startTab, { settings.updateStartTab(it) })
            NxSwitchRow("Thumbnail gambar", "Tampilkan pratinjau gambar di daftar dan grid", settings.showThumbs) { settings.updateShowThumbs(it) }
            NxSwitchRow("Label di item", "Favorit, Arsip, dan Offline Cache", settings.showTags) { settings.updateShowTags(it) }
            NxSwitchRow("Teks menu bawah", "Tampilkan nama di bawah ikon navigasi", settings.navLabels) { settings.updateNavLabels(it) }
            NxSwitchRow("Pil Google di bilah atas", "Pintasan ke halaman Google", settings.showGooglePill) { settings.updateShowGooglePill(it) }
            NxSwitchRow("Pil TDLib di bilah atas", "Pintasan login Telegram", settings.showTdPill) { settings.updateShowTdPill(it) }
        }
        NxCard {
            H("Sinkronisasi")
            NxSwitchRow("Muat katalog saat aplikasi dibuka", "Matikan untuk memakai indeks tersimpan lebih dulu", settings.autoRefreshStart) { settings.updateAutoRefreshStart(it) }
            L("Segarkan otomatis saat aplikasi terbuka")
            NxDropdown(
                listOf("0" to "Mati", "5" to "Tiap 5 menit", "15" to "Tiap 15 menit", "30" to "Tiap 30 menit", "60" to "Tiap 60 menit"),
                settings.autoRefreshMinutes.toString(), { settings.updateAutoRefreshMinutes(it.toInt()) }
            )
            NxSwitchRow("Transfer hanya lewat Wi‑Fi", "Unggah/unduh baru menunggu jaringan tak berbayar", settings.wifiOnly) { settings.updateWifiOnly(it) }
        }
        NxCard {
            H("Batas unggahan")
            L("Ukuran maksimal per file (MB, 0 = tanpa batas)")
            NxField(maxMb, { maxMb = it.filter(Char::isDigit).take(6) }, "0", keyboard = KeyboardType.Number)
            NxButton("Simpan Batas", kind = NxKind.Outline) { settings.updateMaxUploadMb(maxMb.toIntOrNull() ?: 0) }
            L("Ukuran satu bagian chunk Telegram: ${settings.chunkMb} MB")
            Slider(
                value = settings.chunkMb.toFloat(), onValueChange = { settings.updateChunkMb(((it / 50f).toInt() * 50).coerceIn(50, 1900)) },
                valueRange = 50f..1900f, colors = SliderDefaults.colors(thumbColor = c.primary, activeTrackColor = c.primary)
            )
            N("File lebih besar dari ukuran ini dipecah otomatis (maks. 1900 MB per bagian, batas akun non‑Premium).")
        }
        NxCard {
            H("Tombol Upload mengambang")
            NxSwitchRow("Tampilkan tombol", "Bila disembunyikan, Upload tersedia di menu samping dan tab Files", settings.fabVisible) { settings.updateFabVisible(it) }
            NxSwitchRow("Kunci posisi", "Cegah tombol bergeser tak sengaja", settings.fabLocked) { settings.updateFabLocked(it) }
            N("Geser tombol dengan jari untuk memindahkannya ke posisi mana pun (saat tidak dikunci).")
            NxButton("Reset Posisi & Tampilkan", kind = NxKind.Outline) { settings.resetFab() }
        }
        NxCard {
            H("Endpoint API")
            L("URL Nexora Link (bridge Vercel)")
            NxField(bridge, { bridge = it }, "https://nama-proyek.vercel.app")
            L("Basis API GitHub (GitHub Enterprise: https://host/api/v3)")
            NxField(apiBase, { apiBase = it }, "https://api.github.com")
            NxButton("Simpan Endpoint") { settings.updateBridgeUrl(bridge); settings.updateGithubApiBase(apiBase) }
            NxButton("🔗 Kunci & Tes Nexora Link", kind = NxKind.Outline) { onOpenPage("link") }
            NxButton("⚙️ Backend Nexora (URL & API key)", kind = NxKind.Outline) { onOpenPage("config") }
        }
        NxButton("Kembalikan Tampilan & Perilaku ke Bawaan", kind = NxKind.Warn) {
            onConfirm("Reset kustomisasi?", "Tata letak, sinkronisasi, batas unggahan, dan tombol Upload dikembalikan ke bawaan. URL endpoint dan kredensial tidak diubah.", false) { settings.resetAppearanceAndBehavior() }
        }
    }
}

// ====================================================================== Nexora Link

@Composable
fun NxLinkPage(actions: NexoraActions, settings: NxSettings, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c = LocalNexora.current
    val scope = rememberCoroutineScope()
    val secrets = remember { SecretStore(ctx) }
    var url by remember { mutableStateOf(settings.bridgeUrl) }
    var key by remember { mutableStateOf(secrets.get("link_key")) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var scopeChoice by remember { mutableStateOf("drive_file,photos_upload,photos_picker,photos_appdata") }
    var tick by remember { mutableStateOf(0) }
    val googleOn = remember(tick) { NexoraLink.hasTicket(ctx, "google") }
    val githubOn = remember(tick) { NexoraLink.hasTicket(ctx, "github") }

    fun save() { settings.updateBridgeUrl(url); secrets.put("link_key", key.trim()) }
    fun login(provider: String, scopes: String) {
        scope.launch {
            try { save(); actions.openUrl(withContext(Dispatchers.IO) { NexoraLink.beginLogin(ctx, provider, scopes) }); status = "Selesaikan login di browser lalu kembali ke aplikasi." }
            catch (e: Exception) { status = e.message.orEmpty() }
        }
    }

    NxSubpage("Nexora Link (API Bridge)", onBack) {
        NxCard {
            H("Sambungan ke server bridge")
            N("Bridge (proyek nexora-link-vercel) menyimpan client secret Google/GitHub dan token bot Telegram di server, sehingga tidak ada rahasia tersebut di APK.")
            L("URL bridge")
            NxField(url, { url = it }, "https://nama-proyek.vercel.app")
            L("Kunci API (NEXORA_API_KEY)")
            NxField(key, { key = it }, "Kunci yang sama dengan di Vercel", secret = true)
            NxButton("💾 Simpan", kind = NxKind.Outline) { save(); status = "Tersimpan." }
            NxButton(if (busy) "Menguji…" else "🔎 Tes Koneksi", enabled = !busy) {
                scope.launch {
                    busy = true; save()
                    status = withContext(Dispatchers.IO) { runCatching { NexoraLink.test(ctx) }.getOrElse { "Gagal: ${it.message}" } }
                    busy = false
                }
            }
            if (status.isNotEmpty()) N(status, if (status.startsWith("Gagal")) c.danger else c.primary)
        }
        NxCard {
            H("🇬 Google (Drive & Photos)")
            N(if (googleOn) "Terhubung" + NexoraLink.accountEmail(ctx, "google").takeIf { it.isNotBlank() }?.let { " sebagai $it" }.orEmpty() else "Belum terhubung.", if (googleOn) c.success else c.muted)
            L("Izin yang diminta")
            NxDropdown(
                listOf(
                    "drive_file,photos_upload,photos_picker,photos_appdata" to "Disarankan: Drive (file aplikasi) + Photos",
                    "drive,photos_upload,photos_picker,photos_appdata" to "Drive penuh (seluruh isi) + Photos",
                    "drive_file" to "Hanya Drive (file aplikasi)",
                    "photos_upload,photos_picker,photos_appdata" to "Hanya Google Photos"
                ),
                scopeChoice, { scopeChoice = it }
            )
            NxButton(if (googleOn) "Hubungkan Ulang Google" else "Masuk dengan Google") { login("google", scopeChoice) }
            if (googleOn) NxButton("Putuskan Google", kind = NxKind.Danger) {
                scope.launch { withContext(Dispatchers.IO) { NexoraLink.disconnect(ctx, "google") }; tick++; status = "Google diputus dan izin dicabut." }
            }
        }
        NxCard {
            H("🐙 GitHub (OAuth via bridge)")
            N(if (githubOn) "Terhubung." else "Belum terhubung. Alternatif tanpa bridge: Personal Access Token di Multi‑Cloud.", if (githubOn) c.success else c.muted)
            NxButton(if (githubOn) "Hubungkan Ulang GitHub" else "Masuk dengan GitHub") { login("github", "") }
            if (githubOn) NxButton("Putuskan GitHub", kind = NxKind.Danger) {
                scope.launch { withContext(Dispatchers.IO) { NexoraLink.disconnect(ctx, "github") }; tick++; status = "GitHub diputus." }
            }
        }
    }
}

// ====================================================================== Google (Drive & Photos)

@Composable
fun NxGooglePage(vm: ExplorerViewModel, actions: NexoraActions, onBack: () -> Unit, onOpenPage: (String) -> Unit) {
    val ctx = LocalContext.current
    val c = LocalNexora.current
    val scope = rememberCoroutineScope()
    val connected = NexoraLink.hasTicket(ctx, "google")
    val photos = remember { NxRemotes.photos(ctx) }
    var session by remember { mutableStateOf<GooglePhotosStore.PickerSession?>(null) }
    var picked by remember { mutableStateOf<List<RemoteEntry>>(emptyList()) }
    var info by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var dest by remember { mutableStateOf("tdlib") }
    var destFolder by remember { mutableStateOf("") }
    val folders = remember(vm.cloudItems, vm.emptyFolders) { vm.folderChoices("tdlib") }

    NxSubpage("Google Drive & Photos", onBack) {
        if (!connected) {
            NxCard {
                H("Belum terhubung")
                N("Login Google dilakukan lewat Nexora Link (server bridge Anda) agar client secret tidak ada di aplikasi.")
                NxButton("🔗 Buka Nexora Link") { onOpenPage("link") }
            }
            return@NxSubpage
        }
        NxCard {
            H("Google Drive")
            N("Isi Drive tampil di tab Files sebagai sumber \"Google Drive\" lengkap dengan folder, unggah, unduh, ganti nama, pindah, dan salin.")
            N(vm.remoteNotes["gdrive"] ?: "${vm.remoteItems.count { it.source == "gdrive" }} berkas termuat.", if (vm.remoteNotes["gdrive"] != null) c.danger else c.muted)
            NxButton("↻ Muat Ulang Drive", kind = NxKind.Outline) { vm.refresh() }
        }
        NxCard {
            H("Google Photos")
            N("Sejak 2025 Google hanya mengizinkan membaca foto Anda lewat Picker: Anda memilih foto/video di Google Photos, lalu aplikasi mengimpornya.")
            NxButton(if (busy) "Menyiapkan…" else "📷 Pilih Foto/Video dari Google Photos", enabled = !busy) {
                scope.launch {
                    busy = true; picked = emptyList(); info = ""
                    try {
                        val created = withContext(Dispatchers.IO) { photos.createPickerSession() }
                        session = created
                        actions.openUrl(created.pickerUri)
                        info = "Pilih foto di Google Photos, lalu kembali ke sini dan ketuk \"Cek Hasil Pilihan\"."
                    } catch (e: Exception) { info = "Gagal: ${e.message}" }
                    busy = false
                }
            }
            val current = session
            if (current != null) {
                NxButton("✅ Cek Hasil Pilihan", kind = NxKind.Outline, enabled = !busy) {
                    scope.launch {
                        busy = true
                        try {
                            val ready = withContext(Dispatchers.IO) { photos.pickerReady(current.id) }
                            if (ready) { picked = withContext(Dispatchers.IO) { photos.listPicked(current.id) }; info = "${picked.size} item dipilih." }
                            else info = "Pemilihan belum selesai. Selesaikan di Google Photos lalu cek lagi."
                        } catch (e: Exception) { info = "Gagal: ${e.message}" }
                        busy = false
                    }
                }
            }
            if (picked.isNotEmpty()) {
                L("Impor ke")
                NxDropdown(listOf("tdlib" to "✈️ Telegram", "lokal" to "📱 Pustaka Lokal", "gdrive" to "Google Drive"), dest, { dest = it })
                if (dest != "lokal") {
                    val choices = if (dest == "tdlib") folders else vm.folderChoices("gdrive")
                    NxDropdown(choices.map { it to (if (it.isEmpty()) "📁 Root" else "📁 $it") }, destFolder, { destFolder = it })
                }
                NxButton(if (busy) "Mengimpor…" else "⬇️ Impor ${picked.size} Item", enabled = !busy && (dest != "tdlib" || TdClient.isReady)) {
                    scope.launch {
                        busy = true
                        var ok = 0
                        var failed = 0
                        val items = picked
                        for ((index, entry) in items.withIndex()) {
                            info = "Mengimpor ${index + 1}/${items.size}: ${entry.name}"
                            try {
                                withContext(Dispatchers.IO) {
                                    val dir = File(File(ctx.cacheDir, "upload_stage"), System.nanoTime().toString()).apply { mkdirs() }
                                    val file = File(dir, entry.name.replace('/', '_'))
                                    file.outputStream().use { out -> photos.downloadPicked(entry, out) { _, _ -> } }
                                    when (dest) {
                                        "tdlib" -> NxTransferEngine.enqueueUpload(ctx, vm.db, file.absolutePath, file.name, destFolder, "Impor Google Photos")
                                        "lokal" -> {
                                            val target = File(vm.library, file.name).let { if (it.exists()) File(vm.library, "${System.currentTimeMillis()}_${file.name}") else it }
                                            file.copyTo(target); vm.db.index(target); dir.deleteRecursively()
                                        }
                                        else -> { NxRemotes.drive(ctx).upload(destFolder, file, file.name) { _, _ -> }; dir.deleteRecursively() }
                                    }
                                }
                                ok++
                            } catch (e: Exception) { failed++; AppLog.err("Impor Photos gagal: ${e.message}") }
                        }
                        session?.let { s -> withContext(Dispatchers.IO) { photos.deletePickerSession(s.id) } }
                        info = "$ok item diimpor" + (if (failed > 0) ", $failed gagal." else ".") + if (dest == "tdlib") " Unggahan Telegram berjalan di antrean." else ""
                        picked = emptyList(); session = null
                        vm.refresh()
                        busy = false
                    }
                }
            }
            if (info.isNotEmpty()) N(info, c.primary)
            val appCreated = vm.remoteItems.count { it.source == "gphotos" }
            N("Media buatan Nexora di Photos: $appCreated" + (vm.remoteNotes["gphotos"]?.let { " • $it" } ?: ""))
        }
        NxButton("Putuskan / kelola izin", kind = NxKind.Outline) { onOpenPage("link") }
    }
}

// ====================================================================== Multi-cloud (GitHub lengkap)

@Composable
fun NxMultiCloudPage(vm: ExplorerViewModel, onBack: () -> Unit, onOpenPage: (String) -> Unit) {
    val ctx = LocalContext.current
    val c = LocalNexora.current
    val scope = rememberCoroutineScope()
    val github = remember { NxRemotes.github(ctx) }
    val secrets = remember { SecretStore(ctx) }
    var owner by remember { mutableStateOf(github.owner) }
    var repo by remember { mutableStateOf(github.repo) }
    var branch by remember { mutableStateOf(github.branch) }
    var token by remember { mutableStateOf(secrets.get("github_pat")) }
    var result by remember { mutableStateOf("") }
    var repos by remember { mutableStateOf<List<Triple<String, Boolean, String>>>(emptyList()) }
    var newRepo by remember { mutableStateOf("") }
    var newPrivate by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }

    fun perform(block: () -> String) {
        scope.launch {
            busy = true
            result = withContext(Dispatchers.IO) { runCatching(block).getOrElse { "Gagal: ${it.message}" } }
            busy = false
        }
    }
    fun applyConfig() { secrets.put("github_pat", token.trim()); github.configure(owner, repo, branch) }

    NxSubpage("Penyedia Cloud Lainnya", onBack) {
        NxCard {
            H("🐙 GitHub — repositori sebagai penyimpanan")
            N("Isi repo tampil di tab Files sebagai sumber GitHub: baca, tulis, hapus, ganti nama, pindah, salin, dan unggah (maks. 25 MB per berkas lewat API).")
            L("Personal Access Token (scope repo)")
            NxField(token, { token = it }, "ghp_… / github_pat_…", secret = true)
            L("Owner / Repo / Branch")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.weight(1f)) { NxField(owner, { owner = it.trim() }, "owner") }
                Box(Modifier.weight(1f)) { NxField(repo, { repo = it.trim() }, "repo") }
            }
            NxField(branch, { branch = it.trim() }, "branch (kosong = bawaan)")
            val valid = owner.matches(Regex("[A-Za-z0-9_.-]+")) && repo.matches(Regex("[A-Za-z0-9_.-]+"))
            NxButton(if (busy) "Memproses…" else "🔎 Simpan & Verifikasi", enabled = valid && !busy) {
                applyConfig(); perform { val text = github.verify(); vm.refresh(); AppLog.ok("GitHub $owner/$repo terverifikasi"); text }
            }
            NxButton("📚 Daftar Repo Saya", kind = NxKind.Outline, enabled = !busy) {
                secrets.put("github_pat", token.trim())
                scope.launch {
                    busy = true
                    try { repos = withContext(Dispatchers.IO) { github.listRepos() }; result = "${repos.size} repo ditemukan. Ketuk untuk memilih." }
                    catch (e: Exception) { result = "Gagal: ${e.message}" }
                    busy = false
                }
            }
            repos.take(40).forEach { (full, isPrivate, defaultBranch) ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp).background(c.surfaceVariant, RoundedCornerShape(8.dp)).padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(full, Modifier.weight(1f), color = c.text, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (isPrivate) NxPill("privat")
                    NxMiniButton("Pilih", Modifier.padding(start = 6.dp)) {
                        owner = full.substringBefore('/'); repo = full.substringAfter('/'); branch = defaultBranch
                        github.configure(owner, repo, branch); vm.refresh(); result = "Repo dipilih: $full"
                    }
                }
            }
            L("Buat repo baru")
            NxField(newRepo, { newRepo = it.trim() }, "nama-repo")
            NxSwitchRow("Repo privat", "Disarankan untuk penyimpanan pribadi", newPrivate) { newPrivate = it }
            NxButton("➕ Buat & Pakai Repo", enabled = newRepo.isNotBlank() && !busy) {
                secrets.put("github_pat", token.trim())
                perform {
                    val full = github.createRepo(newRepo, newPrivate)
                    owner = full.substringBefore('/'); repo = full.substringAfter('/'); branch = "main"
                    github.configure(owner, repo, branch); vm.refresh(); "Repo dibuat dan dipakai: $full"
                }
            }
            NxButton("⬆️ Sinkronkan Pustaka Lokal ke Repo (folder Nexora)", kind = NxKind.Outline, enabled = github.isConnected() && !busy) {
                perform {
                    val existing = vm.remoteItems.filter { it.source == "github" && it.folder == "Nexora" }.map { it.name }.toSet()
                    var uploaded = 0
                    var skipped = 0
                    for (item in vm.localItems) {
                        val file = File(item.localPath.orEmpty())
                        if (item.name in existing || !file.isFile || file.length() > GitHubStore.MAX_UPLOAD) { skipped++; continue }
                        github.upload("Nexora", file, item.name) { _, _ -> }
                        uploaded++
                    }
                    vm.refresh()
                    "Sinkron selesai: $uploaded diunggah, $skipped dilewati (sudah ada atau > 25 MB)."
                }
            }
            NxButton("Putuskan GitHub", kind = NxKind.Danger) {
                secrets.put("github_pat", ""); github.configure("", "", ""); owner = ""; repo = ""; branch = ""; token = ""; vm.refresh(); result = "GitHub diputus dari perangkat ini."
            }
            NxButton("Login lewat Nexora Link (OAuth)", kind = NxKind.Outline) { onOpenPage("link") }
            if (result.isNotEmpty()) N(result, if (result.startsWith("Gagal")) c.danger else c.primary)
            vm.remoteNotes["github"]?.let { N(it, c.danger) }
        }
        NxCard {
            H("🇬 Google Drive & Photos")
            N("Terhubung lewat Nexora Link.")
            NxButton("Buka Google Drive & Photos", kind = NxKind.Outline) { onOpenPage("google") }
        }
    }
}
