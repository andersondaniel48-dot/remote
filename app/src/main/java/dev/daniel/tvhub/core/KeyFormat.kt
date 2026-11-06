package dev.daniel.tvhub.core

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.interfaces.RSAPublicKey

/** Encodes an RSA public key the way adb stores it in adbkey.pub (the C RSAPublicKey struct). */
object KeyFormat {
    private const val WORDS = 2048 / 32

    fun adbPublicKeyStruct(pub: RSAPublicKey): ByteArray {
        val r32 = BigInteger.ZERO.setBit(32)
        var n = pub.modulus
        val r = BigInteger.ZERO.setBit(WORDS * 32)
        var rr = r.modPow(BigInteger.valueOf(2), n)
        val n0inv = n.remainder(r32).modInverse(r32)
        val nWords = IntArray(WORDS)
        val rrWords = IntArray(WORDS)
        for (i in 0 until WORDS) {
            val a = rr.divideAndRemainder(r32); rr = a[0]; rrWords[i] = a[1].toInt()
            val b = n.divideAndRemainder(r32); n = b[0]; nWords[i] = b[1].toInt()
        }
        val buf = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(WORDS)
        buf.putInt(n0inv.negate().toInt())
        nWords.forEach { buf.putInt(it) }
        rrWords.forEach { buf.putInt(it) }
        buf.putInt(pub.publicExponent.toInt())
        return buf.array()
    }
}
