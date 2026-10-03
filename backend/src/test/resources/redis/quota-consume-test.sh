#!/bin/sh
set -eu

script=/tmp/quota-consume.lua

consume() {
  prefix=$1
  monthly=$2
  user_minute=$3
  global_minute=$4
  idempotent=$5
  redis-cli --raw --eval "$script" \
    "$prefix:month" "$prefix:user-minute" "$prefix:global-minute" "$prefix:idempotency" \
    , "$monthly" "$user_minute" "$global_minute" 60 60 "$idempotent"
}

assert_equal() {
  if [ "$1" != "$2" ]; then
    printf 'Expected %s, got %s\n' "$2" "$1" >&2
    exit 1
  fi
}

prefix="ardor:quota-test:$$"
trap 'redis-cli DEL "$prefix:month" "$prefix:user-minute" "$prefix:global-minute" "$prefix:idempotency" "$prefix:idem:month" "$prefix:idem:user-minute" "$prefix:idem:global-minute" "$prefix:idem:idempotency" "$prefix:parallel:month" "$prefix:parallel:user-minute" "$prefix:parallel:global-minute" "$prefix:parallel:idempotency" >/dev/null; rm -f /tmp/quota-results-"$$"' EXIT

assert_equal "$(consume "$prefix" 2 10 10 0)" 1
assert_equal "$(consume "$prefix" 2 10 10 0)" 1
assert_equal "$(consume "$prefix" 2 10 10 0)" -1
assert_equal "$(redis-cli --raw GET "$prefix:month")" 2
assert_equal "$(redis-cli --raw GET "$prefix:user-minute")" 2
assert_equal "$(redis-cli --raw GET "$prefix:global-minute")" 2

assert_equal "$(consume "$prefix:idem" 2 10 10 1)" 1
assert_equal "$(consume "$prefix:idem" 2 10 10 1)" 2
assert_equal "$(redis-cli --raw GET "$prefix:idem:month")" 1

results=/tmp/quota-results-"$$"
for attempt in $(seq 1 24); do
  (consume "$prefix:parallel" 5 100 100 0 >> "$results") &
done
wait
assert_equal "$(grep -xc '^1$' "$results")" 5
assert_equal "$(grep -xc '^-1$' "$results")" 19
assert_equal "$(redis-cli --raw GET "$prefix:parallel:month")" 5
assert_equal "$(redis-cli --raw GET "$prefix:parallel:user-minute")" 5
assert_equal "$(redis-cli --raw GET "$prefix:parallel:global-minute")" 5

printf 'Redis quota Lua integration checks passed.\n'
