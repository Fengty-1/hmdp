-- 冷却检查、验证码写入与到期设置一起完成，避免并发发送覆盖新验证码。
if redis.call('EXISTS', KEYS[2]) == 1 then
    return 0
end
redis.call('HSET', KEYS[1], 'code', ARGV[1], 'remaining', ARGV[2])
redis.call('PEXPIRE', KEYS[1], ARGV[3])
redis.call('SET', KEYS[2], '1', 'PX', ARGV[4])
return 1