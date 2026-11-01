-- KEYS[1]: one burst counter. ARGV[1]: limit, ARGV[2]: window TTL in seconds.
-- Single key on purpose: the script stays valid on a sharded Redis deployment.
local limit = tonumber(ARGV[1])
if limit <= 0 then return -1 end
local count = tonumber(redis.call('GET', KEYS[1]) or '0')
if count >= limit then return -1 end
local after = redis.call('INCR', KEYS[1])
if after == 1 then redis.call('EXPIRE', KEYS[1], tonumber(ARGV[2])) end
return after
