#!/data/data/com.termux/files/usr/bin/env bash
# ══════════════════════════════════════════════════════
#  make-apk.sh — کامپایل و امضای APK روی خود گوشی
#  استفاده:  make-apk.sh <پوشه‌ی پروژه>
#  خروجی:    /home/build/<نام>.apk  + خط "APK-OK <مسیر>"
# ══════════════════════════════════════════════════════
set -e
PROJ="$1"
if [ -z "$PROJ" ] || [ ! -d "$PROJ" ]; then
  echo "خطا: پوشه‌ی پروژه پیدا نشد: $PROJ"
  exit 1
fi
PROJ="$(cd "$PROJ" && pwd)"
NAME="$(basename "$PROJ")"
PREFIX="/data/data/com.sandbox.box/files/usr"
HOME_DIR="$PREFIX/../home"
SDKJAR="$PREFIX/android-sdk/android.jar"
BT="$PREFIX/bin"
OUT="$HOME_DIR/build/$NAME"
W="$(mktemp -d)"
trap 'rm -rf "$W"' EXIT

[ -x "$BT/javac" ]  || { echo "خطا: javac نیست — بیلدکیت را نصب کن"; exit 1; }
[ -x "$BT/d8" ]    || { echo "خطا: d8 نیست — بیلدکیت را نصب کن"; exit 1; }
[ -f "$SDKJAR" ]   || { echo "خطا: android.jar نیست — بیلدکیت را نصب کن"; exit 1; }

echo "── [1/6] javac (کامپایل جاوا)…"
mkdir -p "$W/obj" "$W/dex" "$OUT"
find "$PROJ/src" -name '*.java' > "$W/sources.txt"
javac -encoding UTF-8 -source 8 -target 8 -nowarn \
  -bootclasspath "$SDKJAR" -classpath "$SDKJAR" \
  -d "$W/obj" @"$W/sources.txt" 2>&1 | grep -v '^warning' || true
[ -n "$(find "$W/obj" -name '*.class' 2>/dev/null)" ] || { echo "خطا: کامپایل نشد"; exit 1; }

echo "── [2/6] d8 (تبدیل به dex)…"
jar cf "$W/obj.jar" -C "$W/obj" . 2>/dev/null || (cd "$W/obj" && jar cf "$W/obj.jar" .)
"$BT/d8" --release --lib "$SDKJAR" --min-api 24 --output "$W/dex" "$W/obj.jar"

echo "── [3/6] aapt (بسته‌بندی منابع)…"
MANIFEST="$PROJ/AndroidManifest.xml"
if [ -x "$BT/aapt2" ]; then
  "$BT/aapt2" compile --dir "$PROJ/res" -o "$W/res.zip" 2>/dev/null || true
  AOPT=(-o "$W/app.unsigned.apk" -I "$SDKJAR" --manifest "$MANIFEST"
        --min-sdk-version 24 --target-sdk-version 28 --version-code 1 --version-name 1.0)
  [ -f "$W/res.zip" ] && AOPT+=("$W/res.zip")
  [ -d "$PROJ/assets" ] && AOPT+=(-A "$PROJ/assets")
  "$BT/aapt2" link "${AOPT[@]}"
else
  "$BT/aapt" package -f -M "$MANIFEST" -S "$PROJ/res" -I "$SDKJAR" \
    -F "$W/app.unsigned.apk" --min-sdk-version 24 --target-sdk-version 28 \
    --version-code 1 --version-name 1.0
  [ -d "$PROJ/assets" ] && "$BT/aapt" add -A "$PROJ/assets" "$W/app.unsigned.apk" 2>/dev/null || true
fi

echo "── [4/6] افزودن classes.dex…"
[ -s "$W/dex/classes.dex" ] || { echo "خطا: classes.dex ساخته نشد"; exit 1; }
( cd "$W/dex" && "$BT/aapt" add "$W/app.unsigned.apk" classes.dex >/dev/null 2>&1 ) || \
( cd "$W/dex" && zip -qj "$W/app.unsigned.apk" classes.dex ) || true
"$BT/aapt" list "$W/app.unsigned.apk" 2>/dev/null | grep -q classes.dex || \
  ls "$W/dex/classes.dex" >/dev/null || { echo "خطا: classes.dex اضافه نشد"; exit 1; }

echo "── [5/6] هم‌ترازی…"
if command -v zipalign >/dev/null 2>&1; then
  "$BT/zipalign" -f -p 4 "$W/app.unsigned.apk" "$W/app.apk" 2>/dev/null || \
    zipalign -f -p 4 "$W/app.unsigned.apk" "$W/app.apk" || cp "$W/app.unsigned.apk" "$W/app.apk"
else
  cp "$W/app.unsigned.apk" "$W/app.apk"
fi

echo "── [6/6] امضا…"
KS="$HOME_DIR/.buildkey.jks"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
    -alias sandbox -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=SandBox Builder, O=SandBox" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KS" --ks-key-alias sandbox \
  --ks-pass pass:android --key-pass pass:android \
  --out "$OUT/$NAME.apk" "$W/app.apk"

SIZE=$(du -h "$OUT/$NAME.apk" | cut -f1)
echo ""
echo "APK-OK $OUT/$NAME.apk"
echo "حجم: $SIZE — در پوشه‌ی Download/SandBox هم کپی شد."
