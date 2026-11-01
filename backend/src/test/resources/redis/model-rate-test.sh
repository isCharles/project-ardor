#!/bin/sh
set -eu

admit=/tmp/model-rate-admit.lua
refund=/tmp/model-rate-refund.lua

# One request at 60 per minute costs 1,000,000 microseconds of bucket time; the burst floor is what the
# service passes, and `now` is an argument, so every figure below is exact rather than clock-dependent.
EMISSION=1000000
TTL=60000
tag="ardor:model:rate:{ardor-model-rate-test-$$}"
trap "redis-cli DEL $tag:requests $tag:models $tag:users >/dev/null" EXIT

assert_equal() {
  if [ "$1" != "$2" ]; then
    printf 'Expected %s, got %s\n' "$2" "$1" >&2
    exit 1
  fi
}

admit_at() {
  redis-cli --raw --eval "$admit" "$1" , "$EMISSION" 1 "$3" "$2" "$TTL" | paste -sd ' '
}

refund_at() {
  redis-cli --raw --eval "$refund" "$1" , "$EMISSION" "$3" "$2" "$TTL" | paste -sd ' '
}

stored() {
  redis-cli --raw GET "$1"
}

# A call against an empty bucket is admitted and answers the horizon its write left behind; the next call
# in the same microsecond is refused by the bucket that forced the wait, and it charged nothing.
assert_equal "$(admit_at "$tag:requests" 0 1000000)" "1 0 0 1000000"
assert_equal "$(admit_at "$tag:requests" 0 1000000)" "0 1000000 1"
assert_equal "$(stored "$tag:requests")" 1000000
if [ "$(redis-cli --raw TTL "$tag:requests")" -le 0 ]; then
  printf 'Rate bucket was left without a TTL\n' >&2
  exit 1
fi

# A refund that arrives while the charge is still owed gives the capacity back: the bucket is drained to
# the present and the next call goes through at once.
assert_equal "$(refund_at "$tag:requests" 100000 1000000)" 1
assert_equal "$(stored "$tag:requests")" 100000
assert_equal "$(admit_at "$tag:requests" 100000 1000000)" "1 0 0 1100000"

# A refund that arrives after its own horizon has passed must do nothing. By then a later call has
# rebased its debt on the present; subtracting the old credit would shorten that call's wait and let the
# group run above the rate it is supposed to hold.
assert_equal "$(admit_at "$tag:models" 0 1000000)" "1 0 0 1000000"
assert_equal "$(admit_at "$tag:models" 2000000 1000000)" "1 0 0 3000000"
assert_equal "$(refund_at "$tag:models" 2500000 1000000)" 0
assert_equal "$(stored "$tag:models")" 3000000
assert_equal "$(admit_at "$tag:models" 2500000 1000000)" "0 500000 1"

# A burst wider than one call admits a second call before the first has drained, and refunding the first
# then gives back only its own second — the second call keeps the debt it paid for.
assert_equal "$(admit_at "$tag:users" 0 2000000)" "1 0 0 1000000"
assert_equal "$(admit_at "$tag:users" 0 2000000)" "1 0 0 2000000"
assert_equal "$(refund_at "$tag:users" 500000 1000000)" 1
assert_equal "$(stored "$tag:users")" 1000000
assert_equal "$(admit_at "$tag:users" 500000 2000000)" "1 0 0 2000000"

# Every bucket of one credential is judged before any is written, so a denial on one leaves the others
# exactly as they were instead of charging a partial debt. Here only the token bucket is still in debt.
multi=$(redis-cli --raw --eval "$admit" "$tag:requests" "$tag:models" "$tag:users" , "$EMISSION" 1 1000000 "$EMISSION" 1 1000000 "$EMISSION" 1 1000000 2500000 "$TTL" | paste -sd ' ')
assert_equal "$multi" "0 500000 2"
assert_equal "$(stored "$tag:requests")" 1100000
assert_equal "$(stored "$tag:models")" 3000000
assert_equal "$(stored "$tag:users")" 2000000

printf 'Redis model rate window Lua integration checks passed.\n'
