-- KEYS: the buckets an admitted call charged. ARGV: one credit (microseconds) per key, then the horizon
-- each of those charges wrote, then "now" in microseconds and the key TTL in milliseconds.
-- Used when a later layer refused the call, so the caller does not pay provider capacity for traffic
-- that never left the process.
-- Only a charge whose horizon is still in the future owes anything back. Once the horizon has passed,
-- the next call rebases its own debt on the present and this charge is already gone from the bucket:
-- subtracting its credit then would shorten that newer call's wait and let the group run above the
-- rate it is supposed to hold. A refund that arrives late is therefore a no-op, not a gift.
local count = #KEYS
local now = tonumber(ARGV[count * 2 + 1])
local ttl = tonumber(ARGV[count * 2 + 2])
local repaid = 0
for index = 1, count do
  if tonumber(ARGV[count + index]) > now then
    local stored = redis.call('GET', KEYS[index])
    if stored then
      local value = tonumber(stored) - tonumber(ARGV[index])
      if value < now then value = now end
      redis.call('SET', KEYS[index], string.format("%.0f", value), 'PX', ttl)
      repaid = repaid + 1
    end
  end
end
return repaid
