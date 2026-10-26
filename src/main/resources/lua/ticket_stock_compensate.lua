-- 订单创建失败后的 Redis 库存补偿：将预扣的数量加回来，并把请求标记为 COMPENSATED，防止重复回补

-- KEYS[1]：库存Key
-- KEYS[2]：请求记录Key
-- ARGV[1]：票档ID
-- ARGV[2]：购买数量
--
-- 返回值：
-- 0：本次补偿成功
-- 1：请求记录不存在
-- 2：请求参数冲突
-- 3：订单已创建，不允许执行创建失败补偿
-- 4：参数或数据异常
-- 5：此前已补偿，不再增加库存
-- 6：库存Key不存在，不能凭空重建

local categoryId = ARGV[1]
local quantityText = ARGV[2]
local quantity = tonumber(quantityText)

if not categoryId
        or not string.match(categoryId, '^[1-9]%d*$')
        or not quantity
        or quantity % 1 ~= 0
        or quantity < 1 or quantity > 6
        or quantityText ~= tostring(quantity) then
    return 4
end

local requestType = redis.call('TYPE', KEYS[2]).ok

if requestType == 'none' then
    return 1
end

if requestType ~= 'hash' then
    return 4
end

local oldCategoryId =
    redis.call('HGET', KEYS[2], 'ticketCategoryId')
local oldQuantity =
    redis.call('HGET', KEYS[2], 'quantity')
local status =
    redis.call('HGET', KEYS[2], 'status')

if not oldCategoryId or not oldQuantity or not status then
    return 4
end

if oldCategoryId ~= categoryId
        or oldQuantity ~= quantityText then
    return 2
end

-- 已补偿的请求，重复调用不能再加库存
if status == 'COMPENSATED' then
    return 5
end

if status == 'ORDER_CREATED' then
    return 3
end

if status ~= 'RESERVED' then
    return 4
end

-- RESERVED却已经带有订单ID，属于不一致数据，不能自动补偿
if redis.call('HEXISTS', KEYS[2], 'orderId') == 1 then
    return 4
end

local stockType = redis.call('TYPE', KEYS[1]).ok

if stockType == 'none' then
    return 6
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

-- 防止回补后超过当前数据库INT字段的范围
if not stock or stock > 2147483647 - quantity then
    return 4
end

redis.call('INCRBY', KEYS[1], quantity)
redis.call('HSET', KEYS[2], 'status', 'COMPENSATED')

return 0

-- TODO: 调用方必须协调创建与补偿，防止回补后数据库又生成订单。
-- TODO: Redis故障、数据缺失或状态异常时，进入核对恢复流程。
-- TODO: 本脚本只处理创建失败；订单取消和超时释放使用独立流程。