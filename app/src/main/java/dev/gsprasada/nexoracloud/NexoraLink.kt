package dev.gsprasada.nexoracloud

import android.content.Context
import android.net.Uri
import android.util.Base64
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONObject

/** Helper HTTP bersama (OkHttp) untuk Nexora Link, GitHub, dan Google. */
internal object NxHttp {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).writeTimeout(90, TimeUnit.SECONDS).build()
    }
    val JSON = "application/json; charset=utf-8".toMediaType()

    class Result(val code: Int, val body: String, val location: String?) { val ok: Boolean get() = code in 200..299 }

    private fun build(method: String, url: String, headers: Map<String, String>, body: RequestBody?): Request {
        val builder = Request.Builder().url(url)
        headers.forEach { (k, v) -> builder.header(k, v) }
        return builder.method(method, if (body == null && method in setOf("POST", "PUT", "PATCH")) ByteArray(0).toRequestBody(null) else body).build()
    }

    fun request(method: String, url: String, headers: Map<String, String> = emptyMap(), body: RequestBody? = null): Result =
        client.newCall(build(method, url, headers, body)).execute().use { Result(it.code, it.body?.string().orEmpty(), it.header("Location")) }

    fun json(method: String, url: String, headers: Map<String, String> = emptyMap(), body: JSONObject? = null): Result =
        request(method, url, headers + ("Content-Type" to "application/json"), body?.toString()?.toRequestBody(JSON))

    /** Mengunduh isi respons ke [out] (streaming). Melempar IOException bila status bukan 2xx. */
    fun download(url: String, headers: Map<String, String>, out: OutputStream, onProgress: (Long, Long) -> Unit): Long =
        client.newCall(build("GET", url, headers, null)).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}: ${response.body?.string().orEmpty().take(200)}")
            val total = response.body?.contentLength() ?: -1L
            var done = 0L
            response.body!!.byteStream().use { input: InputStream ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    done += n
                    onProgress(done, if (total > 0) total else done)
                }
            }
            done
        }

    /** Body streaming dari berkas dengan callback progres. */
    class FileBody(private val file: java.io.File, private val mime: String, private val onProgress: (Long, Long) -> Unit) : RequestBody() {
        override fun contentType() = mime.toMediaType()
        override fun contentLength() = file.length()
        override fun writeTo(sink: BufferedSink) {
            val total = file.length()
            var done = 0L
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    sink.write(buffer, 0, n)
                    done += n
                    onProgress(done, total)
                }
            }
        }
    }

    fun message(result: Result): String =
        runCatching {
            val json = JSONObject(result.body)
            json.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() } ?: json.optString("message").ifBlank { json.optString("error") }
        }.getOrDefault("").ifBlank { result.body.take(160) }.ifBlank { "tanpa pesan" }

    fun check(result: Result, action: String): Result {
        if (!result.ok) throw IOException("$action gagal (HTTP ${result.code}): ${message(result)}")
        return result
    }
}

/**
 * Klien Nexora Link (bridge Vercel). Login OAuth memakai PKCE: aplikasi menyimpan verifier rahasia, bridge hanya mengirim
 * kode penukar lewat deep link, dan tiket (bukan token mentah) disimpan terenkripsi di [SecretStore].
 */
object NexoraLink {
    private val tokenCache = ConcurrentHashMap<String, Pair<String, Long>>()
    /** Naik setiap kali login/putus berhasil agar UI memuat ulang sumber jarak jauh. */
    val changeTick = MutableStateFlow(0)
    private fun secrets(context: Context) = SecretStore(context)
    private fun accounts(context: Context) = context.getSharedPreferences("nexora_accounts", Context.MODE_PRIVATE)

    fun baseUrl(context: Context): String = NxPrefs.bridgeUrl(context)
    fun apiKey(context: Context): String = secrets(context).get("link_key")
    fun isConfigured(context: Context): Boolean = baseUrl(context).startsWith("https://") && apiKey(context).isNotBlank()
    fun hasTicket(context: Context, provider: String): Boolean = secrets(context).get("link_ticket_$provider").isNotBlank()
    fun accountEmail(context: Context, provider: String): String = accounts(context).getString("link_email_$provider", "").orEmpty()

    private fun headers(context: Context) = mapOf("x-nexora-key" to apiKey(context))

