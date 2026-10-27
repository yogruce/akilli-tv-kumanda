package com.akillikumanda.tv

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

class SmartRemoteAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: SmartRemoteAccessibilityService? = null
            private set

        fun isRunning(): Boolean = instance != null
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        RemoteWebServer.broadcastStatus(
            type = "service_status",
            message = "Erişilebilirlik Servisi Aktif",
            accessibilityEnabled = true
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // İsteğe bağlı: Ekranda "İntroyu Atla" gibi bir buton belirdiğinde telefona anlık bildirim gönderilebilir
        val root = rootInActiveWindow ?: return
        val pkg = event?.packageName?.toString() ?: ""
        if (pkg.contains("netflix") || pkg.contains("disney") || pkg.contains("amazon")) {
            val skipNode = findNodeByKeywords(root, listOf("İntroyu Atla", "Skip Intro", "Özeti Atla", "Skip Recap"))
            if (skipNode != null) {
                RemoteWebServer.broadcastJson(JSONObject().apply {
                    put("type", "smart_hint")
                    put("action", "SKIP_INTRO")
                    put("label", "⏭️ 'İntroyu Atla' ekranda belirdi! Dokun ve atla.")
                })
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    /**
     * Telefondan gelen uygulamaya özgü aksiyonları (navigasyon yapmadan) doğrudan çalıştırır
     * ve sonucu çift yönlü geri bildirim olarak telefona döner.
     */
    fun executeSmartAction(action: String, payload: String = ""): JSONObject {
        val response = JSONObject()
        response.put("action", action)

        val root = rootInActiveWindow
        when (action) {
            // --- ORTAK SİSTEM & MEDYA KOMUTLARI ---
            "DPAD_UP" -> sendMediaOrSystemKey(KeyEvent.KEYCODE_DPAD_UP, "Yukarı")
            "DPAD_DOWN" -> sendMediaOrSystemKey(KeyEvent.KEYCODE_DPAD_DOWN, "Aşağı")
            "DPAD_LEFT" -> sendMediaOrSystemKey(KeyEvent.KEYCODE_DPAD_LEFT, "Sol")
            "DPAD_RIGHT" -> sendMediaOrSystemKey(KeyEvent.KEYCODE_DPAD_RIGHT, "Sağ")
            "DPAD_CENTER" -> sendMediaOrSystemKey(KeyEvent.KEYCODE_DPAD_CENTER, "OK")
            "BACK" -> {
                performGlobalAction(GLOBAL_ACTION_BACK)
                return feedbackOk(action, "Geri tuşu gönderildi")
            }
            "HOME" -> {
                performGlobalAction(GLOBAL_ACTION_HOME)
                return feedbackOk(action, "Ana ekrana dönüldü")
            }
            "VOL_UP" -> adjustVolume(AudioManager.ADJUST_RAISE, "Ses artırıldı")
            "VOL_DOWN" -> adjustVolume(AudioManager.ADJUST_LOWER, "Ses azaltıldı")
            "VOL_MUTE" -> adjustVolume(AudioManager.ADJUST_TOGGLE_MUTE, "Ses durumu değiştirildi")
            "PLAY_PAUSE" -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, "Oynat / Duraklat")
            "MEDIA_NEXT" -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT, "Sonraki içerik")
            "MEDIA_PREV" -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS, "Önceki içerik")
            "SEEK_FWD_10" -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, "10 sn ileri sarıldı")
            "SEEK_BWD_10" -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_REWIND, "10 sn geri sarıldı")

            // --- YOUTUBE & SMARTTUBE ÖZEL TUŞLARI ---
            "YT_SUBSCRIBE" -> return clickByKeywords(
                root,
                listOf("Abone ol", "Subscribe", "Abone olundu"),
                "✅ Abone Ol butonuna basıldı",
                "⚠️ Ekranda 'Abone Ol' butonu bulunamadı (Video detaylarını açmayı deneyin)"
            )
            "YT_LIKE" -> return clickByKeywords(
                root,
                listOf("Beğen", "Like", "beğendim", "button_like"),
                "👍 Video beğenildi",
                "⚠️ Beğen butonu görünür değil"
            )
            "YT_DISLIKE" -> return clickByKeywords(
                root,
                listOf("Beğenme", "Dislike", "button_dislike"),
                "👎 Beğenmeme tuşuna basıldı",
                "⚠️ Beğenme butonu görünür değil"
            )
            "YT_COMMENTS" -> {
                val res = clickByKeywords(
                    root,
                    listOf("Yorumlar", "Comments", "Yorum", "comment"),
                    "💬 Yorumlar açıldı ve telefona aktarıldı",
                    "⚠️ Yorumlar butonu bulunamadı"
                )
                return appendScreenItemsToResponse(res, "YT_COMMENTS", "Ekrandaki Yorumlar & Videolar")
            }
            "YT_QUALITY" -> return clickByKeywords(
                root,
                listOf("Kalite", "Quality", "Ayarlar", "Settings", "HD", "4K"),
                "⚙️ Kalite / Ayarlar menüsü açıldı",
                "⚠️ Ayarlar ikonu görünür değil"
            )
            "YT_SPEED" -> return clickByKeywords(
                root,
                listOf("Oynatma hızı", "Playback speed", "Hız", "Speed", "1x", "1.25x", "1.5x"),
                "⚡ Oynatma hızı menüsü tetiklendi",
                "⚠️ Hız menüsü bulunamadı"
            )
            "YT_SPONSOR_SKIP" -> return clickByKeywords(
                root,
                listOf("Sponsoru Atla", "Skip Sponsor", "Atla", "Skip Ad", "Reklamı Atla"),
                "⏭️ Reklam / Sponsor bölümü atlandı!",
                "⚠️ Şu anda atlanabilir reklam/sponsor butonu yok"
            )

            // --- NETFLIX / DISNEY+ / PRIME / EXXEN / BLUTV ÖZEL TUŞLARI ---
            "SKIP_INTRO" -> return clickByKeywords(
                root,
                listOf("İntroyu Atla", "Skip Intro", "Özeti Atla", "Skip Recap", "Jeneriği Atla", "Atla"),
                "⏭️ İntro / Özet doğrudan atlandı!",
                "⚠️ Ekranda şu an 'İntroyu Atla' butonu yok"
            )
            "NEXT_EPISODE" -> return clickByKeywords(
                root,
                listOf("Sonraki Bölüm", "Next Episode", "Sıradaki Bölüm", "Bölümler", "Episodes"),
                "▶️ Sonraki bölüme geçildi!",
                "⚠️ Sonraki Bölüm butonu bulunamadı"
            )
            "SUBTITLES_AUDIO" -> {
                val clicked = clickByKeywords(
                    root,
                    listOf("Ses ve Altyazı", "Audio & Subtitles", "Altyazı", "Subtitles", "Dil Seçenekleri", "Audio", "CC"),
                    "💬 Altyazı ve Ses seçenekleri telefona getirildi!",
                    "💬 Ekrandaki mevcut seçenekler tarandı"
                )
                return appendScreenItemsToResponse(clicked, "SUBTITLES_LIST", "Altyazı & Ses Seçenekleri (Dokun ve Seç)")
            }
            "ADD_WATCHLIST" -> return clickByKeywords(
                root,
                listOf("Listeme Ekle", "My List", "İzleme Listesi", "Watchlist", "Favorilere Ekle"),
                "➕ İzleme listesine eklendi!",
                "⚠️ Listeme Ekle butonu bulunamadı"
            )
            "SWITCH_PROFILE" -> return clickByKeywords(
                root,
                listOf("Profil Değiştir", "Switch Profiles", "Kim İzliyor", "Profiller", "Profiles"),
                "👤 Profil seçimi açıldı ve telefona listelendi",
                "⚠️ Profil butonu ana menüde görünür değil"
            )

            // --- KICK & TWITCH ÖZEL TUŞLARI (ÇİFT YÖNLÜ KANAL LİSTESİ DAHİL) ---
            "KICK_FOLLOWED_CHANNELS", "TWITCH_FOLLOWED_CHANNELS" -> {
                // Önce Takip Edilenler sekmesine tıkla, ardından ekrandaki canlı kanalları okuyup telefona gönder!
                clickByKeywords(
                    root,
                    listOf("Takip Edilen", "Following", "Followed", "Canlı Kanallar", "Live Channels", "Gözat", "Browse"),
                    "⭐ Takip edilen kanallar açıldı",
                    "⭐ Ekrandaki kanallar okundu"
                )
                return scrapeAndReturnScreenList(
                    categoryTitle = "🔴 Canlı & Takip Edilen Kanallar (İzlemek İçin Dokun)",
                    filterHint = "STREAM_CHANNELS"
                )
            }
            "TOGGLE_CHAT" -> return clickByKeywords(
                root,
                listOf("Sohbeti Gizle", "Sohbeti Göster", "Hide Chat", "Show Chat", "Chat", "Sohbet", "Tam Ekran", "Full Screen"),
                "💬 Sohbet görünümü değiştirildi",
                "⚠️ Sohbet gizle/göster butonu bulunamadı"
            )
            "FOLLOW_CHANNEL" -> return clickByKeywords(
                root,
                listOf("Takip Et", "Follow", "Kalp"),
                "❤️ Kanal takip edildi!",
                "⚠️ Takip Et butonu bulunamadı"
            )
            "CREATE_CLIP" -> return clickByKeywords(
                root,
                listOf("Klip", "Clip", "Klip Al"),
                "🎬 Klip alma tetiklendi!",
                "⚠️ Klip butonu bulunamadı"
            )

            // --- SPOTIFY ÖZEL TUŞLARI ---
            "SPOTIFY_LIKE" -> return clickByKeywords(
                root,
                listOf("Beğen", "Like", "Kitaplığına ekle", "Save to Your Library", "Heart"),
                "💚 Şarkı beğenilenlere eklendi!",
                "⚠️ Beğen ikonu bulunamadı"
            )
            "SPOTIFY_SHUFFLE" -> return clickByKeywords(
                root,
                listOf("Karıştır", "Shuffle"),
                "🔀 Karışık çalma değiştirildi",
                "⚠️ Shuffle butonu bulunamadı"
            )
            "SPOTIFY_REPEAT" -> return clickByKeywords(
                root,
                listOf("Tekrarla", "Repeat"),
                "🔁 Tekrarlama modu değiştirildi",
                "⚠️ Repeat butonu bulunamadı"
            )
            "SPOTIFY_LYRICS" -> return clickByKeywords(
                root,
                listOf("Şarkı sözleri", "Lyrics"),
                "🎤 Şarkı sözleri açıldı",
                "⚠️ Şarkı sözü butonu bulunamadı"
            )
            "SPOTIFY_QUEUE" -> {
                clickByKeywords(root, listOf("Sıraya ekle", "Queue", "Sıradaki"), "📋 Sıradaki şarkılar", "")
                return scrapeAndReturnScreenList("🎵 Sıradaki Şarkılar & Çalma Listeleri", "SPOTIFY")
            }

            // --- STREMIO / PLEX / KODI / IPTV (TOD, TABİİ, TIVIMATE) ---
            "STREMIO_SOURCES" -> return scrapeAndReturnScreenList("🎬 Ekrandaki Kaynaklar / Akışlar (Seçmek İçin Dokun)", "SOURCES")
            "SUBTITLE_DELAY_PLUS" -> return clickByKeywords(
                root,
                listOf("+0.5", "+0.25", "Gecikme", "Delay", "Sync"),
                "⏱️ Altyazı +0.5 sn ileri alındı",
                "⚠️ Altyazı senkron menüsü açık değil"
            )
            "SUBTITLE_DELAY_MINUS" -> return clickByKeywords(
                root,
                listOf("-0.5", "-0.25", "Gecikme", "Delay"),
                "⏱️ Altyazı -0.5 sn geri alındı",
                "⚠️ Altyazı senkron menüsü açık değil"
            )
            "LIVE_TV_CHANNELS" -> {
                clickByKeywords(root, listOf("Kanallar", "TV Rehberi", "EPG", "Canlı TV", "Guide", "Channels"), "", "")
                return scrapeAndReturnScreenList("📺 Kanal Listesi & Yayın Akışı (Dokun ve Aç)", "TV_CHANNELS")
            }

            // --- TELEFONDAN YAZILAN METNİ (ARAMA / CHAT / YORUM) TV'YE GÖNDERME ---
            "SEND_TEXT" -> {
                if (root == null) return feedbackError(action, "TV ekranı okunamadı")
                val inputSuccess = injectTextIntoActiveOrSearchField(root, payload)
                return if (inputSuccess) {
                    feedbackOk(action, "⌨️ '$payload' TV'ye yazıldı ve gönderildi!")
                } else {
                    feedbackError(action, "⚠️ TV ekranında metin/arama kutusu bulunamadı")
                }
            }

            // --- TELEFONA GELEN LİSTEDEN BİR ÖĞEYE (KANAL, VİDEO, ALTYAZI) DOKUNULDUĞUNDA TV'DE TIKLA ---
            "CLICK_SCREEN_TEXT" -> {
                return clickByKeywords(
                    root,
                    listOf(payload),
                    "✅ '$payload' TV'de seçildi ve açılıyor!",
                    "⚠️ '$payload' artık ekranda görünmüyor"
                )
            }

            // --- GENEL EKRAN TARAMA (HERHANGİ BİR ANDA EKRANDAKİ TIKLANABİLİR ÖĞELERİ TELEFONA ÇEKME) ---
            "FETCH_SCREEN_ITEMS" -> {
                return scrapeAndReturnScreenList("📲 TV Ekranındaki Seçilebilir Öğeler", "ALL")
            }
        }

        return feedbackOk(action, "Komut işlendi: $action")
    }

    /**
     * Ekrandaki AccessibilityNodeInfo ağacını tarar ve verilen anahtar kelimelerden birini içeren
     * ilk öğeyi bulup (kendisi veya tıklanabilir üst ebeveyni üzerinden) doğrudan tıklar.
     */
    private fun clickByKeywords(
        root: AccessibilityNodeInfo?,
        keywords: List<String>,
        successMsg: String,
        failMsg: String
    ): JSONObject {
        if (root == null) {
            return feedbackError("NODE_SEARCH", "Erişilebilirlik penceresi hazır değil")
        }
        val target = findNodeByKeywords(root, keywords)
        if (target != null) {
            val clicked = performClickOnNodeOrParent(target)
            if (clicked) {
                return feedbackOk("CLICK", successMsg)
            }
        }
        return feedbackError("CLICK", failMsg)
    }

    private fun findNodeByKeywords(root: AccessibilityNodeInfo, keywords: List<String>): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val text = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            val viewId = node.viewIdResourceName ?: ""

            for (kw in keywords) {
                if (text.contains(kw, ignoreCase = true) ||
                    desc.contains(kw, ignoreCase = true) ||
                    viewId.contains(kw, ignoreCase = true)
                ) {
                    return node
                }
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun performClickOnNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 6) {
            if (current.isClickable) {
                current.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val res = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (res) return true
            }
            current = current.parent
            depth++
        }
        // Eğer tıklanabilir bayrağı yoksa koordinatına dokunma (Gesture) uygula
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (!rect.isEmpty && rect.centerX() > 0 && rect.centerY() > 0) {
            val path = Path().apply { moveTo(rect.centerX().toFloat(), rect.centerY().toFloat()) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
                .build()
            dispatchGesture(gesture, null, mainHandler)
            return true
        }
        return false
    }

    /**
     * ÇİFT YÖNLÜ GERİ BİLDİRİM:
     * TV ekranındaki mevcut kanal listesini (Kick/Twitch), altyazı seçeneklerini (Netflix/Stremio)
     * veya videoları okuyup JSON dizisi olarak telefona gönderir. Kullanıcı telefondan bastığında
     * CLICK_SCREEN_TEXT ile doğrudan o öğeyi açar!
     */
    private fun scrapeAndReturnScreenList(categoryTitle: String, filterHint: String): JSONObject {
        val root = rootInActiveWindow
        val items = JSONArray()
        val seen = mutableSetOf<String>()

        if (root != null) {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            while (queue.isNotEmpty() && items.length() < 30) {
                val node = queue.removeFirst()
                val rawText = (node.text?.toString() ?: node.contentDescription?.toString() ?: "").trim()

                if (rawText.length in 2..70 && !seen.contains(rawText.lowercase())) {
                    seen.add(rawText.lowercase())
                    val itemObj = JSONObject().apply {
                        put("title", rawText)
                        put("subtitle", if (node.isClickable || node.parent?.isClickable == true) "Dokun ve TV'de Aç" else "Ekranda Görünür")
                    }
                    items.put(itemObj)
                }

                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it) }
                }
            }
        }

        return JSONObject().apply {
            put("status", "ok")
            put("type", "screen_list")
            put("categoryTitle", categoryTitle)
            put("filterHint", filterHint)
            put("items", items)
            put("message", "📲 ${items.length()} öğe TV ekranından telefona aktarıldı")
        }
    }

    private fun appendScreenItemsToResponse(baseResponse: JSONObject, hint: String, title: String): JSONObject {
        val listData = scrapeAndReturnScreenList(title, hint)
        baseResponse.put("type", "screen_list")
        baseResponse.put("categoryTitle", title)
        baseResponse.put("items", listData.optJSONArray("items"))
        return baseResponse
    }

    private fun injectTextIntoActiveOrSearchField(root: AccessibilityNodeInfo, text: String): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isEditable || node.className?.toString()?.contains("EditText") == true) {
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                if (ok) return true
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return false
    }

    private fun adjustVolume(direction: Int, label: String): JSONObject {
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return JSONObject().apply {
            put("status", "ok")
            put("message", "🔊 $label (Ses: $current / $max)")
            put("volume", current)
            put("maxVolume", max)
        }
    }

    private fun sendMediaKey(keyCode: Int, label: String): JSONObject {
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val downEvent = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
        val upEvent = KeyEvent(KeyEvent.ACTION_UP, keyCode)
        audioManager.dispatchMediaKeyEvent(downEvent)
        audioManager.dispatchMediaKeyEvent(upEvent)
        return feedbackOk("MEDIA_KEY", "⏯️ $label")
    }

    private fun sendMediaOrSystemKey(keyCode: Int, label: String): JSONObject {
        // Odaklanmış öğe üzerinde D-Pad hareketi veya tıklama
        val root = rootInActiveWindow
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER && root != null) {
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            if (focused != null) {
                performClickOnNodeOrParent(focused)
                return feedbackOk("DPAD", "OK ($label)")
            }
        }
        return feedbackOk("DPAD", "Yön tuşu: $label")
    }

    private fun feedbackOk(action: String, message: String) = JSONObject().apply {
        put("status", "ok")
        put("action", action)
        put("message", message)
    }

    private fun feedbackError(action: String, message: String) = JSONObject().apply {
        put("status", "warning")
        put("action", action)
        put("message", message)
    }
}
