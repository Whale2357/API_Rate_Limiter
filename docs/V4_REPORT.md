# Distributed Token Bucket Rate Limiter — V4 보고서

| 항목 | 내용 |
|------|------|
| 프로젝트 | API_Rate_Limiter |
| 버전 | V4 |
| 기술 스택 | Spring Boot 4.1.0, Java 17, Redis 7, Lua Script |
| 작성일 | 2026-06-16 |
| 목적 | 단일 JVM 인메모리 한계를 넘어 Redis Single Source of Truth (SSOT) + Lua 원자성으로 분산 환경 정합성 확보 |

---

## 핵심 결과 요약

| 항목 | 결과 |
|------|------|
| Naive Redis 정합성 | ❌ 실패 (capacity=1에서도 2개 이상 허용 가능) |
| Lua Redis 정합성 | ✅ 성공 (capacity=1에서 정확히 1개 허용) |
| 다중 인스턴스 일관성 | ✅ Redis SSOT로 서버 수와 무관하게 동일 정책 유지 |
| 성능/처리량 | ⚠️ V3(CAS) 대비 감소 (네트워크 I/O + Redis 직렬 실행 비용) |
| 최종 판단 | **처리량 일부를 희생해 분산 정합성을 확보하는 것이 V4의 핵심 가치** |

---

## 1. 개요

V4는 V3까지의 **Single JVM Correct** 한계를 분산 환경으로 확장한다.

로드 밸런서 뒤에 여러 Spring Boot 인스턴스가 떠 있을 때, 각 서버가 자체 인메모리 버킷을 관리하면 정책(예: 초당 10회)의 N배가 허용된다. 이를 해결하기 위해 Redis를 **시스템 전체의 Single Source of Truth (SSOT)** 로 두고, V3의 `NaiveAtomic → CAS` 학습 곡선을 **Naive Redis → Lua Script Redis**로 재현한다.

### V4 로드맵 및 달성 현황

```
[V4-1] Naive Redis — GET/SET 분리 Check-Then-Act 실패 증명          ✅
       ↓
[V4-2] Lua Script Redis — refill + consume 원자 실행                ✅
       ↓
[V4-3] 다중 인스턴스 정합성 검증 및 성능 비교                        ✅ (본 문서)
```

### V4 설계 원칙

- V1~V3의 API, 레이어 구조, `TokenBucket` 인터페이스 유지
- `RateLimiterService` 코드는 수정하지 않고 **전략만 교체**
- 기존 인메모리 구현체 전부 보존
- Redis 전략은 `userId` 기반 키(`bucket:user:{userId}`)로 상태 외부화

### V4 핵심 학습 키워드

| 영역 | 키워드 |
|------|--------|
| 분산 시스템 | Shared State, Scale-out, Network Partition |
| Redis | Hash, TTL, Lua Script, Single-threaded Atomicity |
| 정합성 | Single JVM Correct ≠ Distributed Correct |
| 트레이드오프 | 네트워크 RTT, 처리량 vs 정합성 |

---

## 2. V3 대비 핵심 변경 사항

| 항목 | V3 | V4 |
|------|-----|-----|
| 상태 저장소 | JVM 힙 (`AtomicReference`) | Redis Hash |
| 동시성 제어 | CAS 루프 | Lua Script (Redis 이벤트 루프 내 원자 실행) |
| 다중 서버 | ❌ (서버마다 독립 버킷) | ✅ (Redis Single Source of Truth, SSOT) |
| 재시작 | 상태 소실 | Redis TTL 기반 휴면 버킷 정리 |
| 구현체 수 | 5개 | 7개 (+ NAIVE_REDIS, LUA_REDIS) |
| 실패 증명 | Naive Atomic | Naive Redis (GET → SET) |
| 네트워크 비용 | 없음 (나노초) | 있음 (밀리초) |

---

## 3. 아키텍처

### 3.1 분산 토폴로지

```
                     [ Client Requests ]
                             │
                             ▼
                    [ Load Balancer ]
                             │
            ┌────────────────┴────────────────┐
            ▼                                 ▼
   [ Server A: 8080 ]                [ Server B: 8081 ]
 (Stateless Application)           (Stateless Application)
            │                                 │
            └────────────────┬────────────────┘
                             ▼
                    [ Centralized Redis ]
                (Shared State: Token Buckets)
```

### 3.2 클래스 다이어그램

```
                    <<interface>>
                     TokenBucket
                          │
    ┌─────────┬───────────┼───────────┬─────────────┬──────────────┐
    ▼         ▼           ▼           ▼             ▼              ▼
  (V1~V3    ...      NaiveRedis-   LuaScript-
  인메모리)              TokenBucket  RedisTokenBucket
                         (V4-1)        (V4-2)
                            │              │
                            └──────┬───────┘
                                   ▼
                          Redis Hash (bucket:user:{userId})
                          fields: tokens, lastRefillTime
```

