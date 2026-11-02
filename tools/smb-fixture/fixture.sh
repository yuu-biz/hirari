#!/usr/bin/env bash
# Usage: tools/smb-fixture/fixture.sh up|down|props
#   up     builds and starts the Samba container
#   down   stops and removes it
#   props  prints the -D options that turn on the fixture tests of :core:source, as in
#          ./gradlew :core:source:testDebugUnitTest --rerun $(tools/smb-fixture/fixture.sh props)
set -euo pipefail
cd "$(dirname "$0")"
case "${1:-up}" in
  up) docker compose up -d --build ;;
  down) docker compose down -v ;;
  props) echo "-Dhirari.smb.host=127.0.0.1 -Dhirari.smb.port=14455 -Dhirari.smb.container=hirari-smb" ;;
  *) echo "usage: $0 up|down|props" >&2; exit 2 ;;
esac
