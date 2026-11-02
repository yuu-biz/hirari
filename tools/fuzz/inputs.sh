# shellcheck shell=bash
# Sourced by .github/workflows/fuzz.yml and ci-device.sh. The inputs of a manual run arrive as text that anyone who may
# start the workflow chooses, so each one is checked right where it is used, and only the checked decimal number is
# used in arithmetic or in an argument, never the text itself.
#
#   minutes=$(fuzz_number FUZZ_MINUTES 1 270) || exit 1
#
# prints the value of the environment variable FUZZ_MINUTES when it is a whole number of at most 18 plain decimal
# digits (no sign, no spaces, no leading zeros) between 1 and 270; otherwise says why on stderr and fails.
fuzz_number() {
  local name="$1" min="$2" max="$3" value
  value="${!name-}"
  if ! [[ "$value" =~ ^(0|[1-9][0-9]{0,17})$ ]]; then
    printf '%s is not a whole number in plain decimal digits: %q\n' "$name" "$value" >&2
    return 1
  fi
  if (( value < min || value > max )); then
    printf '%s must be from %s to %s, not %s\n' "$name" "$min" "$max" "$value" >&2
    return 1
  fi
  printf '%s\n' "$value"
}
