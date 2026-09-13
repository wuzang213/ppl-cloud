-- kickAllDevices 原子删除所有设备的双向索引 + 设备集合（Bug #2 #6）
-- KEYS[1]=设备集合key(login:devices:{userId})
-- ARGV[1]=REFRESH_TOKEN_KEY 前缀  ARGV[2]=REFRESH_INDEX_KEY 前缀  ARGV[3]=userId
-- 返回删除的设备数
local devices = redis.call('SMEMBERS', KEYS[1])
local count = 0
for _, deviceId in ipairs(devices) do
  local uidDevice = ARGV[3] .. ':' .. deviceId
  local tokenKey = ARGV[1] .. uidDevice
  local stored = redis.call('GET', tokenKey)
  redis.call('DEL', tokenKey)
  if stored then
    local sep = string.find(stored, ':')
    if sep then
      local token = string.sub(stored, 1, sep - 1)
      redis.call('DEL', ARGV[2] .. token)
    end
  end
  count = count + 1
end
redis.call('DEL', KEYS[1])
return count
