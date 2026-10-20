package com.eyue.reader.data.cloud

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface CloudCredentialStore {
    fun read(): CloudCredentials?
    fun write(credentials: CloudCredentials)
    fun clear()
}

/** Appending the server preserves the previous two-field encrypted payload. */
internal object CloudCredentialPayload {
    fun encode(credentials: CloudCredentials): ByteArray = ByteArrayOutputStream().apply {
        DataOutputStream(this).use {
            it.writeUTF(credentials.account)
            it.writeUTF(credentials.password)
            it.writeUTF(credentials.serverUrl)
        }
    }.toByteArray()

    fun decode(bytes: ByteArray): CloudCredentials = DataInputStream(ByteArrayInputStream(bytes)).use {
        val account = it.readUTF()
        val password = it.readUTF()
        val server = if (it.available() > 0) it.readUTF() else DEFAULT_WEBDAV_SERVER
        require(it.available() == 0) { "云端账号配置损坏，请重新填写" }
        CloudCredentials(account, password, server)
    }
}

/** The preferences contain only authenticated ciphertext; the key stays in Android Keystore. */
class EncryptedCloudCredentialStore(context: Context) : CloudCredentialStore {
    private val preferences = context.getSharedPreferences("cloud-credentials", Context.MODE_PRIVATE)
    private val alias = "EReadJianguoyunCredentials"
    private val aad = "ERead/Jianguoyun/v1".toByteArray(Charsets.UTF_8)

    @Synchronized
    override fun read(): CloudCredentials? {
        val encoded = preferences.getString("encrypted", null) ?: return null
        require(encoded.length <= 65_536) { "云端账号配置损坏，请重新填写" }
        try {
            val data = Base64.decode(encoded, Base64.NO_WRAP)
            require(data.size >= 29 && data[0].toInt() == 1)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(1, 13)))
            cipher.updateAAD(aad)
            val plain = cipher.doFinal(data, 13, data.size - 13)
            return try { CloudCredentialPayload.decode(plain) } finally { plain.fill(0) }
        } catch (error: Exception) {
            throw IllegalStateException("云端账号配置无法解密，请重新填写 WebDAV 密码", error)
        }
    }

    @Synchronized
    override fun write(credentials: CloudCredentials) {
        val plain = CloudCredentialPayload.encode(credentials)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            cipher.updateAAD(aad)
            check(cipher.iv.size == 12)
            val encrypted = byteArrayOf(1) + cipher.iv + cipher.doFinal(plain)
            check(preferences.edit().putString("encrypted", Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit()) { "云端账号保存失败" }
        } finally { plain.fill(0) }
    }

    @Synchronized
    override fun clear() { check(preferences.edit().remove("encrypted").commit()) { "云端账号解绑失败" } }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
}
