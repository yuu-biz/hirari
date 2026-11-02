#!/usr/bin/env bash
# Checks fuzz_number of inputs.sh:  bash tools/fuzz/test-inputs.sh
set -u
# shellcheck source=inputs.sh
source "$(dirname "${BASH_SOURCE[0]}")/inputs.sh"

scratch="$(mktemp -d)"
trap 'rm -rf "$scratch"' EXIT
cd "$scratch" || exit 1

failed=0
expect() {
  local verdict="$1" value="$2" min="$3" max="$4" got out
  if out=$(PROBE="$value" fuzz_number PROBE "$min" "$max" 2>/dev/null); then got=accept; else got=reject; fi
  if [ "$got" != "$verdict" ] || { [ "$got" = accept ] && [ "$out" != "$value" ]; } || [ -n "$(ls -A "$scratch")" ]; then
    echo "FAIL: '$value' in $min..$max gave $got '${out:-}' (wanted $verdict)"
    failed=1
  fi
}

expect accept 240 1 270
expect accept 270 1 270
expect accept 1 1 270
expect accept 0 0 30
expect accept 999999999999999999 0 999999999999999999
expect reject 271 1 270
expect reject 0 1 270
expect reject '' 1 270
expect reject -5 0 30
expect reject +5 0 30
expect reject ' 5' 0 30
expect reject '5 ' 0 30
expect reject 007 0 30
expect reject 00 0 30
expect reject 1.5 0 30
expect reject 1e3 0 30
expect reject 0x10 0 30
expect reject 1000000000000000000 0 9223372036854775807
expect reject $'5\n6' 0 30
expect reject 'probe[$(touch injected)0]' 0 30
expect reject '$(touch injected)' 0 30
expect reject 'a[$(touch injected)]' 0 30
[ "$failed" = 0 ] && echo "all inputs.sh checks passed"
exit $failed
