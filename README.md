# Droidance Fork — Android AI Agent with Terminal, Browser & JVM

**Base:** [ybblab/Droidance](https://github.com/ybblab/Droidance) v0.3.1  
**License:** Apache-2.0

---

## 🎯 Yeni Özellikler (Fork Eklentileri)

| Özellik | Açıklama | Durum |
|---------|----------|-------|
| **🖥️ Gömülü Termux Terminal** | Tam donanımlı `apt`, `pkg`, Python/Node/Git, script çalıştırma, dosya indirme | ✅ Implementasyon tamamlandı |
| **🌐 Gömülü Tarayıcı (CDP)** | Chrome DevTools Protocol ile DOM erişimi, tıklama, yazma, screenshot, iframe, JS eval | ✅ Implementasyon tamamlandı |
| **☕ Gömülü JVM/Gradle** | `kotlinc` derleme, `gradle` build, Kotlin/Java kod çalıştırma (Termux JDK veya embedded) | ✅ Implementasyon tamamlandı |

---

## 🏗️ Mimarî Genel Bakış

```
┌─────────────────────────────────────────────────────────────────────┐
│                         Droidance Fork                              │
├─────────────────────────────────────────────────────────────────────┤
│  Agent Loop (core:agent)                                            │
│    ├── ToolExecutor → DeviceToolNames.{ANDROID_READ, ANDROID_ACT,  │
│    │   SHELL, SCREEN_ACT, BROWSER_ACT, TERMUX_ACT, JVM_ACT,        │
│    │   WORKSPACE}                                                   │
│    └── ToolRiskPolicy (harness_policy.yaml)                         │
├─────────────────────────────────────────────────────────────────────┤
│  core:device                                                          │
│    ├── VirtualSpaceSidecarService (shell uid=2000)                  │
│    │   ├── VirtualDisplay + ImageReader + injectInputEvent          │
│    │   └── Supervisor (auto-restart)                                │
│    ├── BrowserManager + BrowserCdpClient (WebView + CDP)            │
│    │   ├── navigate, click, type, screenshot, eval_js, get_dom     │
│    │   └── Profile/cookie isolation                                 │
│    ├── TermuxBootstrapManager + ProotLauncher                       │
│    │   ├── bootstrap-aarch64.zip (assets) → PRoot mount             │
│    │   ├── apt/pkg/pkg install, session management                  │
│    │   └── File bridge (host ↔ Termux)                              │
│    └── JvmEmbeddedManager (optional embedded or Termux JDK)         │
│        ├── kotlinc compile & run                                    │
│        └── gradle wrapper build                                     │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 🚀 Hızlı Başlangıch

### Gereksinimler
- Android 11+ (API 30+)
- 4 GB+ RAM önerilir (Termux + Browser + JVM için)
- Kablosuz hata ayıklama (Wireless Debugging) **VEYA** Root **VEYA** Shizuku

### Kurulum
1. **APK indir:** [Releases](https://github.com/<SENIN_KULLANICI>/droidance-fork/releases/latest)
2. **Uygulamayı aç** → Onboarding ekranında izinleri ver
3. **Bağlantı yöntemi seç:**
   - **AIR (Wireless Debugging):** Geliştirici seçenekleri → Kablosuz hata ayıklama → Eşleştirme kodu gir
   - **Root:** Root erişimi ver (Magisk/KernelSU/SukiSU)
   - **Shizuku:** Shizuku uygulamasında "İzin Ver"
4. **Model sağlayıcı ekle:** OpenAI, Gemini, DeepSeek, vb. (API key + model)
5. **Görev yaz ve çalıştır**

### Yeni Araçlar Kullanımı

#### 🖥️ Termux Terminal (`termux_act`)
```json
{
  "op": "ensure_ready"
}
```
```json
{
  "op": "exec",
  "command": "apt update && apt install -y python3 git",
  "timeout_ms": 180000
}
```
```json
{
  "op": "install",
  "package_name": "nodejs-lts"
}
```
```json
{
  "op": "session_start",
  "command": "python3 -m http.server 8080"
}
```

#### 🌐 Gömülü Tarayıcı (`browser_act`)
```json
{
  "op": "create_profile",
  "profile_name": "github",
  "viewport_width": 1280,
  "viewport_height": 720
}
```
```json
{
  "op": "launch",
  "profile_id": "profile_1"
}
```
```json
{
  "op": "navigate",
  "webview_id": 1,
  "url": "https://github.com/trending"
}
```
```json
{
  "op": "click",
  "webview_id": 1,
  "selector": "article.Box-row h2 a"
}
```
```json
{
  "op": "get_dom",
  "webview_id": 1,
  "selector": "article.Box-row"
}
```
```json
{
  "op": "eval_js",
  "webview_id": 1,
  "expression": "document.querySelectorAll('article.Box-row').length"
}
```

#### ☕ JVM/Kotlin (`jvm_act`)
```json
{
  "op": "compile_run",
  "source_code": "fun main() { println(\"Merhaba from Kotlin!\") }"
}
```
```json
{
  "op": "gradle_build",
  "project_dir": "/data/data/com.droidance.fork/files/workspace/my-project",
  "tasks": ["build", "test"]
}
```

---

## 🛠️ Geliştirme Ortamı

### GitHub Codespaces (Önerilen)
1. Repository → **Code** → **Codespaces** → **Create codespace on main**
2. Otomatik olarak Android SDK, Emulator, Termux bootstrap hazırlanır
3. Terminalde:
   ```bash
   ./gradlew :app:assembleDebug
   # Emulator başlat:
   emulator -avd droidance_test -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect &
   ./gradlew connectedDebugAndroidTest
   ```

### Local Docker
```bash
docker run --rm -it \
  -v "$PWD":/work -w /work \
  --device=/dev/kvm \
  --cap-add=SYS_ADMIN --cap-add=CAP_DAC_OVERRIDE \
  --security-opt=seccomp=unconfined \
  mcr.microsoft.com/devcontainers/android:1-35 bash

# İçeride:
./gradlew :app:assembleDebug
```

### CI/CD (GitHub Actions)
- **Push/PR:** Static analysis (detekt, lint), Unit test, Instrumented test (emulator), Debug APK build
- **Tag push:** Release APK build, sign, GitHub Release draft
- **Workflow dosyaları:** `.github/workflows/ci.yml`, `release.yml`

---

## 📦 APK Boyutu

| Yapılandırma | Tahmini Boyut |
|--------------|---------------|
| Base Droidance | ~13 MB |
| + Termux Bootstrap (assets) | +8 MB |
| + Embedded JVM (opsiyonel, "Geek Mode") | +20 MB |
| **Toplam (Standart)** | **~21 MB** |
| **Toplam (Geek Mode)** | **~41 MB** |

> Termux bootstrap APK içinde `assets/termux/bootstrap-aarch64.zip` olarak saklanır, ilk kullanımda PRoot'ta açılır.

---

## 🔐 Güvenlik Modeli

| Bileşen | İzolasyon | Yetki |
|---------|-----------|-------|
| **Sidecar (shell uid=2000)** | AIDL + Binder + `linkToDeath` | VirtualDisplay, input injection, `am start --display` |
| **Termux (PRoot)** | Mount namespace, seccomp | `apt`, `pkg`, kendi rootfs, network (CleartextNetworkPolicy) |
| **Browser (WebView)** | Sanal ekranda gizli, CDP localhost | HTTP(S), DOM, JS, cookie/profile isolation |
| **JVM** | Termux içinde veya embedded | `kotlinc`, `gradle`, `java` |
| **Agent (app UID)** | ToolRiskPolicy + HighRiskConfirmationStore | Orchestration, model API |

**Yeni Onay Gerektiren Araçlar (60 sn):**
- `browser_act` — Navigasyon, tıklama, yazma
- `termux_act` — `apt install`, script çalıştırma
- `jvm_act` — Derleme, gradle build

**Shell Yasak Kalıplar (genişletildi):**
- `proot --mount`, `chroot`, `nsenter` — escape denemeleri
- `apt remove/purge`, `pkg uninstall` — sistem paket kaldırma

---

## 🧪 Test

```bash
# Unit testler (Robolectric)
./gradlew testDebugUnitTest

# Entegrasyon testleri (Emulator API 35 ARM64 gerekli)
./gradlew connectedDebugAndroidTest

# Static analysis
./gradlew detekt :app:lintDebug verifyArchitecture

# Termux bootstrap doğrulama
python3 scripts/verify_termux.py
```

---

## 📚 Belge İçi Referanslar

- [FORK_DESIGN.md](FORK_DESIGN.md) — Detaylı teknik tasarım, milestones, riskler
- [app/src/main/assets/rules/harness_policy.yaml](app/src/main/assets/rules/harness_policy.yaml) — Güvenlik politikası
- [core/device/src/main/java/com/droidance/core/device/termux/](core/device/src/main/java/com/droidance/core/device/termux/) — Termux modülü
- [core/device/src/main/java/com/droidance/core/device/browser/](core/device/src/main/java/com/droidance/core/device/browser/) — Browser modülü
- [core/device/src/main/java/com/droidance/core/device/jvm/](core/device/src/main/java/com/droidance/core/device/jvm/) — JVM modülü

---

## 🤝 Katkı

1. Fork → Feature branch → PR
2. `./gradlew detekt testDebugUnitTest connectedDebugAndroidTest` yeşil olmalı
3. `harness_policy.yaml` güncellemesi gerekiyorsa güvenlik incelemesi istenir
4. Yeni araç eklendiğinde `DeviceToolNames` + `ToolRiskPolicy` + `harness_policy.yaml` güncellenmeli

---

## ⚖️ Lisans

Apache-2.0 — Orijinal Droidance lisansı korunur. Değişiklikler `@author` etiketi ile işaretlenir.

---

## 🙏 Teşekkürler

- [ybblab/Droidance](https://github.com/ybblab/Droidance) — Harika temel proje
- [Termux](https://termux.dev/) — PRoot bootstrap ve paketler
- [Chrome DevTools Protocol](https://chromedevtools.github.io/devtools-protocol/) — Browser otomasyon standardı