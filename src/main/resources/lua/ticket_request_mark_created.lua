-- 数据库订单创建成功后，把订单 ID 和成功状态记到 Redis 的请求记录中，方便重复请求查询结果。

-- KEYS[1]：请求记录Key
-- ARGV[1]：票档ID
-- ARGV[2]：购买数量
-- ARGV[3]：数据库订单ID
--
-- 返回值：
-- 0：回写成功，或者此前已回写同一订单
-- 1：请求记录不存在
-- 2：购买参数或订单ID冲突
-- 3：请求已补偿，不允许直接改为创建成功
-- 4：请求记录或参数异常

local categoryId = ARGV[1]
local quantityText = ARGV[2]
local orderId = ARGV[3]
local quantity = tonumber(quantityText)

-- ID使用字符串检查，避免转成Lua数字丢失大整数精度
if not categoryId
        or not string.match(categoryId, '^[1-9]%d*$')
        or not orderId
        or not string.match(orderId, '^[1-9]%d*$')
        or not quantity
        or quantity < 1 or quantity > 6
        or quantity % 1 ~= 0
        or quantityText ~= tostring(quantity) then
    return 4
end

local requestType = redis.call('TYPE', KEYS[1]).ok

if requestType == 'none' then
    return 1
end

if requestType ~= 'hash' then
    return 4
end

local oldCategoryId =
    redis.call('HGET', KEYS[1], 'ticketCategoryId')
local oldQuantity =
    redis.call('HGET', KEYS[1], 'quantity')
local status =
    redis.call('HGET', KEYS[1], 'status')

if not oldCategoryId or not oldQuantity or not status then
    return 4
end

if oldCategoryId ~= categoryId or oldQuantity ~= quantityText then
    return 2
end

-- 同一订单重复回写，直接视为成功
if status == 'ORDER_CREATED' then
    local oldOrderId = redis.call('HGET', KEYS[1], 'orderId')

    if not oldOrderId then
        return 4
    end

    if oldOrderId == orderId then
        return 0
    end

    return 2
end

if status == 'COMPENSATED' then
    return 3
end

if status ~= 'RESERVED' then
    return 4
end

-- 一次命令同时保存订单ID和新状态
redis.call('HSET', KEYS[1],
    'status', 'ORDER_CREATED',
    'orderId', orderId)

return 0

-- TODO: 记录缺失或出现已补偿但数据库有订单时，
--       进入一致性核对流程，不能在这里自行扣库存修复。