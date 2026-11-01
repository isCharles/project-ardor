-- KEYS: rate buckets that share one hash tag, so the whole check stays inside a single Redis slot.
-- ARGV: for every bucket three values (emission microseconds per unit, weight, burst microseconds),
--       then "now" in microseconds and the key TTL in milliseconds.
-- A zero emission disables that bucket. Returns {admitted, wait microseconds, bucket index} where the
-- index (1-based) names the bucket that forced the wait, or 0 when the call is admitted.
-- This is a generic GCRA token bucket: capacity replenishes continuously, so unlike a calendar-minute
-- counter it cannot be doubled by straddling a window edge, and every bucket is judged before any is
-- written, so a denial leaves no partial debt behind.
local buckets = #KEYS
local now = tonumber(ARGV[buckets * 3 + 1])
local ttl = tonumber(ARGV[buckets * 3 + 2])
local horizon = {}
local wait = 0
local worst = 0
for index = 1, buckets do
  horizon[index] = 0
  local emission = tonumber(ARGV[(index - 1) * 3 + 1])
  if emission > 0 then
    local weight = tonumber(ARGV[(index - 1) * 3 + 2])
    local burst = tonumber(ARGV[(index - 1) * 3 + 3])
    local allowed = now
    local stored = redis.call('GET', KEYS[index])
    if stored then allowed = tonumber(stored) end
    if allowed < now then allowed = now end
    local arrival = allowed + weight * emission - burst
    if arrival > now and arrival - now > wait then
      wait = arrival - now
      worst = index
    end
    horizon[index] = allowed + weight * emission
  end
end
if wait > 0 then return {0, math.floor(wait + 0.5), worst} end
for index = 1, buckets do
  if horizon[index] > 0 then
    redis.call('SET', KEYS[index], string.format("%.0f", horizon[index]), 'PX', ttl)
  end
end
return {1, 0, 0}
