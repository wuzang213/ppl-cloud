-- refreshToken 校验 + 原子删除双向索引：返回空串 = 校验失败，返回 version = 校验成功且已原子删除。value 格式为 "refreshToken:tokenVersion"。
-- KEYS[1]=login:refresh:{uid}:{deviceId} KEYS[2]=login:refresh:index:{refreshToken}；ARGV[1]=提交的 refreshToken
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
