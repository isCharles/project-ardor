#!/bin/sh
set -eu

consume=/tmp/quota-burst-consume.lua
refund=/tmp/quota-burst-refund.lua

burst() {
  key=$1
  limit=$2
  redis-cli --raw --eval "$consume" "$key" , "$limit" 120
}

undo() {
  redis-cli --raw --eval "$refund" "$1"
}

assert_equal() {
  if [ "$1" != "$2" ]; then
    printf 'Expected %s, got %s\n' "$2" "$1" >&2
    exit 1
  fi
}

prefix="ardor:quota-burst-test:$$"
trap 'redis-cli DEL "$prefix:limit" "$prefix:zero" "$prefix:refund" "$prefix:parallel" >/dev/null' EXIT

# A window admits up to its limit and then refuses without counting the refusal.
assert_equal "$(burst "$prefix:limit" 2)" 1
assert_equal "$(burst "$prefix:limit" 2)" 2
assert_equal "$(burst "$prefix:limit" 2)" -1
assert_equal "$(redis-cli --raw GET "$prefix:limit")" 2
if [ "$(redis-cli --raw TTL "$prefix:limit")" -le 0 ]; then
  printf 'Burst window was left without a TTL\n' >&2
  exit 1
fi

# A zero ceiling blocks the period instead of disabling it.
assert_equal "$(burst "$prefix:zero" 0)" -1
assert_equal "$(redis-cli --raw EXISTS "$prefix:zero")" 0

# A refund gives the unit back and never invents a negative window.
assert_equal "$(undo "$prefix:limit")" 1
assert_equal "$(burst "$prefix:limit" 2)" 2
assert_equal "$(undo "$prefix:refund")" 0
assert_equal "$(redis-cli --raw EXISTS "$prefix:refund")" 0

# Concurrent callers cannot both take the last unit.
results=/tmp/quota-burst-results-"$$"
for attempt in $(seq 1 24); do
  (burst "$prefix:parallel" 5 >> "$results") &
done
wait
assert_equal "$(grep -xc '^-1$' "$results")" 19
assert_equal "$(grep -vc '^-1$' "$results")" 5
assert_equal "$(redis-cli --raw GET "$prefix:parallel")" 5
rm -f "$results"

printf 'Redis burst window Lua integration checks passed.\n'
