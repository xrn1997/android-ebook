#!/usr/bin/env bash
# 文档检查：AGENTS 字符预算、当前文档的相对链接、规格状态头、ADR 索引/状态/锚点、核心文档、过程归档目录、旧 AGENTS 章节引用。
# 不检查：reference-style links、#fragment 锚点、HTML src/href、根绝对路径、含空格的路径。
# 本机过程目录（.superpowers/ 等）与生成快照不参与链接检查（它们允许指向旧路径）。

set -u

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

fail=0
budget=8000

tmp_links="$(mktemp)"
trap 'rm -f "$tmp_links"' EXIT

# 1. 根 AGENTS 字符预算（中文按字符数，不按词数）
chars="$(wc -m < AGENTS.md | tr -d '[:space:]')"
if [ "$chars" -gt "$budget" ]; then
  printf 'FAIL AGENTS.md: %s chars > budget %s\n' "$chars" "$budget"
  fail=1
else
  printf 'OK   AGENTS.md: %s/%s chars\n' "$chars" "$budget"
fi

# 2. 当前文档的相对 Markdown 链接（跳过代码块、外链、纯锚点）
while IFS= read -r -d '' file; do
  awk '
    /^[[:space:]]*```/ { in_fence = !in_fence; next }
    in_fence { next }
    {
      line = $0
      while (match(line, /\]\([^)]+\)/)) {
        s = substr(line, RSTART + 2, RLENGTH - 3)
        print FILENAME "\t" FNR "\t" s
        line = substr(line, RSTART + RLENGTH)
      }
    }
  ' "$file" >> "$tmp_links"
done < <(find . -name '*.md' \
  -not -path './.git/*' \
  -not -path './.qoder/*' \
  -not -path './third_party/*' \
  -not -path '*/build/*' \
  -not -path './.gradle/*' \
  -not -path './.idea/*' \
  -not -path './.superpowers/*' \
  -not -path './.task-scratch/*' \
  -not -path './.worktrees/*' \
  -print0)

checked=0
link_fail=0
while IFS=$'\t' read -r file line link; do
  case "$link" in
    http://*|https://*|mailto:*|tel:*|data:*|\#*|/*) continue ;;
  esac
  path="${link%%#*}"
  [ -z "$path" ] && continue
  # 去掉 Markdown 链接标题（path "title"）
  path="${path%% *}"
  checked=$((checked + 1))
  target="$(dirname "$file")/$path"
  if [ ! -e "$target" ]; then
    printf 'FAIL link %s:%s -> %s\n' "$file" "$line" "$link"
    link_fail=1
    fail=1
  fi
done < "$tmp_links"

if [ "$link_fail" -eq 0 ]; then
  printf 'OK   relative markdown links checked (%s links)\n' "$checked"
fi

# 3. 过程工件状态头：按目录区分状态词，活跃工件还必须有现行事实源
status_fail=0
check_status() {
  dir="$1"
  shift
  for f in "$dir"/*.md; do
    [ -e "$f" ] || continue
    [ "$(basename "$f")" = "README.md" ] && continue
    for req in "$@"; do
      if ! head -8 "$f" | grep -q "$req"; then
        printf 'FAIL status %s: missing "%s" in first 8 lines\n' "$f" "$req"
        status_fail=1
        fail=1
      fi
    done
  done
}

check_status "docs/specs" "状态：生效"

if [ "$status_fail" -eq 0 ]; then
  printf 'OK   spec status headers\n'
fi

if [ -d docs/superpowers ]; then
  printf 'FAIL process archive: docs/superpowers/ exists; 过程文档不入库\n'
  fail=1
else
  printf 'OK   no in-repo process archive\n'
fi

# 4. 旧 AGENTS 章节引用（已被拆到 architecture/development/CONTRIBUTING 的章节）
retired_forward='AGENTS\.md[^\n]*「[^」]*(语义版本映射|屏幕的无状态根|MVVM 约定|测试约定|模块架构|列表分页|Agent 实战建议|提交规范)[^」]*」'
retired_reverse='(语义版本映射|屏幕的无状态根|MVVM 约定|测试约定|模块架构|列表分页|Agent 实战建议|提交规范)[^\n]*见[^\n]*AGENTS\.md'
stale="$(grep -rnE --include='*.md' --include='*.kt' --include='*.kts' \
  --exclude-dir=.git \
  --exclude-dir=.qoder \
  --exclude-dir=.superpowers \
  --exclude-dir=.task-scratch \
  --exclude-dir=.worktrees \
  --exclude-dir=build \
  --exclude-dir=third_party \
  -e "$retired_forward" -e "$retired_reverse" . 2>/dev/null || true)"

if [ -n "$stale" ]; then
  printf 'FAIL retired AGENTS section references:\n%s\n' "$stale"
  fail=1
else
  printf 'OK   no retired AGENTS section references\n'
fi

# 5. ADR 索引、Status、锚点与核心文档
adr_dir="docs/adr"
core_fail=0
if [ ! -f "$adr_dir/README.md" ]; then
  printf 'FAIL ADR index: %s/README.md missing\n' "$adr_dir"
  core_fail=1
else
  indexed="$(grep -oE '^\| `[0-9]{4}`' "$adr_dir/README.md" | grep -oE '[0-9]{4}' | sort)"
  adr_files=""
  for f in "$adr_dir"/[0-9][0-9][0-9][0-9]-*.md; do
    [ -e "$f" ] || continue
    adr_files="$adr_files$(basename "$f" | cut -c1-4)
"
  done
  adr_files="$(printf '%s' "$adr_files" | sort)"
  if [ "$indexed" != "$adr_files" ]; then
    printf 'FAIL ADR index: docs/adr/README.md 与文件不是一一对应\n'
    printf '  索引：%s\n' "$(printf '%s' "$indexed" | tr '\n' ' ')"
    printf '  文件：%s\n' "$(printf '%s' "$adr_files" | tr '\n' ' ')"
    core_fail=1
  fi
fi

for f in "$adr_dir"/[0-9][0-9][0-9][0-9]-*.md; do
  [ -e "$f" ] || continue
  if ! sed -n '1,6p' "$f" | grep -qE '^Status: (accepted|superseded by [0-9]{4})$'; then
    printf 'FAIL ADR status %s: missing/invalid Status\n' "$f"
    core_fail=1
  fi
  if ! grep -q '^锚点：' "$f"; then
    printf 'FAIL ADR anchor %s: missing 锚点：\n' "$f"
    core_fail=1
  fi
done

for f in AGENTS.md README.md CONTEXT.md CONTRIBUTING.md CHANGELOG.md docs/AGENTS.md docs/architecture.md docs/development.md docs/adr/README.md docs/adr/ADR-FORMAT.md; do
  if [ ! -f "$f" ]; then
    printf 'FAIL core doc: %s missing\n' "$f"
    core_fail=1
  fi
done

if [ "$core_fail" -eq 0 ]; then
  printf 'OK   ADR index/status/anchor and core docs\n'
else
  fail=1
fi

if [ "$fail" -ne 0 ]; then
  printf '\nDocumentation check failed.\n'
  exit 1
fi

printf 'Documentation check passed.\n'
