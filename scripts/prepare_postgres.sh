#!/bin/sh
# 从 Termux 的 PostgreSQL 包提取 aarch64 二进制，并做路径等长补丁。
#
# 需要一个已解包的 Termux usr 目录，形如：
#   <TERMUX_USR>/bin/postgres
#   <TERMUX_USR>/lib/*.so*
#   <TERMUX_USR>/lib/postgresql/*.so
#   <TERMUX_USR>/share/postgresql/**
#
# 获取方式二选一：
#   A) 在 Termux 里 `pkg install postgresql`，再把 $PREFIX 整个拷出来
#   B) 下载 Termux 的 postgresql deb 并解包（.deb 是 ar 归档）：
#        ar x postgresql_<版本>_aarch64.deb && tar xf data.tar.xz
#
# 环境变量：
#   TERMUX_USR   Termux usr 目录（默认 /tmp/termux-usr）
set -e

REPO="$(cd "$(dirname "$0")/.." && pwd)"
TERMUX_USR="${TERMUX_USR:-/tmp/termux-usr}"
OUT="$REPO/assets/pg"

[ -x "$TERMUX_USR/bin/postgres" ] || {
  echo "错误：找不到 $TERMUX_USR/bin/postgres（用 TERMUX_USR= 指定 Termux usr 目录）" >&2
  exit 1
}

echo "[1/3] 复制二进制与库"
rm -rf "$OUT"
mkdir -p "$OUT/lib/postgresql"
cp "$TERMUX_USR/bin/postgres" "$TERMUX_USR/bin/initdb" "$TERMUX_USR/bin/psql" "$OUT/"
cp -L "$TERMUX_USR"/lib/*.so* "$OUT/lib/" 2>/dev/null || true
cp -L "$TERMUX_USR"/lib/postgresql/*.so "$OUT/lib/postgresql/" 2>/dev/null || true

echo "[2/3] 复制 share 数据（757 个文件）"
cp -r "$TERMUX_USR/share" "$OUT/share"

# initdb 需要 lib/postgresql/dict_snowball.so 等模块库，缺了会报 could not access file
[ -f "$OUT/lib/postgresql/dict_snowball.so" ] || {
  echo "错误：缺少 lib/postgresql/dict_snowball.so，initdb 会失败" >&2
  exit 1
}
[ -f "$OUT/share/postgresql/postgres.bki" ] || {
  echo "错误：缺少 share/postgresql/postgres.bki" >&2
  exit 1
}

echo "[3/3] 二进制路径补丁"
python3 "$REPO/scripts/patch_pg_paths.py" "$OUT"

echo "完成：$OUT"
du -sh "$OUT"
