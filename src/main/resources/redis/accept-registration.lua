-- KEYS: runtime, users, candidate request. 所有可预见的类型/参数错误在写入前拒绝。
local function kind(key) return redis.call('TYPE', key).ok end
local function integer(value)
    local n = tonumber(value)
    if n and n == math.floor(n) then return n end
    return nil
end
if #KEYS ~= 3 or #ARGV ~= 3 or not string.match(ARGV[1], '^%d+$')
    or not string.match(ARGV[2], '^%d+$') or not string.match(ARGV[3], '^%d+$') then
    return 'INVALID_ARGUMENT'
end
if kind(KEYS[1]) == 'none' then return 'RUNTIME_NOT_READY' end
if kind(KEYS[1]) ~= 'hash' or (kind(KEYS[2]) ~= 'none' and kind(KEYS[2]) ~= 'hash')
    or kind(KEYS[3]) ~= 'none' then return 'RUNTIME_INVALID' end
local config = redis.call('HMGET', KEYS[1], 'sessionId', 'enabled', 'registrationStartAt',
    'registrationEndAt', 'sessionEndAt', 'expireAt', 'available', 'capacity')
local opens, closes, ends, expires = integer(config[3]), integer(config[4]), integer(config[5]), integer(config[6])
local available, capacity = integer(config[7]), integer(config[8])
if config[1] ~= ARGV[1] or config[2] ~= '1' or not opens or not closes or not ends or not expires
    or not available or not capacity or capacity < 1 or available < 0 or available > capacity
    or not (opens < closes and closes < ends and ends < expires)
    or redis.call('PEXPIRETIME', KEYS[1]) ~= expires then return 'RUNTIME_INVALID' end
if kind(KEYS[2]) == 'hash' and redis.call('PEXPIRETIME', KEYS[2]) ~= expires then return 'RUNTIME_INVALID' end
-- 已有占位在窗口关闭后仍返回原 ID；不续期、不补发。
local previous = redis.call('HGET', KEYS[2], ARGV[2])
if previous then return 'EXISTING:' .. previous end
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
if now < opens then return 'NOT_OPEN' end
if now >= closes then return 'CLOSED' end
if available == 0 then return 'FULL' end
redis.call('HINCRBY', KEYS[1], 'available', -1)
redis.call('HSET', KEYS[2], ARGV[2], ARGV[3])
redis.call('PEXPIREAT', KEYS[2], expires)
redis.call('HSET', KEYS[3], 'status', 'PENDING', 'userId', ARGV[2], 'sessionId', ARGV[1],
    'acceptedAt', now, 'expireAt', expires)
redis.call('PEXPIREAT', KEYS[3], expires)
return 'ACCEPTED:' .. ARGV[3] .. ':' .. now
