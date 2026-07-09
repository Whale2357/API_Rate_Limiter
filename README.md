# API Rate Limiter

> 단일 JVM 동시성부터 분산 정합성, 그리고 성능 엔지니어링까지 — Token Bucket Rate Limiter를 **6단계(V1~V6)** 로 진화시키며 동시성·분산·관측 문제를 직접 재현하고 해결한 학습/포트폴리오 프로젝트.

![Java](https://img.shields.io/badge/Java-17-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.0-6DB33F)
![Redis](https://img.shields.io/badge/Redis-7-DC382D)
![Lua](https://img.shields.io/badge/Lua-Script-000080)
![k6](https://img.shields.io/badge/k6-Load%20Test-7D64FF)
![Prometheus](https://img.shields.io/badge/Prometheus-Metrics-E6522C)
![Grafana](https://img.shields.io/badge/Grafana-Dashboard-F46800)
![Gradle](https://img.shields.io/badge/Build-Gradle-02303A)
![Docker](https://img.shields.io/badge/Docker-Compose-2496ED)

---

## Why this project

단순히 "동작하는 Rate Limiter"를 만드는 것이 아니라, **동시성 버그를 코드로 재현 → 측정 → 해결**하는 과정 자체를 기록하는 데 집중했다. 각 버전은 이전 버전의 **한계를 정량적으로 증명**하고, 다음 버전이 그 문제를 어떻게 푸는지를 보여준다.

- **Check-Then-Act Race Condition** 을 테스트로 재현
- 블로킹 락 vs **CAS Lock-Free** 성능 벤치마크
- **Redis Lua 단일 스레드 직렬 실행** 으로 분산 원자성 확보
- `redis.call('TIME')` 으로 멀티 인스턴스 **Clock Skew 차단**
- Spring **Filter / Interceptor** 게이트웨이 계층 결합
- **k6 + Prometheus + Grafana** 로 게이트웨이 오버헤드 및 429 보호 가동률 정량화

---

## Evolution at a glance (V1 → V6)

| 버전 | 핵심 주제 | 해결한 문제 | 남은 한계 | 보고서 |
|------|-----------|-------------|-----------|--------|
| **V1** | No Lock | 기본 Token Bucket 동작 | Race Condition으로 한도 초과 허용 | [V1](docs/V1_REPORT.md) |
| **V2** | `synchronized` / `ReentrantLock` | 로컬 임계 영역 동시성 해결 | 락 블로킹 대기 비용 | [V2](docs/V2_REPORT.md) |
| **V3** | CAS Lock-Free (`AtomicReference`) | 논블로킹 상태 전환 | 단일 JVM 한계 (Scale-out 불가) | [V3](docs/V3_REPORT.md) |
| **V4** | Redis + Lua (분산) | 멀티 인스턴스 분산 정합성(SSOT) | 실제 요청 파이프라인 미통합 | [V4](docs/V4_REPORT.md) |
| **V5** | API Gateway 통합 | Filter/Interceptor 결합, 등급 정책, 표준 헤더 | 대규모 부하 검증 미수행 | [V5](docs/V5_REPORT.md) |
| **V6** | Performance Engineering | k6 4대 시나리오, 관측 스택, 한계 TPS/Latency 실측 | 프로덕션 규모 확장 검증 | [V6](docs/V6_REPORT.md) |

> 핵심 서사: **V1(버그 발견) → V2(락) → V3(Lock-Free) → V4(분산 원자성) → V5(프로덕션 결합) → V6(성능·한계 검증)**.
> 단일 JVM의 CAS가 하던 역할을, 분산 환경에서는 Redis의 단일 스레드 직렬 실행이 대신한다.

---

## Architecture (V5 Gateway + V6 Observability)

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

  [ k6 Load Generator ] ──HTTP──> Gateway (:8080/:8081)
       │                              │
       │                              ├──> [ Prometheus ] ──> [ Grafana :3000 ]
       │                              └──> [ Redis Exporter ]
```

---

## Tech Stack

- **Language / Runtime**: Java 17
- **Framework**: Spring Boot 4.1.0 (Spring MVC, Servlet Filter, HandlerInterceptor, Actuator)
- **Store**: Redis 7 (Hash + Lua Script)
- **Observability**: Prometheus, Grafana, Redis Exporter
- **Load Test**: k6 (Docker `grafana/k6`)
- **Build**: Gradle
- **Infra / Test**: Docker Compose, Testcontainers, JUnit 5

---

## Quick Start

### 1. 인프라 기동 (Redis + 관측 스택)

```powershell
docker compose up -d
```

- Redis: `localhost:6379`
- Prometheus: `http://localhost:9090`
- Grafana: `http://localhost:3000` (기본 `admin` / `admin`)

### 2. 애플리케이션 실행

```powershell
.\gradlew.bat bootRun
```

### 3. API 호출

```powershell
curl -X POST http://localhost:8080/v1/chat `
  -H "Authorization: Bearer sk-userA" `
  -H "Content-Type: application/json" `
  -d '{\"message\":\"hi\"}' -i
```

- **허용 (200 OK)**: `{ "answer": "Mock AI Response" }` + `X-RateLimit-Remaining` 헤더
- **한도 초과 (429)**: `{ "error": "Too Many Requests" }` + `Retry-After: 1`
- **인증 실패 (401)**: `{ "error": "Unauthorized" }`

### 멀티 인스턴스 (분산 정합성 확인)

```powershell
.\gradlew.bat bootRun --args="--server.port=8080"
.\gradlew.bat bootRun --args="--server.port=8081"
```

두 인스턴스가 동일 Redis 버킷을 공유하므로 합산 한도가 정확히 지켜진다.

---

## 등급별 정책 (Tier)

API Key 프리픽스로 등급을 판정한다 (`RateLimitTier.fromApiKey()`).

| Tier | API Key 프리픽스 | Capacity | Refill Rate |
|------|------------------|----------|-------------|
| FREE | (기본) | 10 | 10 tokens/sec |
| PRO | `sk-pro-` | 50 | 50 tokens/sec |
| ENTERPRISE | `sk-enterprise-` | 200 | 200 tokens/sec |

---

## Tests & Benchmarks

```powershell
# 전체 단위/통합 테스트 (데모·벤치마크 태그는 제외)
.\gradlew.bat test

# 버전별 Race Condition 데모
.\gradlew.bat testV2Demo   # V2-1 로컬 레이스 재현
.\gradlew.bat testV3Demo   # V3-1 naive atomic 레이스 재현
.\gradlew.bat testV4Demo   # V4-1 naive Redis 분산 레이스 재현

# 동시성 성능 벤치마크 (락 vs CAS 등)
.\gradlew.bat testBenchmark
```

> Redis 기반 테스트는 로컬 `localhost:6379` 또는 Testcontainers가 없으면 `Assumptions.abort`로 **SKIPPED** 처리되어 빌드는 성공한다. 완전한 검증을 위해 `docker compose up -d` 후 실행하는 것을 권장한다.

---

## V6 Performance Validation

V6에서는 k6와 관측 스택을 결합해 4개 시나리오를 실행하고, 게이트웨이 계층의 성능 오버헤드와 429 보호 가동률을 정량화한다.

### 시나리오

| 시나리오 | 부하 형태 | 테스트 목적 |
|----------|-----------|-------------|
| Baseline | Rate Limiter OFF, 100 VUs | 순수 Mock 비즈니스 로직 기준선 |
| Distributed User | ON, 100 VUs, 100 keys | 운영 유사 분산 패턴 TPS/Latency |
| Hot User | ON, 100 VUs, 1 key 집중 | 단일 Key 직렬화 경합 및 429 응답성 |
| Mixed Tier | ON, FREE 90% + PRO 10% | 등급별 정책 정합성 |

### 실측 결과 요약 (100 VU, 60s)

| Scenario | TPS | p95 | 200 | 429 |
|----------|----:|----:|----:|----:|
| Baseline | 3,350 | 27ms | 100% | 0% |
| Distributed | 776 | 286ms | 100% | 0% |
| Hot User | 651 | 272ms | 51% | 49% |
| Mixed Tier | 606 | 279ms | 100% | 0% |

> Baseline 대비 게이트웨이 ON 시 TPS **~77% 감소**, avg latency **~10배 증가**. Hot User에서 429 **49%**로 보호 계층이 정상 가동함을 확인했다.

### 실행 방법 (Docker k6)

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass

# Baseline (게이트웨이 OFF, :8082)
.\gradlew.bat bootRun --args="--spring.profiles.active=v6-baseline --server.port=8082"
$env:BASELINE_TARGET="http://host.docker.internal:8082"
.\performance\k6\run-v6.ps1 -Scenario baseline -Vus 100 -Duration 60s -Engine docker

# Scenario 2~4 (게이트웨이 ON, :8080/:8081)
$env:TARGETS="http://host.docker.internal:8080,http://host.docker.internal:8081"
.\performance\k6\run-v6.ps1 -Scenario distributed -Vus 100 -Duration 60s -Engine docker
.\performance\k6\run-v6.ps1 -Scenario hotuser -Vus 100 -Duration 60s -Engine docker
.\performance\k6\run-v6.ps1 -Scenario mixed -Vus 100 -Duration 60s -Engine docker
```

상세 해석 및 Grafana 패널 가이드는 [V6 보고서](docs/V6_REPORT.md)를 참고한다.

---

## Project Structure

```text
src/main/java/com/api_rate_limiter/
├── domain/          # 전략별 TokenBucket 구현 (V1~V4)
├── config/          # RateLimiterStrategy, WebMvcConfig (게이트웨이 ON/OFF)
├── filter/          # ApiKeyFilter (인증, 401)
├── interceptor/     # RateLimitInterceptor (트래픽 제어, 429)
├── ratelimiter/     # LuaRedisRateLimiter, RateLimitTier, RateLimitResult
├── controller/      # ChatController (POST /v1/chat)
└── service/         # AiService (Mock)
src/main/resources/
├── scripts/         # token-bucket.lua (V4), rate-limiter.lua (V5)
└── application-v6-baseline.properties  # V6 Baseline 프로필 (게이트웨이 OFF)
monitoring/          # Prometheus, Grafana 프로비저닝 (V6)
performance/k6/      # k6 시나리오 및 run-v6.ps1 (V6)
docs/                # V1~V6 상세 보고서
```

---

## 핵심 기술 포인트

### 1. 분산 원자성 (Why Lua)
토큰 버킷 처리는 본질적으로 **읽기 → 계산 → 쓰기** 3단계다. 이를 자바에서 개별 Redis 명령으로 나누면 명령 사이에 다른 요청이 끼어드는 Check-Then-Act Race Condition이 발생한다(V4-1에서 증명). Redis는 **Lua 스크립트 전체를 단일 스레드 이벤트 루프에서 직렬 실행**하므로, 3단계를 하나의 원자 단위로 묶어 분산 환경에서도 레이스를 원천 차단한다.

### 2. Clock Skew 차단 (시간 동기화)
멀티 서버가 동일 버킷을 공유할 때 `System.nanoTime()`(장비 부팅 기준 단조 시각)을 넘기면 서버 간 시각 불일치로 정합성이 깨진다. V5는 Lua 내부에서 `redis.call('TIME')`을 호출해 **모든 인스턴스가 동일한 Redis 시계**를 기준으로 refill을 계산한다.

### 3. 책임 분리된 게이트웨이
- `ApiKeyFilter` (Servlet Filter): 인증/Key 추출 → 실패 시 401
- `RateLimitInterceptor` (HandlerInterceptor): `preHandle` 반환 boolean이 통과/차단 스위치 → 초과 시 429 Early Return
- 표준 헤더(`X-RateLimit-Limit/Remaining`, `Retry-After`) 주입

### 4. 성능 엔지니어링 (V6)
- Baseline(게이트웨이 OFF)과 ON 시나리오를 분리 측정해 **오버헤드의 원인**을 분리한다.
- Hot User 시나리오로 단일 Redis Key 직렬화 병목과 **429 Early Return** 응답성을 검증한다.
- Actuator 메트릭 + Grafana 대시보드로 TPS, Latency, 200/429 비율, Redis CPU를 실시간 관측한다.

---

## Documentation

각 단계의 문제 정의 · 실험 · 측정 결과 · 한계는 보고서에 상세히 정리되어 있다.

- [V1 — No Lock & Race Condition](docs/V1_REPORT.md)
- [V2 — Locking (synchronized / ReentrantLock)](docs/V2_REPORT.md)
- [V3 — Lock-Free CAS](docs/V3_REPORT.md)
- [V4 — Distributed Token Bucket (Redis + Lua)](docs/V4_REPORT.md)
- [V5 — Enterprise API Gateway Integration](docs/V5_REPORT.md)
- [V6 — Performance Engineering and Limit Verification](docs/V6_REPORT.md)