### 3.3 요청 처리 흐름 (변경 없음)

```
POST /api/request?userId={userId}
        │
        ▼
  RateLimiterService.tryAcquire(userId)
        │
        ▼
  TokenBucketManager.getOrCreate(userId)   ← userId별 Redis 래퍼 캐시
        │
        ▼
  TokenBucketFactory.create(userId)        ← Redis 전략 시 userId 필수
        │
        ▼
  TokenBucket.tryConsume()                 ← Redis I/O 또는 Lua EVAL
```

### 3.4 Redis 데이터 모델

| 항목 | 값 |
|------|-----|
| Key | `bucket:user:{userId}` |
| Field `tokens` | 잔여 토큰 수 (String/Float) |
| Field `lastRefillTime` | 마지막 충전 시각 (밀리초) |
| TTL | `rate-limiter.redis.bucket-ttl-seconds` (기본 3600초) |

### 3.5 신규 클래스

| 클래스 | 패키지 | 역할 |
|--------|--------|------|
| `NaiveRedisTokenBucket` | domain | V4-1: HMGET → 계산 → HMSET (실패 증명) |
| `LuaScriptRedisTokenBucket` | domain | V4-2: Lua EVAL로 refill + consume 원자 실행 |
| `RedisTokenBucketKeys` | redis | Redis 키 네이밍 |
| `TokenBucketLuaScript` | redis | `token-bucket.lua` 스크립트 로더 |

### 3.6 전략 설정

`application.properties`:

```properties
# NO_LOCK | SYNCHRONIZED | REENTRANT_LOCK | NAIVE_ATOMIC | CAS | NAIVE_REDIS | LUA_REDIS
rate-limiter.strategy=LUA_REDIS

spring.data.redis.host=localhost
spring.data.redis.port=6379
rate-limiter.redis.bucket-ttl-seconds=3600
```

---

## 4. 구현체 상세

### 4.1 NaiveRedisTokenBucket (V4-1) — 실패 증명

Redis Hash에서 상태를 **읽고(HMGET)**, 애플리케이션에서 refill/consume을 계산한 뒤 **쓴다(HMSET)**.  
V3-1의 Check-Then-Act가 네트워크 경계를 넘어 **분산 레이스 컨디션**으로 재등장한다.

```java
List<String> values = redisTemplate.opsForHash()
        .multiGet(redisKey, List.of("tokens", "lastRefillTime"));
// ... refill 계산 ...
if (tokens < 1.0) { writeState(...); return false; }

tokens -= 1.0;          // Check 완료 후 Act — 다른 서버도 동시에 통과 가능
writeState(tokens, ...);
return true;
```

#### 분산 Race Condition 발생 원리

```
Server A ──> HMGET bucket:alice ──> tokens=1 확인
Server B ──> HMGET bucket:alice ──> tokens=1 확인 (동시 읽기)
Server A ──> HMSET tokens=0, return true
Server B ──> HMSET tokens=0, return true ──> capacity=1인데 2개 허용!
```

**핵심 명제:** Redis를 써도 **명령 간 원자성**이 없으면 V1과 동일한 문제가 재발한다.

---

### 4.2 LuaScriptRedisTokenBucket (V4-2) — Redis 직렬 실행 기반 분산 원자성 확보

`CasTokenBucket`의 `BucketState + CAS` 패턴을 Redis Lua Script로 1:1 대응한다.  
이 방식은 Lock-Free 알고리즘이 아니라, Redis의 **단일 스레드 이벤트 루프 직렬 실행**으로 원자성을 강제하는 방식이다. 즉, Lua 스크립트 실행 중에는 다른 명령이 개입하지 못해 refill + consume이 단일 원자 연산이 된다.

`src/main/resources/scripts/token-bucket.lua`:

```lua
-- 1. HMGET으로 상태 조회
-- 2. refill 계산 (CasTokenBucket.refill()과 동일)
-- 3. tokens < 1 → 상태 반영 후 0 반환
-- 4. tokens >= 1 → tokens-1 저장 후 1 반환
-- 5. EXPIRE로 TTL 갱신
```

Java 호출:

```java
Long result = redisTemplate.execute(
        consumeScript,
        List.of(redisKey),
        Integer.toString(capacity),
        Double.toString(refillRate),
        Long.toString(bucketTtlSeconds));
return result != null && result == 1L;
```

---

## 5. V4-1 — Naive Redis 실패 증명

### 5.1 실험 설계

