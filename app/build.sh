#!/usr/bin/env bash
# بیلد اپ «سندباکس» — بدون Gradle، مستقیم با ابزارهای SDK
set -euo pipefail
JDK=/usr/lib/jvm/jdk-11
export JAVA_HOME=$JDK
JAR_BIN="$JDK/bin/jar"

ROOT="$(cd "$(dirname "$0")" && pwd)"

# ═══ بازگردانی خودکار SDK از فشرده‌ی ورک‌اسپیس (اگر سندباکس ریست شده باشد)
if [ ! -x /opt/asdk/build-tools/35.0.0/aapt2 ] && [ -f /home/user/.tools/asdk.tar.gz ]; then
  echo "[0/6] بازگردانی SDK از فشرده…"
  sudo rm -rf /opt/asdk && sudo mkdir -p /opt/asdk
  sudo tar xzf /home/user/.tools/asdk.tar.gz -C /opt/asdk
fi

SDK=/opt/asdk
BT=$SDK/build-tools/35.0.0
PLAT=$SDK/platforms/android-34/android.jar

# android.jar از assets (در فشرده‌ی SDK نیست تا حجم ورک‌اسپیس کم بماند)
if [ ! -f "$PLAT" ]; then
  python3 -c "import gzip,shutil; shutil.copyfileobj(gzip.open('$ROOT/assets/android.jar.gz'), open('/tmp/android.jar','wb'))"
  sudo mkdir -p "$SDK/platforms/android-34"
  sudo mv /tmp/android.jar "$PLAT"
fi

W=$ROOT/work
OUT=/home/user/SandBox.apk

rm -rf "$W"
mkdir -p "$W/obj" "$W/dex"

echo "[1/6] javac..."
find "$ROOT/src" -name '*.java' > "$W/sources.txt"
"$JDK/bin/javac" -encoding UTF-8 -source 8 -target 8 -nowarn \
  -bootclasspath "$PLAT" -d "$W/obj" @"$W/sources.txt" 2>&1 | grep -v -E 'warning|deprecat|bootstrap' || true

echo "[2/6] d8 (dex)..."
"$JAR_BIN" cf "$W/obj.jar" -C "$W/obj" .
"$BT/d8" --release --lib "$PLAT" --min-api 24 --output "$W/dex" "$W/obj.jar"

echo "[3/6] aapt2 (resources + assets)..."
"$BT/aapt2" compile --dir "$ROOT/res" -o "$W/res.zip"
"$BT/aapt2" link -o "$W/app.unsigned.apk" -I "$PLAT" \
  --manifest "$ROOT/AndroidManifest.xml" -A "$ROOT/assets" "$W/res.zip" \
  --min-sdk-version 24 --target-sdk-version 28 \
  --version-code 16 --version-name 9.4

echo "[4/6] افزودن classes.dex..."
(cd "$W/dex" && zip -q -j "$W/app.unsigned.apk" classes.dex)

echo "[5/6] zipalign..."
"$BT/zipalign" -f -p 4 "$W/app.unsigned.apk" "$W/app.aligned.apk"

echo "[6/6] امضا..."
if [ ! -f "$ROOT/keys/debug.jks" ]; then
  mkdir -p "$ROOT/keys"
  "$JDK/bin/keytool" -genkeypair -keystore "$ROOT/keys/debug.jks" -storetype JKS \
    -storepass android -keypass android -alias sandbox \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=SandBox, O=SandBox, C=IR" 2>/dev/null
fi
"$BT/apksigner" sign --ks "$ROOT/keys/debug.jks" --ks-type JKS --ks-key-alias sandbox \
  --ks-pass pass:android --key-pass pass:android \
  --out "$OUT" "$W/app.aligned.apk"

"$BT/apksigner" verify "$OUT" && echo "== SIGN OK =="
ls -lh "$OUT"
