#!/usr/bin/env bash
# Lists the (要検証) marks of docs/spec.md with the heading each one sits under.
#
#   bash tools/verification/list.sh           print the sentences found, as key, line number and sentence
#   bash tools/verification/list.sh --check   compare the count per key with the register in docs/verification.md
#
# The unit is the sentence that holds a mark. Two marks in one sentence are one item, and marks in two sentences of
# the same line are two items. In a table row, the sentences of the cell that holds the mark are counted.
# The key is the section number (followed by the sub-heading under 4.8 and the like), the ID of a row of the table
# in chapter 10 (Q18 and so on), or the milestone of chapter 9 (M6 and so on). Not counted:
#   - the legend (the "表記" line at the top);
#   - the "確認の状況" section of chapter 9, which only points at the register;
#   - the completion condition that says every mark is resolved or registered, which points at the register itself.
# The register side is the first column (the key) of the table between `register:start` and `register:end` in
# docs/verification.md.
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
