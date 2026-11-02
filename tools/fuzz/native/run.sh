#!/usr/bin/env bash
# Builds the libFuzzer targets of this directory and runs one of them (docs/spec.md section 6.2).
#
#   tools/fuzz/native/run.sh archive|jxl SECONDS [SEED]
#
# Needs clang with libFuzzer and the sanitizer runtimes, cmake, ninja or make, and the zlib headers (on Ubuntu 24.04:
# clang-18 libclang-rt-18-dev cmake ninja-build zlib1g-dev, with CC=clang-18 CXX=clang++-18), and the corpus:
# ./gradlew :tools:corpus:generateCorpus. Crashes land in tools/fuzz/build/native/artifacts/
# with the stack trace in the .log beside them, shrunk by libFuzzer when it can. With TIME_LOG set, the run is appended
# to that log in the format of :tools:fuzz:fuzz, so that it counts toward the accumulated fuzzing time.
set -euo pipefail

target="${1:?usage: run.sh archive|jxl SECONDS [SEED]}"
seconds="${2:?usage: run.sh archive|jxl SECONDS [SEED]}"
seed="${3:-1}"

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/../../.." && pwd)"
build="$repo/tools/fuzz/build/native"
# A cached configuration would keep the compiler of an earlier run, and old artifacts would be taken for new ones.
rm -rf "$build/cmake" "$build/artifacts"
mkdir -p "$build/artifacts"

cc="${CC:-clang}"
cxx="${CXX:-clang++}"
generator=()
if command -v ninja >/dev/null 2>&1; then generator=(-G Ninja); fi
cmake -S "$here" -B "$build/cmake" "${generator[@]}" -DCMAKE_C_COMPILER="$cc" -DCMAKE_CXX_COMPILER="$cxx" >/dev/null
cmake --build "$build/cmake" --target "${target}_fuzzer" -j "$(nproc 2>/dev/null || echo 4)"

seeds="$build/seeds-$target"
corpus="$build/corpus-$target"
rm -rf "$seeds"
mkdir -p "$seeds" "$corpus"
case "$target" in
  archive)
    for dir in zip rar4 rar5 7z tar; do
      find "$repo/tools/corpus/build/corpus/$dir" -type f -size -256k -exec cp {} "$seeds"/ \;
    done
    ;;
  jxl)
    # The first byte of an input chooses the memory limit; a seed gets limit byte 3.
    for file in "$repo"/core/image/src/androidTest/assets/jxl/*.jxl; do
      { printf '\003'; cat "$file"; } > "$seeds/$(basename "$file")"
    done
    ;;
  *)
    echo "unknown target '$target'" >&2
    exit 2
    ;;
esac

started=$(date +%s)
status=0
"$build/cmake/${target}_fuzzer" -max_total_time="$seconds" -seed="$seed" -timeout=20 -rss_limit_mb=2048 -print_final_stats=1 \
  -artifact_prefix="$build/artifacts/$target-" "$corpus" "$seeds" 2>&1 | tee "$build/artifacts/$target-run.log" || status=$?

for crash in "$build"/artifacts/"$target"-crash-* "$build"/artifacts/"$target"-timeout-* "$build"/artifacts/"$target"-oom-*; do
  [ -f "$crash" ] || continue
  case "$crash" in *.min | *.log) continue ;; esac
  "$build/cmake/${target}_fuzzer" -minimize_crash=1 -runs=2000 -timeout=20 -exact_artifact_path="$crash.min" "$crash" >"$crash.log" 2>&1 || true
  echo "reproducer: $crash (shrunk: $crash.min)"
done

if [ -n "${TIME_LOG:-}" ]; then
  mkdir -p "$(dirname "$TIME_LOG")"
  elapsed=$(( $(date +%s) - started ))
  echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) seed=$seed seconds=$elapsed path=host-$target exit=$status commit=$(git -C "$repo" rev-parse --short HEAD 2>/dev/null || echo unknown)" >> "$TIME_LOG"
fi
exit "$status"
