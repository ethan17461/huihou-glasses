# 会后纪要 · AI 眼镜（需求文档第六章落地）

散会后：整场会的文字 → qwen-plus 生成纪要 → 逐条核对原话 → 眼镜底部弹一条提示 + 飞书推一张待办卡片。
只处理文字，不碰录音。开会时的录音转写归会中模块，这里从“散会”开始。

## 下载

- **眼镜 App（安卓 14）**：[huihou-glasses-1.0.apk](https://github.com/ethan17461/huihou-glasses/releases/download/v1.0/huihou-glasses-1.0.apk)
- 所有版本：[Releases](https://github.com/ethan17461/huihou-glasses/releases)

APK 里不带任何密钥。装好后按下面第三步，把自己的配置推进眼镜。

## 实测截图（2026-11-09）

镜片上依次看到的画面（真 App 运行在安卓 14 模拟器上）：

![镜片画面六步](evidence/03-%E7%9C%BC%E9%95%9C%E7%94%BB%E9%9D%A2-%E5%85%AD%E6%AD%A5%E6%8B%BC%E5%9B%BE.png)

散会后飞书群收到的待办卡片（由眼镜 App 自动推送）：

![飞书待办卡片](evidence/02-%E9%A3%9E%E4%B9%A6%E5%BE%85%E5%8A%9E%E5%8D%A1%E7%89%87.png)

| 目录 | 内容 |
|---|---|
| `core/` | 纪要生成、原话核对、眼镜文案、飞书卡片。纯 Kotlin，电脑和眼镜共用 |
| `cli/` | 电脑上用真实接口跑整条链路，并按验收标准对答案 |
| `app/` | 眼镜端安卓 App（安卓 14，包名 `com.huihou.glasses`） |
| `testdata/` | 测试会议文字 `review-meeting.txt` 和标准答案 `review-meeting.expected.json` |
| `dist/` | 打好的 APK |

## 一、配置（只填一次）

编辑项目根目录的 `secrets.properties`（UTF-8，不要提交、不要发群里）：

| 项 | 必填 | 说明 |
|---|---|---|
| `DASHSCOPE_API_KEY` | 是 | 阿里云百炼 API Key（北京地域） |
| `FEISHU_WEBHOOK` | 否 | 飞书群机器人地址。建一个只有自己的群 → 设置 → 群机器人 → 添加自定义机器人 |
| `FEISHU_SECRET` | 否 | 机器人开了“签名校验”才填 |
| `USER_NAMES` | 建议 | 戴眼镜的人在会上被怎么称呼，逗号隔开，如 `林然,小林`。不填就显示全部待办 |
| `DASHSCOPE_BASE_URL` / `QWEN_MODEL` | 否 | 一般不用改 |

同一个文件既给电脑上的 `cli` 用，也直接推进眼镜当 `config.properties` 用（见第三步），APK 里不带 Key。

## 二、电脑上验收（真实接口）

本机的 JDK 不在系统路径里，先执行一次：

```bash
export JAVA_HOME="$HOME/.local/jdk-17.0.21+9/Contents/Home"
```

```bash
./gradlew :cli:run --args="testdata/review-meeting.txt --expect testdata/review-meeting.expected.json"
```

会打印：用时、眼镜上依次看到的每一行、纪要全文、核对时丢掉的条目、飞书是否送达，最后逐项打 ✓/✗：

| 项 | 标准 |
|---|---|
| 速度 | 生成纪要 ≤ 60 秒 |
| 待办漏抽 | 标准答案必有 5 条，漏 ≤ 1 条（另有 1 条可有可无：“细化两套方案”和“发设计稿”可以合并） |
| 凭空多出 | 0 条（原话在会议文字里找不到的待办会被自动丢掉） |
| 我的待办 | “我的”标记全对 |
| 不用记 | 会上说了“这个不用记”的内容（调薪）0 次出现 |
| 有据可查 | 每条待办都带原话 |
| 飞书 | 手机收到卡片 |

换自己的会议文字：`./gradlew :cli:run --args="你的文字.txt"`（任何格式都行，带时间和说话人效果更好）。

## 三、装进眼镜

需要：眼镜开了 USB 调试、专用线、电脑上有 adb。

```bash
adb install -r dist/huihou-glasses-1.0.apk
# 新装的 App 要先打开一次，否则收不到散会广播
adb shell am start -n com.huihou.glasses/.GlassesActivity
# 允许纪要生成好时从后台弹到镜片上（不开的话只会发一条系统通知）
adb shell appops set com.huihou.glasses SYSTEM_ALERT_WINDOW allow
adb shell pm grant com.huihou.glasses android.permission.POST_NOTIFICATIONS
# 把配置推进去（就是第一步那个文件）
adb push secrets.properties /sdcard/Android/data/com.huihou.glasses/files/config.properties
```

## 四、在眼镜上跑一次

```bash
adb push testdata/review-meeting.txt /sdcard/Android/data/com.huihou.glasses/files/inbox/review-meeting.txt
adb shell am broadcast -a com.huihou.glasses.MEETING_ENDED -n com.huihou.glasses/.MeetingEndedReceiver --es transcript_file inbox/review-meeting.txt
adb logcat -s Huihou
```

应该看到：镜片出现 “…” → 1 分钟内一行冷色通知（`USER_NAMES=林然` 时是“评审会纪要已生成 · 你有2条待办”）→ 手机飞书收到卡片。

## 五、镜片上的操作

| 操作 | 效果 |
|---|---|
| 单击 | 通知 → 第 1 条我的待办（橙色）→ 这条是谁说的、原话（小字，长了会滚动） |
| 双击 / 返回键 | 退一层，在通知上双击就关掉 |
| 前后滑动 | 上一条 / 下一条待办 |
| 长按 | 念给我听；任意操作停止 |
| 不操作 | 通知 5 秒、浏览 8 秒后自动消失 |

按需求文档第七章“眼镜端显示规范”：只在视野底部边缘显示；主行不超过 15 个字，最多再加一行小字；细边框、黑底（光波导上黑色 = 透明）；蓝色是提醒和答案，橙色是答应别人的事；字号 22 往下缩，最小 17。

飞书卡片只放“我的待办”和“其他人的待办”；结论、还没定的事保存在纪要文件里，放到完整会议纪要总结中展示。

## 六、眼镜到手后要确认的三件事

1. **镜腿发的是什么输入**：开着 `adb logcat -s Huihou`，单击、双击、前后滑、长按各做一次，看“输入”那几行。按键、触摸、触控板滚动都已经接了；对不上就把按键码告诉我，改 `GlassesActivity` 里的 `CENTER_KEYS / NEXT_KEYS / PREV_KEYS`。
2. **屏幕分辨率**：`adb shell wm size`。字号在 `GlassesActivity` 的 `MIN_SP / MAX_SP`。
3. **能不能联网**：眼镜连 Wi-Fi 或手机热点，`adb shell ping -c 1 dashscope.aliyuncs.com`。断网时会等联网后自动重试。

## 七、给会中模块的接口

散会时发一个广播，把整场会的文字交过来：

```kotlin
val intent = Intent("com.huihou.glasses.MEETING_ENDED")
    .setPackage("com.huihou.glasses")
    .putExtra("transcript_text", fullTranscript) // 整场会的文字，格式不限，最好带时间和说话人
    .putExtra("meeting_title", "评审会")           // 可选
context.sendBroadcast(intent)
```

| 参数 | 说明 |
|---|---|
| `transcript_text` | 会议文字（字符串）。三选一 |
| `transcript_uri` | `content://` 地址，发送方加 `FLAG_GRANT_READ_URI_PERMISSION`。三选一 |
| `transcript_file` | 本 App 外部目录下的相对路径，如 `inbox/xx.txt`（adb 测试用）。三选一 |
| `meeting_title` | 可选，会议名称；不给就由模型从内容推断 |

推荐的文字格式（跟飞书妙记导出差不多）：`[01:44] 林然：排期我周五前发给大家。`

## 八、存了什么

每场会一个文件夹（App 私有目录 `files/meetings/时间戳/`）：`transcript.txt` 会议文字、`minutes.json` 纪要、`feishu.ok` 或 `feishu.error`。只有文字，没有录音。`minutes.json` 也可以给会前、会中当“上次会的结论”用。

## 九、已知限制

- 转写分不出谁在说话时，“我的待办”只能靠称呼和上下文判断，可能判错；所以每条都附原话，方便核对。
- 散会入口没加权限，方便会中模块和 adb 调用；眼镜是个人设备，风险可控。
- 需要联网。会议文字上限 12 万字（约 6 小时）。

## 重新打包

本机已装 JDK 17 和安卓 SDK：

```bash
export JAVA_HOME="$HOME/.local/jdk-17.0.21+9/Contents/Home"
./gradlew :core:test :app:assembleDebug
cp app/build/outputs/apk/debug/app-debug.apk dist/huihou-glasses-1.0.apk
```

默认打出来的 APK 不带任何密钥，可以放心公开。只给自己用、想把 `secrets.properties` 打进 APK 时，加 `-PembedSecrets=true`（这样的 APK 不要外传）。

在模拟器或真眼镜上一键跑一遍并截图：`scripts/device-evidence.sh`（真机用 `scripts/device-evidence.sh 04-眼镜真机`）。

别人的电脑：装 Android Studio 打开本目录即可，或者装 JDK 17 + 安卓 SDK 34 后用同样的命令。
