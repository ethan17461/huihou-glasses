#!/bin/bash
# 在安卓设备（模拟器或真眼镜）上把会后纪要完整跑一遍，并把镜片上的每一步截成高清图存进 evidence/
#
# 用法：scripts/device-evidence.sh [截图文件名前缀]
#   默认前缀：03-眼镜模拟器
#   真眼镜上：scripts/device-evidence.sh 04-眼镜真机
#
# 说明：
# - 配置用 secrets.properties；测试会议里“我”是林然，所以推到设备上的配置把 USER_NAMES 设成林然
# - 默认会把纪要推到飞书群；不想推时加环境变量 NO_FEISHU=1
set -u
cd "$(dirname "$0")/.." || exit 1

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="$SDK/platform-tools/adb"
PKG=com.huihou.glasses
APK=dist/huihou-glasses-1.0.apk
PREFIX="${1:-03-眼镜模拟器}"
OUT=evidence
mkdir -p "$OUT"

say() { echo "[$(date +%H:%M:%S)] $*"; }

say "等待设备开机…"
"$ADB" wait-for-device
until [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 2; done
say "设备：$("$ADB" shell getprop ro.product.model | tr -d '\r')，安卓 $("$ADB" shell getprop ro.build.version.release | tr -d '\r')，屏幕 $("$ADB" shell wm size | tr -d '\r' | awk '{print $NF}')"

say "安装 App"
"$ADB" install -r "$APK" >/dev/null || { echo "安装失败"; exit 1; }
# 刚开机时包管理器可能还没就绪，打开 App 失败就重试；App 第一次打开才会建好它的文件夹
for i in $(seq 1 15); do
  if ! "$ADB" shell am start -W -n "$PKG/.GlassesActivity" 2>&1 | grep -q "Error"; then break; fi
  sleep 2
done
for i in $(seq 1 20); do
  "$ADB" shell ls "/sdcard/Android/data/$PKG/files/inbox" >/dev/null 2>&1 && break
  sleep 1
done
"$ADB" shell ls "/sdcard/Android/data/$PKG/files/inbox" >/dev/null 2>&1 || { echo "App 的文件夹没建好"; exit 1; }
sleep 1
"$ADB" shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow
"$ADB" shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null

say "推配置和测试会议"
TMP=$(mktemp)
if [ "${NO_FEISHU:-0}" != "1" ]; then
  grep -v '^USER_NAMES=' secrets.properties > "$TMP"
else
  grep -v -E '^(USER_NAMES|FEISHU_WEBHOOK|FEISHU_SECRET)=' secrets.properties > "$TMP"
fi
echo "USER_NAMES=林然" >> "$TMP"
"$ADB" push "$TMP" "/sdcard/Android/data/$PKG/files/config.properties" >/dev/null
rm -f "$TMP"
"$ADB" push testdata/review-meeting.txt "/sdcard/Android/data/$PKG/files/inbox/review-meeting.txt" >/dev/null

# 回到桌面，再“散会”：镜片提示是从后台弹出来的
"$ADB" shell input keyevent KEYCODE_HOME
sleep 1

# App 日志里某个镜片状态（例如 镜片[NOTICE]）出现了几次
count_state() { "$ADB" logcat -d -s Huihou:I 2>/dev/null | grep -c "镜片\[$1\]"; }

# 等这个状态比 before 次数多出现一次
wait_state() {
  local state="$1" timeout="$2" before="${3:-0}" end=$((SECONDS + $2))
  while [ $SECONDS -lt $end ]; do
    [ "$(count_state "$state")" -gt "$before" ] && return 0
    sleep 0.3
  done
  echo "等了 ${timeout} 秒没等到 $state"
  return 1
}

shot() {
  sleep 0.7
  "$ADB" exec-out screencap -p > "$OUT/$PREFIX-$1.png"
  say "截图 $OUT/$PREFIX-$1.png（$(sips -g pixelWidth -g pixelHeight "$OUT/$PREFIX-$1.png" 2>/dev/null | awk '/pixel/{printf "%s ", $2}')）"
}

step() { # 按键 → 等新状态出现 → 截图
  local key="$1" state="$2" name="$3" before
  before=$(count_state "$state")
  "$ADB" shell input keyevent "$key"
  wait_state "$state" 5 "$before" && shot "$name"
}

say "散会：把会议文字交给 App"
"$ADB" logcat -c
T0=$SECONDS
"$ADB" shell am broadcast -a com.huihou.glasses.MEETING_ENDED -n "$PKG/.MeetingEndedReceiver" \
  --es transcript_file inbox/review-meeting.txt >/dev/null
wait_state WORKING 10 && shot "1-生成中"

wait_state NOTICE 90 || { "$ADB" logcat -d -s Huihou:I | tail -20; exit 1; }
shot "2-通知"
say "从散会到弹出通知：$((SECONDS - T0)) 秒"

step KEYCODE_DPAD_CENTER TODO  "3-单击-第1条待办"
step KEYCODE_DPAD_CENTER QUOTE "4-再单击-第1条原话"
step KEYCODE_DPAD_RIGHT  TODO  "5-往后滑-第2条待办"
step KEYCODE_DPAD_CENTER QUOTE "6-再单击-第2条原话"

say "镜片日志："
"$ADB" logcat -d -s Huihou:I | grep "镜片\[" | sed 's/^.*Huihou *: */  /'
say "完成"
