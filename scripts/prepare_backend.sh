#!/bin/sh
# 从 ARTEX 上游源码编译 android/arm64 后端（前端经 embedui 嵌入同一二进制）
#
# 环境变量：
#   ARTEX_SRC         ARTEX 源码目录（默认 ~/ARTEX）
#   SKIP_FRONTEND=1   跳过前端构建，复用已有的 server/webui/dist
#   VERSION           写入二进制的版本号（默认 dev）
set -e

REPO="$(cd "$(dirname "$0")/.." && pwd)"
ARTEX_SRC="${ARTEX_SRC:-$HOME/ARTEX}"
VERSION="${VERSION:-dev}"
OUT="$REPO/assets/backend/artex"

[ -f "$ARTEX_SRC/cmd/artex/main.go" ] || {
  echo "错误：$ARTEX_SRC 不是 ARTEX 源码目录（用 ARTEX_SRC= 指定）" >&2
  exit 1
}

# 1) 前端静态导出 → server/webui/dist（embedui 构建标签会嵌入此处）
if [ "${SKIP_FRONTEND:-0}" != "1" ]; then
  echo "[1/3] 构建前端静态资源"
  (cd "$ARTEX_SRC/web" && npm ci && npm run build:static)
  mkdir -p "$ARTEX_SRC/server/webui/dist"
  if command -v rsync >/dev/null 2>&1; then
    rsync -a --delete "$ARTEX_SRC/web/out/" "$ARTEX_SRC/server/webui/dist/"
  else
    rm -rf "$ARTEX_SRC/server/webui/dist"
    cp -r "$ARTEX_SRC/web/out" "$ARTEX_SRC/server/webui/dist"
  fi
else
  echo "[1/3] 跳过前端构建"
fi

# 2) 交叉编译 Android（GOOS=android 产出 PIE，Android 才能执行）
echo "[2/3] 编译 android/arm64"
mkdir -p "$(dirname "$OUT")"
(cd "$ARTEX_SRC" && CGO_ENABLED=0 GOOS=android GOARCH=arm64 go build \
  -tags embedui -trimpath \
  -ldflags "-s -w -buildid= -X main.version=$VERSION" \
  -o "$OUT" ./cmd/artex)

# 3) 确认是 PIE 且拿到构建信息
echo "[3/3] 校验产物"
ls -la "$OUT"
go version -m "$OUT" | head -3
if command -v readelf >/dev/null 2>&1; then
  readelf -h "$OUT" | grep -i "type:" || true
fi
echo "完成：$OUT"
