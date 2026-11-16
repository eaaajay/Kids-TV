#!/bin/bash
# 把视频音轨的响度统一到目标值（默认 -16 LUFS），视频流直接 copy，不重编码、不掉画质。
#
# 背景：同一批动画有的 -13 LUFS、有的 -23 LUFS，连播时音量忽大忽小。
# 这个脚本用 EBU R128（ffmpeg loudnorm）把每集拉到同一水平。
#
# 用法（在 fnOS 终端里）：
#   bash normalize-audio.sh /vol1/1000/视频资源/儿童视频
#
# 可选环境变量：
#   TARGET=-16     目标响度 LUFS（儿童动画建议 -16 ~ -14）
#   TP=-1.5        真峰值上限 dBTP（留一点余量，避免削波）
#   LRA=11         允许的响度范围
#   BITRATE=128k   重编码后的音频码率
#   JOBS=2         并行数（机械盘别超过 2）
#   DRY=1          只列出会处理哪些文件
#   LIMIT=20       只处理前 20 个（先小批量验证）
#   FORCE=1        忽略已完成清单，全部重做
#
# 安全措施：
#   - 先写临时文件，ffprobe 校验「时长一致 + 有音轨 + 体积合理」才替换原文件
#   - 失败只记日志，原文件不动
#   - 处理过的记在 <ROOT>/.loudnorm-done，重跑自动跳过，以后加新片也是增量处理
set -u

ROOT=${1:-}
if [ -z "$ROOT" ] || [ ! -d "$ROOT" ]; then
    echo "用法: $0 <视频根目录>"
    exit 1
fi
command -v ffmpeg >/dev/null 2>&1 || { echo "没装 ffmpeg。执行： sudo apt update && sudo apt install -y ffmpeg"; exit 1; }
command -v ffprobe >/dev/null 2>&1 || { echo "没装 ffprobe（通常在 ffmpeg 包里）"; exit 1; }

TARGET=${TARGET:--16}
TP=${TP:--1.5}
LRA=${LRA:-11}
BITRATE=${BITRATE:-128k}
JOBS=${JOBS:-2}
DRY=${DRY:-0}
LIMIT=${LIMIT:-0}
FORCE=${FORCE:-0}

STATE="$ROOT/.loudnorm-done"
LOG="$ROOT/.loudnorm.log"
touch "$STATE" "$LOG" 2>/dev/null

export ROOT TARGET TP LRA BITRATE DRY FORCE STATE LOG

process_one() {
    f=$1
    rel=${f#"$ROOT"/}

    if [ "$FORCE" != "1" ] && grep -qxF "$rel" "$STATE" 2>/dev/null; then
        return 0
    fi

    lower=$(printf '%s' "$f" | tr '[:upper:]' '[:lower:]')
    case "$lower" in
        *.mp4|*.m4v|*.mov) ext=mp4 ;;
        *.mkv) ext=mkv ;;
        *)
            echo "SKIP 容器不支持 $rel" >> "$LOG"
            return 0
            ;;
    esac

    if [ "$DRY" = "1" ]; then
        echo "会处理: $rel"
        return 0
    fi

    dur_in=$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$f" 2>/dev/null | cut -d. -f1)
    has_audio=$(ffprobe -v error -select_streams a -show_entries stream=index -of csv=p=0 "$f" 2>/dev/null | head -1)
    if [ -z "$has_audio" ]; then
        echo "SKIP 没有音轨 $rel" >> "$LOG"
        return 0
    fi

    size_in=$(stat -c %s "$f" 2>/dev/null || echo 0)
    tmp="${f%.*}.loudnorm.$ext"

    extra=""
    [ "$ext" = "mp4" ] && extra="-movflags +faststart"

    if ! ffmpeg -nostdin -hide_banner -loglevel error -i "$f" \
            -map 0:v -map 0:a -sn -c:v copy \
            -af "loudnorm=I=${TARGET}:TP=${TP}:LRA=${LRA}" \
            -c:a aac -b:a "$BITRATE" $extra -y "$tmp" >> "$LOG" 2>&1; then
        rm -f "$tmp"
        echo "FAIL 转码失败 $rel" >> "$LOG"
        echo "FAIL $rel"
        return 0
    fi

    # 校验：时长差 < 2 秒、体积 > 原文件 30%、音轨还在
    dur_out=$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$tmp" 2>/dev/null | cut -d. -f1)
    size_out=$(stat -c %s "$tmp" 2>/dev/null || echo 0)
    audio_out=$(ffprobe -v error -select_streams a -show_entries stream=index -of csv=p=0 "$tmp" 2>/dev/null | head -1)
    ok=1
    [ -z "$audio_out" ] && ok=0
    [ "${dur_in:-0}" -gt 0 ] && [ "${dur_out:-0}" -gt 0 ] && {
        diff=$((dur_in - dur_out)); [ ${diff#-} -gt 3 ] && ok=0
    }
    [ "$size_in" -gt 0 ] && [ "$size_out" -lt $((size_in * 30 / 100)) ] && ok=0

    if [ "$ok" != "1" ]; then
        rm -f "$tmp"
        echo "FAIL 校验不通过(时长 ${dur_in}->${dur_out}, 体积 ${size_in}->${size_out}) $rel" >> "$LOG"
        echo "FAIL $rel"
        return 0
    fi

    mv -f "$tmp" "$f"
    echo "$rel" >> "$STATE"
    echo "OK   $rel"
}

export -f process_one

echo "根目录：$ROOT"
echo "目标：I=${TARGET} LUFS  TP=${TP}  LRA=${LRA}  音频 ${BITRATE}  并行 ${JOBS}"
[ "$DRY" = "1" ] && echo "（DRY 模式，不会修改文件）"

find "$ROOT" -type f \
    \( -iname '*.mp4' -o -iname '*.m4v' -o -iname '*.mov' -o -iname '*.mkv' \) -print0 |
    { if [ "$LIMIT" -gt 0 ]; then head -z -n "$LIMIT"; else cat; fi; } |
    xargs -0 -r -P "$JOBS" -n 1 bash -c 'process_one "$1"' _

echo
echo "完成。失败明细见：$LOG"
echo "已处理清单：$STATE"
