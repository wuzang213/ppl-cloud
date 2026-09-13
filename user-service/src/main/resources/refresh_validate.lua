-- refreshToken 校验 + 原子删除双向索引（Bug #3 #5）
-- REFRESH_TOKEN_KEY 的 value 格式为 "refreshToken:tokenVersion"
-- KEYS[1]=正向key(login:refresh:{uid}:{deviceId})  KEYS[2]=反向key(login:refresh:index:{refreshToken})
-- ARGV[1]=提交的 refreshToken
-- 返回空串表示校验失败，返回 version 字符串表示校验成功且已原子删除双向索引
local stored = redis.call('GET', KEYS[1])
if not stored then return '' end
local sep = string.find(stored, ':')
if not sep then return '' end
local token = string.sub(stored, 1, sep - 1)
local version = string.sub(stored, sep + 1)
if token ~= ARGV[1] then return '' end
redis.call('DEL', KEYS[1])
redis.call('DEL', KEYS[2])
return version
