package com.akillikumanda.tv

import android.content.Context
import android.content.Intent
import android.net.Uri
import fi.iki.elonen.NanoHTTPD
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.net.InetSocketAddress

/**
 * DZ Browser mantığıyla çalışan Gömülü HTTP + WebSocket Sunucusu:
 * - Port 8080: Telefonda QR okutulduğunda Kumanda Web Arayüzünü (remote.html) sunar.
 * - Port 8081: Telefon ile TV arasında çift yönlü gerçek zamanlı iletişim kurar
 *   (Uygulama açma, özel butonlara basma, Kick takip edilen kanalları/altyazıları telefona gönderme).
 */
class RemoteWebServer(
    private val context: Context,
    httpPort: Int = 8080,
    wsPort: Int = 8081,
    private val onClientCountChanged: (Int) -> Unit
) : NanoHTTPD(httpPort) {

    companion object {
        private var wsServerInstance: RemoteSocketServer? = null

        fun broadcastJson(json: JSONObject) {
            wsServerInstance?.broadcast(json.toString())
        }

        fun broadcastStatus(type: String, message: String, accessibilityEnabled: Boolean) {
            val obj = JSONObject().apply {
                put("type", type)
                put("message", message)
                put("accessibilityEnabled", accessibilityEnabled)
            }
            broadcastJson(obj)
        }
    }

    // Desteklenen 14 Uygulamanın Android TV Paket İsimleri
    private val appPackages = mapOf(
        "youtube" to listOf("com.google.android.youtube.tv", "com.google.android.youtube"),
        "smarttube" to listOf("com.liskovsoft.smarttubetv.beta", "com.liskovsoft.smarttubetv"),
        "netflix" to listOf("com.netflix.ninja", "com.netflix.mediaclient"),
        "prime" to listOf("com.amazon.amazonvideo.livingroom", "com.amazon.avod.thirdpartyclient"),
        "disney" to listOf("com.disney.disneyplus"),
        "exxen" to listOf("com.exxen.android"),
        "blutv" to listOf("com.blutv.android", "com.wbd.stream"),
        "tod" to listOf("com.beinsports.andtv", "com.digiturk.iq.mobil"),
        "tabii" to listOf("com.trt.tabii.android"),
        "kick" to listOf("com.kick.mobile", "tv.kick.android"),
        "twitch" to listOf("tv.twitch.android.viewer", "tv.twitch.android.app"),
        "spotify" to listOf("com.spotify.tv.android", "com.spotify.music"),
        "stremio" to listOf("com.stremio.one"),
        "tivimate" to listOf("ar.tvplayer.tv", "com.nst.iptvsmarterstvbox")
    )

    init {
        wsServerInstance = RemoteSocketServer(wsPort)
    }

    override fun start() {
        super.start(SOCKET_READ_TIMEOUT, false)
        wsServerInstance?.start()
    }

    override fun stop() {
        super.stop()
        try {
            wsServerInstance?.stop()
        } catch (_: Exception) {}
    }

    override fun serve(session: IHTTPSession): Response {
        val html = context.assets.open("remote.html").bufferedReader().use { it.readText() }
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }

    private fun launchTvApp(appKey: String): JSONObject {
        val pm = context.packageManager
        val candidates = appPackages[appKey.lowercase()] ?: emptyList()

        for (pkg in candidates) {
            val intent = pm.getLaunchIntentForPackage(pkg)
                ?: pm.getLeanbackLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return JSONObject().apply {
                    put("status", "ok")
                    put("type", "app_launched")
                    put("app", appKey)
                    put("message", "📺 ${appKey.uppercase()} TV'de başlatıldı, özel kumandası aktif!")
                }
            }
        }

        // Yüklü değilse web/deep-link yedeği veya uyarı
        return JSONObject().apply {
            put("status", "warning")
            put("type", "app_launched")
            put("app", appKey)
            put("message", "ℹ️ ${appKey.uppercase()} kumandası telefonda açıldı (Uygulama TV'de bulunamadıysa Play Store'dan yüklenebilir)")
        }
    }

    inner class RemoteSocketServer(port: Int) : WebSocketServer(InetSocketAddress(port)) {

        override fun onOpen(conn: WebSocket, handshake: ClientHandshake?) {
            onClientCountChanged(connections.size)
            conn.send(JSONObject().apply {
                put("type", "welcome")
                put("accessibilityEnabled", SmartRemoteAccessibilityService.isRunning())
                put("message", "TV'ye başarıyla bağlandı!")
            }.toString())
        }

        override fun onClose(conn: WebSocket?, code: Int, reason: String?, remote: Boolean) {
            onClientCountChanged(connections.size)
        }

        override fun onMessage(conn: WebSocket, message: String) {
            try {
                val req = JSONObject(message)
                val cmdType = req.optString("type")

                when (cmdType) {
                    "LAUNCH_APP" -> {
                        val appKey = req.optString("app")
                        val res = launchTvApp(appKey)
                        conn.send(res.toString())
                    }
                    "SMART_ACTION" -> {
                        val action = req.optString("action")
                        val payload = req.optString("payload", "")
                        val svc = SmartRemoteAccessibilityService.instance
                        if (svc == null) {
                            conn.send(JSONObject().apply {
                                put("status", "error")
                                put("type", "permission_required")
                                put("message", "⚠️ TV'de Erişilebilirlik İzni kapalı! Lütfen TV ekranındaki uyarıdan izni aktif edin.")
                            }.toString())
                        } else {
                            val result = svc.executeSmartAction(action, payload)
                            conn.send(result.toString())
                        }
                    }
                }
            } catch (e: Exception) {
                conn.send(JSONObject().apply {
                    put("status", "error")
                    put("message", "Hata: ${e.localizedMessage}")
                }.toString())
            }
        }

        override fun onError(conn: WebSocket?, ex: Exception?) {}
        override fun onStart() {}
    }
}
