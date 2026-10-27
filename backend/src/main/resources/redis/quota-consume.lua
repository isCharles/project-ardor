-- KEYS: user month, user week, user minute, global minute, optional idempotency key.
-- ARGV: corresponding limits, monthly TTL, weekly TTL, minute TTL, idempotency enabled, request-ID TTL.
if ARGV[8] == '1' and redis.call('EXISTS', KEYS[5]) == 1 then return 2 end
for i = 1, 4 do
  if tonumber(redis.call('GET', KEYS[i]) or '0') >= tonumber(ARGV[i]) then return -i end
end
for i = 1, 4 do
  local count = redis.call('INCR', KEYS[i])
  if count == 1 then
    local ttl = i == 1 and tonumber(ARGV[5]) or (i == 2 and tonumber(ARGV[6]) or tonumber(ARGV[7]))
    redis.call('EXPIRE', KEYS[i], ttl)
  end
end
if ARGV[8] == '1' then redis.call('SET', KEYS[5], '1', 'EX', tonumber(ARGV[9])) end
return 1
