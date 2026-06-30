# Enterprise API Rate Limiter Integration — V5 보고서

| 항목 | 내용 |
|------|------|
| 프로젝트 | API_Rate_Limiter |
| 버전 | V5 |
| 기술 스택 | Spring Boot 4.1.0, Java 17, Redis 7, Lua Script, Servlet Filter / Spring Interceptor |
| 작성일 | 2026-06-30 |
| 목적 | V4 분산 토큰 버킷 엔진을 Spring MVC 요청 파이프라인에 결합하여 상용 가용 형태의 API 게이트웨이 보호 시스템 구축 |

---

## 핵심 결과 요약

| 항목 | 결과 |
|------|------|
| 인증 차단 | ✅ `ApiKeyFilter`가 Authorization 누락/오류 시 비즈니스 로직 이전에 HTTP 401 |
| 트래픽 제어 | ✅ `RateLimitInterceptor`가 컨트롤러 진입 전 토큰 차감, 초과 시 HTTP 429 Early Return |
| 표준 헤더 | ✅ `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `Retry-After` 주입 |
| 등급 정책 | ✅ API Key 프리픽스 기반 FREE / PRO / ENTERPRISE 분기 |
| 분산 정합성 | ✅ Lua 스크립트가 `redis.call('TIME')` 사용 → 멀티 인스턴스 Clock Skew 차단 |

---

## 1. 개요

V5는 V1~V4에서 고도화한 Redis Lua 기반 토큰 버킷 엔진을 실제 웹 요청 파이프라인의 **정문(Gateway)** 에 배치한다. 모든 API 요청은 비즈니스 로직(Controller)에 도달하기 전에 인증과 트래픽 제어 계층을 순차 통과한다.

외부 LLM API 대신 **Mock Provider**(`AiService`)를 두어, 오직 **Rate Limiter 시스템 자체의 보호 능력**을 증명하는 데 집중한다.

### 프로젝트 성장 서사

```
[V1~V3 로컬 가속] ── 단일 JVM 동시성 버그 발견, 락/Lock-Free 한계 정량화
        ↓
[V4 분산 정합성]  ── Scale-out 시 분산 레이스 증명, Redis Lua로 SSOT 확보
        ↓
[V5 프로덕션 결합] ── 분산 엔진을 Filter/Interceptor 인프라 계층에 결합 (본 문서)
        ↓
[V6 한계 검증]    ── k6 등 부하 테스트로 게이트웨이 p99 Latency 오버헤드 검증 (예정)
```

---

## 2. 요청 처리 흐름 및 토폴로지 통합 구조

```text
                    [ Client Request ]
                          │  POST /v1/chat
                          ▼  (Authorization: Bearer sk-userA)
╔════════════════════════════════════════════════════════════════════════╗
║   🔒 API Gateway Layer  (시스템 전면 보호 및 트래픽 제어)              ║
║                                                                        ║
║   1. ApiKeyFilter                                                      ║
║      └─ 헤더 검증 및 API Key 추출 (실패 시 401 Unauthorized 즉시 차단) ║
║                                                                        ║
║   2. RateLimitInterceptor                                              ║
║      └─ 컨트롤러 진입 전 전처리 스위치 역할 (tryAcquire 호출)          ║
║                                                                        ║
║   3. Redis Centralized Server                                          ║
║      └─ 단일 진실 공급원(SSOT) 시계 및 Lua Script 직렬화 원자 연산     ║
╚════════════════════════════════════════════════════════════════════════╝
                          │
                          ▼  [토큰 잔여 시 허용 (HTTP 200)]
          [ Core Business Controller (Mock AI Service) ]
```

- **Filter → Interceptor 순서**: Servlet Filter는 Spring MVC `DispatcherServlet`보다 앞단이므로, 인증(Key 추출)은 Filter에서, 트래픽 제어(핸들러 매핑 인지)는 Interceptor에서 수행하는 것이 책임 분리상 자연스럽다.

---

## 3. 디렉터리 구조 및 컴포넌트

```
src/main/java/com/api_rate_limiter/
├── config/
│   └── WebMvcConfig.java          # 필터 등록 + 인터셉터 경로 매핑
├── filter/
│   └── ApiKeyFilter.java          # Authorization 파싱, API Key 추출 (401)
├── interceptor/
│   └── RateLimitInterceptor.java  # preHandle tryAcquire 전처리 (429 + 헤더)
├── ratelimiter/
│   ├── LuaRedisRateLimiter.java   # V4 Lua 엔진 게이트웨이 래퍼
│   ├── RateLimitLuaScript.java    # rate-limiter.lua 로더 ({allowed, remaining})
│   ├── RateLimitTier.java         # 등급별 정책 (FREE/PRO/ENTERPRISE)
│   └── RateLimitResult.java       # tryAcquire 결과 (allowed/limit/remaining/retryAfter)
├── controller/
│   └── ChatController.java        # POST /v1/chat Mock 엔드포인트
├── service/
│   └── AiService.java             # 가짜 AI 응답 생성
└── dto/
    ├── ChatRequest.java
    └── ChatResponse.java
