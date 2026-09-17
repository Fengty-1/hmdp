-- 发布事务提交后调用；仅初始化场次配置，不执行 Stage 3 的报名预占。
-- 所有类型、值和时间检查必须在第一次写入之前完成。
local capacity = tonumber(ARGV[3])
local opens = tonumber(ARGV[4])
local closes = tonumber(ARGV[5])
local starts = tonumber(ARGV[6])
local ends = tonumber(ARGV[7])
local expires = tonumber(ARGV[8])
if not tonumber(ARGV[1]) or not tonumber(ARGV[2]) or not capacity or capacity < 1
    or capacity ~= math.floor(capacity) or not opens or not closes or not starts
    or not ends or not expires or not (opens < closes and closes <= starts and starts < ends and ends < expires) then
    return -1
end
local fields = {'sessionId', 'activityId', 'capacity', 'registrationStartAt',
    'registrationEndAt', 'sessionStartAt', 'sessionEndAt', 'expireAt'}
local kind = redis.call('TYPE', KEYS[1]).ok
if kind ~= 'none' then
    if kind ~= 'hash' then return -2 end
    for i, field in ipairs(fields) do
        if redis.call('HGET', KEYS[1], field) ~= ARGV[i] then return -2 end
    end
    local available = tonumber(redis.call('HGET', KEYS[1], 'available'))
    if not available or available < 0 or available > capacity or available ~= math.floor(available)
        or redis.call('HGET', KEYS[1], 'enabled') ~= '1'
        or redis.call('PEXPIRETIME', KEYS[1]) ~= expires then return -2 end
    return 0
end
local now = redis.call('TIME')
local nowMillis = tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000)
-- 开放之后缺失运行键必须拒绝重建；即使 MySQL 仍有容量也不能推断未受理。
if nowMillis >= opens or redis.call('EXISTS', KEYS[2]) ~= 0 then return -3 end
redis.call('HSET', KEYS[1],
    'sessionId', ARGV[1], 'activityId', ARGV[2], 'capacity', ARGV[3],
    'registrationStartAt', ARGV[4], 'registrationEndAt', ARGV[5],
    'sessionStartAt', ARGV[6], 'sessionEndAt', ARGV[7], 'expireAt', ARGV[8],
    'available', ARGV[3], 'enabled', '1')
redis.call('PEXPIREAT', KEYS[1], ARGV[8])
return 1