| 항목 | 값 |
|------|-----|
| 전략 | `NAIVE_REDIS` |
| capacity | 1 |
| refillRate | 0.0 |
| 스레드 수 | 500 |
| 반복 횟수 | 50회 (매 회 새 userId) |
| 테스트 | `DistributedConcurrencyTest.v4_naiveRedis_capacityOne_exceedsAllowedLimit` |
| 태그 | `v4-demo` |

### 5.2 기대 결과

capacity=1인데 **2개 이상 허용** → GET/SET 비원자성 증명.

### 5.3 실행 방법

```bash
docker compose up -d
./gradlew testV4Demo
```

> Redis가 없으면 테스트는 `Assumptions.abort`로 **SKIPPED** 처리된다.

---

## 6. V4-2 — Lua Script 정확성 검증

### 6.1 단일 버킷 동시성

| 항목 | 값 |
|------|-----|
| 전략 | `LUA_REDIS` |
| capacity | 1 |
| 스레드 수 | 500 |
| 기대값 | allowed = **1** |

### 6.2 다중 인스턴스 시뮬레이션

동일 `userId`에 대해 **서로 다른 `TokenBucket` 래퍼 2개**(Server A/B)를 생성하고, Round-Robin으로 500 스레드가 교차 요청한다.

| 구현체 | 2개 서버 합산 허용 | 정합성 |
|--------|-------------------|--------|
| In-Memory CAS (V3) | 최대 2 | ❌ |
| Naive Redis (V4-1) | 2 이상 | ❌ |
| **Lua Redis (V4-2)** | **정확히 1** | ✅ |

테스트: `ConcurrencyRaceConditionTest.v4_inMemoryCas_twoInstances_*`, `DistributedConcurrencyTest.v4_luaRedis_twoInstances_*`

---

## 7. V4-3 — 성능 트레이드오프

### 7.1 네트워크 레이턴시 오버헤드

| 구현체 | 저장소 접근 | 대략적 지연 |
|--------|------------|------------|
| CAS (V3) | JVM 메모리 | 나노초 |
| LUA_REDIS (V4) | Redis 네트워크 I/O | 밀리초 |

V3 대비 V4 처리량은 **네트워크 RTT** 때문에 크게 감소할 수 있다. 이는 버그가 아니라 **분산 정합성을 위한 필연적 비용**이다.

### 7.2 해석 포인트 (실험 결과 요약 문장)

- `LUA_REDIS`는 Race Condition을 제거하지만, Redis 왕복과 Lua 실행으로 단건 처리 지연이 증가한다.
- Hot User(동일 key 집중) 구간의 병목은 Redis 자체 성능 저하가 아니라, **동일 key에 대한 Lua 실행 경로 직렬화**라는 동시성 모델의 구조적 특성에서 기인한다.
- 따라서 V4의 목적은 "최고 TPS"가 아니라 "**분산 환경에서의 정책 정확성 보장**"이다.

### 7.3 TTL 운영 당위성

- TTL이 없거나 과도하게 길면, 비활성 사용자 버킷 데이터가 Redis 메모리를 영구 점유한다.
- 사용자 수 증가 시 휴면 키가 누적되어 인프라 비용 상승과 OOM 리스크로 이어질 수 있다.
- 따라서 `rate-limiter.redis.bucket-ttl-seconds`는 기능 옵션이 아니라 운영 안정성을 위한 필수 제어 파라미터다.

### 7.4 벤치마크 실행

```bash
docker compose up -d
./gradlew testBenchmark
```

Redis 전략 벤치마크: `DistributedConcurrencyBenchmarkTest` (`NAIVE_REDIS`, `LUA_REDIS`)

---

## 8. 로컬 개발 환경

### 8.1 Redis 기동

```bash
docker compose up -d
```

### 8.2 다중 애플리케이션 서버

```bash
./gradlew bootRun --args='--server.port=8080 --rate-limiter.strategy=LUA_REDIS'
./gradlew bootRun --args='--server.port=8081 --rate-limiter.strategy=LUA_REDIS'
```

### 8.3 부하 테스트 (capacity=1, Round-Robin)

8080/8081에 교차 요청 500건을 보내고, Lua Redis는 **합산 1건만 허용**되는지 확인한다.

---

## 9. 테스트 체계

| 테스트 | 태그 | Gradle 태스크 | 목적 |
|--------|------|--------------|------|
| `DistributedConcurrencyTest.v4_naiveRedis_*` | `v4-demo` | `testV4Demo` | Naive Redis 실패 증명 |
| `DistributedConcurrencyTest.v4_luaRedis_*` | — | `test` | Lua 정확성 (Redis 필요) |
| `ConcurrencyRaceConditionTest.v4_inMemoryCas_*` | — | `test` | 인메모리 분산 실패 기준선 |
| `DistributedConcurrencyBenchmarkTest` | `benchmark` | `testBenchmark` | Redis 처리량 측정 |

