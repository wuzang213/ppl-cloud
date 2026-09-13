-- logout 原子删除双向索引（Bug #5）
-- KEYS[1]=反向key(login:refresh:index:{refreshToken})  ARGV[1]=REFRESH_TOKEN_KEY 前缀
-- 返回 1 删除成功，0 反向 key 不存在
local uidDevice = redis.call('GET', KEYS[1])
if not uidDevice then return 0 end
redis.call('DEL', ARGV[1] .. uidDevice)
redis.call('DEL', KEYS[1])
return 1
