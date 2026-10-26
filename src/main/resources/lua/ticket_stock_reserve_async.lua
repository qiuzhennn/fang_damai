
-- 异步建单：库存预扣 + 可靠任务登记
--
-- KEYS[1] 库存 Key
-- KEYS[2] 购买请求记录 Key
-- KEYS[3] Redis Stream Key
--
-- ARGV[1] 购买数量
-- ARGV[2] 票档 ID
-- ARGV[3] 用户 ID
-- ARGV[4] requestId
-- ARGV[5] 服务端单价
-- ARGV[6] 演出开始时间
-- ARGV[7] 请求受理时间
--
-- 0  新预扣成功并登记任务
-- 1  库存未初始化
-- 2  库存不足
-- 3  参数非法
-- 4  库存数据异常
-- 5  同一请求已预扣
-- 6  请求参数冲突
-- 7  订单已创建
-- 8  请求已补偿
-- 9  请求记录异常
-- 10 Stream 登记失败
-- 11 请求来自其他下单模式

if #KEYS ~= 3 or #ARGV ~= 7 then
    return 3
end

local quantityText = ARGV[1]
local categoryId = ARGV[2]
local userId = ARGV[3]
local requestId = ARGV[4]
local unitPrice = ARGV[5]
local eventStartTime = ARGV[6]
local acceptedAt = ARGV[7]

local quantity = tonumber(quantityText)

-- 1. 校验购买数量
if not quantity
        or quantity % 1 ~= 0
        or quantity < 1
        or quantity > 6
        or quantityText ~= tostring(quantity) then
    return 3
end

-- 2. ID 校验：按字符串处理，避免大整数精度丢失
if not categoryId
        or not string.match(categoryId, '^[1-9]%d*$')
        or not userId
        or not string.match(userId, '^[1-9]%d*$') then
    return 3
end

-- 3. requestId 必须为小写 UUID
if not requestId or #requestId ~= 36
        or requestId:sub(9, 9) ~= '-'
        or requestId:sub(14, 14) ~= '-'
        or requestId:sub(19, 19) ~= '-'
        or requestId:sub(24, 24) ~= '-' then
    return 3
end

local hex = string.gsub(requestId, '-', '')

if #hex ~= 32 or not string.match(hex, '^[0-9a-f]+$') then
    return 3
end

-- 4. 票价是服务端快照，允许零元票
if not unitPrice
        or not string.match(unitPrice, '^%d+%.?%d*$')
        or not tonumber(unitPrice)
        or tonumber(unitPrice) < 0 then
    return 3
end

if not eventStartTime or eventStartTime == ''
        or not acceptedAt or acceptedAt == '' then
    return 3
end

if KEYS[1] == KEYS[2]
        or KEYS[1] == KEYS[3]
        or KEYS[2] == KEYS[3] then
    return 3
end

-- 5. 先检查请求幂等
local requestType = redis.call('TYPE', KEYS[2]).ok

if requestType ~= 'none' then

    if requestType ~= 'hash' then
        return 9
    end

    local oldCategoryId =
        redis.call('HGET', KEYS[2], 'ticketCategoryId')
    local oldQuantity =
        redis.call('HGET', KEYS[2], 'quantity')
    local status =
        redis.call('HGET', KEYS[2], 'status')
    local mode =
        redis.call('HGET', KEYS[2], 'mode')

    if not oldCategoryId or not oldQuantity or not status then
        return 9
    end

    if oldCategoryId ~= categoryId
            or oldQuantity ~= quantityText then
        return 6
    end

    -- 不允许同步和异步模式混用同一请求
    if mode ~= 'ASYNC' then
        return 11
    end

    if status == 'RESERVED' then
        return 5
    elseif status == 'ORDER_CREATED' then
        return 7
    elseif status == 'COMPENSATED' then
        return 8
    else
        return 9
    end
end

-- 6. 检查库存
local stockType = redis.call('TYPE', KEYS[1]).ok

if stockType == 'none' then
    return 1
end

if stockType ~= 'string' then
    return 4
end

local stockText = redis.call('GET', KEYS[1])

if stockText ~= '0'
        and not string.match(stockText, '^[1-9]%d*$') then
    return 4
end

local stock = tonumber(stockText)

if not stock or stock > 2147483647 then
    return 4
end

if stock < quantity then
    return 2
end

-- 7. 检查 Stream 的类型
local streamType = redis.call('TYPE', KEYS[3]).ok

if streamType ~= 'none' and streamType ~= 'stream' then
    return 10
end

-- 8. 先登记可靠待投递任务:它向 Redis Stream 添加一条待建单任务，返回 Stream 消息 ID。
local outboxId = redis.pcall(
    'XADD', KEYS[3], '*',
    'requestKey', KEYS[2],
    'userId', userId,
    'requestId', requestId,
    'ticketCategoryId', categoryId,
    'quantity', quantityText,
    'unitPrice', unitPrice,
    'eventStartTime', eventStartTime,
    'acceptedAt', acceptedAt
)

if type(outboxId) == 'table' and outboxId.err then
    return 10
end

-- 9. 扣减 Redis 可售库存
redis.call('DECRBY', KEYS[1], quantity)

-- 10. 登记本次请求的处理状态:mode：区分同步下单和异步下单;outboxId：关联 Redis Stream 任务，便于后续恢复与对账。
redis.call('HSET', KEYS[2],
    'ticketCategoryId', categoryId,
    'quantity', quantityText,
    'status', 'RESERVED',
    'mode', 'ASYNC',
    'outboxId', outboxId)

return 0
