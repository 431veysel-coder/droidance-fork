#!/usr/bin/env bash
set -euo pipefail

echo "🚀 Droidance Fork Codespaces Setup Starting..."

# Android SDK ve Emulator hazır
export ANDROID_SDK_ROOT=/opt/android-sdk
export PATH=$PATH:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools:$ANDROID_SDK_ROOT/emulator

# Gradle wrapper izinleri
chmod +x ./gradlew

# Termux bootstrap indir (validation için)
echo "📦 Termux bootstrap indiriliyor..."
BOOTSTRAP_URL="https://github.com/termux/termux-packages/releases/latest/download/bootstrap-aarch64.zip"
wget -q "$BOOTSTRAP_URL" -O /tmp/termux-bootstrap.zip
unzip -l /tmp/termux-bootstrap.zip | head -20

# KVM kontrolü (emulator hızlandırma için)
if [[ -e /dev/kvm ]]; then
  echo "✅ KVM mevcut - emulator hızlandırma aktif"
  sudo chmod 666 /dev/kvm || true
else
  echo "⚠️ KVM yok - emulator yavaş çalışacak (software rendering)"
fi

# ADB server başlat
adb kill-server 2>/dev/null || true
adb start-server
echo "📱 ADB server başladı"

# Emulator AVD oluştur (API 35 ARM64)
AVD_NAME="droidance_test"
if ! avdmanager list avd | grep -q "Name: $AVD_NAME"; then
  echo "📱 AVD oluşturuluyor: $AVD_NAME"
  echo "no" | avdmanager create avd -n "$AVD_NAME" -k "system-images;android-35;google_apis;arm64" --device "pixel" --force
else
  echo "✅ AVD zaten var: $AVD_NAME"
fi

# Pre-build dependency cache
echo "📥 Gradle bağımlılıkları önbelleğe alınıyor..."
./gradlew --offline :app:dependencies 2>/dev/null || ./gradlew :app:dependencies --no-daemon

# Detekt cache
./gradlew detekt --offline 2>/dev/null || true

echo "✅ Codespaces hazır! Emülatör başlatmak için:"
echo "   emulator -avd $AVD_NAME -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -memory 4096 -cores 4 &"
echo ""
echo "🔧 Yaygın komutlar:"
echo "   ./gradlew :app:assembleDebug          # Debug APK build"
echo "   ./gradlew testDebugUnitTest           # Unit testler"
echo "   ./gradlew connectedDebugAndroidTest   # Instrumented testler (emulator gerekli)"
echo "   ./gradlew detekt                      # Static analysis"
echo "   adb devices                           # Cihazları listele"