-- KEYS: user month, user minute, global minute, optional idempotency key.
-- ARGV: corresponding limits, monthly TTL, minute TTL, idempotency enabled.
if ARGV[6] == '1' and redis.call('EXISTS', KEYS[4]) == 1 then return 2 end
for i = 1, 3 do
  if tonumber(redis.call('GET', KEYS[i]) or '0') >= tonumber(ARGV[i]) then return -i end
end
for i = 1, 3 do
  local count = redis.call('INCR', KEYS[i])
  if count == 1 then
    local ttl = i == 1 and tonumber(ARGV[4]) or tonumber(ARGV[5])
    redis.call('EXPIRE', KEYS[i], ttl)
  end
end
if ARGV[6] == '1' then redis.call('SET', KEYS[4], '1', 'EX', tonumber(ARGV[4])) end
return 1
