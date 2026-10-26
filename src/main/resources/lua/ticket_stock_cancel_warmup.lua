-- KEYS[1]：票档库存Key
-- ARGV[1]：MySQL当前可售库存
--
-- 返回：
-- 0：Key已经不存在
-- 1：删除成功
-- 2：Redis库存与MySQL不一致
-- 3：参数或库存数据异常

local expected = ARGV[1]

if not expected
        or (expected ~= '0'
            and not string.match(expected, '^[1-9]%d*$'))
        or not tonumber(expected)
        or tonumber(expected) > 2147483647 then
    return 3
end

local stockType = redis.call('TYPE', KEYS[1]).ok

if stockType == 'none' then return 0 end
if stockType ~= 'string' then return 3 end

local actual = redis.call('GET', KEYS[1])

if actual ~= '0'
        and not string.match(actual, '^[1-9]%d*$') then
    return 3
end

if not tonumber(actual) or tonumber(actual) > 2147483647 then
    return 3
end

-- 数量不一致，可能还有尚未完成的预扣或回补。
-- 保留库存现场，交由调用方提示核对。
if actual ~= expected then return 2 end

-- 比较和删除共同执行，避免比较后又有请求抢先预扣。
return redis.call('DEL', KEYS[1])