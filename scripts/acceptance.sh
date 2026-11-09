#!/bin/bash
# 一键验收：用真实接口跑测试会议，并按验收标准对答案
cd "$(dirname "$0")/.." || exit 1
if [ -z "$JAVA_HOME" ] && [ -d "$HOME/.local/jdk-17.0.21+9/Contents/Home" ]; then
  export JAVA_HOME="$HOME/.local/jdk-17.0.21+9/Contents/Home"
fi
[ -x cli/build/install/cli/bin/cli ] || ./gradlew -q :cli:installDist || exit 1
clear
echo "会后纪要 · 电脑验收（真实接口）   $(date '+%Y-%m-%d %H:%M:%S')"
echo
cli/build/install/cli/bin/cli testdata/review-meeting.txt --expect testdata/review-meeting.expected.json "$@"
