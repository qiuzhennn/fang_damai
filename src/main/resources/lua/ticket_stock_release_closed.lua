-- 只处理已提交关单事务留下的释放任务。
-- KEYS[1]：票档可售库存
-- KEYS[2]：用户本次购买的预扣记录
-- ARGV：票档ID、购买数量、MySQL订单ID
--
-- 返回：
-- 0：本次释放成功
-- 5：此前已经释放
-- 1：预扣请求记录缺失
-- 2：购买资料或订单ID冲突
-- 3：记录为建单失败已补偿
-- 4：参数、库存或请求数据异常
-- 6：库存Key缺失

local categoryId = ARGV[1]
local quantityText = ARGV[2]
local orderId = ARGV[3]
local quantity = tonumber(quantityText)

if not categoryId or not string.match(categoryId, '^[1-9]%d*$')
        or not orderId or not string.match(orderId, '^[1-9]%d*$')
        or not quantity or quantity < 1 or quantity > 6
        or quantity % 1 ~= 0
        or quantityText ~= tostring(quantity) then
    return 4
end

local requestType = redis.call('TYPE', KEYS[2]).ok

if requestType == 'none' then return 1 end
if requestType ~= 'hash' then return 4 end

local savedCategory =
    redis.call('HGET', KEYS[2], 'ticketCategoryId')
local savedQuantity =
    redis.call('HGET', KEYS[2], 'quantity')
local status =
    redis.call('HGET', KEYS[2], 'status')
local savedOrder =
    redis.call('HGET', KEYS[2], 'orderId')

if not savedCategory or not savedQuantity or not status then
    return 4
end

if savedCategory ~= categoryId
        or savedQuantity ~= quantityText then
    return 2
end

-- MySQL已有关闭的订单，Redis却记录建单失败已补偿，
-- 说明两边数据存在冲突，不能再次增加库存。
if status == 'COMPENSATED' then
    return 3
end

if status == 'ORDER_CREATED' then
    if not savedOrder then return 4 end
    if savedOrder ~= orderId then return 2 end

elseif status == 'RESERVED' then
    -- MySQL可能已经建单成功，但成功状态尚未回写Redis。
    -- 此时请求记录不应已经带有订单ID。
    if savedOrder then return 4 end

else
    return 4
end

-- Redis已经回补，但MySQL任务可能尚未标记完成。
-- 任务重试时直接确认已释放，禁止重复增加库存。
local released =
    redis.call('HGET', KEYS[2], 'stockReleased')

if released then
    if released ~= '1' or status ~= 'ORDER_CREATED' then
        return 4
    end

    return 5
end

local stockType = redis.call('TYPE', KEYS[1]).ok

if stockType == 'none' then return 6 end
if stockType ~= 'string' then return 4 end

local stockText = redis.call('GET', KEYS[1])

if stockText ~= '0'
        and not string.match(stockText, '^[1-9]%d*$') then
    return 4
end

local stock = tonumber(stockText)

if not stock or stock > 2147483647 - quantity then
    return 4
end

redis.call('INCRBY', KEYS[1], quantity)

-- MySQL任务证明订单已存在且已关闭。
-- 若建单成功状态曾回写失败，这里补上订单ID，
-- 同时记录这笔订单占用的Redis库存已经释放。
redis.call('HSET', KEYS[2],
    'status', 'ORDER_CREATED',
    'orderId', orderId,
    'stockReleased', '1')

return 0