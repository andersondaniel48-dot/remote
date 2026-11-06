package dev.daniel.tvhub.core

import android.content.Context
import android.util.Base64
import dadb.AdbKeyPair
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec

/**
 * The phone's adb identity. Each TV asks once ("Allow USB debugging?") and remembers it
 * when you tick "Always allow from this computer".
 */
object AdbKeys {
    fun load(context: Context): AdbKeyPair {
        val privFile = File(context.filesDir, "adbkey")
        val pubFile = File(context.filesDir, "adbkey.pub")
        if (!privFile.exists() || !pubFile.exists()) generate(privFile, pubFile)
        val priv = KeyFactory.getInstance("RSA")
            .generatePrivate(PKCS8EncodedKeySpec(Base64.decode(privFile.readText(), Base64.DEFAULT)))
        // adb expects the public key text followed by a NUL byte.
        val pub = pubFile.readBytes() + byteArrayOf(0)
        return AdbKeyPair(priv, pub)
    }

    private fun generate(privFile: File, pubFile: File) {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.genKeyPair()
        privFile.writeText(Base64.encodeToString(kp.private.encoded, Base64.NO_WRAP))
        val struct = KeyFormat.adbPublicKeyStruct(kp.public as RSAPublicKey)
        pubFile.writeText(Base64.encodeToString(struct, Base64.NO_WRAP) + " tvhub@phone")
    }
}
