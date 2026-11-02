#!/usr/bin/env bash
# What .github/workflows/fuzz.yml runs while its emulator is up: the device fuzzer, then the monkey test. Both always
# run, and the exit status says whether either found something. Settings come from the environment, and are checked
# here again (inputs.sh) because they reach the Gradle arguments:
#   FUZZ_MINUTES    minutes of device fuzzing, 1 to 270
#   FUZZ_SEED       seed of the input stream
#   MONKEY_EVENTS   monkey events over all books, 0 to skip the monkey test, at most 50000
set -u
cd "$(dirname "${BASH_SOURCE[0]}")/../.." || exit 1
source tools/fuzz/inputs.sh

minutes=$(fuzz_number FUZZ_MINUTES 1 270) || exit 1
seed=$(fuzz_number FUZZ_SEED 0 999999999999999999) || exit 1
events=$(fuzz_number MONKEY_EVENTS 0 50000) || exit 1

abi=-Pandroid.injected.build.abi=x86_64
status=0
./gradlew :tools:fuzz:fuzz "$abi" --args="--minutes $minutes --seed $seed --time-log tools/fuzz/build/fuzz-time.log" || status=1
if [ "$events" != 0 ]; then
  ./gradlew :tools:fuzz:monkey "$abi" --args="--events $events --seed $seed" || status=1
fi
exit $status
