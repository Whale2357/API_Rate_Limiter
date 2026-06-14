# In-Memory Token Bucket Rate Limiter — V1 보고서

| 항목 | 내용 |
|------|------|
| 프로젝트 | API_Rate_Limiter |
| 버전 | V1 |
| 기술 스택 | Spring Boot 4.1.0, Java 17 |
| 작성일 | 2026-06-12 |
| 목적 | 사용자별 Token Bucket Rate Limiter 기반 구현 및 향후 동시성 분석(V2)을 위한 토대 마련 |

---

## 1. 개요

V1은 Spring Boot 기반의 **인메모리(In-Memory) Token Bucket Rate Limiter**이다.  
단순한 기능 구현을 넘어, **의도적으로 동기화를 적용하지 않은 상태**에서 동시성 문제를 관찰·분석할 수 있는 기반을 만드는 것이 목표이다.

### V1 설계 원칙

- Redis, DB 등 외부 저장소 사용 금지
- 분산 시스템 개념 도입 금지
- 동시성 최적화 의도적 배제 (단순 구현 유지)
- Race Condition 분석은 V2에서 수행

---

## 2. Rate Limiting 정책

| 항목 | 값 | 설명 |
|------|-----|------|
| Bucket Capacity | 10 | 사용자당 최대 보유 토큰 수 (Burst) |
| Refill Rate | 초당 10개 | 시간 경과에 따른 토큰 자동 충전 |
| 소비 비용 | 요청 1회당 1토큰 | 토큰 부족 시 요청 거부 |

### 동작 요약

- 최초 요청 시 해당 사용자의 버킷 생성, 토큰 **10개**로 시작
- 요청 1회마다 토큰 1개 소비 → `{"allowed": true}`
- 토큰이 1개 미만이면 요청 거부 → `{"allowed": false}`
- 시간이 지나면 토큰이 자동 충전 (초당 10개, 최대 10개까지)

### 정책 해석

이 정책은 **"평생 10회"** 제한이 아니라 **"초당 약 10회"** 를 허용하는 Token Bucket 방식이다.

- **Burst**: 짧은 시간에 최대 10회까지 허용
- **Sustained**: 초당 10회 이하로 요청하면 충전과 소비가 균형을 이루어 계속 허용될 수 있음

---

## 3. 아키텍처

### 3.1 요청 처리 흐름

```
POST /api/request?userId={userId}
        │
        ▼
  RequestController
        │
        ▼
  RateLimiterService.tryAcquire(userId)
        │
        ▼
  TokenBucketManager.getOrCreate(userId)
        │
        ▼
  ConcurrentHashMap<String, TokenBucket>
        │
        ▼
  TokenBucket.tryConsume()
```

### 3.2 레이어별 역할

| 레이어 | 클래스 | 책임 |
|--------|--------|------|
| Controller | `RequestController` | HTTP 요청 수신, `userId` 추출, 응답 반환 |
| Service | `RateLimiterService` | Rate Limiting 비즈니스 오케스트레이션 |
| Manager | `TokenBucketManager` | 사용자별 `TokenBucket` 생성 및 조회 |
| Domain | `TokenBucket` | 토큰 충전(`refill`) 및 소비(`tryConsume`) |
| DTO | `RateLimitResponse` | API 응답 (`allowed` 필드) |

### 3.3 저장소

- `ConcurrentHashMap<String, TokenBucket>` 기반 인메모리 저장
- 사용자(`userId`)별로 독립적인 `TokenBucket` 인스턴스 보유
- 서버 재시작 시 모든 상태 초기화

---

## 4. 도메인 모델 — TokenBucket

### 4.1 필드

| 필드 | 타입 | 설명 |
|------|------|------|
| `tokens` | `double` | 현재 보유 토큰 수 |
| `lastRefillTime` | `long` | 마지막 충전 시각 (nanoTime) |
| `capacity` | `int` | 최대 토큰 수 (10) |
| `refillRate` | `double` | 초당 충전 속도 (10.0) |

### 4.2 `double`을 사용한 이유

`tokens` 필드는 `int`가 아닌 **`double`** 로 선언했다.

Token Bucket의 충전은 경과 시간에 **비례**한다. `refillRate`가 초당 10개이면, 요청 사이에 0.1초가 지났을 때 **1토큰**이 충전되어야 한다.

```
0.1초 경과 → 0.1 × 10 = 1.0 토큰 충전
0.05초 경과 → 0.05 × 10 = 0.5 토큰 충전
```

| 타입 | 문제 |
|------|------|
| `int` | 1초 미만의 경과에서 충전량이 0으로 잘림 → 충전이 뚝뚝 끊김 |
| `double` | 소수 단위 충전 누적 가능 → 시간에 비례한 **연속적(continuous) refill** 구현 |

