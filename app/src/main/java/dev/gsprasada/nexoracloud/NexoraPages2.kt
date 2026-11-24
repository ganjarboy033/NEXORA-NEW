package dev.gsprasada.nexoracloud

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import org.json.JSONObject

@Composable
private fun PageTitle(text: String) {
    val c = LocalNexora.current
    Text(text, color = c.text, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 4.dp))
}

@Composable
private fun PageNote(text: String, color: Color? = null) {
    val c = LocalNexora.current
    Text(text, color = color ?: c.muted, fontSize = 11.5.sp, modifier = Modifier.padding(vertical = 2.dp))
}

@Composable
private fun Label(text: String) {
    val c = LocalNexora.current
    Text(text, color = c.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
}

// ------------------------------------------------------------------ Telegram App Configuration

@Composable
fun NxTgConfigPage(onBack: () -> Unit, onConfirm: NxConfirm) {
    val ctx = LocalContext.current
    val c = LocalNexora.current
    val scope = rememberCoroutineScope()
    val authState by TdClient.state.collectAsState()
    val ready = authState is TdAuthState.Ready
    val cfg = remember { TelegramAppConfiguration.load(ctx) }
    val tgPrefs = remember { ctx.getSharedPreferences(TelegramAppConfiguration.PREFS, Context.MODE_PRIVATE) }
    val proxyPrefs = remember { ctx.getSharedPreferences("nexora_proxy", Context.MODE_PRIVATE) }
    val secrets = remember { SecretStore(ctx) }

    var title by remember { mutableStateOf(cfg.appTitle) }
    var apiId by remember { mutableStateOf(cfg.apiId) }
    var apiHash by remember { mutableStateOf(cfg.apiHash) }
    var testDc by remember { mutableStateOf(tgPrefs.getBoolean("use_test_dc", false)) }
    var proxyType by remember { mutableStateOf(proxyPrefs.getString("type", "socks5") ?: "socks5") }
    var proxyHost by remember { mutableStateOf(proxyPrefs.getString("host", "") ?: "") }
    var proxyPort by remember { mutableStateOf(proxyPrefs.getString("port", "") ?: "") }
    var proxyUser by remember { mutableStateOf(proxyPrefs.getString("user", "") ?: "") }
    var proxySecret by remember { mutableStateOf(secrets.get("proxy_secret")) }
    var proxyPass by remember { mutableStateOf(secrets.get("proxy_password")) }
    var status by remember { mutableStateOf("") }

    fun saveCredentials() {
        val id = apiId.trim()
        val hash = apiHash.trim()
        if ((id.toIntOrNull() ?: 0) <= 0 || !hash.matches(Regex("[0-9a-fA-F]{32}"))) {
            status = "API ID harus angka dan API Hash harus 32 karakter heksadesimal."
            return
        }
        val apiChanged = id != cfg.apiId || hash != cfg.apiHash
        val testChanged = testDc != tgPrefs.getBoolean("use_test_dc", false)
        TelegramAppConfiguration.save(ctx, cfg.copy(appTitle = title.ifBlank { cfg.appTitle }, apiId = id, apiHash = hash))
        tgPrefs.edit().putBoolean("use_test_dc", testDc).apply()
        status = when {
            !apiChanged && !testChanged -> "Konfigurasi disimpan."
            ready -> "Tersimpan. Perubahan API/Test DC berlaku setelah Anda keluar dari akun lalu login ulang."
            else -> { TdClient.resetLocalSession(); "Tersimpan. Sesi login dimulai ulang dengan konfigurasi baru." }
        }
    }

    NxSubpage("TelegramAppConfiguration", onBack) {
        NxCard {
            PageTitle("Kredensial Aplikasi (my.telegram.org/apps)")
            Label("App Title")
            NxField(title, { title = it }, "NEXORA CLOUD")
            Label("API ID")
            NxField(apiId, { apiId = it.filter(Char::isDigit).take(10) }, "Contoh: 31474812", keyboard = KeyboardType.Number)
            Label("API Hash")
            NxField(apiHash, { apiHash = it.trim().take(32) }, "32 karakter heksadesimal", secret = true)
            NxSwitchRow("Mode Test Server Telegram", "Memakai server uji dengan database terpisah (akun produksi tidak tersentuh)", testDc) { testDc = it }
            NxButton("💾 Simpan Kredensial") { saveCredentials() }
            PageNote("Nilai TELEGRAM_API_ID/TELEGRAM_API_HASH yang dikompilasi saat build tetap diprioritaskan.")
        }
        NxCard {
            PageTitle("Data Center (DC) Telegram")
            PageNote("Produksi DC2: ${cfg.productionDc2}")
            PageNote("Test DC2: ${cfg.testDc2}")
            PageNote("TDLib memilih dan berpindah DC secara otomatis; alamat DC tidak dapat diubah manual dari aplikasi. Gunakan Proxy di bawah bila jaringan Anda memblokir Telegram.")
        }
        NxCard {
            PageTitle("Proxy MTProto / SOCKS5")
            NxDropdown(listOf("socks5" to "SOCKS5", "mtproto" to "MTProto"), proxyType, { proxyType = it })
            Label("Host / IP")
            NxField(proxyHost, { proxyHost = it.trim() }, "proxy.contoh.com")
            Label("Port")
            NxField(proxyPort, { proxyPort = it.filter(Char::isDigit).take(5) }, "1080 / 443", keyboard = KeyboardType.Number)
            if (proxyType == "mtproto") {
                Label("Secret (heksadesimal)")
                NxField(proxySecret, { proxySecret = it.trim() }, "dd…", secret = true)
            } else {
                Label("Username (opsional)")
                NxField(proxyUser, { proxyUser = it }, "username")
                Label("Password (opsional)")
                NxField(proxyPass, { proxyPass = it }, "password", secret = true)
            }
            NxButton("✅ Simpan & Aktifkan Proxy", enabled = proxyHost.isNotBlank() && (proxyPort.toIntOrNull() ?: 0) in 1..65535) {
                scope.launch {
                    try {
                        TdClient.start(ctx)
                        val type: TdApi.ProxyType = if (proxyType == "mtproto") TdApi.ProxyTypeMtproto(proxySecret) else TdApi.ProxyTypeSocks5(proxyUser, proxyPass)
                        TdClient.request(TdApi.AddProxy(TdApi.Proxy(proxyHost.trim(), proxyPort.toInt(), type), true, "Nexora"))
                        proxyPrefs.edit().putString("type", proxyType).putString("host", proxyHost).putString("port", proxyPort).putString("user", proxyUser).apply()
                        secrets.put("proxy_secret", proxySecret)
                        secrets.put("proxy_password", proxyPass)
                        status = "Proxy diaktifkan untuk koneksi Telegram."
                        AppLog.ok("Proxy $proxyType ${proxyHost.trim()}:$proxyPort diaktifkan")
                    } catch (e: Exception) {
                        status = "Proxy gagal: ${e.message}"
                        AppLog.err("Proxy gagal: ${e.message}")
                    }
                }
            }
            NxButton("Nonaktifkan Proxy", kind = NxKind.Outline) {
                scope.launch {
                    try {
                        TdClient.start(ctx)
                        TdClient.request(TdApi.DisableProxy())
                        status = "Proxy dinonaktifkan (koneksi langsung)."
                    } catch (e: Exception) {
                        status = "Gagal menonaktifkan proxy: ${e.message}"
                    }
                }
            }
        }
        if (status.isNotEmpty()) Text(status, color = c.primary, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 6.dp))
    }
}

