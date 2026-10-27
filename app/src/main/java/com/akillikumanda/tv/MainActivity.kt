package com.akillikumanda.tv

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.wifi.WifiManager
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {

    private var webServer: RemoteWebServer? = null
    private lateinit var statusBadge: TextView
    private lateinit var clientCountText: TextView
    private lateinit var permissionButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val ipAddress = getLocalIpAddress()
        val remoteUrl = "http://$ipAddress:8080"

        // Android TV Ekran Arayüzü (Programatik, Ekstra XML bağımlılığı olmadan temiz kurulum)
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#0B0F19"))
            gravity = Gravity.CENTER
            setPadding(64, 48, 64, 48)
        }

        // Sol Panel: Başlık, Durum, Erişilebilirlik İzni Yönlendirme Butonu
        val leftColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f).apply {
                marginEnd = 48
            }
        }

        val titleView = TextView(this).apply {
            text = "📱 Akıllı TV Kumanda"
            textSize = 32f
            setTextColor(Color.WHITE)
        }

        val subtitleView = TextView(this).apply {
            text = "Yandaki QR kodu telefonunuzun kamerasıyla okutun.\nUygulama indirmeden YouTube, Netflix, Kick, Twitch, Stremio ve daha fazlasını özel tuşlarla doğrudan yönetin."
            textSize = 16f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 16, 0, 24)
        }

        statusBadge = TextView(this).apply {
            textSize = 16f
            setPadding(24, 16, 24, 16)
        }

        clientCountText = TextView(this).apply {
            text = "🔌 Bağlı Telefon: 0 (QR okutulması bekleniyor)"
            textSize = 16f
            setTextColor(Color.parseColor("#38BDF8"))
            setPadding(0, 16, 0, 24)
        }

        permissionButton = Button(this).apply {
            text = "⚙️ Erişilebilirlik İznini Aç (Zorunlu)"
            textSize = 15f
            setBackgroundColor(Color.parseColor("#6366F1"))
            setTextColor(Color.WHITE)
            setPadding(32, 20, 32, 20)
            setOnClickListener {
                openAccessibilitySettingsWithGuide()
            }
        }

        leftColumn.addView(titleView)
        leftColumn.addView(subtitleView)
        leftColumn.addView(statusBadge)
        leftColumn.addView(clientCountText)
        leftColumn.addView(permissionButton)

        // Sağ Panel: QR Kod Kartı + IP Adresi
        val rightColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#1E293B"))
            setPadding(40, 40, 40, 40)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val qrImageView = ImageView(this).apply {
            setImageBitmap(generateQrCodeBitmap(remoteUrl, 420))
        }

        val urlText = TextView(this).apply {
            text = remoteUrl
            textSize = 18f
            setTextColor(Color.parseColor("#F8FAFC"))
            gravity = Gravity.CENTER
            setPadding(0, 20, 0, 0)
        }

        rightColumn.addView(qrImageView)
        rightColumn.addView(urlText)

        rootLayout.addView(leftColumn)
        rootLayout.addView(rightColumn)
        setContentView(rootLayout)

        // Gömülü HTTP (8080) ve WebSocket (8081) sunucusunu başlat
        try {
            webServer = RemoteWebServer(this, 8080, 8081) { count ->
                runOnUiThread {
                    clientCountText.text = if (count > 0) {
                        "🟢 Bağlı Telefon Sayısı: $count (Kumanda Aktif)"
                    } else {
                        "🔌 Bağlı Telefon: 0 (QR okutulması bekleniyor)"
                    }
                }
            }
            webServer?.start()
        } catch (e: Exception) {
            Toast.makeText(this, "Sunucu başlatılamadı: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        checkAndPromptAccessibilityPermission()
    }

    /**
     * Erişilebilirlik Servisi açık mı kontrol eder; kapalıysa otomatik olarak uyarı penceresi açıp
     * kullanıcıyı doğrudan Android TV Erişilebilirlik Ayarlarına yönlendirir.
     */
    private fun checkAndPromptAccessibilityPermission() {
        val enabled = isAccessibilityServiceEnabled(this, SmartRemoteAccessibilityService::class.java)
        if (enabled) {
            statusBadge.text = "✅ Erişilebilirlik Servisi Aktif (Tüm Özel Tuşlar ve Kanal Okuma Hazır)"
            statusBadge.setBackgroundColor(Color.parseColor("#064E3B"))
            statusBadge.setTextColor(Color.parseColor("#6EE7B7"))
            permissionButton.text = "✅ Erişilebilirlik İzni Verildi (Ayarları Gör)"
        } else {
            statusBadge.text = "⚠️ Erişilebilirlik Servisi Kapalı! (Navigasyonsuz özel tuşlar için izin gerekiyor)"
            statusBadge.setBackgroundColor(Color.parseColor("#7F1D1D"))
            statusBadge.setTextColor(Color.parseColor("#FCA5A5"))
            permissionButton.text = "⚙️ Erişilebilirlik İznini Şimdi Aç"
            permissionButton.requestFocus()

            // Otomatik Uyarı ve Yönlendirme Diyaloğu
            AlertDialog.Builder(this)
                .setTitle("🔓 Erişilebilirlik İzni Gerekli")
                .setMessage(
                    "Netflix'te 'İntroyu Atla', YouTube'da 'Abone Ol / Beğen' ve Kick'te 'Takip Edilen Kanalları Telefona Getir' " +
                    "özelliklerinin yön tuşları olmadan doğrudan çalışabilmesi için Erişilebilirlik Servisini aktif etmeniz gerekir.\n\n" +
                    "Adımlar:\n" +
                    "1. 'Ayarlara Git' butonuna basın.\n" +
                    "2. 'İndirilen Uygulamalar / Servisler' altında 'Akıllı TV Kumanda Servisi'ni seçin.\n" +
                    "3. Servisi 'Açık' konuma getirin."
                )
                .setPositiveButton("Ayarlara Git ve Aç") { _, _ ->
                    openAccessibilitySettingsWithGuide()
                }
                .setNegativeButton("Daha Sonra", null)
                .show()
        }
    }

    private fun openAccessibilitySettingsWithGuide() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            Toast.makeText(
                this,
                "Listeden 'Akıllı TV Kumanda Servisi'ni bulup AÇIK konuma getirin",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            val fallbackIntent = Intent(Settings.ACTION_SETTINGS)
            startActivity(fallbackIntent)
        }
    }

    private fun isAccessibilityServiceEnabled(context: Context, serviceClass: Class<*>): Boolean {
        val expectedComponentName = "${context.packageName}/${serviceClass.canonicalName}"
        val enabledServicesSetting = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServicesSetting)
        while (colonSplitter.hasNext()) {
            val componentName = colonSplitter.next()
            if (componentName.equals(expectedComponentName, ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    private fun generateQrCodeBitmap(content: String, size: Int): Bitmap {
        val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bmp.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bmp
    }

    private fun getLocalIpAddress(): String {
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ipInt = wifiManager.connectionInfo.ipAddress
            if (ipInt != 0) {
                return String.format(
                    "%d.%d.%d.%d",
                    ipInt and 0xff,
                    ipInt shr 8 and 0xff,
                    ipInt shr 16 and 0xff,
                    ipInt shr 24 and 0xff
                )
            }
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: "192.168.1.100"
                    }
                }
            }
        } catch (_: Exception) {}
        return "192.168.1.100"
    }

    override fun onDestroy() {
        super.onDestroy()
        webServer?.stop()
    }
}
