#!/usr/bin/env bash
# docs/spec.md の (要検証) を、節の見出しと一緒に一覧にする。
#
#   bash tools/verification/list.sh           見つかった文を「キー、行番号、抜き出した文」で出す
#   bash tools/verification/list.sh --check   docs/verification.md の台帳と、キーごとの件数を突き合わせる
#
# 数える単位は、(要検証) を含む文である。1 つの文に (要検証) が 2 つあっても 1 件で、
# 同じ行に別の文の (要検証) があれば別の件になる。表の行は、(要検証) を含むセルの中の文を数える。
# キーは、節の番号（4.8 節の小見出しがあるときは「4.8 小見出し」）、10 章の表は ID（Q18 など）、
# 9 章のマイルストーンは M6 のような名前である。次は数えない。
#   - 凡例（先頭の「表記」の行）。
#   - 9 章の「確認の状況」。台帳への参照だけで、元ではない。
#   - 完了条件の「(要検証) がすべて解消…」の行。台帳そのものを指す。
# 台帳の側は、docs/verification.md の `register:start` と `register:end` の間の表の、先頭の列（キー）を使う。
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
spec="$root/docs/spec.md"
register="$root/docs/verification.md"

extract() {
  awk '
    function emit(key, text,    sentences, parts, i, excerpt, opened, closed, open_count, close_count) {
      sentences = split(text, parts, "。")
      for (i = 1; i <= sentences; i++) {
        if (parts[i] !~ /要検証/) continue
        excerpt = parts[i]
        while (i < sentences) {
          opened = excerpt; closed = excerpt
          open_count = gsub(/[(（]/, "", opened)
          close_count = gsub(/[)）]/, "", closed)
          if (open_count <= close_count) break
          i++
          excerpt = excerpt "。" parts[i]
        }
        gsub(/^[-* ]+/, "", excerpt)
        gsub(/^ +| +$/, "", excerpt)
        printf "%s\t%d\t%s\n", key, NR, excerpt
      }
    }
    /^## / { h2 = $0; h3 = ""; h4 = ""; skip = 0; next }
    /^### / {
      h3 = $0; h4 = ""; skip = 0
      if (h3 ~ /確認の状況/) skip = 1
      next
    }
    /^#### / { h4 = $0; next }
    /要検証/ {
      if (skip) next
      if (h2 == "") next
      if ($0 ~ /\(要検証\) がすべて解消/) next
      if (h2 ~ /^## 10\./) {
        n = split($0, cells, "|")
        key = cells[2]
        gsub(/^ +| +$/, "", key)
      } else {
        key = h3
        sub(/^### /, "", key)
        sub(/ .*/, "", key)
        if (h4 != "" && h2 !~ /^## 9\./) {
          title = h4
          sub(/^#### /, "", title)
          key = key " " title
        }
      }
      if ($0 ~ /^\|/) {
        n = split($0, cells, "|")
        for (c = 1; c <= n; c++) {
          if (cells[c] ~ /要検証/) emit(key, cells[c])
        }
      } else {
        emit(key, $0)
      }
    }
  ' "$spec"
}

if [ "${1:-}" != "--check" ]; then
  extract
  exit 0
fi

want="$(extract | cut -f1 | sort | uniq -c | sed -E 's/^ +//')"
have="$(awk '
  /register:start/ { inside = 1; next }
  /register:end/ { inside = 0 }
  inside && /^\| / {
    n = split($0, cells, "|")
    key = cells[2]
    gsub(/^ +| +$/, "", key)
    if (key == "節" || key ~ /^-+$/) next
    print key
  }
' "$register" | sort | uniq -c | sed -E 's/^ +//')"

if [ "$want" = "$have" ]; then
  echo "register matches the spec: $(extract | wc -l | tr -d ' ') items"
  exit 0
fi

echo "docs/verification.md and docs/spec.md disagree (count key):" >&2
diff <(printf '%s\n' "$want") <(printf '%s\n' "$have") >&2 || true
exit 1
