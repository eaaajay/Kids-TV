#!/bin/bash
# 把绘本 PDF 逐页转成 jpg，App 就能「翻到哪页才下哪页」。
#
# App 的规则：PDF 旁边如果有一个 <书名>__pages/ 目录，就用里面的图片翻页；
# 没有就退回「整本下载 + PdfRenderer」。所以这个脚本跑完，绘本自动变快。
#
# 用法（在 fnOS 终端/SSH 里）：
#   ./pdf2pages.sh /vol1/1000/绘本
#
# 可选环境变量：
#   DPI=110   渲染分辨率，110 大约是 900~1000px 宽，电视上够清楚（默认 110）
#   Q=80      jpeg 质量（默认 80）
#   WIDTH=1200 若设置，会按这个宽度等比缩放（优先于 DPI 的直觉控制）
#   DRY=1     只列出会处理哪些 PDF
#   FORCE=1   已经转过的也重转
set -u

ROOT=${1:-}
if [ -z "$ROOT" ]; then
    echo "用法: $0 <绘本目录>"
    exit 1
fi
if [ ! -d "$ROOT" ]; then
    echo "目录不存在：$ROOT"
    exit 1
fi
if ! command -v pdftoppm >/dev/null 2>&1; then
    echo "没装 poppler。执行： sudo apt update && sudo apt install -y poppler-utils"
    exit 1
fi

DPI=${DPI:-110}
Q=${Q:-80}
DRY=${DRY:-0}
FORCE=${FORCE:-0}

count=0
fail=0
while IFS= read -r -d '' f; do
    base=${f%.*}
    out="${base}__pages"
    existing=$(ls -1 "$out" 2>/dev/null | wc -l | tr -d ' ')
    if [ "$FORCE" != "1" ] && [ "$existing" -gt 0 ]; then
        continue
    fi
    if [ "$DRY" = "1" ]; then
        echo "会转: $f"
        count=$((count + 1))
        continue
    fi
    mkdir -p "$out"
    if pdftoppm -jpeg -r "$DPI" -jpegopt quality="$Q" "$f" "$out/page" 2>/dev/null; then
        n=$(ls -1 "$out" | wc -l | tr -d ' ')
        if [ "$n" -gt 0 ]; then
            echo "OK   $n 页  $(basename "$f")"
            count=$((count + 1))
        else
            rm -rf "$out"
            echo "FAIL 空输出  $f"
            fail=$((fail + 1))
        fi
    else
        rm -rf "$out"
        echo "FAIL 转换失败  $f"
        fail=$((fail + 1))
    fi
done < <(find "$ROOT" -type f -iname '*.pdf' -print0)

echo
echo "完成：成功 $count 本，失败 $fail 本"
