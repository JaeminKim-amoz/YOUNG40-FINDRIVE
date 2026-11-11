package app.findrive.car

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class ConnectionVault(context: Context) {
    private val prefs = context.getSharedPreferences("findrive-connection", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("findrive-connection", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("findrive-connection", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun save(token: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val value = Base64.encodeToString(cipher.iv + cipher.doFinal(token.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        check(prefs.edit().putString("secret", value).commit()) { "연결을 안전하게 저장하지 못했습니다" }
    }
    fun read(): String {
        val value = prefs.getString("secret", null) ?: return ""
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12))) }
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }
    fun clear() { check(prefs.edit().clear().commit()) }
}

class ConnectionFailure(val status: Int, message: String) : Exception(message)
object ConnectionClient {
    fun validate(base: String): String {
        val uri = URI(base.trim().trimEnd('/'))
        require(uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null && uri.path.isNullOrEmpty()) { "서버 기본 주소를 확인해 주세요" }
        val local = uri.host in listOf("10.0.2.2", "127.0.0.1", "localhost") || uri.host.startsWith("192.168.") || uri.host.startsWith("10.")
        require(uri.scheme == "https" || (BuildConfig.DEBUG && local && uri.scheme == "http")) { "HTTPS 연결이 필요합니다" }
        return uri.toString()
    }
    fun request(base: String, path: String, token: String = "", body: JSONObject? = null, method: String = "POST", timeoutMs:Int=8000): JSONObject {
        val connection = URI("${validate(base)}$path").toURL().openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false; connection.requestMethod = method; connection.connectTimeout = 8000; connection.readTimeout = timeoutMs
            if (token.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                connection.doOutput = true; connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val ok = connection.responseCode in 200..299
            val stream = if (ok) connection.inputStream else connection.errorStream
            val bytes = stream?.use { input ->
                val buffer = java.io.ByteArrayOutputStream(); val chunk = ByteArray(8192)
                while (true) { val count = input.read(chunk); if (count < 0) break; check(buffer.size() + count <= 8_000_000) { "수신 자료가 너무 큽니다" }; buffer.write(chunk, 0, count) }; buffer.toByteArray()
            }
            val result = JSONObject(bytes?.toString(Charsets.UTF_8) ?: "{}")
            if (!ok) throw ConnectionFailure(connection.responseCode,result.optString("error", "연결 실패 · ${connection.responseCode}"))
            return result
        } finally { connection.disconnect() }
    }
}
