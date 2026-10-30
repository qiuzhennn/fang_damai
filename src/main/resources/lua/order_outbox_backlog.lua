-- KEYS[1]：异步建单的Redis Stream
-- ARGV[1]：Relay消费组名称
--
-- 返回非负数：尚未确认转发完成的任务数量
-- 返回-1：无法确认积压量，调用方应停止当前新购买

if #KEYS ~= 1 or #ARGV ~= 1
        or not ARGV[1] or ARGV[1] == '' then
    return -1
end

local streamType = redis.call('TYPE', KEYS[1]).ok

-- 尚未创建任务流时，这个流中没有待转发任务。
if streamType == 'none' then
    return 0
end

if streamType ~= 'stream' then
    return -1
end

local groups = redis.call('XINFO', 'GROUPS', KEYS[1])

for _, group in ipairs(groups) do
    local name = nil
    local pending = nil
    local lag = nil

    -- XINFO返回字段名与字段值交替排列的列表。
    for i = 1, #group, 2 do
        local field = group[i]
        local value = group[i + 1]

        if field == 'name' then
            name = value
        elseif field == 'pending' then
            pending = value
        elseif field == 'lag' then
            lag = value
        end
    end

    if name == ARGV[1] then
        -- 未知数量不能按0放行，否则可能在转发异常时继续接受占票。
        if type(pending) ~= 'number'
                or type(lag) ~= 'number'
                or pending < 0 or lag < 0
                or pending % 1 ~= 0 or lag % 1 ~= 0 then
            return -1
        end

        -- 未领取任务，加上已领取但未确认转发完成的任务。
        return lag + pending
    end
end

-- 指定消费组尚未就绪，不能凭空认定任务已经全部转发。
return -1