// ------------------------------------------------------------------ Debug

@Composable
fun NxDebugPage(vm: ExplorerViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c = LocalNexora.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val log by AppLog.lines.collectAsState()
    val authState by TdClient.state.collectAsState()
    var tab by remember { mutableStateOf("log") }
    val mono = FontFamily.Monospace

    val summary: String = remember(authState, vm.cloudItems, vm.localItems, vm.filter, vm.cacheTick, vm.favTd, vm.archivedIds) {
        JSONObject()
            .put("versi_app", BuildConfig.VERSION_NAME)
            .put("auth", authState.javaClass.simpleName)
            .put("tdlib_siap", authState is TdAuthState.Ready)
            .put("item_telegram", vm.cloudItems.size)
            .put("item_lokal", vm.localItems.size)
            .put("folder_kosong", vm.emptyFolders.size)
            .put("favorit_telegram", vm.favTd.size)
            .put("arsip", vm.archivedIds.size)
            .put("cache_sesi", vm.sessionCacheFiles().size)
            .put("filter", vm.filter)
            .put("folder_files", vm.filesFolder)
            .toString(2)
    }
    val dbInfo by produceState("memuat…", tab) {
        value = withContext(Dispatchers.IO) {
            val size = ctx.getDatabasePath("nexora_local.db").let { if (it.exists()) it.length() else 0L }
            "Berkas lokal terindeks : ${vm.db.listFiles().size}\nRiwayat transfer      : ${vm.db.listTransfers().size}\nUkuran nexora_local.db : ${nxSize(size)}"
        }
    }
    val targetInfo by produceState("—", authState) {
        value = if (authState is TdAuthState.Ready) withContext(Dispatchers.IO) {
            runCatching { val t = TdCloud.get(ctx).currentTarget(); "${t.title} (id ${t.chatId})" }.getOrElse { "gagal: ${it.message}" }
        } else "belum login"
    }

    NxSubpage("🐞 Panel Debug", onBack) {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NxChip("Log", tab == "log") { tab = "log" }
            NxChip("Cloud", tab == "cloud") { tab = "cloud" }
            NxChip("SQLite", tab == "db") { tab = "db" }
            NxChip("State", tab == "state") { tab = "state" }
        }
        when (tab) {
            "log" -> NxCard {
                Box(Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 360.dp).background(Color(0xFF0B0F14), RoundedCornerShape(10.dp)).padding(10.dp).verticalScroll(rememberScrollState(), reverseScrolling = true)) {
                    Text(
                        if (log.isEmpty()) "(log kosong)" else log.joinToString("\n"),
                        color = Color(0xFF86EFAC), fontSize = 10.sp, fontFamily = mono
                    )
                }
                NxButton("📡 Ping TDLib (TestNetwork)", enabled = TdClient.isReady) {
                    scope.launch {
                        val started = System.currentTimeMillis()
                        val outcome = withContext(Dispatchers.IO) { runCatching { TdClient.call(TdApi.TestNetwork(), 20) } }
                        val ms = System.currentTimeMillis() - started
                        outcome.onSuccess { AppLog.ok("Ping TDLib ${ms} ms") }.onFailure { AppLog.err("Ping gagal: ${it.message}") }
                    }
                }
                NxButton("Salin Log", kind = NxKind.Outline) { clipboard.setText(AnnotatedString(log.joinToString("\n"))); vm.showToast("Log disalin.") }
                NxButton("Bersihkan Log", kind = NxKind.Warn) { AppLog.clear() }
            }
            "cloud" -> NxCard {
                PageTitle("Status Cloud")
                PageNote("Auth TDLib : ${authState.javaClass.simpleName}")
                PageNote("Chat penyimpanan : $targetInfo")
                PageNote("Berkas Telegram : ${vm.cloudItems.size} • Folder kosong lokal: ${vm.emptyFolders.size}")
                PageNote("Batas chunk : ${nxSize(TdCloud.CHUNK_BYTES)} per bagian")
                PageNote("Backend Nexora : " + if (BackendClient(ctx).baseUrl().isBlank()) "tidak diatur" else "diatur")
                vm.cloudNote?.let { PageNote("Catatan terakhir : $it", c.warning) }
                NxButton("↻ Muat Ulang Katalog") { vm.refresh() }
            }
            "db" -> NxCard {
                PageTitle("Basis Data Lokal")
                Text(dbInfo, color = c.text, fontSize = 11.5.sp, fontFamily = mono)
            }
            else -> NxCard {
                PageTitle("Inspektur State (tanpa rahasia)")
                Text(summary, color = c.text, fontSize = 11.sp, fontFamily = mono)
                NxButton("Salin JSON", kind = NxKind.Outline) { clipboard.setText(AnnotatedString(summary)); vm.showToast("JSON disalin.") }
            }
        }
    }
}
