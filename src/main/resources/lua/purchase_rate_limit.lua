-- KEYS[1]：当前用户的令牌桶
-- KEYS[2]：当前票档的令牌桶
--
-- ARGV[1]：用户桶容量
-- ARGV[2]：用户每秒补充的令牌数
-- ARGV[3]：票档桶容量
-- ARGV[4]：票档每秒补充的令牌数
--
-- 返回0：允许继续购买
-- 返回1：用户请求过于频繁
-- 返回2：当前票档繁忙
-- 返回3：参数或桶记录异常

local userCapacity = tonumber(ARGV[1])
local userRate = tonumber(ARGV[2])
local ticketCapacity = tonumber(ARGV[3])
local ticketRate = tonumber(ARGV[4])

-- 限流配置异常时不能继续放行，也不能修改桶。
local function validConfig(capacity, rate)
    return capacity and rate
        and capacity >= 1 and capacity <= 1000000
        and rate >= 1 and rate <= 1000000
        and capacity % 1 == 0
        and rate % 1 == 0
        and capacity / rate <= 86400
end

if #KEYS ~= 2 or #ARGV ~= 4
        or KEYS[1] == KEYS[2]
        or not validConfig(userCapacity, userRate)
        or not validConfig(ticketCapacity, ticketRate) then
    return 3
end

-- 使用Redis时间，避免多个后端实例的机器时间不一致。
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000
        + math.floor(tonumber(time[2]) / 1000)

-- 读取并计算当前可用额度，只计算，不修改Redis。
local function calculateBucket(key, capacity, rate)
    local keyType = redis.call('TYPE', key).ok

    -- 第一次使用或空闲过期后，从满桶开始。
    if keyType == 'none' then
        return capacity, now
    end

    if keyType ~= 'hash' then
        return nil
    end

    local record = redis.call(
        'HMGET', key, 'tokens', 'updatedAt'
    )

    local tokens = tonumber(record[1])
    local updatedAt = tonumber(record[2])

    -- 桶记录异常时停止处理，不能擅自当作满桶重新放行。
    if not tokens or not updatedAt
            or tokens < 0 or updatedAt < 0 then
        return nil
    end

    -- 距离上次记录过去多久，就补充对应数量，最多补到桶容量。
    local elapsed = math.max(0, now - updatedAt)
    local available = math.min(
        capacity,
        tokens + elapsed * rate / 1000
    )

    -- Redis时间短暂回拨时，不倒退记录时间，避免重复补充。
    return available, math.max(now, updatedAt)
end

local userTokens, userUpdatedAt =
        calculateBucket(KEYS[1], userCapacity, userRate)

local ticketTokens, ticketUpdatedAt =
        calculateBucket(KEYS[2], ticketCapacity, ticketRate)

if not userTokens or not ticketTokens then
    return 3
end

-- 两个额度都确认允许之前，不进行任何扣减。
if userTokens < 1 then
    return 1
end

if ticketTokens < 1 then
    return 2
end

local function saveBucket(key, tokens, updatedAt, capacity, rate)
    redis.call(
        'HSET', key,
        'tokens', tostring(tokens - 1),
        'updatedAt', tostring(updatedAt)
    )

    -- 空闲超过补满桶所需的时间后，可以让桶自动过期。
    -- 下次访问从满桶开始，与空闲期间自然补满的效果一致。
    local ttl = math.ceil(capacity / rate * 1000) + 1000
    redis.call('PEXPIRE', key, ttl)
end

-- 用户和票档都允许，本次各消耗一个进入额度。
saveBucket(
    KEYS[1], userTokens, userUpdatedAt,
    userCapacity, userRate
)

saveBucket(
    KEYS[2], ticketTokens, ticketUpdatedAt,
    ticketCapacity, ticketRate
)

return 0