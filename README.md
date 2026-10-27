# 📺 Akıllı TV Kumanda (Android TV + QR Web Kumanda)

Navigasyon tuşlarıyla uğraşmadan, **uygulamaya özgü doğrudan aksiyon butonları (İntroyu Atla, Abone Ol, Beğen, Altyazı Seç)** ve **çift yönlü geri bildirim (Kick/Twitch Takip Edilen Kanalları veya Netflix Altyazılarını Telefona Çekme)** sunan Android TV uygulaması.

## 🚀 Özellikler
- **Uygulama İndirmeden QR ile Bağlantı (DZ Browser Mantığı):** TV ekranındaki QR kodu telefon kamerasıyla okuttuğunuzda, gömülü HTTP (`8080`) + WebSocket (`8081`) sunucusu üzerinden kumanda arayüzü anında telefon tarayıcısında açılır.
- **14 Uygulama İçin Özel Kumanda:** YouTube, SmartTube, Netflix, Amazon Prime Video, Disney+, Exxen, BluTV (Max), TOD (beIN), Tabii, Kick, Twitch, Spotify, Stremio, IPTV (TiviMate).
- **Navigasyonsuz Doğrudan Tuşlar (`AccessibilityService`):** Ekrandaki `İntroyu Atla`, `Sonraki Bölüm`, `Abone Ol`, `Beğen`, `Sohbeti Gizle` gibi butonları imleç gezdirmeden doğrudan bulup tıklar.
- **Çift Yönlü Ekran Listesi Aktarımı:** Kick/Twitch'te *Takip Edilen Kanallar*, Netflix/Stremio'da *Altyazı & Ses / Kaynak Seçenekleri* TV ekranından okunup telefon ekranına tıklanabilir liste olarak gelir.
- **Otomatik Erişilebilirlik İzni Yönlendirmesi:** İlk açılışta izin kapalıysa uyarı ekranı açar ve doğrudan Android TV Erişilebilirlik Ayarlarına yönlendirir.

## 📦 Kurulum
1. [`AkilliTvKumanda-v1.0.apk`](apk/AkilliTvKumanda-v1.0.apk) dosyasını Android TV'nize yükleyin ve açın.
2. Ekrana gelen uyarıdan **"Ayarlara Git ve Aç"** butonuna basarak **Akıllı TV Kumanda Servisi** erişilebilirlik iznini aktif edin.
3. Ekrandaki QR kodu telefonunuzdan okutarak kullanmaya başlayın.
