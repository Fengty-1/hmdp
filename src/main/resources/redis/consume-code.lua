-- 验证成功即删除；并发登录最多只有一次能消费同一个验证码。
local code = redis.call('HGET', KEYS[1], 'code')
if not code then
    return 0
end
if code == ARGV[1] then
    redis.call('DEL', KEYS[1])
    return 1
end
local remaining = redis.call('HINCRBY', KEYS[1], 'remaining', -1)
if remaining <= 0 then
    redis.call('DEL', KEYS[1])
end
return 0
