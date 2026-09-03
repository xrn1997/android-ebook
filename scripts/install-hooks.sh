#!/bin/sh
# 安装 git hooks 到 .git/hooks/
# 用法: bash scripts/install-hooks.sh

HOOK_DIR=".git/hooks"
HOOK_NAME="commit-msg"
SRC="scripts/commit-msg"

if [ ! -d "$HOOK_DIR" ]; then
    echo "❌ 未找到 .git/hooks 目录，请确认在 git 仓库根目录执行"
    exit 1
fi

cp "$SRC" "$HOOK_DIR/$HOOK_NAME"
chmod +x "$HOOK_DIR/$HOOK_NAME"
echo "✅ $HOOK_NAME hook 已安装"