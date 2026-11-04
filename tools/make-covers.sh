#!/bin/bash
# 在 NAS 上给每个视频生成「同名封面 jpg」。
#
# App 的规则是：视频旁边有同名图片（jpg/png/webp）就当封面用，所以跑完这个
# 脚本，下次 App 扫描（每次启动都会重扫）就自动有封面了，不需要改代码、
# 也不需要 App 去视频里抽帧。
#
# 用法（在 fnOS 的终端/SSH 里跑）：
#   ./make-covers.sh /vol1/1000/视频资源/儿童视频
#
# 可选环境变量：
#   JOBS=4      并行进程数，默认 4
#   SMART=1     用 thumbnail 滤镜挑"最有代表性"的一帧（更慢，但不会抽到黑场/台标）
#   DRY=1       只打印会处理哪些文件，不实际生成
#   OFFSET=10   普通模式下取第几秒（默认 10 秒；片头常是黑场，别用 0）
#
# 建议先 DRY=1 看一眼文件数，再正式跑；跑完可以用 -r 重跑补漏。
set -u

ROOT=${1:-}
if [ -z "$ROOT" ]; then
    echo "用法: $0 <视频根目录>"
    exit 1
fi
if [ ! -d "$ROOT" ]; then
    echo "目录不存在：$ROOT"
    exit 1
fi
if ! command -v ffmpeg >/dev/null 2>&1; then
    echo "没装 ffmpeg。fnOS 是 Debian 系，执行： sudo apt update && sudo apt install -y ffmpeg"
    exit 1
fi

JOBS=${JOBS:-4}
SMART=${SMART:-0}
DRY=${DRY:-0}
OFFSET=${OFFSET:-10}

make_one() {
    f=$1
    base=${f%.*}
    out="${base}.jpg"

    # 已经有封面就跳过（想覆盖就手动删掉那张图）
    [ -s "$out" ] && return 0
    if [ "$DRY" = "1" ]; then
        echo "$f"
        return 0
    fi

    # 480 宽够电视卡片用了，再大只是浪费 NAS 和带宽
    if [ "$SMART" = "1" ]; then
        ffmpeg -nostdin -loglevel error -i "$f" \
            -vf "thumbnail=100,scale=480:-2" -frames:v 1 -q:v 4 -y "$out" </dev/null 2>/dev/null
    else
        ffmpeg -nostdin -loglevel error -ss "$OFFSET" -i "$f" \
            -vf "scale=480:-2" -frames:v 1 -q:v 4 -y "$out" </dev/null 2>/dev/null
    fi

    # 视频比 OFFSET 还短时上面的会失败，退到第 3 秒再试
    if [ ! -s "$out" ]; then
        ffmpeg -nostdin -loglevel error -ss 3 -i "$f" \
            -vf "scale=480:-2" -frames:v 1 -q:v 4 -y "$out" </dev/null 2>/dev/null
    fi

    if [ -s "$out" ]; then
        echo "OK   $out"
    else
        rm -f "$out"
        echo "FAIL $f"
    fi
}

export -f make_one
export SMART DRY OFFSET

echo "根目录：$ROOT"
echo "并行：$JOBS   模式：$([ "$SMART" = "1" ] && echo '智能取帧(humbnail)' || echo "第 ${OFFSET}s 取帧")"
[ "$DRY" = "1" ] && echo "（DRY 模式，只列出文件）"

find "$ROOT" -type f \
    \( -iname '*.mp4' -o -iname '*.mkv' -o -iname '*.mov' -o -iname '*.avi' \
       -o -iname '*.webm' -o -iname '*.m4v' -o -iname '*.ts' -o -iname '*.flv' \
       -o -iname '*.wmv' -o -iname '*.mpg' -o -iname '*.mpeg' \) \
    -print0 |
    xargs -0 -r -P "$JOBS" -n 1 bash -c 'make_one "$1"' _

echo "完成。"
