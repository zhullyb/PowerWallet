#!/usr/bin/env bash
# PowerWallet for Linux: build the companion APK and drive the legacy key helper over ADB.
# (On ROMs with native launch_QAW_wallet, e.g. Xiaomi 15 HyperOS 4, the app itself switches
#  modes via wireless debugging and the helper is not needed; "build" is still useful.)
#   ./powerwallet.sh build                 # needs JDK 17+, Android SDK (ANDROID_HOME)
#   ./powerwallet.sh probe                 # read-only device facts
#   ./powerwallet.sh activate [--force]    # install + start helper (--force: unverified ROM)
#   ./powerwallet.sh mode google|mi        # double-press opens Google Wallet / native Mi Pay
#   ./powerwallet.sh status | restore | log
# Env: POWERWALLET_MARKER (default google_pay) = value written to double_click_power_key.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(dirname "$HERE")
OUT=${POWERWALLET_BUILD:-$HERE/build}
APK=$OUT/PowerWallet-linux.apk
JAR=/data/local/tmp/powerwallet-helper.jar
WALLET=com.google.android.apps.walletnfcrel
MARKER=${POWERWALLET_MARKER:-google_pay}
ADB=(adb ${ANDROID_SERIAL:+-s "$ANDROID_SERIAL"})
die() { echo "ERROR: $*" >&2; exit 1; }
sh_() { "${ADB[@]}" shell "$@"; }

build() {
  local sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
  [ -n "$sdk" ] || die "set ANDROID_HOME"
  local bt; bt=$(ls -d "$sdk"/build-tools/* | sort -V | tail -1)
  local jar; jar=$(ls "$sdk"/platforms/android-*/android.jar | sort -V | tail -1)
  rm -rf "$OUT/classes" "$OUT/dex" "$OUT/tests"; mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/tests"
  javac -encoding UTF-8 --release 8 -cp "$jar" -d "$OUT/classes" $(find "$ROOT/android/src" -name '*.java') 2>&1 | grep -v 'warning' || true
  [ -f "$OUT/classes/cn/haiyu/powerwallet/Helper.class" ] || die "javac failed"
  javac -encoding UTF-8 -cp "$OUT/classes" -d "$OUT/tests" "$ROOT/android/test/PressDetectorTest.java"
  java -cp "$OUT/classes:$OUT/tests" PressDetectorTest
  (cd "$OUT/classes" && jar cf "$OUT/classes.jar" .)
  "$bt/d8" --lib "$jar" --min-api 28 --output "$OUT/dex" "$OUT/classes.jar"
  "$bt/aapt2" compile --dir "$ROOT/android/res" -o "$OUT/res.zip"
  "$bt/aapt2" link -I "$jar" --manifest "$ROOT/android/AndroidManifest.xml" -o "$OUT/unsigned.apk" "$OUT/res.zip"
  (cd "$OUT/dex" && jar uf "$OUT/unsigned.apk" classes.dex)
  "$bt/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
  local ks=$OUT/powerwallet-personal.jks
  [ -f "$ks" ] || keytool -genkeypair -keystore "$ks" -alias powerwallet -keyalg RSA -keysize 3072 \
      -validity 10000 -storepass powerwallet-local -keypass powerwallet-local -dname 'CN=PowerWallet Personal Build'
  "$bt/apksigner" sign --ks "$ks" --ks-key-alias powerwallet --ks-pass pass:powerwallet-local \
      --key-pass pass:powerwallet-local --out "$APK" "$OUT/aligned.apk"
  echo "Built: $APK"
}

probe() {
  for p in ro.product.manufacturer ro.product.device ro.product.marketname ro.build.version.incremental \
           ro.build.version.release ro.mi.os.version.name; do
    printf '%-32s %s\n' "$p" "$(sh_ getprop $p | tr -d '\r')"
  done
  printf '%-32s %s\n' double_click_power_key "$(sh_ settings get system double_click_power_key | tr -d '\r')"
  printf '%-32s %s\n' "Google Wallet" "$(sh_ pm path $WALLET >/dev/null 2>&1 && echo installed || echo MISSING)"
  echo "--- power-key input devices:"
  sh_ getevent -lp 2>/dev/null | awk '/^add device/{d=$0} /KEY_POWER/{print d}' | sort -u
}

helper() { sh_ "CLASSPATH=$JAR app_process /system/bin cn.haiyu.powerwallet.Helper $*"; }

activate() {
  local force=0; [ "${1:-}" = --force ] && force=1
  [ -f "$APK" ] || build
  sh_ pm path $WALLET >/dev/null 2>&1 || die "Google Wallet is not installed"
  sh_ "[ -f $JAR ] && { CLASSPATH=$JAR app_process /system/bin cn.haiyu.powerwallet.Helper --handoff >/dev/null 2>&1 || CLASSPATH=$JAR app_process /system/bin cn.haiyu.powerwallet.Helper --stop >/dev/null 2>&1; }; true"
  "${ADB[@]}" install -r "$APK" | grep -q '^Success' || die "APK install failed (allow USB install on the phone; uninstall other-signed cn.haiyu.powerwallet first)"
  "${ADB[@]}" push "$APK" "$JAR" >/dev/null
  sh_ chmod 600 "$JAR"
  sh_ "pm grant cn.haiyu.powerwallet android.permission.WRITE_SECURE_SETTINGS >/dev/null 2>&1; POWERWALLET_MARKER=$MARKER POWERWALLET_ALLOW_UNVERIFIED=$force CLASSPATH=$JAR nohup app_process /system/bin cn.haiyu.powerwallet.Helper </dev/null >/data/local/tmp/powerwallet.log 2>&1 &"
  for _ in 1 2 3 4 5 6; do
    sleep 0.7
    if out=$(helper --status 2>/dev/null) && [[ $out == 已激活* ]]; then echo "$out"; echo "OK: double-press power to test; you may unplug."; return; fi
  done
  sh_ tail -n 15 /data/local/tmp/powerwallet.log; die "helper did not become active"
}

restore() {
  [ -f "$APK" ] || build
  "${ADB[@]}" push "$APK" /data/local/tmp/powerwallet-control.jar >/dev/null
  sh_ "CLASSPATH=/data/local/tmp/powerwallet-control.jar app_process /system/bin cn.haiyu.powerwallet.Helper --restore"
}

case ${1:-} in
  build) build ;;
  probe) probe ;;
  activate) shift; activate "$@" ;;
  status) helper --status ;;
  mode) [[ ${2:-} =~ ^(google|mi)$ ]] || die "usage: mode google|mi"; helper --mode "$2" ;;
  restore) restore ;;
  log) sh_ cat /data/local/tmp/powerwallet.log ;;
  *) sed -n 2,11p "$0"; exit 1 ;;
esac
