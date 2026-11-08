package tw.liuli.launcher.adb

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 極簡 ADB 用戶端（典型 TCP 版，對應 `adb connect ip:5555`）。
 * 只做一件事：連到本機 ADB、完成 RSA 握手、執行一行 shell 指令、拿回輸出。
 *
 * 不支援 Android 11 的「無線偵錯」TLS 配對流程（那需要配對碼）；
 * 目標是已開啟網路 ADB（5555）的電視。
 */
class AdbClient(private val host: String, private val port: Int, private val key: AdbKey) : AutoCloseable {

    private lateinit var socket: Socket
    private lateinit var input: DataInputStream
    private lateinit var output: DataOutputStream
    private var nextLocalId = 1

    class AdbException(message: String) : Exception(message)

    /** 連線並完成握手。第一次連會讓電視跳出授權對話框。 */
    fun connect(timeoutMs: Int = 8000) {
        socket = Socket()
        socket.connect(InetSocketAddress(host, port), timeoutMs)
        socket.soTimeout = timeoutMs
        input = DataInputStream(socket.getInputStream().buffered())
        output = DataOutputStream(socket.getOutputStream())

        send(A_CNXN, VERSION, MAX_DATA, "host::features=shell_v2,cmd,stat_v2".toByteArray())

        var sentPubKey = false
        while (true) {
            val m = receive()
            when (m.command) {
                A_CNXN -> return // 連線成功
                A_AUTH -> {
                    // arg0=1 TOKEN：先用私鑰簽；電視不認得這把金鑰時會再要一次 TOKEN，這時送公鑰
                    if (m.arg0 == AUTH_TOKEN) {
                        if (!sentPubKey) {
                            send(A_AUTH, AUTH_SIGNATURE, 0, key.sign(m.payload))
                        } else {
                            throw AdbException("電視未授權這把金鑰")
                        }
                    } else {
                        throw AdbException("ADB 認證流程異常（arg0=${m.arg0}）")
                    }
                    // 簽章後若電視不認得，會再送一次 TOKEN；下一輪送公鑰
                    val next = receive()
                    when (next.command) {
                        A_CNXN -> return
                        A_AUTH -> {
                            send(A_AUTH, AUTH_RSAPUBLICKEY, 0, key.adbPublicKey() + 0)
                            sentPubKey = true
                            // 等使用者在電視上按「允許」
                            socket.soTimeout = 60_000
                            val after = receive()
                            socket.soTimeout = timeoutMs
                            if (after.command == A_CNXN) return
                            throw AdbException("授權未完成")
                        }
                        else -> throw AdbException("未預期的回應 0x${next.command.toString(16)}")
                    }
                }
                else -> throw AdbException("未預期的回應 0x${m.command.toString(16)}")
            }
        }
    }

    /** 執行一行 shell 指令，回傳輸出。 */
    fun shell(command: String): String {
        val localId = nextLocalId++
        send(A_OPEN, localId, 0, ("shell:$command\u0000").toByteArray())

        var remoteId = 0
        val out = StringBuilder()
        while (true) {
            val m = receive()
            when (m.command) {
                A_OKAY -> remoteId = m.arg0
                A_WRTE -> {
                    out.append(String(m.payload))
                    send(A_OKAY, localId, m.arg0, ByteArray(0))
                }
                A_CLSE -> {
                    send(A_CLSE, localId, m.arg0, ByteArray(0))
                    return out.toString()
                }
                else -> { /* 忽略 */ }
            }
            if (remoteId == 0 && m.command == A_OKAY) remoteId = m.arg0
        }
    }

    override fun close() {
        runCatching { socket.close() }
    }

    // ── 協定訊息 ──

    private class Message(val command: Int, val arg0: Int, val arg1: Int, val payload: ByteArray)

    private fun send(command: Int, arg0: Int, arg1: Int, payload: ByteArray) {
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(command)
        header.putInt(arg0)
        header.putInt(arg1)
        header.putInt(payload.size)
        header.putInt(checksum(payload))
        header.putInt(command.inv())
        output.write(header.array())
        if (payload.isNotEmpty()) output.write(payload)
        output.flush()
    }

    private fun receive(): Message {
        val header = ByteArray(24)
        input.readFully(header)
        val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val command = b.getInt(0)
        val arg0 = b.getInt(4)
        val arg1 = b.getInt(8)
        val length = b.getInt(12)
        val payload = if (length > 0) ByteArray(length).also { input.readFully(it) } else ByteArray(0)
        return Message(command, arg0, arg1, payload)
    }

    private fun checksum(payload: ByteArray): Int {
        var sum = 0
        for (byte in payload) sum += byte.toInt() and 0xFF
        return sum
    }

    private companion object {
        const val VERSION = 0x01000000
        const val MAX_DATA = 256 * 1024

        val A_CNXN = tag('C', 'N', 'X', 'N')
        val A_AUTH = tag('A', 'U', 'T', 'H')
        val A_OPEN = tag('O', 'P', 'E', 'N')
        val A_OKAY = tag('O', 'K', 'A', 'Y')
        val A_WRTE = tag('W', 'R', 'T', 'E')
        val A_CLSE = tag('C', 'L', 'S', 'E')

        const val AUTH_TOKEN = 1
        const val AUTH_SIGNATURE = 2
        const val AUTH_RSAPUBLICKEY = 3

        fun tag(a: Char, b: Char, c: Char, d: Char): Int =
            a.code or (b.code shl 8) or (c.code shl 16) or (d.code shl 24)
    }
}