src/main/resources/scripts/rate-limiter.lua
```

### 레이어별 책임

1. **`ApiKeyFilter` (`OncePerRequestFilter`)**: `Authorization: Bearer ...`에서 Key를 추출해 `request.setAttribute("apiKey", ...)`로 전달. 누락/형식 오류 시 401 즉시 반환.
2. **`RateLimitInterceptor` (`HandlerInterceptor`)**: `preHandle()`에서 attribute의 `apiKey`로 `tryAcquire(apiKey)` 수행. **`preHandle`의 반환값 `boolean`이 곧 통과/차단 스위치다** — `true`면 컨트롤러로 진행, `false`면 컨트롤러를 실행하지 않고 응답을 종료한다.
   - 허용: 표준 헤더 주입 후 `true` 반환 → Controller 위임
   - 거부: `Retry-After` 포함 헤더 주입 후 429 Early Return (`false`)
3. **`ChatController`**: 외부 호출 없이 `AiService`로 고정 구조 Mock JSON 반환.

---

## 4. API 스펙 및 표준 헤더

### 4.1 허용 (HTTP 200 OK)

- **Endpoint**: `POST /v1/chat`
- **Headers**: `Authorization: Bearer sk-userA`
- **Response Body**:

```json
{ "answer": "Mock AI Response" }
```

- **Response Headers**:
  - `X-RateLimit-Limit: 10`
  - `X-RateLimit-Remaining: 9`
  - `Retry-After: 0`

### 4.2 한도 초과 (HTTP 429 Too Many Requests)

- **Response Body**:

```json
{ "error": "Too Many Requests" }
```

- **Response Headers**:
  - `Retry-After: 1` (1초 후 토큰 충전되므로 재시도 가이드)

> **[엔지니어링 노트: Retry-After 산정 방식]**
> HTTP 표준 명세에 따라 `Retry-After` 헤더는 초 단위 정수를 권장한다. 본 시스템은 밀리초 단위로 충전되는 토큰 버킷의 다음 가용 시점을 계산한 뒤, **클라이언트 규격을 만족하기 위해 초 단위로 올림/반올림 가공**하여 반환하도록 설계하였다.

### 4.3 인증 실패 (HTTP 401 Unauthorized)

```json
{ "error": "Unauthorized" }
```

---

## 5. 데이터 모델 및 등급별 정책

### 5.1 Redis 상태 구조

| 항목 | 값 |
|------|-----|
| Key 포맷 | `bucket:{apiKey}` (예: `bucket:sk-userA`) |
| 자료구조 | Hash (`tokens`, `lastRefillTime`) |
| TTL | 60초 (`rate-limiter.api.bucket-ttl-seconds`) — 휴면 키 메모리 점유 방지 |

### 5.2 등급별(Tier) 정책

| Tier | API Key 프리픽스 | Capacity | Refill Rate |
|------|------------------|----------|-------------|
| FREE | (기본) | 10 | 10 tokens/sec |
| PRO | `sk-pro-` | 50 | 50 tokens/sec |
| ENTERPRISE | `sk-enterprise-` | 200 | 200 tokens/sec |

정책 결정은 `RateLimitTier.fromApiKey()` 한 곳에 모았다. 향후 DB 연동으로 등급을 조회하도록 교체해도 나머지 코드는 영향받지 않는다.

> **[엔지니어링 노트: API Key Prefix 정책의 전제]**
> 본 프로젝트는 외부 인프라 의존성을 최소화하고 Rate Limiter 엔진 자체의 분산 정합성 검증에 집중하기 위해 프리픽스 기반 등급 판정을 채택하였다. 이는 프로덕션 환경에서 API Key 유효성 검증 후 **분산 캐시(Redis) 또는 RDBMS에서 유저 등급 데이터를 조회하여 파라미터로 주입하는 방식**으로 투명하게 확장 가능하다.

---

## 6. 분산 정합성의 두 축: 원자성 + 시간 동기화

V5가 멀티 인스턴스 환경에서 정확히 동작하려면 두 가지 문제를 동시에 풀어야 한다. 하나는 **연산의 원자성**(왜 Lua인가), 다른 하나는 **시간 기준의 통일**(Clock Skew)이다.

### 6.1 왜 Lua 스크립트인가 (원자성)

토큰 버킷 한 번의 처리는 본질적으로 **읽기 → 계산 → 쓰기**의 3단계다.

```
HGET(현재 토큰 조회) → refill/consume 계산 → HSET(갱신된 토큰 저장)
```

이 3단계를 애플리케이션(자바)에서 개별 Redis 명령으로 나눠 실행하면, 명령 사이에 다른 요청이 끼어드는 **Check-Then-Act Race Condition**이 발생한다. 이는 V4-1 `NaiveRedisTokenBucket`에서 이미 실패로 증명한 시나리오다.

```
Server A ──> HGET tokens=1 (확인)
Server B ──> HGET tokens=1 (동시에 확인)
Server A ──> HSET tokens=0, 허용
Server B ──> HSET tokens=0, 허용   ← capacity=1인데 2건 허용 (정합성 붕괴)
```

Redis는 **Lua 스크립트 전체를 단일 스레드 이벤트 루프에서 직렬 실행**하므로, 스크립트가 도는 동안 다른 명령이 절대 끼어들 수 없다. 즉 `rate-limiter.lua`는 읽기·계산·쓰기를 **하나의 원자 단위**로 묶어, 분산 환경에서도 Race Condition을 원천 차단한다.

> 이것이 V1(락 없음) → V2(락) → V3(CAS) → V4(분산 Lua)로 이어진 동시성 서사의 종착점이다. 단일 JVM의 CAS가 하던 역할을, 분산 환경에서는 Redis의 단일 스레드 직렬 실행이 대신한다.

### 6.2 Clock Skew 해결 (시간 동기화)

> **⚠️ 멀티 서버(8080/8081)가 동일 Redis 버킷을 공유할 때, 애플리케이션의 `System.nanoTime()`을 시간 기준으로 넘기면 정합성이 파괴된다.**
> `nanoTime()`은 장비 부팅 기준 단조 시각이라 서버 간 절대 시각이 동기화되지 않기 때문이다.

V5의 `rate-limiter.lua`는 시간 기준을 **Redis 자체의 `redis.call('TIME')`** 으로 계산한다.

```lua
local time = redis.call('TIME')
local now = (tonumber(time[1]) * 1000) + math.floor(tonumber(time[2]) / 1000)
```

- 모든 인스턴스가 동일한 Redis 시계를 기준으로 refill을 계산 → **Clock Skew 원천 차단**
- 음수 elapsed 방어(`elapsedMillis < 0 → 0`)로 시계 역행 상황도 방어

**밀리초 변환의 이유:** `redis.call('TIME')` 명령은 Redis 엔진의 현재 시각을 `[Seconds, Microseconds]` 배열로 반환한다. Token Bucket의 연속적 충전(`refill`) 연산 시 소수점 누적 오차를 줄이고 자바 백엔드 시스템과의 단위 일관성을 유지하기 위해, 소스 데이터를 **밀리초(ms) 단위의 단일 타임스탬프로 변환**하여 연산에 활용한다.

> **🔥 [비결정적 명령과 복제 정합성]**
> 루아 스크립트 내부에서 `TIME`과 같은 비결정적(Non-deterministic) 명령을 호출하면 과거 레디스 버전(5.0 미만)에서는 복제 노드 간 정합성 붕괴를 막기 위해 에포크 에러를 발생시켰다.
> 그러나 **Redis 5.0 이후(본 프로젝트 채택 버전 7)부터는 스크립트 구문 자체가 아닌 실행 결과로 변경된 '데이터(Effects)'만을 복제본으로 전송**하므로, 레디스 내장 시계를 활용한 분산 시계 동기화 아키텍처를 안전하게 구축할 수 있다.

### 6.3 종합

원자성(6.1)과 시간 동기화(6.2)를 모두 Redis 단(端)에서 해결하므로, V5는 8080/8081 다중 인스턴스 환경에서도 동일 정책을 일관되게 적용한다.

| 위협 | 원인 | V5의 해결 |
|------|------|-----------|
| 분산 Race Condition | 읽기/계산/쓰기 분리 실행 | Lua 단일 스레드 원자 실행 |
| Clock Skew | 서버별 `nanoTime()` 불일치 | `redis.call('TIME')` 단일 시계 |

---

## 7. 실행 방법

### 7.1 Redis 기동

```powershell
docker compose up -d
```

### 7.2 단일 인스턴스

```powershell
.\gradlew.bat bootRun
```

### 7.3 멀티 인스턴스 (분산 정합성 확인)

```powershell
.\gradlew.bat bootRun --args="--server.port=8080"
.\gradlew.bat bootRun --args="--server.port=8081"
```

### 7.4 호출 예시

```powershell
curl -X POST http://localhost:8080/v1/chat -H "Authorization: Bearer sk-userA" -H "Content-Type: application/json" -d '{\"message\":\"hi\"}' -i
```

- 11번째 요청부터 `429 Too Many Requests` + `Retry-After: 1`

---

## 8. 테스트 체계

| 테스트 | Redis 필요 | 목적 |
|--------|-----------|------|
| `RateLimitTierTest` | ❌ | 등급 프리픽스 분기 로직 (순수 단위) |
| `LuaRedisRateLimiterTest` | ✅ | 엔진 tryAcquire: 허용/잔여 토큰/거부/등급 capacity |
| `ChatApiGatewayTest` | ✅ | 게이트웨이 통합: 401 / 200+헤더 / 429+Retry-After |

```powershell
.\gradlew.bat test
```

> Redis(로컬 `localhost:6379` 또는 Testcontainers)가 없으면 Redis 기반 테스트는 `Assumptions.abort`로 **SKIPPED** 처리되어 빌드는 성공한다. 게이트웨이 검증을 완전히 수행하려면 `docker compose up -d` 후 실행한다.

---

## 9. 성공 기준 달성 여부

| 기준 | 달성 |
|------|------|
| 인증 실패 시 401 차단 (Filter) | ✅ |
| 한도 초과 시 429 Early Return (Interceptor) | ✅ |
| 표준 레이트 리밋 헤더 주입 | ✅ |
| 등급별 정책 분기 구조 | ✅ |
| `redis.call('TIME')` 기반 분산 시간 동기화 | ✅ |
| Mock 비즈니스 엔드포인트 (`/v1/chat`) | ✅ |

---

## 10. 한계 및 다음 단계 (V6 예고)

| 항목 | 설명 |
|------|------|
| 부하 검증 부재 | 게이트웨이 추가로 인한 p95/p99 Latency 오버헤드 미측정 |
| 등급 정적 분기 | 현재 프리픽스 기반, DB/캐시 연동 등급 조회는 미구현 |
| 인증 단순화 | API Key 형식 검증만 수행, 실제 키 유효성/권한 검증 없음 |
| Redis SPOF | 단일 Redis 의존 — 고가용(Sentinel/Cluster) 구성 별도 필요 |

**V6 제언:** V6 단계에서는 가상 유저(Virtual Users) 부하 생성 도구인 **k6**를 활용하여, 서빙 파이프라인 전면에 Gateway Layer가 추가됨에 따라 발생하는 p95/p99 지연 시간(Latency) 오버헤드를 아래 3가지 다차원 워크로드 시나리오로 정량화할 예정이다.

| 시나리오 | 부하 형태 | 테스트 목적 | 기대 결과 |
| --- | --- | --- | --- |
| **Distributed User** | 1,000명 유저가 개별 키로 무작정 인입 | 대규모 분산 환경 하의 인프라 기초 체력 및 TPS 측정 | 높은 대역폭 처리, 완만한 지연 시간 |
| **Hot User** | 특정 단일 API Key로 10만 건 집중 타격 | 단일 Redis Key 직렬화 실행에 따른 병목 임계점 파악 | CPU 스핀 부하 및 429 Early Return 레이턴시 방어력 확인 |
| **Mixed Tier** | FREE / PRO / ENTERPRISE 비율 혼합 인입 | 등급별 동적 한도 제어의 정확성 및 자원 배분 정합성 검증 | 티어별 용량 한도 한계 도달 시 정확한 차단 여부 데이터화 |

---

## 부록: V5 신규 파일 트리

```
src/main/resources/scripts/rate-limiter.lua
src/main/java/com/api_rate_limiter/
├── config/WebMvcConfig.java
├── filter/ApiKeyFilter.java
├── interceptor/RateLimitInterceptor.java
├── ratelimiter/
│   ├── LuaRedisRateLimiter.java
│   ├── RateLimitLuaScript.java
│   ├── RateLimitTier.java
│   └── RateLimitResult.java
├── controller/ChatController.java
├── service/AiService.java
└── dto/
    ├── ChatRequest.java
    └── ChatResponse.java
src/test/java/com/api_rate_limiter/
├── ChatApiGatewayTest.java
└── ratelimiter/
    ├── RateLimitTierTest.java
    └── LuaRedisRateLimiterTest.java
docs/V5_REPORT.md
```
