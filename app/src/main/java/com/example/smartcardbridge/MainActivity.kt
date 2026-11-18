package com.example.smartcardbridge

import android.app.Activity
import android.app.PendingIntent
import android.content.*
import android.graphics.Color
import android.hardware.usb.*
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var usbManager: UsbManager
    private lateinit var statusText: TextView
    private var hasLaunchedBrowser = false
    private val ACTION_USB_PERMISSION = "com.example.smartcardbridge.USB_PERMISSION"
    private val KIOSK_URL = "https://www.miniheealthstation.com"

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
            text = "🟢 JHCIS Card Bridge ทำงานเบื้องหลังแล้ว\n(ป้องกันการปัดแอปทิ้ง 100%)"
        }

        val openWebBtn = Button(this).apply {
            text = "🌐 เปิดเว็บ Mini Health Station ทันที"
            textSize = 16f
            setBackgroundColor(Color.parseColor("#10B981"))
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

        // สั่งรัน Foreground Service ทันที
        val serviceIntent = Intent(this, BridgeService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        // ขอสิทธิ์แสดงแจ้งเตือนบน Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 100)
        }

        // ขอปลดล็อกการประหยัดพลังงานของ Samsung ไม่ให้ตัดการทำงานเบื้องหลัง
        requestIgnoreBatteryOptimization()

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }

        checkUsbAndAutoLaunch()
    }

    private fun requestIgnoreBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {}
            }
        }
    }

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
                openWebKiosk()
            } else {
                statusText.text = "🔌 พบเครื่องอ่านบัตร!\nกรุณากด 'ตกลง' บนหน้าต่างขอสิทธิ์ USB..."
                val permissionIntent = Intent(ACTION_USB_PERMISSION).apply { setPackage(packageName) }
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                val pi = PendingIntent.getBroadcast(this, 0, permissionIntent, flags)
                usbManager.requestPermission(device, pi)
            }
        }
    }

    private fun openWebKiosk() {
        if (hasLaunchedBrowser) return
        hasLaunchedBrowser = true

        try {
            val chromeIntent = Intent(Intent.ACTION_VIEW, Uri.parse(KIOSK_URL)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                setPackage("com.android.chrome")
            }
            startActivity(chromeIntent)
        } catch (e: Exception) {
            val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse(KIOSK_URL)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(fallbackIntent)
        }
        // ปิดหน้าต่าง Activity ลงไปเหลือแต่ Service เบื้องหลังเพื่อไม่ให้เกะกะหน้าจอ
        finish()
    }

    override fun onDestroy() {
        try { unregisterReceiver(usbReceiver) } catch (e: Exception) {}
        super.onDestroy()
    }
}