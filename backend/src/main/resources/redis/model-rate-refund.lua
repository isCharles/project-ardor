-- KEYS: the buckets an admitted call charged. ARGV: one credit (microseconds) per key, then "now" in
-- microseconds and the key TTL in milliseconds.
-- Used when a later layer refused the call, so the caller does not pay provider capacity for traffic
-- that never left the process. Giving credit back can only pull a bucket closer to the present, and a
-- bucket that has already drained needs no state.
local count = #KEYS
local now = tonumber(ARGV[count + 1])
local ttl = tonumber(ARGV[count + 2])
local repaid = 0
for index = 1, count do
  local stored = redis.call('GET', KEYS[index])
  if stored then
    local credit = tonumber(ARGV[index])
    local value = tonumber(stored) - credit
    if value < now then value = now end
    redis.call('SET', KEYS[index], string.format("%.0f", value), 'PX', ttl)
    repaid = repaid + 1
  end
end
return repaid
