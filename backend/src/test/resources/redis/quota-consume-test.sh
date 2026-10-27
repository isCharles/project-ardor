#!/bin/sh
set -eu

script=/tmp/quota-consume.lua

consume() {
  prefix=$1
  monthly=$2
  weekly=$3
  user_minute=$4
  global_minute=$5
  idempotent=$6
  redis-cli --raw --eval "$script" \
    "$prefix:month" "$prefix:week" "$prefix:user-minute" "$prefix:global-minute" "$prefix:idempotency" \
    , "$monthly" "$weekly" "$user_minute" "$global_minute" 60 60 60 "$idempotent" 3000
}

assert_equal() {
  if [ "$1" != "$2" ]; then
    printf 'Expected %s, got %s\n' "$2" "$1" >&2
    exit 1
  fi
}

prefix="ardor:quota-test:$$"
trap 'redis-cli DEL "$prefix:month" "$prefix:week" "$prefix:user-minute" "$prefix:global-minute" "$prefix:idempotency" "$prefix:idem:month" "$prefix:idem:week" "$prefix:idem:user-minute" "$prefix:idem:global-minute" "$prefix:idem:idempotency" "$prefix:parallel:month" "$prefix:parallel:week" "$prefix:parallel:user-minute" "$prefix:parallel:global-minute" "$prefix:parallel:idempotency" >/dev/null; rm -f /tmp/quota-results-"$$"' EXIT

assert_equal "$(consume "$prefix" 2 2 10 10 0)" 1
assert_equal "$(consume "$prefix" 2 2 10 10 0)" 1
assert_equal "$(consume "$prefix" 2 2 10 10 0)" -1
assert_equal "$(redis-cli --raw GET "$prefix:month")" 2
assert_equal "$(redis-cli --raw GET "$prefix:week")" 2
assert_equal "$(redis-cli --raw GET "$prefix:user-minute")" 2
assert_equal "$(redis-cli --raw GET "$prefix:global-minute")" 2

assert_equal "$(consume "$prefix:idem" 2 2 10 10 1)" 1
assert_equal "$(consume "$prefix:idem" 2 2 10 10 1)" 2
assert_equal "$(redis-cli --raw GET "$prefix:idem:month")" 1
assert_equal "$(redis-cli --raw GET "$prefix:idem:week")" 1
if [ "$(redis-cli --raw TTL "$prefix:idem:idempotency")" -lt 2990 ]; then
  printf 'Idempotency marker expired too soon\n' >&2
  exit 1
fi

results=/tmp/quota-results-"$$"
for attempt in $(seq 1 24); do
  (consume "$prefix:parallel" 5 5 100 100 0 >> "$results") &
done
wait
assert_equal "$(grep -xc '^1$' "$results")" 5
assert_equal "$(grep -xc '^-1$' "$results")" 19
assert_equal "$(redis-cli --raw GET "$prefix:parallel:month")" 5
assert_equal "$(redis-cli --raw GET "$prefix:parallel:week")" 5
assert_equal "$(redis-cli --raw GET "$prefix:parallel:user-minute")" 5
assert_equal "$(redis-cli --raw GET "$prefix:parallel:global-minute")" 5

printf 'Redis quota Lua integration checks passed.\n'
