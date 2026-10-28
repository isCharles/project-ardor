-- KEYS: model minute requests, model minute tokens, user minute requests.
-- ARGV: QPM limit, TPM limit, user QPM limit, estimated tokens, minute TTL.
-- Limits at or below zero are disabled. Check every enabled dimension before incrementing any.
local qpm = tonumber(ARGV[1])
local tpm = tonumber(ARGV[2])
local userQpm = tonumber(ARGV[3])
local tokens = math.max(0, tonumber(ARGV[4]))
if qpm > 0 and tonumber(redis.call('GET', KEYS[1]) or '0') >= qpm then return -1 end
if tpm > 0 and tonumber(redis.call('GET', KEYS[2]) or '0') + tokens > tpm then return -2 end
if userQpm > 0 and tonumber(redis.call('GET', KEYS[3]) or '0') >= userQpm then return -3 end
if qpm > 0 then
  local count = redis.call('INCR', KEYS[1])
  if count == 1 then redis.call('EXPIRE', KEYS[1], tonumber(ARGV[5])) end
end
if tpm > 0 then
  local count = redis.call('INCRBY', KEYS[2], tokens)
  if count == tokens then redis.call('EXPIRE', KEYS[2], tonumber(ARGV[5])) end
end
if userQpm > 0 then
  local count = redis.call('INCR', KEYS[3])
  if count == 1 then redis.call('EXPIRE', KEYS[3], tonumber(ARGV[5])) end
end
return 1
