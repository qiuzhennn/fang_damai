-- 检查重复请求，扣减redis库存，同时记录 status = RESERVED，但尚未确认数据库订单创建成功

-- KEYS[1]：库存Key
-- KEYS[2]：当前用户的请求记录Key
-- ARGV[1]：购买数量
-- ARGV[2]：票档ID，按字符串比较，避免大整数精度问题

local quantityText = ARGV[1]
local categoryId = ARGV[2]
local quantity = tonumber(quantityText)

-- 参数校验必须放在任何写操作之前
if not quantity or quantity % 1 ~= 0
        or quantity < 1 or quantity > 6
        or quantityText ~= tostring(quantity) then
    return 3
end

if not categoryId or not string.match(categoryId, '^[1-9]%d*$') then
    return 3
end

-- 先检查请求记录，重复请求不应再次检查并扣减库存
local requestType = redis.call('TYPE', KEYS[2]).ok


if requestType ~= 'none' then
    -- 请求记录异常
    if requestType ~= 'hash' then
        return 9
    end

    local oldCategoryId = redis.call('HGET', KEYS[2], 'ticketCategoryId')
    local oldQuantity = redis.call('HGET', KEYS[2], 'quantity')
    local status = redis.call('HGET', KEYS[2], 'status')

    --请求记录异常
    if not oldCategoryId or not oldQuantity or not status then
        return 9
    end

    -- 同一请求ID不能换票档或购买数量（同一请求 ID 的购买参数不一致）
    if oldCategoryId ~= categoryId
            or oldQuantity ~= quantityText then
        return 6
    end
    --相同请求已经预扣，不再扣减
    if status == 'RESERVED' then
        return 5
    --请求已创建订单
    elseif status == 'ORDER_CREATED' then
        return 7
    --请求已补偿，不允许重新预扣。（预扣后未完成下单，扣掉的库存已经恢复）
    elseif status == 'COMPENSATED' then
        return 8
    else
        return 9
    end
end

local stockType = redis.call('TYPE', KEYS[1]).ok

-- 库存 Key 不存在
if stockType == 'none' then
    return 1
end

if stockType ~= 'string' then
    return 4
end

local stockText = redis.call('GET', KEYS[1])

-- 只接受规范的非负整数字符串，避免DECRBY发生格式错误
if stockText ~= '0'
        and not string.match(stockText, '^[1-9]%d*$') then
    return 4
end

local stock = tonumber(stockText)

if not stock or stock > 2147483647 then
    return 4
end

-- 库存不足
if stock < quantity then
    return 2
end

-- 校验通过后才执行写操作。本次预扣成功，并建立请求记录
redis.call('DECRBY', KEYS[1], quantity)

redis.call('HSET', KEYS[2],
    'ticketCategoryId', categoryId,
    'quantity', quantityText,
    'status', 'RESERVED')

return 0

-- TODO: 实现订单创建后的ORDER_CREATED状态及orderId回写。
-- TODO: 实现失败补偿与COMPENSATED状态，避免重复回补。
-- TODO: 结合数据库幂等约束确定请求记录保留期限，当前不设过期。
-- TODO: 完善异常记录巡检及Redis故障恢复，不能直接删除记录后重试。