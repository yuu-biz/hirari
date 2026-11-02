#!/usr/bin/env bash
# What .github/workflows/fuzz.yml runs while its emulator is up: the device fuzzer, then the monkey test. Both always
# run, and the exit status says whether either found something. Settings come from the environment:
#   FUZZ_MINUTES    minutes of device fuzzing
#   FUZZ_SEED       seed of the input stream
#   MONKEY_EVENTS   monkey events over all books, 0 to skip the monkey test
set -u
cd "$(dirname "${BASH_SOURCE[0]}")/../.."

abi=-Pandroid.injected.build.abi=x86_64
status=0
./gradlew :tools:fuzz:fuzz "$abi" --args="--minutes ${FUZZ_MINUTES:?} --seed ${FUZZ_SEED:?} --time-log tools/fuzz/build/fuzz-time.log" || status=1
if [ "${MONKEY_EVENTS:-0}" != 0 ]; then
  ./gradlew :tools:fuzz:monkey "$abi" --args="--events $MONKEY_EVENTS --seed ${FUZZ_SEED:?}" || status=1
fi
exit $status
