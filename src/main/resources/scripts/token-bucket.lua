-- V4-2: CasTokenBucket.tryConsume()과 동일한 refill + consume 로직을 Redis 단일 스레드에서 원자 실행
local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refillRate = tonumber(ARGV[2])
local ttlSeconds = tonumber(ARGV[3])

-- 분산 환경 기준 시각은 애플리케이션 서버 시간이 아니라 Redis TIME을 사용한다.
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

if tokens < 1.0 then
    redis.call('HSET', key, 'tokens', tostring(tokens), 'lastRefillTime', tostring(lastRefillTime))
    redis.call('EXPIRE', key, ttlSeconds)
    return 0
end

tokens = tokens - 1.0
redis.call('HSET', key, 'tokens', tostring(tokens), 'lastRefillTime', tostring(lastRefillTime))
redis.call('EXPIRE', key, ttlSeconds)
return 1
