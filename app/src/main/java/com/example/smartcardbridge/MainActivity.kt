package com.example.smartcardbridge

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.*
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var usbManager: UsbManager
    private lateinit var statusText: TextView
    private var isRunning = true
    private var serverSocket: ServerSocket? = null
    private val ACTION_USB_PERMISSION = "com.example.smartcardbridge.USB_PERMISSION"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(40, 40, 40, 40)
        }
        statusText = TextView(this).apply {
            textSize = 20f
            gravity = Gravity.CENTER
            text = "🟢 JHCIS Card Bridge (Port 8189)\nกำลังรอเครื่องอ่านบัตร..."
        }
        layout.addView(statusText)
        setContentView(layout)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        checkAndRequestUsbPermission()
        startLocalServer()
    }

    private fun checkAndRequestUsbPermission(): UsbDevice? {
        for (device in usbManager.deviceList.values) {
            val isSmartCard = (0 until device.interfaceCount).any {
                device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_CSCID
            }
            if (isSmartCard || device.deviceClass == UsbConstants.USB_CLASS_CSCID) {
                if (!usbManager.hasPermission(device)) {
                    val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                    val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), flags)
                    usbManager.requestPermission(device, pi)
                }
                return device
            }
        }
        return null
    }

    private fun readCidFromHardware(): String? {
        val device = checkAndRequestUsbPermission() ?: return null
        if (!usbManager.hasPermission(device)) return null

        val conn = usbManager.openDevice(device) ?: return null
        val intf = (0 until device.interfaceCount)
            .map { device.getInterface(it) }
            .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_CSCID }
            ?: device.getInterface(0)

        if (!conn.claimInterface(intf, true)) {
            conn.close()
            return null
        }

        var epIn: UsbEndpoint? = null
        var epOut: UsbEndpoint? = null
        for (i in 0 until intf.endpointCount) {
            val ep = intf.getEndpoint(i)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep
                else epOut = ep
            }
        }

        if (epIn == null || epOut == null) {
            conn.releaseInterface(intf)
            conn.close()
            return null
        }

        var seq: Byte = 0
        fun sendCcidApdu(apdu: ByteArray): ByteArray? {
            val header = byteArrayOf(
                0x6F, (apdu.size and 0xFF).toByte(), ((apdu.size shr 8) and 0xFF).toByte(),
                0x00, 0x00, 0x00, seq++, 0x00, 0x00, 0x00
            )
            val packet = header + apdu
            if (conn.bulkTransfer(epOut, packet, packet.size, 1500) < 0) return null

            val buf = ByteArray(256)
            for (retry in 0..4) {
                val len = conn.bulkTransfer(epIn, buf, buf.size, 1500)
                if (len >= 10) {
                    val bStatus = buf[7].toInt() and 0xFF
                    val timeExt = (bStatus shr 6) and 0x03
                    if (timeExt != 2) return buf.copyOf(len)
                }
            }
            return null
        }

        try {
            val powerOn = byteArrayOf(0x62, 0x00, 0x00, 0x00, 0x00, 0x00, seq++, 0x00, 0x00, 0x00)
            conn.bulkTransfer(epOut, powerOn, powerOn.size, 1000)
            val atrBuf = ByteArray(128)
            val atrLen = conn.bulkTransfer(epIn, atrBuf, atrBuf.size, 1000)
            if (atrLen < 10 || ((atrBuf[7].toInt() and 0x03) == 2)) {
                return null
            }

            sendCcidApdu(byteArrayOf(
                0x00, 0xA4.toByte(), 0x04, 0x00, 0x08,
                0xA0.toByte(), 0x00, 0x00, 0x00, 0x54, 0x48, 0x00, 0x01
            )) ?: return null

            var resp = sendCcidApdu(byteArrayOf(
                0x80.toByte(), 0xB0.toByte(), 0x00, 0x04, 0x02, 0x00, 0x0D
            )) ?: return null

            if (resp.size < 23) {
                resp = sendCcidApdu(byteArrayOf(0x00, 0xC0.toByte(), 0x00, 0x00, 0x0D)) ?: return null
            }

            if (resp.size >= 23) {
                val cid = String(resp, 10, 13, Charsets.US_ASCII).replace(Regex("[^0-9]"), "")
                if (cid.length == 13) return cid
            }
            return null
        } finally {
            conn.releaseInterface(intf)
            conn.close()
        }
    }

    private fun startLocalServer() {
        thread(start = true) {
            try {
                serverSocket = ServerSocket(8189)
                while (isRunning) {
                    val client = serverSocket?.accept() ?: break
                    thread(start = true) {
                        try {
                            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                            val requestLine = reader.readLine() ?: ""

                            var line: String?
                            while (reader.readLine().also { line = it } != null) {
                                if (line.isNullOrEmpty()) break
                            }

                            val out = client.getOutputStream()
                            if (requestLine.startsWith("OPTIONS")) {
                                val preflight = "HTTP/1.1 204 No Content\r\n" +
                                        "Access-Control-Allow-Origin: *\r\n" +
                                        "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                                        "Access-Control-Allow-Headers: *\r\n" +
                                        "Access-Control-Allow-Private-Network: true\r\n\r\n"
                                out.write(preflight.toByteArray())
                            } else if (requestLine.contains("/api/smartcard/read")) {
                                val cid = readCidFromHardware()
                                val jsonBody = if (cid != null) """{"pid":"$cid"}""" else """\{\}"""
                                runOnUiThread {
                                    statusText.text = if (cid != null) "✅ อ่านบัตรสำเร็จ: $cid" else "⏳ รอเสียบบัตรประชาชน..."
                                }
                                val response = "HTTP/1.1 200 OK\r\n" +
                                        "Content-Type: application/json; charset=utf-8\r\n" +
                                        "Access-Control-Allow-Origin: *\r\n" +
                                        "Access-Control-Allow-Private-Network: true\r\n" +
                                        "Connection: close\r\n\r\n$jsonBody"
                                out.write(response.toByteArray(Charsets.UTF_8))
                            } else {
                                val okResp = "HTTP/1.1 200 OK\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Private-Network: true\r\n\r\n{\"status\":\"ok\"}"
                                out.write(okResp.toByteArray())
                            }
                            out.flush()
                        } catch (e: Exception) {
                            e.printStackTrace()
                        } finally {
                            client.close()
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { statusText.text = "❌ พอร์ต 8189 ถูกใช้งานอยู่: ${e.message}" }
            }
        }
    }

    override fun onDestroy() {
        isRunning = false
        serverSocket?.close()
        super.onDestroy()
    }
}
