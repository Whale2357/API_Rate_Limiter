-- V5: refill + consume을 Redis 단일 스레드에서 원자 실행하고, 허용 여부와 잔여 토큰을 함께 반환한다.
-- 분산 환경에서 Clock Skew를 차단하기 위해 기준 시각은 redis.call('TIME')을 사용한다.
local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refillRate = tonumber(ARGV[2])
local ttlSeconds = tonumber(ARGV[3])

local time = redis.call('TIME')
local now = (tonumber(time[1]) * 1000) + math.floor(tonumber(time[2]) / 1000)

local tokens = redis.call('HGET', key, 'tokens')
local lastRefillTime = redis.call('HGET', key, 'lastRefillTime')

if tokens == false then
    tokens = capacity
    lastRefillTime = now
else
    tokens = tonumber(tokens)
    lastRefillTime = tonumber(lastRefillTime)
end

local elapsedMillis = now - lastRefillTime
if elapsedMillis < 0 then
    elapsedMillis = 0
end
local tokensToAdd = (elapsedMillis / 1000.0) * refillRate
if tokensToAdd > 0 then
    tokens = math.min(capacity, tokens + tokensToAdd)
    lastRefillTime = now
end

local allowed = 0
if tokens >= 1.0 then
    tokens = tokens - 1.0
    allowed = 1
end

redis.call('HSET', key, 'tokens', tostring(tokens), 'lastRefillTime', tostring(lastRefillTime))
redis.call('EXPIRE', key, ttlSeconds)

-- {허용 여부(1/0), 잔여 토큰(floor)}
return {allowed, math.floor(tokens)}
