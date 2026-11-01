-- KEYS[1]: one burst counter. Undoes a reservation that the durable period check later refused.
-- A counter that already expired is left absent: refunding a vanished window would invent quota.
local count = tonumber(redis.call('GET', KEYS[1]) or '0')
if count <= 0 then return 0 end
return redis.call('DECR', KEYS[1])
