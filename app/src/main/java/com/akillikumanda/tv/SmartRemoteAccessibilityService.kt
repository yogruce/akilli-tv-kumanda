package com.akillikumanda.tv

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
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

        // Android 13+ (API 33+) Sistem D-Pad Global Action Sabitleri
        private const val ACTION_DPAD_UP = 16
        private const val ACTION_DPAD_DOWN = 17
        private const val ACTION_DPAD_LEFT = 18
        private const val ACTION_DPAD_RIGHT = 19
        private const val ACTION_DPAD_CENTER = 20
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        RemoteWebServer.broadcastStatus(
            type = "service_status",
            message = "Erişilebilirlik Servisi Aktif (v1.1 Tam Ekran Motoru)",
            accessibilityEnabled = true
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val roots = getAllWindowRoots()
        if (roots.isEmpty()) return
        val pkg = event?.packageName?.toString() ?: ""
        if (pkg.contains("netflix") || pkg.contains("disney") || pkg.contains("amazon")) {
            val skipNode = findNodeInRoots(
                roots,
                listOf("İntroyu Atla", "Skip Intro", "Özeti Atla", "Skip Recap", "skip_intro")
            )
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
     * Sadece tek pencereyi (rootInActiveWindow) değil, tam ekran video oynatıcıların
     * üst katman (HUD / Overlay / Dialog) pencerelerinin tamamını döndürür.
     */
    private fun getAllWindowRoots(): List<AccessibilityNodeInfo> {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        try {
            val winList = windows
            if (!winList.isNullOrEmpty()) {
                // Aktif ve odaklanmış pencereleri öne al
                val sorted = winList.sortedByDescending { (if (it.isActive) 10 else 0) + (if (it.isFocused) 5 else 0) + it.layer }
                for (w in sorted) {
                    w.root?.let { roots.add(it) }
                }
            }
        } catch (_: Exception) {}

        rootInActiveWindow?.let { activeRoot ->
            if (roots.none { it == activeRoot }) {
                roots.add(0, activeRoot)
            }
        }
        return roots
    }

    /**
     * Telefondan gelen komutları çalıştırır.
     * Tam ekran video oynatılırken gizli olan butonlar için otomatik 3 aşamalı
     * "HUD Uyandırma -> Üst Bar Açma -> Tıklama / Makro Yedeği" motorunu kullanır.
     */
    fun executeSmartAction(action: String, payload: String = ""): JSONObject {
        when (action) {
            // --- GERÇEK YÖN TUŞLARI (D-PAD) ---
            "DPAD_UP" -> return sendDpadAction(ACTION_DPAD_UP, View.FOCUS_UP, "Yukarı ▲")
            "DPAD_DOWN" -> return sendDpadAction(ACTION_DPAD_DOWN, View.FOCUS_DOWN, "Aşağı ▼")
            "DPAD_LEFT" -> return sendDpadAction(ACTION_DPAD_LEFT, View.FOCUS_LEFT, "Sol ◀")
            "DPAD_RIGHT" -> return sendDpadAction(ACTION_DPAD_RIGHT, View.FOCUS_RIGHT, "Sağ ▶")
            "DPAD_CENTER" -> return sendDpadCenterAction()

            "BACK" -> {
                performGlobalAction(GLOBAL_ACTION_BACK)
                return feedbackOk(action, "🔙 Geri tuşu gönderildi")
            }
            "HOME" -> {
                performGlobalAction(GLOBAL_ACTION_HOME)
                return feedbackOk(action, "🏠 Ana ekrana dönüldü")
            }
            "VOL_UP" -> return adjustVolume(AudioManager.ADJUST_RAISE, "Ses artırıldı")
            "VOL_DOWN" -> return adjustVolume(AudioManager.ADJUST_LOWER, "Ses azaltıldı")
            "VOL_MUTE" -> return adjustVolume(AudioManager.ADJUST_TOGGLE_MUTE, "Ses durumu değiştirildi")
            "PLAY_PAUSE" -> return sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, "Oynat / Duraklat")
            "MEDIA_NEXT" -> return sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT, "Sonraki içerik")
            "MEDIA_PREV" -> return sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS, "Önceki içerik")
            "SEEK_FWD_10" -> {
                sendMediaKey(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, "10 sn ileri")
                sendDpadAction(ACTION_DPAD_RIGHT, View.FOCUS_RIGHT, "İleri")
                return feedbackOk(action, "⏩ 10 sn ileri sarıldı")
            }
            "SEEK_BWD_10" -> {
                sendMediaKey(KeyEvent.KEYCODE_MEDIA_REWIND, "10 sn geri")
                sendDpadAction(ACTION_DPAD_LEFT, View.FOCUS_LEFT, "Geri")
                return feedbackOk(action, "⏪ 10 sn geri sarıldı")
            }

            // --- YOUTUBE & SMARTTUBE TAM EKRAN DESTEKLİ ÖZEL TUŞLAR ---
            "YT_SUBSCRIBE" -> {
                huntAndClickInFullscreen(
                    action = action,
                    keywords = listOf("Abone ol", "Subscribe", "Abone", "subscribe_button", "channel_subscribe"),
                    expandKeywords = listOf("Daha fazla", "More actions", "More", "Kanal", "Channel", "overflow"),
                    successMsg = "🔔 Abone Ol butonuna basıldı!",
                    fallbackMacro = { runYoutubeTopBarMacro(stepRightCount = 0, label = "🔔 Abone Ol / Kanal barı açıldı ve seçildi") }
                )
                return feedbackPending(action, "⏳ Tam ekran kontrolü uyandırılıyor: Abone Ol...")
            }
            "YT_LIKE" -> {
                huntAndClickInFullscreen(
                    action = action,
                    keywords = listOf("Beğen", "Like", "beğendim", "button_like", "like_button", "thumbs_up"),
                    expandKeywords = listOf("Daha fazla", "More actions", "More", "Diğer", "overflow"),
                    successMsg = "👍 Video beğenildi!",
                    fallbackMacro = { runYoutubeTopBarMacro(stepRightCount = 1, label = "👍 Tam ekranda Beğen aksiyonu tetiklendi") }
                )
                return feedbackPending(action, "⏳ Tam ekran kontrolü uyandırılıyor: Beğen...")
            }
            "YT_DISLIKE" -> {
                huntAndClickInFullscreen(
                    action = action,
                    keywords = listOf("Beğenme", "Dislike", "button_dislike", "dislike_button", "thumbs_down"),
                    expandKeywords = listOf("Daha fazla", "More actions", "More", "Diğer", "overflow"),
                    successMsg = "👎 Beğenmeme tuşuna basıldı!",
                    fallbackMacro = { runYoutubeTopBarMacro(stepRightCount = 2, label = "👎 Tam ekranda Beğenme aksiyonu tetiklendi") }
                )
                return feedbackPending(action, "⏳ Tam ekran kontrolü uyandırılıyor: Beğenme...")
            }
            "YT_COMMENTS" -> {
                huntAndClickInFullscreen(
                    action = action,
                    keywords = listOf("Yorumlar", "Comments", "Yorum", "comment", "comments_button"),
                    expandKeywords = listOf("Daha fazla", "More actions", "More", "Açıklama", "Description"),
                    successMsg = "💬 Yorumlar açıldı ve telefona aktarıldı!",
                    scrapeAfterClick = "💬 Ekrandaki Yorumlar & Videolar",
                    fallbackMacro = {
                        runYoutubeTopBarMacro(stepRightCount = 3, label = "💬 Yorumlar paneli tetiklendi")
                        mainHandler.postDelayed({
                            RemoteWebServer.broadcastJson(scrapeAndReturnScreenList("💬 Ekrandaki Yorumlar & Videolar", "YT_COMMENTS"))
                        }, 700)
                    }
                )
                return feedbackPending(action, "⏳ Yorumlar açılıyor ve telefona çekiliyor...")
            }
            "YT_QUALITY" -> {
                huntAndClickInFullscreen(
                    action = action,
                    keywords = listOf("Kalite", "Quality", "Ayarlar", "Settings", "HD", "4K", "1080p", "player_settings", "quality"),
                    expandKeywords = listOf("Daha fazla", "More actions", "More", "Ayarlar", "Settings"),
                    successMsg = "⚙️ Kalite / Ayarlar menüsü açıldı!",
                    fallbackMacro = {
                        sendMediaKey(KeyEvent.KEYCODE_SETTINGS, "Ayarlar")
                        runYoutubeTopBarMacro(stepRightCount = 4, label = "⚙️ Kalite / Ayarlar barı açıldı")
                    }
                )
                return feedbackPending(action, "⏳ Kalite menüsü açılıyor...")
            }
            "YT_SPEED" -> {
                huntAndClickInFullscreen(
                    action = action,
                    keywords = listOf("Oynatma hızı", "Playback speed", "Hız", "Speed", "1x", "1.25x", "1.5x", "2x", "speed"),
                    expandKeywords = listOf("Ayarlar", "Settings", "Daha fazla", "More actions"),
                    successMsg = "⚡ Oynatma hızı menüsü açıldı!",
                    fallbackMacro = { runYoutubeTopBarMacro(stepRightCount = 5, label = "⚡ Oynatma hızı barı açıldı") }
                )
                return feedbackPending(action, "⏳ Oynatma hızı menüsü açılıyor...")
            }
            "YT_SPONSOR_SKIP" -> {
                huntAndClickInFullscreen(
                    action = action,
                    keywords = listOf("Atla", "Skip", "Reklamı Atla", "Skip Ad", "Sponsoru Atla", "Skip Sponsor", "skip_ad", "ad_skip"),
                    expandKeywords = emptyList(),
                    successMsg = "⏭️ Reklam / Sponsor doğrudan atlandı!",
                    fallbackMacro = {
                        // Tam ekranda sağ alt köşedeki "Reklamı/İntroyu Atla" koordinatına dokun + OK gönder
                        tapProportional(0.86f, 0.80f)
                        sendDpadCenterAction()
                        RemoteWebServer.broadcastJson(feedbackOk(action, "⏭️ Atla komutu (Tam ekran sağ-alt + OK) gönderildi!"))
                    }
                )
                return feedbackPending(action, "⏳ Reklam / Sponsor atlanıyor...")
            }

            // --- NETFLIX / DISNEY+ / PRIME VIDEO / EXXEN / BLUTV TAM EKRAN TUŞLARI ---
            "SKIP_INTRO" -> {
                huntAndClickInFullscreen(
                    action = action,
                    keywords = listOf(
                        "İntroyu Atla", "Skip Intro", "Özeti Atla", "Skip Recap",
                        "Jeneriği Atla", "Atla", "Skip", "skip_intro", "skip_recap"
                    ),
                    expandKeywords = emptyList(),
                    successMsg = "⏭️ İntro / Özet doğrudan atlandı!",
                    fallbackMacro = {
                        // Netflix, Prime ve Disney+'ta İntroyu Atla butonu sağ altta çıkar veya doğrudan OK ile tetiklenir
                        sendDpadCenterAction()
                        tapProportional(0.85f, 0.82f)
                        RemoteWebServer.broadcastJson(feedbackOk(action, "⏭️ İntro Atla tetiklendi!"))
                    }
                )
                return feedbackPending(action, "⏳ İntro Atla kontrol ediliyor...")
            }
            "NEXT_EPISODE" -> {
                huntAndClickInFullscreen(
                    action = action,
                    keywords = listOf(
                        "Sonraki Bölüm", "Next Episode", "Sıradaki Bölüm",
                        "Sonraki", "Next", "next_episode", "postplay"
                    ),
                    expandKeywords = listOf("Bölümler", "Episodes", "Diğer"),
                    successMsg = "▶️ Sonraki bölüme geçildi!",
                    fallbackMacro = {
                        sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT, "Sonraki Bölüm")
                        RemoteWebServer.broadcastJson(feedbackOk(action, "▶️ Sonraki Bölüm medya komutu gönderildi!"))
                    }
                )
                return feedbackPending(action, "⏳ Sonraki bölüme geçiliyor...")
            }
            "SUBTITLES_AUDIO" -> {
                // Önce doğrudan Android sistem Altyazı (KEYCODE_CAPTIONS) tuşunu da tetikle
                sendMediaKey(KeyEvent.KEYCODE_CAPTIONS, "Altyazı")
                huntAndClickInFullscreen(
                    action = action,
                    keywords = listOf(
                        "Ses ve Altyazı", "Audio & Subtitles", "Altyazı", "Subtitles",
                        "Dil Seçenekleri", "Audio", "CC", "Dublaj", "subtitles", "audio_subtitles", "exo_subtitles"
                    ),
                    expandKeywords = listOf("Ayarlar", "Settings", "Seçenekler", "Options", "Daha fazla"),
                    successMsg = "💬 Altyazı ve Ses menüsü açıldı, seçenekler telefona getirildi!",
                    scrapeAfterClick = "💬 Altyazı & Ses Seçenekleri (Dokun ve Seç)",
                    fallbackMacro = {
                        sendMediaKey(KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK, "Ses / Altyazı İzi")
                        mainHandler.postDelayed({
                            RemoteWebServer.broadcastJson(
                                scrapeAndReturnScreenList("💬 Altyazı & Ses Seçenekleri (Dokun ve Seç)", "SUBTITLES_LIST")
                            )
                        }, 600)
                    }
                )
                return feedbackPending(action, "⏳ Altyazı ve Ses menüsü uyandırılıyor...")
            }
            "ADD_WATCHLIST" -> {
                huntAndClickInFullscreen(
                    action = action,
                    keywords = listOf("Listeme Ekle", "My List", "İzleme Listesi", "Watchlist", "Favorilere Ekle", "my_list", "watchlist"),
                    expandKeywords = listOf("Detaylar", "Details", "Daha fazla"),
                    successMsg = "➕ İzleme listesine eklendi!"
                )
                return feedbackPending(action, "⏳ İzleme listesine ekleniyor...")
            }
            "SWITCH_PROFILE" -> {
                huntAndClickInFullscreen(
                    action = action,
                    keywords = listOf("Profil Değiştir", "Switch Profiles", "Kim İzliyor", "Profiller", "Profiles", "profile"),
                    expandKeywords = listOf("Menü", "Menu", "Sol Menü"),
                    successMsg = "👤 Profil seçimi açıldı!"
                )
                return feedbackPending(action, "⏳ Profil menüsü aranıyor...")
            }

            // --- KICK & TWITCH TAM EKRAN VE KANAL LİSTESİ ---
            "KICK_FOLLOWED_CHANNELS", "TWITCH_FOLLOWED_CHANNELS" -> {
                huntAndClickInFullscreen(
                    action = action,
                    keywords = listOf("Takip Edilen", "Following", "Followed", "Canlı Kanallar", "Live Channels", "Gözat", "Browse"),
                    expandKeywords = listOf("Menü", "Menu", "Ana Sayfa", "Home"),
                    successMsg = "⭐ Takip edilen kanallar telefona aktarıldı!",
                    scrapeAfterClick = "🔴 Canlı & Takip Edilen Kanallar (İzlemek İçin Dokun)",
                    fallbackMacro = {
                        RemoteWebServer.broadcastJson(
                            scrapeAndReturnScreenList("🔴 Ekrandaki Canlı Kanallar (İzlemek İçin Dokun)", "STREAM_CHANNELS")
                        )
                    }
                )
                return feedbackPending(action, "⏳ Takip edilen kanallar taranıyor...")
            }
            "TOGGLE_CHAT" -> {
                huntAndClickInFullscreen(
                    action = action,
                    keywords = listOf(
                        "Sohbeti Gizle", "Sohbeti Göster", "Hide Chat", "Show Chat",
                        "Chat", "Sohbet", "Tam Ekran", "Full Screen", "fullscreen", "chat_toggle"
                    ),
                    expandKeywords = listOf("Ayarlar", "Settings"),
                    successMsg = "💬 Sohbet / Tam ekran görünümü değiştirildi!"
                )
                return feedbackPending(action, "⏳ Sohbet görünümü değiştiriliyor...")
            }
            "FOLLOW_CHANNEL" -> {
                huntAndClickInFullscreen(
                    action = action,
                    keywords = listOf("Takip Et", "Follow", "follow_button"),
                    expandKeywords = listOf("Kanal", "Channel"),
                    successMsg = "❤️ Kanal takip edildi!"
                )
                return feedbackPending(action, "⏳ Takip Et butonu aranıyor...")
            }
            "CREATE_CLIP" -> {
                huntAndClickInFullscreen(
                    action = action,
                    keywords = listOf("Klip", "Clip", "Klip Al", "clip_button"),
                    expandKeywords = listOf("Daha fazla", "Ayarlar"),
                    successMsg = "🎬 Klip alma tetiklendi!"
                )
                return feedbackPending(action, "⏳ Klip butonu tetikleniyor...")
            }

            // --- SPOTIFY ÖZEL TUŞLARI ---
            "SPOTIFY_LIKE" -> {
                huntAndClickInFullscreen(
                    action = action,
                    keywords = listOf("Beğen", "Like", "Kitaplığına ekle", "Save to Your Library", "Heart", "like_button"),
                    expandKeywords = emptyList(),
                    successMsg = "💚 Şarkı beğenilenlere eklendi!"
                )
                return feedbackPending(action, "⏳ Şarkı beğenilenlere ekleniyor...")
            }
            "SPOTIFY_SHUFFLE" -> {
                huntAndClickInFullscreen(
                    action = action,
                    keywords = listOf("Karıştır", "Shuffle", "shuffle"),
                    expandKeywords = emptyList(),
                    successMsg = "🔀 Karışık çalma değiştirildi!"
                )
                return feedbackPending(action, "⏳ Karışık çalma tetikleniyor...")
            }
            "SPOTIFY_REPEAT" -> {
                huntAndClickInFullscreen(
                    action = action,
                    keywords = listOf("Tekrarla", "Repeat", "repeat"),
                    expandKeywords = emptyList(),
                    successMsg = "🔁 Tekrarlama modu değiştirildi!"
                )
                return feedbackPending(action, "⏳ Tekrarlama modu tetikleniyor...")
            }
            "SPOTIFY_LYRICS" -> {
                huntAndClickInFullscreen(
                    action = action,
                    keywords = listOf("Şarkı sözleri", "Lyrics", "lyrics"),
                    expandKeywords = emptyList(),
                    successMsg = "🎤 Şarkı sözleri açıldı!"
                )
                return feedbackPending(action, "⏳ Şarkı sözleri açılıyor...")
            }
            "SPOTIFY_QUEUE" -> {
                huntAndClickInFullscreen(
                    action = action,
                    keywords = listOf("Sıraya ekle", "Queue", "Sıradaki", "queue"),
                    expandKeywords = emptyList(),
                    successMsg = "📋 Sıradaki şarkılar telefona getirildi!",
                    scrapeAfterClick = "🎵 Sıradaki Şarkılar & Çalma Listeleri"
                )
                return feedbackPending(action, "⏳ Sıradaki şarkılar okunuyor...")
            }

            // --- STREMIO / PLEX / IPTV ---
            "STREMIO_SOURCES" -> {
                wakeUpPlayerOsd()
                mainHandler.postDelayed({
                    RemoteWebServer.broadcastJson(
                        scrapeAndReturnScreenList("🎬 Ekrandaki Kaynaklar / Akışlar (Seçmek İçin Dokun)", "SOURCES")
                    )
                }, 400)
                return feedbackPending(action, "⏳ Ekrandaki kaynaklar telefona çekiliyor...")
            }
            "SUBTITLE_DELAY_PLUS" -> {
                huntAndClickInFullscreen(
                    action = action,
                    keywords = listOf("+0.5", "+0.25", "Gecikme", "Delay", "Sync", "sub_delay"),
                    expandKeywords = listOf("Altyazı", "Subtitles", "Ayarlar"),
                    successMsg = "⏱️ Altyazı gecikmesi (+0.5 sn) ayarlandı!"
                )
                return feedbackPending(action, "⏳ Altyazı senkronu ayarlanıyor...")
            }
            "SUBTITLE_DELAY_MINUS" -> {
                huntAndClickInFullscreen(
                    action = action,
                    keywords = listOf("-0.5", "-0.25", "Gecikme", "Delay", "Sync", "sub_delay"),
                    expandKeywords = listOf("Altyazı", "Subtitles", "Ayarlar"),
                    successMsg = "⏱️ Altyazı gecikmesi (-0.5 sn) ayarlandı!"
                )
                return feedbackPending(action, "⏳ Altyazı senkronu ayarlanıyor...")
            }
            "LIVE_TV_CHANNELS" -> {
                huntAndClickInFullscreen(
                    action = action,
                    keywords = listOf("Kanallar", "TV Rehberi", "EPG", "Canlı TV", "Guide", "Channels"),
                    expandKeywords = emptyList(),
                    successMsg = "📺 Kanal listesi telefona aktarıldı!",
                    scrapeAfterClick = "📺 Kanal Listesi & Yayın Akışı (Dokun ve Aç)"
                )
                return feedbackPending(action, "⏳ Kanal listesi telefona çekiliyor...")
            }

            // --- TELEFONDAN METİN GÖNDERME ---
            "SEND_TEXT" -> {
                val roots = getAllWindowRoots()
                if (roots.isEmpty()) return feedbackError(action, "TV ekranı okunamadı")
                val inputSuccess = injectTextIntoActiveOrSearchField(roots, payload)
                return if (inputSuccess) {
                    feedbackOk(action, "⌨️ '$payload' TV'ye yazıldı!")
                } else {
                    // Arama kutusu kapalıysa önce Arama butonuna basıp sonra yazmayı dene
                    huntAndClickInFullscreen(
                        action = action,
                        keywords = listOf("Ara", "Search", "Arama", "search"),
                        expandKeywords = emptyList(),
                        successMsg = "🔍 Arama açıldı ve '$payload' yazıldı!",
                        fallbackMacro = {
                            sendMediaKey(KeyEvent.KEYCODE_SEARCH, "Arama")
                        }
                    )
                    mainHandler.postDelayed({
                        injectTextIntoActiveOrSearchField(getAllWindowRoots(), payload)
                    }, 700)
                    feedbackPending(action, "🔍 Arama kutusu açılıp '$payload' yazılıyor...")
                }
            }

            // --- TELEFONA GELEN LİSTEDEN BİR ÖĞEYE DOKUNULDUĞUNDA ---
            "CLICK_SCREEN_TEXT" -> {
                huntAndClickInFullscreen(
                    action = action,
                    keywords = listOf(payload),
                    expandKeywords = emptyList(),
                    successMsg = "✅ '$payload' TV'de seçildi ve açılıyor!"
                )
                return feedbackPending(action, "⏳ '$payload' TV'de seçiliyor...")
            }

            // --- EKRANDAKİ TÜM SEÇENEKLERİ TELEFONA ÇEK (TAM EKRAN DAHİL) ---
            "FETCH_SCREEN_ITEMS" -> {
                val initialList = scrapeAndReturnScreenList("📲 TV Ekranındaki Seçilebilir Öğeler", "ALL")
                val count = initialList.optJSONArray("items")?.length() ?: 0
                if (count < 2) {
                    // Tam ekranda arayüz gizliyse önce arayüzü uyandır, 450ms sonra oku!
                    wakeUpPlayerOsd()
                    mainHandler.postDelayed({
                        val wokenList = scrapeAndReturnScreenList("📲 Tam Ekran Kontrolleri & Öğeler", "ALL")
                        RemoteWebServer.broadcastJson(wokenList)
                    }, 450)
                    return feedbackPending(action, "⏳ Tam ekran arayüzü uyandırılıp ekrandaki öğeler okunuyor...")
                }
                return initialList
            }
        }

        return feedbackOk(action, "Komut işlendi: $action")
    }

    /**
     * 3 AŞAMALI TAM EKRAN AVCI MOTORU (huntAndClickInFullscreen):
     * 1. Aşama (0ms): Tüm pencerelerde (overlay/HUD dahil) butonu arar, bulursa anında tıklar.
     * 2. Aşama (450ms): Tam ekranda video oynarken butonlar gizli olduğu için otomatik olarak
     *    oynatıcı arayüzünü (OSD/HUD) uyandırır ve tekrar tarar.
     * 3. Aşama (900ms): Buton "Daha Fazla / Ayarlar / Üst Bar" altındaysa (ör. YouTube tam ekran)
     *    üst barı açar (DPAD_UP veya Daha Fazla'ya tıklar) ve tekrar tarar.
     * 4. Aşama (1300ms): Uygulama OpenGL/Cobalt Canvas kullanıyorsa (YouTube TV gibi) ve Android'e
     *    hiç AccessibilityNodeInfo vermiyorsa, 'görünür değil' hatası vermek yerine doğrudan
     *    D-Pad / Koordinat Makrosunu çalıştırır!
     */
    private fun huntAndClickInFullscreen(
        action: String,
        keywords: List<String>,
        expandKeywords: List<String>,
        successMsg: String,
        scrapeAfterClick: String? = null,
        fallbackMacro: (() -> Unit)? = null
    ) {
        // AŞAMA 1: Şu an ekranda görünür mü?
        val rootsNow = getAllWindowRoots()
        val nodeNow = findNodeInRoots(rootsNow, keywords)
        if (nodeNow != null && performClickOnNodeOrParent(nodeNow)) {
            onTargetClicked(action, successMsg, scrapeAfterClick)
            return
        }

        // AŞAMA 2: Tam ekran oynatıcı arayüzünü (OSD/HUD) uyandır ve 450ms sonra tekrar bak
        wakeUpPlayerOsd()

        mainHandler.postDelayed({
            val rootsAfterWake = getAllWindowRoots()
            val nodeAfterWake = findNodeInRoots(rootsAfterWake, keywords)
            if (nodeAfterWake != null && performClickOnNodeOrParent(nodeAfterWake)) {
                onTargetClicked(action, successMsg, scrapeAfterClick)
                return@postDelayed
            }

            // AŞAMA 3: "Daha Fazla İşlem / Ayarlar" alt barını aç veya bir kez daha DPAD_UP gönder
            if (expandKeywords.isNotEmpty()) {
                val expandNode = findNodeInRoots(rootsAfterWake, expandKeywords)
                if (expandNode != null) {
                    performClickOnNodeOrParent(expandNode)
                } else {
                    sendDpadAction(ACTION_DPAD_UP, View.FOCUS_UP, "Üst Bar")
                }
            } else {
                sendDpadAction(ACTION_DPAD_UP, View.FOCUS_UP, "Üst Bar")
            }

            mainHandler.postDelayed({
                val rootsAfterExpand = getAllWindowRoots()
                val nodeAfterExpand = findNodeInRoots(rootsAfterExpand, keywords)
                if (nodeAfterExpand != null && performClickOnNodeOrParent(nodeAfterExpand)) {
                    onTargetClicked(action, successMsg, scrapeAfterClick)
                    return@postDelayed
                }

                // AŞAMA 4: Cobalt / Özel Canvas Oynatıcılar için Makro Yedeği
                if (fallbackMacro != null) {
                    fallbackMacro.invoke()
                } else if (scrapeAfterClick != null) {
                    RemoteWebServer.broadcastJson(scrapeAndReturnScreenList(scrapeAfterClick, action))
                } else {
                    // Son çare: Odaklanmış öğeye OK bas
                    sendDpadCenterAction()
                    RemoteWebServer.broadcastJson(
                        feedbackOk(action, "$successMsg (Tam ekran kontrolü üzerinden tetiklendi)")
                    )
                }
            }, 450)
        }, 450)
    }

    private fun onTargetClicked(action: String, successMsg: String, scrapeTitle: String?) {
        if (scrapeTitle != null) {
            mainHandler.postDelayed({
                val listJson = scrapeAndReturnScreenList(scrapeTitle, action)
                listJson.put("message", successMsg)
                RemoteWebServer.broadcastJson(listJson)
            }, 500)
        } else {
            RemoteWebServer.broadcastJson(feedbackOk(action, successMsg))
        }
    }

    /**
     * Videoyu duraklatmadan tam ekran oynatıcı arayüzünü (HUD/OSD) uyandırır.
     */
    private fun wakeUpPlayerOsd() {
        var dpadSent = false
        if (Build.VERSION.SDK_INT >= 33) {
            dpadSent = performGlobalAction(ACTION_DPAD_UP)
        }
        if (!dpadSent) {
            // Ekranın alt-orta kısmına hafif dokunma hareketi göndererek OSD'yi uyandır
            tapProportional(0.50f, 0.85f)
        }
    }

    /**
     * YouTube TV (Cobalt OpenGL motoru) tam ekranda AccessibilityNodeInfo vermediğinde
     * çalışan D-Pad + Koordinat Makrosu:
     * Tam ekranda OSD'yi uyandırır, üst aksiyon barına (Abone Ol, Beğen, Beğenme, Yorumlar, Ayarlar)
     * çıkar ve ilgili tuşu seçer.
     */
    private fun runYoutubeTopBarMacro(stepRightCount: Int, label: String) {
        // 1. OSD'yi uyandır ve üst aksiyon satırına çık
        sendDpadAction(ACTION_DPAD_UP, View.FOCUS_UP, "OSD")
        mainHandler.postDelayed({
            sendDpadAction(ACTION_DPAD_UP, View.FOCUS_UP, "Üst Satır")
            mainHandler.postDelayed({
                for (i in 0 until stepRightCount) {
                    sendDpadAction(ACTION_DPAD_RIGHT, View.FOCUS_RIGHT, "Sağ")
                }
                mainHandler.postDelayed({
                    sendDpadCenterAction()
                    RemoteWebServer.broadcastJson(feedbackOk("YT_MACRO", label))
                }, 200)
            }, 250)
        }, 250)
    }

    /**
     * Gerçek D-Pad Yön Tuşu Gönderimi:
     * 1) Android 13+ (API 33+) ise sistem düzeyinde GLOBAL_ACTION_DPAD_* kullanır.
     * 2) Android 7-12 ise odaklanmış öğeden focusSearch veya geometrik en yakın komşu bulma uygular.
     */
    private fun sendDpadAction(globalActionId: Int, focusDirection: Int, label: String): JSONObject {
        if (Build.VERSION.SDK_INT >= 33) {
            val ok = performGlobalAction(globalActionId)
            if (ok) return feedbackOk("DPAD", "🕹️ Yön: $label")
        }

        val roots = getAllWindowRoots()
        for (root in roots) {
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)

            if (focused != null) {
                val next = focused.focusSearch(focusDirection)
                    ?: findNearestNodeInDirection(root, focused, focusDirection)
                if (next != null) {
                    next.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    next.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
                    next.performAction(AccessibilityNodeInfo.ACTION_SELECT)
                    return feedbackOk("DPAD", "🕹️ Yön: $label")
                }
            }
        }

        // Ekranda hiçbir odak yoksa (ör. tam ekran video) OSD'yi uyandır
        tapProportional(0.50f, 0.85f)
        return feedbackOk("DPAD", "🕹️ $label (Ekran uyandırıldı)")
    }

    private fun sendDpadCenterAction(): JSONObject {
        if (Build.VERSION.SDK_INT >= 33) {
            if (performGlobalAction(ACTION_DPAD_CENTER)) {
                return feedbackOk("DPAD", "🔘 OK basıldı")
            }
        }
        val roots = getAllWindowRoots()
        for (root in roots) {
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            if (focused != null && performClickOnNodeOrParent(focused)) {
                return feedbackOk("DPAD", "🔘 OK basıldı")
            }
        }
        tapProportional(0.50f, 0.50f)
        return feedbackOk("DPAD", "🔘 OK (Ekran merkezine dokunuldu)")
    }

    private fun findNearestNodeInDirection(
        root: AccessibilityNodeInfo,
        current: AccessibilityNodeInfo,
        direction: Int
    ): AccessibilityNodeInfo? {
        val curRect = Rect()
        current.getBoundsInScreen(curRect)
        if (curRect.isEmpty) return null

        var bestNode: AccessibilityNodeInfo? = null
        var bestDist = Double.MAX_VALUE
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val candidate = queue.removeFirst()
            if (candidate != current && (candidate.isFocusable || candidate.isClickable)) {
                val r = Rect()
                candidate.getBoundsInScreen(r)
                if (!r.isEmpty) {
                    val dx = (r.centerX() - curRect.centerX()).toDouble()
                    val dy = (r.centerY() - curRect.centerY()).toDouble()
                    val inDir = when (direction) {
                        View.FOCUS_UP -> dy < -15 && Math.abs(dy) >= Math.abs(dx) * 0.4
                        View.FOCUS_DOWN -> dy > 15 && Math.abs(dy) >= Math.abs(dx) * 0.4
                        View.FOCUS_LEFT -> dx < -15 && Math.abs(dx) >= Math.abs(dy) * 0.4
                        View.FOCUS_RIGHT -> dx > 15 && Math.abs(dx) >= Math.abs(dy) * 0.4
                        else -> false
                    }
                    if (inDir) {
                        val dist = Math.hypot(dx, dy)
                        if (dist < bestDist) {
                            bestDist = dist
                            bestNode = candidate
                        }
                    }
                }
            }
            for (i in 0 until candidate.childCount) {
                candidate.getChild(i)?.let { queue.add(it) }
            }
        }
        return bestNode
    }

    private fun findNodeInRoots(roots: List<AccessibilityNodeInfo>, keywords: List<String>): AccessibilityNodeInfo? {
        for (root in roots) {
            val found = findNodeByKeywords(root, keywords)
            if (found != null) return found
        }
        return null
    }

    private fun findNodeByKeywords(root: AccessibilityNodeInfo, keywords: List<String>): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val text = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            val viewId = node.viewIdResourceName ?: ""
            val hint = if (Build.VERSION.SDK_INT >= 26) node.hintText?.toString() ?: "" else ""

            for (kw in keywords) {
                if (text.contains(kw, ignoreCase = true) ||
                    desc.contains(kw, ignoreCase = true) ||
                    viewId.contains(kw, ignoreCase = true) ||
                    hint.contains(kw, ignoreCase = true)
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
        while (current != null && depth < 7) {
            if (current.isClickable || current.isFocusable) {
                current.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                current.performAction(AccessibilityNodeInfo.ACTION_SELECT)
                if (current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true
                }
            }
            current = current.parent
            depth++
        }
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (!rect.isEmpty && rect.centerX() > 0 && rect.centerY() > 0) {
            tapCoordinates(rect.centerX().toFloat(), rect.centerY().toFloat())
            return true
        }
        return false
    }

    private fun tapProportional(xRatio: Float, yRatio: Float) {
        val dm = resources.displayMetrics
        val w = if (dm.widthPixels > 0) dm.widthPixels else 1920
        val h = if (dm.heightPixels > 0) dm.heightPixels else 1080
        tapCoordinates(w * xRatio, h * yRatio)
    }

    private fun tapCoordinates(x: Float, y: Float) {
        try {
            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 75))
                .build()
            dispatchGesture(gesture, null, mainHandler)
        } catch (_: Exception) {}
    }

    private fun scrapeAndReturnScreenList(categoryTitle: String, filterHint: String): JSONObject {
        val roots = getAllWindowRoots()
        val items = JSONArray()
        val seen = mutableSetOf<String>()

        for (root in roots) {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            while (queue.isNotEmpty() && items.length() < 35) {
                val node = queue.removeFirst()
                val rawText = (node.text?.toString() ?: node.contentDescription?.toString() ?: "").trim()

                if (rawText.length in 2..80 && !seen.contains(rawText.lowercase())) {
                    seen.add(rawText.lowercase())
                    val itemObj = JSONObject().apply {
                        put("title", rawText)
                        put("subtitle", if (node.isClickable || node.parent?.isClickable == true) "Dokun ve TV'de Seç" else "Ekranda Görünür")
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

    private fun injectTextIntoActiveOrSearchField(roots: List<AccessibilityNodeInfo>, text: String): Boolean {
        for (root in roots) {
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

    private fun feedbackOk(action: String, message: String) = JSONObject().apply {
        put("status", "ok")
        put("action", action)
        put("message", message)
    }

    private fun feedbackPending(action: String, message: String) = JSONObject().apply {
        put("status", "pending")
        put("action", action)
        put("message", message)
    }

    private fun feedbackError(action: String, message: String) = JSONObject().apply {
        put("status", "warning")
        put("action", action)
        put("message", message)
    }
}
