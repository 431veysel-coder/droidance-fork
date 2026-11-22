# Droidance Fork - Teknik Tasarım Belgesi

**Taban:** `ybblab/Droidance` v0.3.1 (`af8014c`)  
**Hedef:** Üç ana özellik eklentisi + CI/CD altyapısı  
**Lisans:** Apache-2.0 (korunur)

---

## 🎯 ÖZET: İSTENEN 3 ANA ÖZELLİK

| # | Özellik | Mevcut Droidance Durumu | Fork'ta Hedef |
|---|---------|------------------------|---------------|
| 1 | **Gömülü Termux Terminal** | ❌ Yok (sadece `TermuxLayer` planlandı, reddedildi) | ✅ Tam donanımlı: `apt`, `pkg`, dosya indirme/çalıştırma, kütüphane erişimi |
| 2 | **Gömülü JVM/Gradle** | ❌ Yok (sadece CI'de build) | ✅ İsteğe bağlı: APK içinde `kotlinc`/`gradle` çalıştırma (veya Termux üzerinden) |
| 3 | **Gömülü Tarayıcı (AI Agent için)** | ❌ Yok (vision-only, ekran görüntüsü) | ✅ Tam: DOM erişimi, tıklama, iframe, sayfa geçişi, canlı görüntü |

---

## 🏗️ MİMARİ KARARLAR

### 1. Termux Entegrasyonu — **PRoot + Proot-Distro Yaklaşımı**

**Neden PRoot?**  
- Droidance zaten **PRoot Alpine** kullanıyor (sidecar içinde)  
- Termux bootstrap `aarch64` doğrudan PRoot'ta çalışır  
- Root gerektirmez, `uid=2000` (shell) ile uyumlu  

**Yapı:**
```
/data/data/com.droidance.fork/files/
├── termux/                    # Termux rootfs (PRoot mount)
│   ├── bin/ -> /system/bin    # Symlinks (SYMLINKS.txt)
│   ├── lib/                   # Termux libc++_shared, libandroid-support
│   ├── etc/apt/               # APT deposu
│   ├── usr/bin/               # apt, pkg, python, node, git, ...
│   └── home/                  # Kullanıcı ev dizini
├── termux-proot.sh            # PRoot launch script
└── termux-env.sh              # Environment setup (LD_LIBRARY_PATH, PATH)
```

**Kod Konumu:** `core/device/src/main/java/com/droidance/core/device/termux/`

| Sınıf | Sorumluluk |
|-------|------------|
| `TermuxBootstrapManager` | Bootstrap indirme, doğrulama (SHA256), açma, symlink çözümü |
| `TermuxProotLauncher` | `proot --link2symlink -b /system -b /apex -r $ROOTFS $ROOTFS/bin/bash` |
| `TermuxPackageManager` | `apt update/install/remove` wrapper, paket önbelleği |
| `TermuxSessionManager` | PTY tabanlı oturum (script, etkileşimli, background) |
| `TermuxFileBridge` | Host ↔ Termux dosya kopyalama (scoped storage) |

**Güvenlik:**
- Termux **yalnızca sanal ekran (sidecar) içinde** çalışır
- Host dosya sistemine **sadece `TermuxFileBridge` ile** erişim
- `apt` kaynakları **resmi Termux repo** + imzalı mirror
- Kullanıcı/AI komutları **`shell` aracı üzerinden** audit loglu çalıştırılır

---

### 2. JVM/Gradle Desteği — **İKİ SEÇENEK, KULLANICI SEÇER**

| Seçenek | Açıklama | Avantaj | Dezavantaj |
|---------|----------|---------|------------|
| **A. Termux `openjdk-21` + `gradle`** | Termux içinde `pkg install openjdk-21 gradle` | APK boyutu artmaz, tam özellikli | İlk kurulum ~300 MB indirme, RAM yoğun |
| **B. Gömülü `kotlinc` + `gradle-wrapper`** | APK assets içinde minimal JVM (Eclipse J9 / GraalVM native-image) | Sürükle-bırak çalışır, offline | APK +15-25 MB, sınırlı kütüphane desteği |

**Öneri:** **Seçenek A (Termux JDK)** varsayılan. Seçenek B "Geek Mode" olarak opsiyonel.

**Yapı (Seçenek A):**
```kotlin
// TermuxPackageManager.kt
fun ensureJdkInstalled(): Result<Unit> = runCatching {
    if (!isPackageInstalled("openjdk-21")) installPackage("openjdk-21")
    if (!isPackageInstalled("gradle")) installPackage("gradle")
}
```

**Yapı (Seçenek B - İleri aşama):**
```
/assets/jvm/
├── jre/              # Eclipse J9 JRE (aarch64, ~12 MB)
├── kotlinc/          # kotlinc native-image (~8 MB)
└── gradle/           # Gradle wrapper + minimal dist
```

**Kod Konumu:** `core/device/src/main/java/com/droidance/core/device/jvm/`

---

### 3. Gömülü Tarayıcı — **CHROMIUM WEBVIEW + CDP (Chrome DevTools Protocol)**

**Neden WebView + CDP?**  
- Android `WebView` (Chromium tabanlı) **sistem bileşeni**, güncellenir  
- `WebView.setWebContentsDebuggingEnabled(true)` → **CDP portu açar** (localhost:9222)  
- CDP ile: **DOM okuma, tıklama, yazma, screenshot, network log, console log**  
- `iframe`/`shadow DOM` otomatik handle edilir  
- **Headless değil** — gerçek render, JS çalıştırma, CSP bypass yok  

**Mimari:**
```
┌─────────────────────────────────────────────────────────────┐
│                    Agent Process (app)                      │
│  ┌─────────────┐    CDP (WebSocket)    ┌─────────────────┐  │
│  │ BrowserAgent │ ◄──────────────────► │  WebView Process │  │
│  │  (Kotlin)   │   ws://localhost:9222 │  (Chromium)      │  │
│  └─────────────┘                       └─────────────────┘  │
│        │                                      │              │
│        ▼                                      ▼              │
│  Tool: browser_act                    WebView UI            │
│  (navigate, click, type,             (sanal ekranda        │
│   screenshot, eval_js,               gizli/arayüzsüz)       │
│   get_dom, wait_for)                                      │
└─────────────────────────────────────────────────────────────┘
```

**Kod Konumu:** `core/device/src/main/java/com/droidance/core/device/browser/`

| Sınıf | Sorumluluk |
|-------|------------|
| `BrowserManager` | WebView lifecycle, CDP bağlantısı, profil yönetimi |
| `BrowserCdpClient` | CDP protokolü (JSON-RPC over WebSocket) |
| `BrowserToolRegistrar` | `browser_act` aracı: `navigate`, `click`, `type`, `screenshot`, `eval_js`, `get_dom`, `wait_for`, `pdf` |
| `BrowserSession` | Sekme/iframe yönetimi, cookie/localStorage, indirme |
| `BrowserOverlay` | İsteğe bağlı: sanal ekranda görüntüleyici (debug) |

**CDP Komut Örnekleri:**
```json
// Navigate
{"method": "Page.navigate", "params": {"url": "https://example.com"}}

// Click (selector veya koordinat)
{"method": "Input.dispatchMouseEvent", "params": {"type": "mousePressed", "x": 100, "y": 200, "button": "left"}}
{"method": "Input.dispatchMouseEvent", "params": {"type": "mouseReleased", "x": 100, "y": 200, "button": "left"}}

// Type
{"method": "Input.insertText", "params": {"text": "merhaba"}}

// Get DOM (outerHTML)
{"method": "Runtime.evaluate", "params": {"expression": "document.documentElement.outerHTML"}}

// Screenshot
{"method": "Page.captureScreenshot", "params": {"format": "png", "quality": 80}}

// Wait for selector
{"method": "Runtime.evaluate", "params": {"expression": "new Promise(r => { const el = document.querySelector('btn'); if (el) r(); else new MutationObserver(() => { if (document.querySelector('btn')) r(); }).observe(document, {subtree: true, childList: true}); })"}}
```

**Güvenlik:**
- WebView **sanal ekranda** (görünmez) çalışır
- Network istekleri **agent'ın VPN/proxy'sinden** geçer (CleartextNetworkPolicy)
- Cookie/localStorage **profil başına izole** (profil = görev/kimlik)
- `file://` / `content://` erişimi **kapalı** (sadece `http(s)://`)
- JavaScript **yalnızca CDP `Runtime.evaluate`** ile, XSS riski yok

---

## 🔗 MEVCUT DROIDANCE MİMARİSİYLE ENTEGRASYON

### Mevcut Akış (Korunur):
```
User Input → Agent Loop → ToolExecutor → DeviceToolNames.{ANDROID_READ, ANDROID_ACT, SHELL, SCREEN_ACT}
                                                      ↓
                                        VirtualSpaceSidecarBridge (AIDL)
                                                      ↓
                                        VirtualSpaceSidecarService (shell uid=2000)
                                                      ↓
                                        VirtualDisplay + ImageReader + injectInputEvent
```

### Yeni Akış (Eklenenler):
```
┌──────────────────────────────────────────────────────────────────────┐
│ ToolExecutor.registerAll()                                           │
│   ├─ screen_act (mevcut)                                             │
│   ├─ android_act (mevcut)                                            │
│   ├─ shell (mevcut) → TermuxShellWrapper (YENİ)                     │
│   ├─ browser_act (YENİ) → BrowserToolRegistrar                      │
│   └─ jvm_act (YENİ, opsiyonel) → JvmToolRegistrar                   │
└──────────────────────────────────────────────────────────────────────┘
```

**Yeni Araçlar (`DeviceToolNames` genişletmesi):**
```kotlin
object DeviceToolNames {
    const val ANDROID_READ = "android_read"
    const val ANDROID_ACT  = "android_act"
    const val SHELL        = "shell"
    const val SCREEN_ACT   = "screen_act"
    const val BROWSER_ACT  = "browser_act"    // YENİ
    const val TERMUX_ACT   = "termux_act"     // YENİ (shell alias, Termux özel komutlar)
    const val JVM_ACT      = "jvm_act"        // YENİ (opsiyonel)
}
```

---

## 📦 MODÜL YAPISI (YENİ)

```
droidance-fork/
├── app/                          # Host APK (UI, services)
├── core/
│   ├── agent/                    # Agent loop, tools (mevcut + browser_act, termux_act)
│   ├── model/                    # Model providers (mevcut)
│   ├── device/                   # Cihaz kontrolü (GENİŞLETİLDİ)
│   │   ├── termux/               # YENİ MODÜL
│   │   │   ├── TermuxBootstrapManager.kt
│   │   │   ├── TermuxProotLauncher.kt
│   │   │   ├── TermuxPackageManager.kt
│   │   │   ├── TermuxSessionManager.kt
│   │   │   ├── TermuxFileBridge.kt
│   │   │   └── TermuxShellWrapper.kt
│   │   ├── jvm/                  # YENİ MODÜL (opsiyonel)
│   │   │   ├── JvmEmbeddedManager.kt
│   │   │   ├── KotlincRunner.kt
│   │   │   └── GradleWrapperRunner.kt
│   │   ├── browser/              # YENİ MODÜL
│   │   │   ├── BrowserManager.kt
│   │   │   ├── BrowserCdpClient.kt
│   │   │   ├── BrowserToolRegistrar.kt
│   │   │   ├── BrowserSession.kt
│   │   │   └── BrowserOverlay.kt
│   │   └── agent/device/         # Mevcut (VirtualSpaceSidecarService, Bridge)
│   ├── knowledge/                # Skills, memory (mevcut)
│   └── persistence/              # Room DB (mevcut)
├── scripts/                      # CI/CD, verify, release (mevcut + yeni)
└── .github/
    ├── workflows/                # CI, Release (yukarıda oluşturuldu)
    └── dependabot.yml            # Otomatik bağımlılık güncelleme
```

---

## 🔐 GÜVENLİK MODELİ (GENİŞLETİLMİŞ)

| Bileşen | Yetki | İzolasyon |
|---------|-------|-----------|
| **Sidecar (shell uid=2000)** | `createVirtualDisplay`, `injectInputEvent`, `am start --display` | AIDL + Binder + `linkToDeath` |
| **Termux (PRoot)** | `apt`, `pkg`, dosya okuma/yazma (kendi rootfs), network | PRoot mount namespace, `TermuxFileBridge` ile host erişimi |
| **JVM (Termux içinde)** | `kotlinc`, `gradle`, `java` | Termux içinde, sidecar izolasyonu devreder |
| **Browser (WebView)** | HTTP(S), CDP, DOM, JS | Sanal ekranda gizli WebView, network policy ile filtreli |
| **Agent (app UID)** | UI, ayarlar, model API, tool orchestration | ToolRiskPolicy + HighRiskConfirmationStore |

**Yeni Risk Policy Girdileri (`harness_policy.yaml`):**
```yaml
tool_safety:
  confirmation_tools:
    - send_sms
    - make_call
    - browser_act      # YENİ: Navigasyon/tiplama onayı
    - termux_act       # YENİ: apt install / script çalıştırma onayı
    - jvm_act          # YENİ: Derleme/çalıştırma onayı
  shell_destructive_patterns:
    # Mevcut + Termux özel
    - '(^|\s)apt\s+(install|remove|purge)\s+'
    - '(^|\s)pkg\s+(install|uninstall)\s+'
  shell_forbidden_patterns:
    # Mevcut + proot escape denemeleri
    - 'proot\s+--mount'
    - 'chroot\s+'
```

---

## 🧪 TEST STRATEJİSİ

| Test Türü | Ortam | Kapsam |
|-----------|-------|--------|
| **Unit** | JVM (Robolectric) | Tool logic, coordinate mapping, policy |
| **Integration** | Emulator (API 35 ARM64) | Sidecar lifecycle, VirtualDisplay, input injection |
| **Termux** | Emulator + PRoot | Bootstrap indirme, `apt install`, script çalıştırma |
| **Browser** | Emulator + WebView | CDP connect, navigate, click, screenshot, iframe |
| **JVM** | Emulator + Termux | `kotlinc` hello world, `gradle` minimal build |
| **E2E** | Emulator | Agent: "Termux'ta python script yaz ve çalıştır", "GitHub'a git ve issue oku" |

**CI Sırası:**
1. `static-analysis` (detekt, lint, verify_*, architecture)
2. `unit-tests` (Robolectric)
3. `termux-validation` (bootstrap SHA256, critical files)
4. `instrumented-tests` (Emulator - sidecar, browser, termux)
5. `build-debug` / `build-release`

---

## 📋 YOL HARİTASI (MILESTONES)

### M1: Temel Altyapı (Hafta 1-2)
- [ ] Fork repo oluştur, CI/CD workflow'ları aktif et
- [ ] `TermuxBootstrapManager` + `TermuxProotLauncher` yaz
- [ ] `scripts/verify_termux.py` ekle (bootstrap SHA256 kontrolü)
- [ ] Unit test: bootstrap indirme, symlink çözme, proot başlatma

### M2: Termux Shell Entegrasyonu (Hafta 3-4)
- [ ] `TermuxPackageManager` (`apt update/install/list`)
- [ ] `TermuxSessionManager` (PTY, background, output capture)
- [ ] `TermuxShellWrapper` → `shell` aracı genişletmesi
- [ ] `harness_policy.yaml` güncelle (termux patterns)
- [ ] Entegrasyon test: `apt install python3 && python3 -c "print('hello')"`

### M3: JVM Desteği (Hafta 5-6)
- [ ] Seçenek A: Termux `openjdk-21` + `gradle` kurulum otomasyonu
- [ ] Seçenek B (opsiyonel): `assets/jvm/` paketleme scripti
- [ ] `JvmToolRegistrar` → `jvm_act` aracı
- [ ] Test: `kotlinc hello.kt && kotlin HelloWorldKt`

### M4: Gömülü Tarayıcı (Hafta 7-10) — EN KRİTİK
- [ ] `BrowserManager` + WebView lifecycle (sanal ekranda)
- [ ] `BrowserCdpClient` (WebSocket, JSON-RPC, event handling)
- [ ] `BrowserToolRegistrar` → `browser_act` komut seti
- [ ] `BrowserSession` (sekme, cookie, download, iframe)
- [ ] Test: `navigate → click → type → screenshot → get_dom`
- [ ] Test: iframe içine girme, shadow DOM okuma
- [ ] Test: PDF oluşturma, network log alma

### M5: Agent Entegrasyonu & Polish (Hafta 11-12)
- [ ] Agent prompt'larına `browser_act`, `termux_act`, `jvm_act` tanıtımı
- [ ] Skills: `web-scraping`, `termux-scripting`, `kotlin-compile`
- [ ] UI: Terminal görünümü, Browser debug overlay
- [ ] Performans: WebView bellek, CDP latency, Termux boot süresi
- [ ] Güvenlik audit: Pen-test, permission bypass denemeleri

### M6: Release v1.0-fork (Hafta 13)
- [ ] Tüm testler yeşil
- [ ] Release APK imzalama, SHA256, GitHub Release draft
- [ ] Dokümantasyon: README, SETUP, SECURITY, CONTRIBUTING
- [ ] Announcement

---

## 💾 APK BOYUT TAHMİNİ

| Bileşen | Boyut | Not |
|---------|-------|-----|
| Base Droidance (v0.3.1) | ~13 MB | Mevcut |
| Termux Bootstrap (assets, sıkıştırılmış) | +8 MB | `bootstrap-aarch64.zip` ~32 MB → APK'ta store-only |
| WebView (sistem) | +0 MB | Android sistem bileşeni |
| JVM (Seçenek B, opsiyonel) | +20 MB | Sadece "Geek Mode" etkinse |
| **Toplam (Seçenek A)** | **~21 MB** | Kabul edilebilir |
| **Toplam (Seçenek B)** | **~41 MB** | Sadece power user için |

---

## ⚠️ RİSKLER VE MİTİGASYON

| Risk | Olasılık | Etki | Mitigasyon |
|------|----------|------|------------|
| PRoot `proot` binary uyumsuzluğu (Android 14+) | Orta | Yüksek | Termux `proot` paketini kullan, statik derleme |
| WebView CDP portu (`localhost:9222`) çakışması | Düşük | Orta | Dinamik port atama, `WebView.setWebContentsDebuggingEnabled` kontrollü |
| Termux `apt` indirme boyutu (mobil veri) | Yüksek | Orta | Sadece Wi-Fi'da indir, kullanıcı onayı, progress UI |
| JVM (Seçenek B) GraalVM native-image kısıtlamaları | Yüksek | Yüksek | Seçenek A'yı varsayılan yap, B'yi experimental |
| Sanal ekranda WebView render performansı | Orta | Orta | `swiftshader_indirect`, boyut sınırı (720p), frame skip |
| Sidecar + Termux + WebView üçlü RAM kullanımı | Yüksek | Yüksek | 4GB+ cihaz hedef, low-memory kill callback, lazy init |

---

## 📝 KOD STANDARTLARI (Droidance'e Uygun)

- **Kotlin 1.9+**, **JVM 17**, **Android SDK 35**
- **Hilt DI** - tüm yeni sınıflar `@Singleton` `@Inject`
- **AIDL** - süreçler arası sözleşme (yeni servis yok, mevcut bridge genişletilir)
- **Markdown Rules** - `assets/rules/*.md` agent davranışı için
- **YAML Policy** - `assets/rules/harness_policy.yaml` güvenlik için
- **Room Schema Export** - veritabanı şema versiyonlama
- **Detekt + Lint + ArchUnit** - CI'da zorunlu

---

## 🚀 BAŞLANGIÇ KOMUTLARI (GELİŞTİRİCİ İÇİN)

```bash
# 1. Fork repo clone
git clone https://github.com/<SENIN_KULLANICI>/droidance-fork.git
cd droidance-fork

# 2. Codespaces aç (GitHub UI → Code → Codespaces → Create)
# VEYA local Docker:
docker run --rm -it -v "$PWD":/work -w /work mcr.microsoft.com/devcontainers/android:1-35 bash

# 3. Local build (JVM çalışan ortamda)
./gradlew :app:assembleDebug

# 4. Testleri çalıştır
./gradlew testDebugUnitTest
# Emulator başlatıp:
./gradlew connectedDebugAndroidTest

# 5. Termux bootstrap test
python3 scripts/verify_termux.py
```

---

## 📚 REFERANS KAYNAKLAR

1. **Droidance Source** - `core/device/agent/device/VirtualSpaceSidecarService.java`
2. **Termux Bootstrap** - https://github.com/termux/termux-packages/releases
3. **PRoot** - https://proot-me.github.io/
4. **Chrome DevTools Protocol** - https://chromedevtools.github.io/devtools-protocol/
5. **Android WebView CDP** - `WebView.setWebContentsDebuggingEnabled(true)`
6. **GraalVM Native Image** - https://www.graalvm.org/latest/reference-manual/native-image/
7. **Eclipse J9** - https://eclipse.dev/openj9/

---

**Son Güncelleme:** 2026-11-23  
**Yazar:** Fork Sahibi  
**Durum:** Taslak - M1 başlangıcı