```bash
./gradlew test              # Redis 없으면 Redis 테스트 SKIPPED
./gradlew testV4Demo        # V4-1 실패 증명 (Redis 필요)
./gradlew testBenchmark     # 성능 벤치마크
```

---

## 10. 성공 기준 달성 여부

| 기준 | 달성 |
|------|------|
| V4-1 Naive Redis로 분산 Check-Then-Act 실패 증명 | ✅ |
| V4-2 Lua Script로 capacity=1 시 정확히 1건만 허용 | ✅ |
| 다중 서버 시뮬레이션에서 Lua Redis 정합성 | ✅ |
| V1~V3 구현체 및 API 호환 유지 | ✅ |
| docker-compose Redis 환경 제공 | ✅ |

---

## 11. 한계 및 잔존 문제

| 항목 | 설명 |
|------|------|
| Redis SPOF | Redis 장애 시 Rate Limiting 불가 → Sentinel/Cluster는 V5+ |
| Hot User 병목 | 단일 Redis 키 직렬화 → Sharding/Redis Cluster 검토 |
| Clock Skew | `LUA_REDIS`는 Redis `TIME` 기준으로 계산하여 서버 간 시각 불일치를 원천 차단. `NAIVE_REDIS`는 `currentTimeMillis()` + NTP 동기화 가정 |
| 네트워크 비용 | 정합성 대가로 처리량 감소 |
| 실험 범위 한계 | 본 결과는 로컬 실험 환경 기준이며, 프로덕션의 네트워크 파티션·복제 지연·페일오버 변수는 별도 검증 필요 |

---

## 12. 결론

V4는 **"Single JVM Correct ≠ Distributed Correct"** 를 실험으로 증명하고, Lua Script로 분산 원자성을 확보했다.

1. **V4-1 (Naive Redis):** 외부 저장소 도입만으로는 부족 — 명령 간 원자성이 핵심
2. **V4-2 (Lua Redis):** `CasTokenBucket`의 CAS 패턴을 Redis 단일 스레드 실행으로 분산 확장
3. **트레이드오프:** 처리량은 감소하지만, **본 실험 범위 내에서는** 정책 위반 없이 분산 정합성을 보장함을 확인

**권장 운영 전략:** 프로덕션 Scale-out 환경에서는 `LUA_REDIS` 전략 + Redis 고가용 구성을 사용한다.

---

## 13. V3 CAS vs V4 Lua 동시성 모델 비교

| 비교 항목 | JVM CAS (V3) | Redis Lua Script (V4) |
| --- | --- | --- |
| 실행 위치 (Context) | 애플리케이션 프로세스 내부 (JVM Heap) | 원격 인메모리 저장소 (Redis Engine) |
| 원자성 보장 방식 | 하드웨어 CAS 연산 기반 | Single-thread 기반 명령 직렬화 (Serialization) |
| 네트워크 비용 (RTT) | 없음 (메모리 접근) | 있음 (TCP/IP 통신 오버헤드) |
| 분산 환경 지원 여부 | 불가능 (단일 JVM 메모리 국한) | 가능 (다중 인스턴스가 동일 상태 공유) |
| Hot User 병목 지점 | CPU Cache Line 경합, CAS 재시도 스핀 | 단일 Redis Key 직렬 실행 경로 병목 |

---

## 14. 다음 단계 (V5 제언)

V5 단계에서는 JMeter 또는 k6를 도입해 대규모 가상 유저 트래픽을 생성하고, `NO_LOCK`, `SYNCHRONIZED`, `REENTRANT_LOCK`, `CAS`, `LUA_REDIS` 전 전략의 처리량(Throughput), 평균 지연, p95/p99 지연을 동일 조건에서 비교한다. 이를 통해 각 동시성 모델의 정합성-성능 트레이드오프를 운영 관점에서 최종 정량화한다.

---

## 부록: V4 신규 파일 트리

```
docker-compose.yml
src/main/resources/scripts/token-bucket.lua
src/main/java/com/api_rate_limiter/
├── domain/
│   ├── NaiveRedisTokenBucket.java
│   └── LuaScriptRedisTokenBucket.java
└── redis/
    ├── RedisTokenBucketKeys.java
    └── TokenBucketLuaScript.java
src/test/java/com/api_rate_limiter/
├── DistributedConcurrencyTest.java
├── DistributedConcurrencyBenchmarkTest.java
└── support/
    ├── RedisTestSupport.java
    └── RedisTestResource.java
docs/V4_REPORT.md
```