    private fun randomUrlSafe(bytes: Int): String {
        val data = ByteArray(bytes).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    /** Memeriksa bridge: /health (tanpa kunci) lalu /api/info (dengan kunci). Mengembalikan ringkasan untuk pengguna. */
    fun test(context: Context): String {
        require(baseUrl(context).startsWith("https://")) { "URL Nexora Link harus diawali https://" }
        val health = NxHttp.check(NxHttp.request("GET", baseUrl(context) + "/health"), "Cek /health")
        val h = JSONObject(health.body)
        if (!h.optBoolean("ok")) throw IOException("Server belum dikonfigurasi: " + h.optJSONArray("problems")?.toString().orEmpty())
        val info = NxHttp.request("GET", baseUrl(context) + "/api/info", headers(context))
        if (info.code == 401) throw IOException("Kunci API ditolak server (periksa NEXORA_API_KEY).")
        NxHttp.check(info, "Cek /api/info")
        val providers = JSONObject(info.body).optJSONObject("providers")
        fun flag(name: String) = if (providers?.optBoolean(name) == true) "aktif" else "belum diatur"
        return "Terhubung. Google: ${flag("google")}, GitHub: ${flag("github")}, Telegram proxy: ${flag("telegram")}."
    }

    /** Menyiapkan login dan mengembalikan URL yang harus dibuka di browser. */
    fun beginLogin(context: Context, provider: String, scope: String = ""): String {
        require(isConfigured(context)) { "Isi URL dan kunci API Nexora Link terlebih dahulu." }
        val state = randomUrlSafe(24)
        val verifier = randomUrlSafe(48)
        val challenge = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        secrets(context).put("link_pending", JSONObject().put("state", state).put("verifier", verifier).put("provider", provider).put("t", System.currentTimeMillis()).toString())
        val builder = Uri.parse(baseUrl(context) + "/api/$provider/start").buildUpon().appendQueryParameter("state", state).appendQueryParameter("challenge", challenge)
        if (scope.isNotBlank()) builder.appendQueryParameter("scope", scope)
        return builder.build().toString()
    }

    /** Memproses deep link nexoracloud://link?... dan menyimpan tiket. Mengembalikan pesan sukses. */
    fun handleRedirect(context: Context, uri: Uri): String {
        val provider = uri.getQueryParameter("provider").orEmpty()
        val code = uri.getQueryParameter("code").orEmpty()
        val state = uri.getQueryParameter("state").orEmpty()
        val pending = runCatching { JSONObject(secrets(context).get("link_pending")) }.getOrNull() ?: throw IOException("Tidak ada login yang sedang berlangsung.")
        if (pending.optString("state") != state || pending.optString("provider") != provider) throw IOException("State tidak cocok; login dibatalkan demi keamanan.")
        if (System.currentTimeMillis() - pending.optLong("t") > 15 * 60 * 1000) throw IOException("Login kedaluwarsa. Ulangi dari awal.")
        val result = NxHttp.json("POST", baseUrl(context) + "/api/link/exchange", headers(context), JSONObject().put("code", code).put("verifier", pending.optString("verifier")))
        NxHttp.check(result, "Penukaran kode")
        val data = JSONObject(result.body)
        secrets(context).put("link_ticket_$provider", data.getString("ticket"))
        secrets(context).put("link_pending", "")
        accounts(context).edit().putString("link_email_$provider", data.optString("email")).apply()
        tokenCache.remove(provider)
        AppLog.ok("Nexora Link: $provider terhubung")
        changeTick.value = changeTick.value + 1
        return "$provider terhubung" + data.optString("email").takeIf { it.isNotBlank() }?.let { " sebagai $it" }.orEmpty() + "."
    }

    /** Access token terbaru dari bridge (di-cache hingga mendekati kedaluwarsa). */
    fun accessToken(context: Context, provider: String): String {
        tokenCache[provider]?.let { (token, expiry) -> if (System.currentTimeMillis() < expiry) return token }
        val ticket = secrets(context).get("link_ticket_$provider")
        if (ticket.isBlank()) throw IOException("$provider belum terhubung lewat Nexora Link.")
        val result = NxHttp.json("POST", baseUrl(context) + "/api/$provider/token", headers(context), JSONObject().put("ticket", ticket))
        if (result.code == 401) { disconnectLocal(context, provider); throw IOException("Sesi $provider berakhir atau dicabut. Login ulang lewat Nexora Link.") }
        NxHttp.check(result, "Mengambil token $provider")
        val data = JSONObject(result.body)
        val token = data.getString("access_token")
        val expiresIn = data.optLong("expires_in", 3000L)
        tokenCache[provider] = token to (System.currentTimeMillis() + (expiresIn - 120).coerceAtLeast(60) * 1000)
        return token
    }

    private fun disconnectLocal(context: Context, provider: String) {
        secrets(context).put("link_ticket_$provider", "")
        accounts(context).edit().remove("link_email_$provider").apply()
        tokenCache.remove(provider)
    }

    /** Memutus provider; untuk Google juga mencabut izin di server (best-effort). */
    fun disconnect(context: Context, provider: String) {
        val ticket = secrets(context).get("link_ticket_$provider")
        if (provider == "google" && ticket.isNotBlank() && isConfigured(context)) {
            runCatching { NxHttp.json("POST", baseUrl(context) + "/api/google/revoke", headers(context), JSONObject().put("ticket", ticket)) }
        }
        disconnectLocal(context, provider)
        changeTick.value = changeTick.value + 1
    }
}