`int`를 쓰려면 "마지막 충전 이후 100ms가 지났는지"처럼 별도 임계값 로직이 필요하다. `double`은 `tokensToAdd = elapsed × refillRate` 한 줄로 자연스럽게 표현된다.

소비 시점에는 `tokens >= 1` 조건으로 **정수 1개 단위**만 차감하므로, API 응답 의미(허용/거부)는 변하지 않는다.

### 4.3 `System.nanoTime()`을 사용한 이유

`lastRefillTime` 기록 및 경과 시간 계산에 **`System.currentTimeMillis()`가 아닌 `System.nanoTime()`** 을 사용한다.

| 메서드 | 특성 | Rate Limiter에 적합한가 |
|--------|------|-------------------------|
| `currentTimeMillis()` | Unix epoch 기준 **절대 시각** | ❌ NTP 동기화, DST, 수동 시각 변경에 영향받음 |
| `nanoTime()` | 임의 기준점부터의 **경과 시간** | ✅ 순수 경과 시간 측정용 |

```
currentTimeMillis() 문제 시나리오:
  1. lastRefillTime = 1000ms 기록
  2. OS가 시각을 5초 뒤로 조정
  3. elapsed가 음수 → 충전 로직 오작동 가능

nanoTime():
  → 시스템 시각 변경과 무관하게 monotonic한 경과 시간만 측정
```

Rate Limiter가 필요한 것은 "지금 몇 시인가"가 아니라 **"마지막 충전 이후 얼마나 지났는가"** 이다. `nanoTime()`은 이 목적에 맞는 Java 표준 API이다. 나노초 단위 해상도도 짧은 요청 간격의 경과 시간을 정밀하게 계산하는 데 유리하다.

### 4.4 토큰 충전 알고리즘 (`refill`)

```
1. 현재 시각(now)과 lastRefillTime의 차이(elapsed) 계산
2. tokensToAdd = (elapsed / 1초) × refillRate
3. tokensToAdd > 0 이면:
     tokens = min(capacity, tokens + tokensToAdd)
     lastRefillTime = now
```

경과 시간에 비례하여 토큰을 충전하며, `capacity`를 상한으로 한다.

### 4.5 토큰 소비 (`tryConsume`)

```
1. refill() 호출 (충전 먼저)
2. tokens >= 1 이면 tokens -= 1, return true
3. 그렇지 않으면 return false
```

---

## 5. API 명세

### Request

```
POST /api/request?userId={userId}
```

### Response

**허용**
```json
{"allowed": true}
```

**거부**
```json
{"allowed": false}
```

---

## 6. 테스트

### 6.1 단위 테스트 시나리오

| 시나리오 | 테스트 메서드 | 검증 내용 | 기대 결과 |
|----------|---------------|-----------|-----------|
| 1 | `scenario1_sameUserTenRequests_allAllowed` | 동일 사용자 10회 연속 요청 | 1~10회 모두 `true` |
| 2 | `scenario2_eleventhRequest_rejected` | 지연 없이 11번째 요청 | 11번째 `false` |
| 3 | `scenario3_waitOneSecond_requestsAvailableAgain` | 거부 후 1초 대기 | 다시 `true` |
| 4 | `differentUsers_haveIndependentBuckets` | 서로 다른 사용자 각 11회 | 사용자별 독립 동작 |

### 6.2 테스트 실행

```powershell
.\gradlew.bat test
```

### 6.3 결과 확인

**터미널**: `build.gradle`의 `testLogging` 설정으로 `STANDARD_OUT`에 요청별 응답 출력

```
userId=user-2, request #1 -> {"allowed": true}
...
userId=user-2, request #11 -> {"allowed": false}
```

**HTML 리포트**: `build\reports\tests\test\index.html`  
→ 테스트 클래스 클릭 → **Standard output** 섹션에서 응답 확인

### 6.4 수동 API 테스트 (curl)

```powershell
# 서버 실행 (터미널 1)
.\gradlew.bat bootRun

# API 호출 (터미널 2) — PowerShell에서는 curl.exe 사용
curl.exe -X POST "http://localhost:8080/api/request?userId=alice"
```

> **주의**: PowerShell의 `curl`은 `Invoke-WebRequest` 별칭이므로 `curl.exe`를 사용해야 한다.

---

## 7. 수동 테스트 시 주의사항

curl로 **한 줄씩 수동 입력**하면 요청 간격이 길어져 토큰이 충전된다.

| 요청 간격 | 충전량 (refillRate=10/s) | 결과 |
|-----------|--------------------------|------|
| ~100ms | ~1토큰 | 소비와 충전이 상쇄 → 계속 `true` |
| ~1초 | ~10토큰 | 버킷이 다시 가득 참 |

