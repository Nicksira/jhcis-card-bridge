package com.example.smartcardbridge

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.hardware.usb.*
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
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
    private var hasLaunchedBrowser = false
    private var serverSocket: ServerSocket? = null
    private val ACTION_USB_PERMISSION = "com.example.smartcardbridge.USB_PERMISSION"
    private val KIOSK_URL = "https://www.miniheealthstation.com"

    // ดักจับจังหวะที่ผู้ใช้กดปุ่ม "ตกลง" อนุญาตสิทธิ์ USB บนหน้าจอ
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                val device = findSmartCardDevice()
                if (device != null && usbManager.hasPermission(device)) {
                    openWebKiosk()
                } else {
                    statusText.text = "⚠️ ไม่ได้รับอนุญาตให้เข้าถึง USB\nกรุณาถอดสายแล้วเสียบใหม่อีกครั้ง"
                }
            }
        }
    }

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
            text = "🟢 JHCIS Card Bridge (Port 8189)\nกำลังตรวจสอบเครื่องอ่านบัตร..."
        }

        val openWebBtn = Button(this).apply {
            text = "🌐 เปิดเว็บ Mini Health Station ทันที"
            textSize = 16f
            setBackgroundColor(Color.parseColor("#2563EB"))
            setTextColor(Color.WHITE)
            setPadding(40, 25, 40, 25)
            setOnClickListener {
                hasLaunchedBrowser = false
                openWebKiosk()
            }
        }

        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 40 }

        layout.addView(statusText)
        layout.addView(openWebBtn, params)
        setContentView(layout)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        // ลงทะเบียนตัวรับสัญญาณการกดอนุญาต USB (รองรับ Android 8 - 14+)
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }

        startLocalServer()
        checkUsbAndAutoLaunch()
    }

    // กรณีแอปเปิดอยู่แล้วในพื้นหลัง แล้วมีการถอด-เสียบสาย OTG ใหม่
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        hasLaunchedBrowser = false
        checkUsbAndAutoLaunch()
    }

    private fun findSmartCardDevice(): UsbDevice? {
        for (device in usbManager.deviceList.values) {
            val isSmartCard = (0 until device.interfaceCount).any {
                device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_CSCID
            }
            if (isSmartCard || device.deviceClass == UsbConstants.USB_CLASS_CSCID) {
                return device
            }
        }
        return null
    }

    private fun checkUsbAndAutoLaunch() {
        val device = findSmartCardDevice()
        if (device != null) {
            if (usbManager.hasPermission(device)) {
                // ถ้าเคยติ๊กจำสิทธิ์ไว้แล้ว ให้เด้งเปิดเว็บทันที
                openWebKiosk()
            } else {
                statusText.text = "🔌 พบเครื่องอ่านบัตร!\nกรุณากด 'ตกลง' บนหน้าต่างขอสิทธิ์ USB..."
                val permissionIntent = Intent(ACTION_USB_PERMISSION).apply {
                    setPackage(packageName)
                }
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                val pi = PendingIntent.getBroadcast(this, 0, permissionIntent, flags)
                usbManager.requestPermission(device, pi)
            }
        } else {
            statusText.text = "🟢 JHCIS Card Bridge (Port 8189)\nรอเสียบสายเครื่องอ่านบัตร ACR39U / SCR-N88..."
        }
    }

    private fun openWebKiosk() {
        if (hasLaunchedBrowser) return
        hasLaunchedBrowser = true

        runOnUiThread {
            statusText.text = "✅ เชื่อมต่อเครื่องอ่านบัตรพร้อมใช้งาน!\nกำลังเปิดเว็บ www.miniheealthstation.com..."
        }

        try {
            // บังคับเปิดด้วยแอป Google Chrome เป็นหลัก
            val chromeIntent = Intent(Intent.ACTION_VIEW, Uri.parse(KIOSK_URL)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                setPackage("com.android.chrome")
            }
            startActivity(chromeIntent)
        } catch (e: Exception) {
            // หากเครื่องไม่มี Chrome ให้เปิดด้วยเบราว์เซอร์เริ่มต้นแทน
            val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse(KIOSK_URL)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(fallbackIntent)
        }
    }

    private fun readCidFromHardware(): String? {
        val device = findSmartCardDevice() ?: return null
        if (!usbManager.hasPermission(device)) {
            runOnUiThread { checkUsbAndAutoLaunch() }
            return null
        }

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
                                val jsonBody = if (cid != null) "{\"pid\":\"$cid\"}" else "{}"
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
                runOnUiThread { statusText.text = "Port 8189 Error: ${e.message}" }
            }
        }
    }

    override fun onDestroy() {
        isRunning = false
        try { unregisterReceiver(usbReceiver) } catch (e: Exception) {}
        serverSocket?.close()
        super.onDestroy()
    }
}