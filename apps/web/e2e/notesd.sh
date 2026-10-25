#!/bin/sh
# A throwaway notesd for the end-to-end tests: fresh data, a known owner.
set -e
dir=$(mktemp -d)
trap 'rm -rf "$dir"' EXIT INT TERM
go -C ../../server build -o "$dir/notesd" ./cmd/notesd
NOTESD_OWNER_USERNAME=e2e NOTESD_OWNER_PASSWORD='e2e password' "$dir/notesd" --data-dir "$dir/data" --listen "$1" --log-level warn
