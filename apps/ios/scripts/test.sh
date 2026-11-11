#!/bin/sh
# xcodebuild test on a simulator against a fresh notesd, built from server/.
# With arguments, only those: a target, class or test, as -only-testing takes them.
#   usage: test.sh [NotesTests | NotesUITests/FlowTests | NotesUITests/FlowTests/testSpaces ...]
set -eu
cd "$(dirname "$0")/.."
for t; do
  set -- "$@" "-only-testing:$t"
  shift
done
bin="$(pwd)/build/notesd"
(cd ../../server && go build -o "$bin" ./cmd/notesd)
sim=$(scripts/simulator.sh)
scripts/with-server.sh "$bin" scripts/xctest.sh build/test.log -project Notes.xcodeproj -scheme Notes \
  -destination "id=$sim" -derivedDataPath build/DerivedData "$@" test