따라서 curl 수동 테스트만으로는 `false` 응답을 관찰하기 어렵다.  
**한도 초과 거부(`false`) 검증은 단위 테스트(scenario2)가 적합**하다.

---

## 8. 동시성 분석 (V1 한계)

### 8.1 문제의 본질

V1의 동시성 문제는 **공유 자원(`TokenBucket` 내부 상태)에 대한 동기화 부재**에서 발생한다.

```
ConcurrentHashMap          ← 맵 구조만 스레드 안전
    └── TokenBucket (alice)
            ├── tokens          ← 동기화 없음
            └── lastRefillTime  ← 동기화 없음
```

- `ConcurrentHashMap`은 **맵 자체의 동시 접근**만 보호한다.
- `TokenBucket`의 `tokens`, `lastRefillTime` 필드는 **여러 스레드가 동시에 읽기/쓰기** 가능하다.

### 8.2 Race Condition 유형

#### (1) Check-Then-Act

```java
if (tokens >= 1) {   // Thread A, B 모두 true 판정 가능
    tokens -= 1;       // 둘 다 차감 실행
    return true;
}
```

토큰 1개 남은 상태에서 동시 요청 2개 → **둘 다 `true`** (기대: 1개만 허용)

#### (2) Lost Update

`refill()`과 `tokens -= 1`이 원자적이지 않아, 토큰 수 갱신이 덮어씌워질 수 있다.

#### (3) lastRefillTime 경쟁

동시 `refill()` 호출 시 충전량이 중복 적용되거나 누락될 수 있다.

### 8.3 영향 범위

| 상황 | 동작 | 비고 |
|------|------|------|
| 같은 사용자, **순차** 요청 | 정상 | 단위 테스트로 검증 완료 |
| **다른 사용자** 동시 요청 | 정상 | 버킷이 사용자별로 분리됨 |
| **같은 사용자** 동시 요청 | 비정상 가능 | 한도 초과에도 `true` 반환 가능 |

> 여러 사용자가 동시에 요청해도 **서로의 토큰을 빼앗지 않는다**.  
> 문제는 **한 사용자에 대한 동시 요청**에서만 발생한다.

### 8.4 V1에서 관찰 가능한 오류 패턴

| 기대 동작 | V1 동시 요청 시 가능한 결과 |
|-----------|----------------------------|
| 토큰 0 → 요청 거부 | 여러 요청이 동시에 `true` |
| Burst 10회 초과 거부 | 11회 이상 허용 가능 |
| 정확한 Rate Limiting | 제한 무력화 가능 |

전형적인 결과는 **`false`가 더 나오는 것이 아니라, `true`가 과다 반환**되는 것이다.

---

## 9. V1 성공 기준 달성 여부

| 기준 | 달성 | 검증 방법 |
|------|------|-----------|
| 동일 사용자 10회 요청 모두 허용 | ✅ | scenario1 |
| 11번째 요청 거부 | ✅ | scenario2 |
| 1초 대기 후 재허용 | ✅ | scenario3 |
| 사용자별 독립 버킷 | ✅ | differentUsers 테스트 |
| 인메모리 저장 (DB/Redis 미사용) | ✅ | TokenBucketManager |
| 의도적 비동기화 (V2 분석 기반) | ✅ | TokenBucket에 lock 없음 |

---

## 10. V2 개선 방향 (예고)

| 항목 | V1 | V2 (예정) |
|------|-----|-----------|
| TokenBucket 동기화 | 없음 | `synchronized` / `ReentrantLock` |
| 동시 요청 테스트 | 없음 | 멀티스레드 부하 테스트 추가 |
| Race Condition | 존재 (의도적) | 해결 및 검증 |

---

## 11. 결론

V1은 Spring Boot 기반의 인메모리 Token Bucket Rate Limiter로, **단일 스레드·순차 요청 환경에서는 정책대로 정확히 동작**한다. 사용자별 독립 버킷, 토큰 충전/소비, 한도 초과 거부, 시간 경과 후 재허용이 모두 구현·검증되었다.

동시에 **의도적으로 동기화를 적용하지 않아**, 동일 사용자의 동시 요청 시 `TokenBucket` 내부 상태에 Race Condition이 발생할 수 있다. 이는 V1의 결함이 아니라 **V2에서 분석·개선할 대상을 남겨둔 설계**이다.

---

## 부록: 프로젝트 구조

```
src/main/java/com/api_rate_limiter/
├── ApiRateLimiterApplication.java
├── controller/
│   └── RequestController.java
├── service/
│   └── RateLimiterService.java
├── manager/
│   └── TokenBucketManager.java
├── domain/
│   └── TokenBucket.java
└── dto/
    └── RateLimitResponse.java

src/test/java/com/api_rate_limiter/
└── RateLimiterServiceTest.java
```
