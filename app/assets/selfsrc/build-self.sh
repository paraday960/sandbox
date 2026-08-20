#!/data/data/com.termux/files/usr/bin/env bash
# ══════════════════════════════════════════════════════
#  build-self.sh — سندباکس خودش را می‌سازد! 🪞
#  خروجی: SELF-APK-OK <مسیر> | نسخه <n>
# ══════════════════════════════════════════════════════
set -e
SRC="${1:-$HOME/SandBox-src}"
SRC="$(cd "$SRC" && pwd)"
PREFIX="/data/data/com.sandbox.box/files/usr"
FILES="$PREFIX/.."
BT="$PREFIX/bin"
SDKJAR="$PREFIX/android-sdk/android.jar"
KS="$FILES/selfbuild.jks"
W="$(mktemp -d)"
trap 'rm -rf "$W"' EXIT

[ -x "$BT/javac" ]   || { echo "خطا: بیلدکیت نصب نیست (تب ساخت → نصب بیلدکیت)"; exit 1; }
[ -f "$SDKJAR" ]     || { echo "خطا: android.jar نیست — بیلدکیت را نصب کن"; exit 1; }
[ -f "$KS" ]         || { echo "خطا: کلید امضا پیدا نشد"; exit 1; }
[ -f "$SRC/AndroidManifest.xml" ] || { echo "خطا: سورس استخراج نشده — دکمه‌ی استخراج سورس را بزن"; exit 1; }

# شماره‌ی نسخه: یکی بیشتر
VER=$(cat "$SRC/version.txt" 2>/dev/null || echo 14)
NEW=$((VER + 1))
echo "$NEW" > "$SRC/version.txt"
echo "── نسخه‌ی جدید: $NEW"

echo "── [1/6] javac (کامپایل سندباکس)…"
mkdir -p "$W/obj" "$W/dex" "$HOME/build"
find "$SRC/src" -name '*.java' > "$W/sources.txt"
"$BT/javac" -encoding UTF-8 -source 8 -target 8 -nowarn \
  -bootclasspath "$SDKJAR" -d "$W/obj" @"$W/sources.txt" 2>&1 | grep -v '^warning' || true
[ -n "$(find "$W/obj" -name '*.class' 2>/dev/null)" ] || { echo "خطا: کامپایل نشد"; exit 1; }

echo "── [2/6] d8…"
jar cf "$W/obj.jar" -C "$W/obj" . 2>/dev/null || (cd "$W/obj" && jar cf "$W/obj.jar" .)
"$BT/d8" --release --lib "$SDKJAR" --min-api 24 --output "$W/dex" "$W/obj.jar"

echo "── زنجیره‌ی نسل بعد: android.jar.gz…"
[ -s "$SRC/assets/android.jar.gz" ] || gzip -c "$SDKJAR" > "$SRC/assets/android.jar.gz"

echo "── [3/6] aapt2…"
"$BT/aapt2" compile --dir "$SRC/res" -o "$W/res.zip" 2>/dev/null || true
AOPT=(-o "$W/app.u.apk" -I "$SDKJAR" --manifest "$SRC/AndroidManifest.xml"
      --min-sdk-version 24 --target-sdk-version 28
      --version-code "$NEW" --version-name "9.$(($NEW - 12)).self")
[ -f "$W/res.zip" ]      && AOPT+=("$W/res.zip")
[ -d "$SRC/assets" ]     && AOPT+=(-A "$SRC/assets")
"$BT/aapt2" link "${AOPT[@]}" 2>/dev/null || "$BT/aapt" package -f -M "$SRC/AndroidManifest.xml" -S "$SRC/res" -I "$SDKJAR" -F "$W/app.u.apk" --min-sdk-version 24 --target-sdk-version 28 --version-code "$NEW" --version-name "9.self"

echo "── [4/6] افزودن dex…"
[ -s "$W/dex/classes.dex" ] || { echo "خطا: dex ساخته نشد"; exit 1; }
( cd "$W/dex" && "$BT/aapt" add "$W/app.u.apk" classes.dex >/dev/null 2>&1 ) || \
( cd "$W/dex" && zip -qj "$W/app.u.apk" classes.dex ) || true
"$BT/aapt" list "$W/app.u.apk" 2>/dev/null | grep -q classes.dex || true

echo "── [5/6] هم‌ترازی…"
if command -v zipalign >/dev/null 2>&1; then
  "$BT/zipalign" -f -p 4 "$W/app.u.apk" "$W/app.apk" 2>/dev/null || zipalign -f -p 4 "$W/app.u.apk" "$W/app.apk" || cp "$W/app.u.apk" "$W/app.apk"
else
  cp "$W/app.u.apk" "$W/app.apk"
fi

echo "── [6/6] امضا (کلید خودِ دستگاه)…"
"$BT/apksigner" sign --ks "$KS" --ks-type JKS --ks-key-alias sandbox \
  --ks-pass pass:android --key-pass pass:android \
  --out "$HOME/build/SandBox-self-$NEW.apk" "$W/app.apk"

SIZE=$(du -h "$HOME/build/SandBox-self-$NEW.apk" | cut -f1)
echo ""
echo "SELF-APK-OK $HOME/build/SandBox-self-$NEW.apk|$NEW|$SIZE"
