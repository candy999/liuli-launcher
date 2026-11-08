package tw.liuli.launcher.adb

import android.content.Context
import android.util.Base64
import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher

/**
 * ADB 連線用的 RSA 金鑰：產生一次、永久保存。
 * 第一次連線時，電視會顯示這把金鑰的指紋並問「允許 USB 偵錯？」，使用者按一律允許後就記住。
 *
 * 這整套只用來連本機（127.0.0.1）的 ADB，開啟自己的無障礙按鍵服務；
 * 與 Shizuku、AppManager 的免 root 授權是同一種做法。
 */
class AdbKey private constructor(val keyPair: KeyPair, private val name: String) {

    /**
     * 簽 ADB 送來的 20 bytes token。
     * ADB 用「手動補好 PKCS#1 v1.5 padding 後做原始 RSA」的方式，所以用 NoPadding 加密 padding+token。
     */
    fun sign(token: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keyPair.private)
        return cipher.doFinal(SIGNATURE_PADDING + token)
    }

    /** Android 版 ADB 公鑰字串：base64(struct) + " " + 名稱（結尾的 \0 由呼叫端補）。 */
    fun adbPublicKey(): ByteArray {
        val pub = keyPair.public as RSAPublicKey
        val n = pub.modulus
        val r32 = BigInteger.ZERO.setBit(32)
        val n0inv = r32.subtract(n.mod(r32).modInverse(r32)).toLong()
        val rr = BigInteger.ZERO.setBit(RSA_BITS * 2).mod(n) // 2^4096 mod n

        val buf = ByteBuffer.allocate(4 + 4 + MODULUS_BYTES + MODULUS_BYTES + 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(MODULUS_BYTES / 4)       // 模數字數（64）
        buf.putInt(n0inv.toInt())           // -1/n mod 2^32
        buf.put(toLittleEndian(n))          // 模數（小端）
        buf.put(toLittleEndian(rr))         // rr（小端）
        buf.putInt(pub.publicExponent.toInt()) // 指數（通常 65537）

        val b64 = Base64.encodeToString(buf.array(), Base64.NO_WRAP)
        return (b64 + " " + name).toByteArray()
    }

    private fun toLittleEndian(v: BigInteger): ByteArray {
        val be = v.toByteArray() // big-endian，可能有前導 0x00
        val out = ByteArray(MODULUS_BYTES)
        var len = be.size
        var off = 0
        if (len > MODULUS_BYTES) { off = len - MODULUS_BYTES; len = MODULUS_BYTES }
        for (i in 0 until len) out[i] = be[off + len - 1 - i] // 反轉成小端
        return out
    }

    companion object {
        private const val RSA_BITS = 2048
        private const val MODULUS_BYTES = RSA_BITS / 8 // 256

        /** PKCS#1 v1.5 + SHA-1 DigestInfo 的完整 padding（總長 236，加 20 bytes token 剛好 256）。 */
        private val SIGNATURE_PADDING: ByteArray = run {
            val digestInfo = byteArrayOf(
                0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
                0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
            )
            val out = ByteArray(MODULUS_BYTES - 20)
            var i = 0
            out[i++] = 0x00
            out[i++] = 0x01
            while (i < out.size - digestInfo.size - 1) out[i++] = 0xFF.toByte()
            out[i++] = 0x00
            System.arraycopy(digestInfo, 0, out, i, digestInfo.size)
            out
        }

        /** 載入或產生金鑰，存在 App 私有空間。 */
        fun load(context: Context, name: String = "liuli-launcher@tv"): AdbKey {
            val dir = File(context.filesDir, "adb").apply { mkdirs() }
            val privFile = File(dir, "key.pk8")
            val pubFile = File(dir, "key.x509")
            val pair = if (privFile.exists() && pubFile.exists()) {
                val kf = KeyFactory.getInstance("RSA")
                val priv = kf.generatePrivate(PKCS8EncodedKeySpec(privFile.readBytes())) as RSAPrivateKey
                val pub = kf.generatePublic(X509EncodedKeySpec(pubFile.readBytes())) as RSAPublicKey
                KeyPair(pub, priv)
            } else {
                val gen = KeyPairGenerator.getInstance("RSA").apply { initialize(RSA_BITS) }
                val kp = gen.generateKeyPair()
                privFile.writeBytes(kp.private.encoded)
                pubFile.writeBytes(kp.public.encoded)
                kp
            }
            return AdbKey(pair, name)
        }
    }
}
