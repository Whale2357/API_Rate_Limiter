# In-Memory Token Bucket Rate Limiter — V2 보고서

| 항목 | 내용 |
|------|------|
| 프로젝트 | API_Rate_Limiter |
| 버전 | V2 |
| 기술 스택 | Spring Boot 4.1.0, Java 17 |
| 작성일 | 2026-06-13 |
| 목적 | V1의 Race Condition을 재현·분석하고, 동시성 제어 전략별 정확성·성능을 비교 검증 |

---

## 1. 개요

V2는 V1에서 **의도적으로 남겨둔 동시성 문제**를 해결하기 위한 버전이다.  
`TokenBucket`을 인터페이스로 추상화하고 **전략 패턴(Strategy Pattern)** 을 적용하여, 설정 변경만으로 실험군을 교체하며 벤치마킹할 수 있도록 설계했다.

### V2 로드맵 및 달성 현황

```
[V2-1] Race Condition 재현 (CountDownLatch)          ✅
       ↓
[V2-2] synchronized 적용 (비관적 락 - 암묵적)       ✅
       ↓
[V2-3] ReentrantLock 적용 (비관적 락 - 명시적)        ✅
       ↓
[V2-5] 정량적 성능 및 정확성 비교 분석 보고서 작성     ✅ (본 문서)
```

### V2 설계 원칙

- V1의 기능(정책, API, 레이어 구조)은 유지
- `RateLimiterService` 코드는 수정하지 않고 **전략만 교체**
- V1(`NO_LOCK`) 구현체를 보존하여 Race Condition 재현 가능
- 단일 JVM 인메모리 범위 유지 (분산 환경은 V4에서 다룸)

---

## 2. V1 대비 핵심 변경 사항

| 항목 | V1 | V2 |
|------|-----|-----|
| `TokenBucket` | 단일 클래스 | 인터페이스 + 3개 구현체 |
| 동기화 | 없음 | `synchronized` / `ReentrantLock` 선택 가능 |
| 전략 교체 | 불가 | `application.properties` 설정 |
| 동시성 테스트 | 없음 | Race Condition, 벤치마크, Hot User 분석 |
| Race Condition | 존재 (의도적) | SYNCHRONIZED/LOCK으로 해결 |

---

## 3. 아키텍처 — 전략 패턴 적용

### 3.1 클래스 다이어그램

```
               <<interface>>
                TokenBucket
                     │
    ┌────────────────┼────────────────┐
    ▼                ▼                ▼
V1TokenBucket   Synchronized-    ReentrantLock-
 (No Lock)        TokenBucket      TokenBucket
                     │
              AbstractTokenBucket (공통 refill 로직)
```

### 3.2 요청 처리 흐름 (변경 없음)

```
POST /api/request?userId={userId}
        │
        ▼
  RequestController
        │
        ▼
  RateLimiterService.tryAcquire(userId)     ← 변경 없음
        │
        ▼
  TokenBucketManager.getOrCreate(userId)
        │
        ▼
  TokenBucketFactory.create()               ← V2 추가
        │
        ▼
  RateLimiterStrategy에 따른 구현체 선택
        │
        ▼
  TokenBucket.tryConsume()
```

### 3.3 신규/변경 클래스

| 클래스 | 패키지 | 역할 |
|--------|--------|------|
| `TokenBucket` | domain | 인터페이스 (`tryConsume`, getter) |
| `AbstractTokenBucket` | domain | 공통 필드, `refill()` 로직 |
| `V1TokenBucket` | domain | 동기화 없음 (V1 로직 이관) |
| `SynchronizedTokenBucket` | domain | `synchronized(this)` 임계 영역 |
| `ReentrantLockTokenBucket` | domain | `ReentrantLock` 명시적 락 |
| `RateLimiterStrategy` | config | 전략 enum (`NO_LOCK`, `SYNCHRONIZED`, `REENTRANT_LOCK`) |
| `TokenBucketFactory` | factory | 설정 기반 `TokenBucket` 생성 |

### 3.4 전략 설정

`application.properties`:

```properties
# NO_LOCK | SYNCHRONIZED | REENTRANT_LOCK
rate-limiter.strategy=NO_LOCK
```

운영 환경에서 동시성 정확성이 필요하면 `SYNCHRONIZED` 또는 `REENTRANT_LOCK`으로 변경한다.

---

## 4. 구현체 상세

### 4.1 V1TokenBucket (NO_LOCK) — V2-1 실험군

```java
public boolean tryConsume() {
    refill();
    if (tokens >= 1) {
        tokens -= 1;
        return true;
    }
    return false;
}
```

- `refill()` → 확인 → 차감이 **원자적이지 않음**
- 동시 요청 시 Check-Then-Act Race Condition 발생

### 4.2 SynchronizedTokenBucket — V2-2

```java
public boolean tryConsume() {
    synchronized (this) {
        refill();
        if (tokens >= 1) {
            tokens -= 1;
            return true;
        }
        return false;
    }
}
```

- JVM **Monitor Lock** 사용
- `refill()` + 차감 전체를 하나의 임계 영역으로 보호
- 코드가 간결하고 `unlock` 누락 위험 없음

### 4.3 ReentrantLockTokenBucket — V2-3

```java
public boolean tryConsume() {
    lock.lock();
    try {
        refill();
        if (tokens >= 1) {
            tokens -= 1;
            return true;
        }
        return false;
    } finally {
        lock.unlock();
    }
}
```

- **명시적 락** (`java.util.concurrent.locks.ReentrantLock`)
- `fair` 파라미터로 공정성(Fairness) 설정 가능 (기본값: `false`)
- `tryLock()`, `lockInterruptibly()` 등 확장 가능 (현재 미사용)

---

## 5. V2-1 — Race Condition 재현

### 5.1 실험 설계

| 항목 | 값 |
|------|-----|
| 구현체 | `V1TokenBucket` (NO_LOCK) |
| capacity | 1 |
| refillRate | 0.0 (충전 없음) |
| 스레드 수 | 500 |
| 동기화 방식 | `CountDownLatch`로 동시 출발 |
| 반복 횟수 | 50회 (비결정적 재현 대비) |

**설계 의도:** 토큰이 1개뿐인 상황에서 최대한 많은 스레드가 동시에 `tryConsume()`을 호출하여 경합을 극대화한다.

### 5.2 테스트 코드

`ConcurrencyRaceConditionTest.v1_raceCondition_capacityOne_exceedsAllowedLimit`

```java
for (int attempt = 0; attempt < 50; attempt++) {
    TokenBucket bucket = factory.create(1, 0.0);
    int allowedCount = runConcurrentConsume(bucket);  // 500 스레드 동시 요청
    maxAllowed = Math.max(maxAllowed, allowedCount);
}
assertTrue(maxAllowed > 1);  // 1개 초과 허용 = Race Condition 재현
```

### 5.3 실행 결과

```
[V2-1 DEMO] V1 (No Lock) - max allowed across 50 attempts: 2 (expected 1, race condition reproduced)
PASSED
```

**해석:** capacity=1인데 **2개 요청이 허용**됨. 토큰 1개를 2개 스레드가 동시에 소비한 것이다.

### 5.4 Race Condition 발생 원리

```
Thread A: refill() → tokens=1 확인 → (대기)
Thread B: refill() → tokens=1 확인 → tokens-=1 → return true
Thread A: (재개)   → tokens-=1 → return true   ← 이미 0인데 또 차감
```

V1 보고서에서 예측한 **Check-Then-Act** 패턴이 실제로 재현되었다.

---

## 6. V2-2 / V2-3 — 동시성 제어 검증

### 6.1 실험 설계

V2-1과 동일한 조건(capacity=1, 500 스레드)에서 `SYNCHRONIZED`, `REENTRANT_LOCK` 전략을 검증한다.

### 6.2 실행 결과

```
[SYNCHRONIZED] allowed=1   PASSED
[REENTRANT_LOCK] allowed=1 PASSED
```

**해석:** 500개 스레드가 동시에 요청해도 **정확히 1개만 허용**. V1의 Race Condition이 해결되었음을 확인.

---

## 7. V2-5 — 성능 및 정확성 비교

### 7.1 실험 환경

| 항목 | 값 |
|------|-----|
| JVM | OpenJDK 17.0.17 |
| OS | Windows 10 |
| 총 요청 수 | 100,000 |
| 스레드 수 | 100 |
| 스레드당 요청 | 1,000 |
| 측정 도구 | `System.nanoTime()` |

> 벤치마크 수치는 실행 환경(CPU, 부하)에 따라 달라질 수 있다. 본 보고서의 값은 2026-06-13 측정 기준이다.

> **측정 한계:** 본 벤치마크는 `System.nanoTime()` 기반의 애플리케이션 레벨 측정으로 수행되었다. JIT 컴파일, Garbage Collection, CPU 스케줄링 등의 영향을 완전히 배제하지 못한다. 측정 결과는 절대적인 성능 수치라기보다 **각 동기화 전략 간의 상대적 경향성**을 확인하기 위한 참고 자료로 해석해야 한다.

### 7.2 전략별 처리량 비교 (`ConcurrencyBenchmarkTest`)

| 구현체 | elapsed (ms) | throughput (req/s) | 정확성 | 상대 속도 |
|--------|-------------|-------------------|--------|-----------|
| **NO_LOCK** | 6.20 | 16,131,894 | ❌ (경합 시 오차) | 1.0x (기준) |
| **SYNCHRONIZED** | 12.76 | 7,837,359 | ✅ | 2.1x 느림 |
| **REENTRANT_LOCK** | 19.98 | 5,005,180 | ✅ | 3.2x 느림 |

**분석:**

- 락 도입 시 처리 시간이 **약 2~3배** 증가
- NO_LOCK은 가장 빠르지만 동시 요청 시 데이터 정합성 보장 불가
- 본 실험 조건(단일 버킷, 100 스레드 경합)에서는 `synchronized`(12.76 ms)가 `ReentrantLock`(19.98 ms)보다 빠르게 측정되었으나, 이 결과를 **일반 환경에서의 우열로 일반화해서는 안 된다** (→ [7.6절](#76-synchronized-vs-reentrantlock-결과-해석의-주의점) 참고)

### 7.3 Hot User 병목 분석 (`HotUserBottleneckTest`)

동일 10만 건 요청을 **한 사용자(Hot User)** vs **100명 분산(Distributed)** 으로 비교.

#### SYNCHRONIZED

| 시나리오 | elapsed (ms) | throughput (req/s) |
|----------|-------------|-------------------|
| Hot User (버킷 1개 공유) | 12.20 | 8,198,939 |
| Distributed (버킷 100개) | 4.86 | 20,566,399 |
| **Hot User 느림 비율** | **2.5x** | |

#### REENTRANT_LOCK

| 시나리오 | elapsed (ms) | throughput (req/s) |
|----------|-------------|-------------------|
| Hot User | 8.66 | 11,547,611 |
| Distributed | 6.17 | 16,200,891 |
| **Hot User 느림 비율** | **1.4x** | |

**분석:**

- 사용자 수가 많아도 **특정 인기 사용자(Hot User)에게 요청이 집중**되면 해당 버킷의 락이 병목이 된다.
- 사용자별 버킷 분리 설계는 일반적인 상황에서는 효과적이나, Hot User 문제는 V2만으로 해결되지 않는다.
- **주목할 점:** 동일 Hot User 시나리오에서 `ReentrantLock`(8.66 ms)이 `synchronized`(12.20 ms)보다 **더 빠른 처리 시간**을 보였다. 이는 [7.2절](#72-전략별-처리량-비교-concurrencybenchmarktest)의 결과(synchronized 우세)와 상반된다. 비공정 락(`fair=false`)의 barging 특성, AQS 기반 큐잉 전략, JVM 스케줄링 차이 등이 복합적으로 영향을 주었을 가능성이 있으나, **본 실험만으로 특정 요인을 단정할 수는 없으며**, 추가적인 프로파일링이 필요하다.

### 7.4 ReentrantLock 공정성 비교 (`ReentrantLockFairnessBenchmarkTest`)

| 설정 | elapsed (ms) | throughput (req/s) | 상대 속도 |
|------|-------------|-------------------|-----------|
| `fair=false` (기본값) | 10.48 | 9,539,800 | 1.0x |
| `fair=true` | 456.30 | 219,156 | **43.5x 느림** |

**분석:**

- `fair=true`는 먼저 대기한 스레드부터 락을 부여하여 **공정성**을 보장하지만, 대기 큐 관리 비용으로 **극심한 성능 저하**가 발생한다.
- Rate Limiter처럼 처리량이 중요한 경우 `fair=false`가 적합하다.

### 7.5 종합 실험 매트릭스

| 구현체 (Strategy) | 정확성 보장 | 예상 처리 시간 (10만 건) | 실측 (10만 건) | 기술적 특징 |
| --- | --- | --- | --- | --- |
| **V1 (No Lock)** | ❌ | 가장 빠름 ($T$) | 6.20 ms | 동기화 없음, 데이터 정합성 파괴 |
| **V2-A (synchronized)** | ✅ | $4T \sim 5T$ | 12.76 ms (2.1x) | Monitor Lock, 스레드 Block 오버헤드 |
| **V2-B (ReentrantLock)** | ✅ | $3T \sim 4T$ | 19.98 ms (3.2x) | AQS 기반, 고급 동시성 API 제공 |

> 사전 예상치에서 ReentrantLock이 synchronized보다 빠를 수 있다고 했으나, **7.2절 단일 버킷 벤치마크**에서는 synchronized가 더 빠르게 측정되었다. 반면 **7.3절 Hot User 시나리오**에서는 ReentrantLock이 더 빠른 결과를 보였다. 시나리오에 따라 상대적 우열이 달라질 수 있음을 확인하였으며, 이에 대한 해석 주의점은 [7.6절](#76-synchronized-vs-reentrantlock-결과-해석의-주의점) 및 [8.4절](#84-synchronized-vs-reentrantlock-속도-차이)을 참고한다.

### 7.6 synchronized vs ReentrantLock 결과 해석의 주의점

본 실험의 수치(synchronized: 12.76 ms, ReentrantLock: 19.98 ms)를 근거로 **"synchronized가 ReentrantLock보다 항상 우수하다"** 고 일반화하는 것은 심각한 논리적 오류를 범할 수 있다.

#### 실험 조건의 제약성

본 벤치마크는 단일 JVM 내부에서 **극도로 짧은 임계 영역**(단순 연산 및 필드 차감)을 타겟으로 10만 건 요청을 수행한 결과이다. 이 환경에서는 경합 오버헤드보다 **자바 객체 및 AQS(AbstractQueuedSynchronizer) 인스턴스 관리 비용**이 상대적으로 크게 작용하는 특수한 조건이다.

#### 시나리오별 결과가 상이함

| 시나리오 | synchronized | ReentrantLock | 우세 (본 실험) |
|----------|-------------|---------------|---------------|
| 단일 버킷 10만 건 (7.2절) | 12.76 ms | 19.98 ms | synchronized |
| Hot User 10만 건 (7.3절) | 12.20 ms | 8.66 ms | ReentrantLock |

동일한 두 구현체임에도 **실험 시나리오에 따라 상대적 성능이 역전**된다. 따라서 벤치마크 결과는 특정 조건에서의 경향성으로만 해석해야 한다.

#### 기능적 트레이드오프

실무 환경에서는 단순 락 획득/해제 외에 다음과 같은 고급 동시성 제어 기능이 요구될 수 있다.

| API | 용도 |
|-----|------|
| `tryLock()` | 락을 획득하지 못했을 때 블로킹 없이 즉시 다른 로직 수행 (Non-blocking) |
| `Condition` | 스레드 간 정교한 Signal 제어 및 대기 큐 분리 |
| `lockInterruptibly()` | 락 획득 대기 중인 스레드의 인터럽트 처리 |

본 벤치마크는 이러한 기능을 사용하지 않는 **순수 `lock()`/`unlock()` 및 `synchronized` 블록**만을 비교 대상으로 한다. `ReentrantLock`의 가치는 처리량 수치만으로 평가하기 어렵다.

---

## 8. 락이 느려지는 이유

### 8.1 직렬화 (Serialization)

| V1 (NO_LOCK) | V2 (Lock) |
|--------------|-----------|
| 100 스레드 동시 처리 시도 | 1 스레드씩 순차 처리 |
| CPU 최대 활용 | 나머지 99 스레드 대기 |

### 8.2 락 경합 비용 (Contention)

1. 락이 이미 잡혀 있으면 스레드 **Blocked** 상태로 전환
2. OS **문맥 교환(Context Switch)** 발생
3. 락 해제 후 깨어나 락 획득 시도

경합이 심할수록(Hot User) 이 비용이 누적된다.

### 8.3 락 관리 자체 비용

실제 작업(`refill()` + `tokens -= 1`)은 나노초 단위로 가볍다.  
락 획득/해제, 메모리 배리어, 대기 큐 관리 비용이 **본연의 작업보다 클 수 있다.**

### 8.4 synchronized vs ReentrantLock 속도 차이

| 요인 | synchronized | ReentrantLock |
|------|--------------|---------------|
| 구현 | JVM Monitor (바이트코드 수준) | 별도 객체 + AQS |
| 최적화 | 경량 락(Lightweight Lock), Lock Escalation, Adaptive Spinning | AQS 큐잉, barging (`fair=false`) |
| 호출 | `monitorenter`/`monitorexit` | `lock()`/`unlock()` 명시 호출 |
| 추가 기능 | 없음 | Fairness, `tryLock()`, `Condition` |

#### JDK 17 환경에서의 synchronized 최적화

측정 결과는 다음 요인이 **복합적으로 작용**한 결과로 해석하는 것이 적절하다.

- **경량 락(Lightweight Lock):** 경합이 낮을 때 객체 헤더의 Mark Word를 활용한 빠른 락 획득
- **적응형 스피닝(Adaptive Spinning):** 락 해제를 기다리는 동안 CPU를 소모하며 대기하여, 짧은 임계 영역에서는 문맥 교환 비용을 회피
- **짧은 임계 영역 특성:** `refill()` + 필드 차감은 나노초 단위로, 락 보유 시간이 매우 짧아 스피닝 효과가 크게 작용

#### ReentrantLock이 특정 시나리오에서 유리했던 이유 (7.3절)

Hot User 시나리오에서 `ReentrantLock`(8.66 ms)이 `synchronized`(12.20 ms)보다 빠른 결과가 나온 것은, 비공정 락(`fair=false`)의 **barging** 특성(대기 큐를 건너뛰고 락을 선점), AQS 기반 큐잉 전략, JVM 스케줄링 차이 등이 복합적으로 영향을 주었을 가능성이 있다. 다만 본 실험만으로 특정 요인을 단정할 수는 없으며, **추가적인 프로파일링**(JFR, async-profiler 등)이 필요하다.

#### 일반화 금지

[7.6절](#76-synchronized-vs-reentrantlock-결과-해석의-주의점)에서 기술했듯이, 본 실험 조건의 수치만으로 두 방식의 절대적 우열을 판단해서는 안 된다.

---

## 9. V2 한계 및 잔존 문제

V2는 V1의 **Race Condition(데이터 정합성)** 을 해결했으나, 다음 문제는 남아 있다.

| 문제 | 설명 | V2에서의 상태 |
|------|------|---------------|
| **Hot User 병목** | 인기 사용자에게 요청 집중 시 해당 버킷 락이 병목 | ❌ 미해결 (SYNCHRONIZED 2.5x, REENTRANT_LOCK 1.4x) |
| **단일 JVM 한계** | 서버 재시작 시 상태 소실, 다중 인스턴스 간 공유 불가 | ❌ 미해결 (인메모리 유지, V4에서 분산 환경 개선 예정) |
| **블로킹 대기** | 락을 못 잡으면 스레드가 Blocked → 스레드 풀 고갈 위험 | ❌ 미해결 (`tryLock()` 미사용) |
| **락 범위** | `refill()` + 차감 전체가 잠김 → 락 보유 시간 증가 | ⚠️ 정확성을 위한 트레이드오프 |
| **메모리 증가** | 사용자 수만큼 버킷 생성 | ❌ V1과 동일 |
| **성능 저하** | 락 도입으로 2~3배 처리 시간 증가 | ⚠️ 정확성과 교환 |

---

## 10. 테스트 체계

### 10.1 테스트 파일 목록

| 테스트 클래스 | 태그 | 목적 |
|--------------|------|------|
| `RateLimiterServiceTest` | (기본) | V1 기능 시나리오 4종 |
| `ConcurrencyRaceConditionTest` | `v2-demo` / (기본) | Race Condition 재현 + 동기화 정확성 |
| `ConcurrencyBenchmarkTest` | `benchmark` | 전략별 10만 건 처리량 |
| `HotUserBottleneckTest` | `benchmark` | Hot User vs Distributed 병목 |
| `ReentrantLockFairnessBenchmarkTest` | `benchmark` | fair vs unfair 성능 |

### 10.2 실행 명령

```powershell
# 기본 테스트 (V1 시나리오 + V2 동기화 정확성)
.\gradlew.bat test

# V2-1 Race Condition 데모 (V1 버그 재현)
.\gradlew.bat testV2Demo

# 성능 벤치마크 전체 (처리량 + Hot User + Fairness)
.\gradlew.bat testBenchmark

# 한 번에 전부
.\gradlew.bat test testV2Demo testBenchmark
```

### 10.3 HTML 리포트

| 테스트 | 리포트 경로 |
|--------|-------------|
| 기본 | `build/reports/tests/test/index.html` |
| V2 데모 | `build/reports/tests/testV2Demo/index.html` |
| 벤치마크 | `build/reports/tests/testBenchmark/index.html` |

### 10.4 테스트와 설정의 관계

JUnit 테스트는 `application.properties`를 읽지 않는다.  
테스트 코드에서 `new TokenBucketFactory(RateLimiterStrategy.XXX)`로 전략을 직접 지정한다.

**설정 파일 변경이 필요한 경우:** `bootRun`으로 서버를 띄워 API를 직접 호출할 때만 해당.

---

## 11. V2 성공 기준 달성 여부

| 기준 | 달성 | 검증 방법 |
|------|------|-----------|
| V1 Race Condition 재현 | ✅ | `testV2Demo` — max allowed=2 |
| synchronized로 정확성 보장 | ✅ | 500 스레드, allowed=1 |
| ReentrantLock으로 정확성 보장 | ✅ | 500 스레드, allowed=1 |
| 전략 패턴으로 실험군 교체 | ✅ | `RateLimiterStrategy` + Factory |
| 10만 건 성능 비교 | ✅ | `testBenchmark` |
| Hot User 병목 분석 | ✅ | `HotUserBottleneckTest` |
| Fairness 성능 비교 | ✅ | `ReentrantLockFairnessBenchmarkTest` |
| V1 기능 회귀 없음 | ✅ | `RateLimiterServiceTest` 4종 통과 |

---

## 12. V3 개선 방향 (예고) — 단일 JVM 동시성 고도화

V3는 **단일 JVM 범위** 내에서 V2의 락 기반 동시성 제어를 한 단계 발전시키는 것을 목표로 한다.

| 항목 | V2 | V3 (예정) |
|------|-----|-----------|
| 동시성 제어 | 비관적 락 (`synchronized` / `ReentrantLock`) | CAS 기반 Lock-free |
| Non-blocking | 미지원 (`lock()` 블로킹) | `tryLock()` 즉시 거부 |
| Hot User | 버킷 단위 락 병목 | JVM 내 Sharding, 세분화된 락 |
| 저장소 | 인메모리 (단일 JVM) | 인메모리 유지 |
| 분산 환경 | 미지원 | 미지원 (→ V4) |

### V3 로드맵 (안)

```
[V3-1] ReentrantLock tryLock() 기반 Non-blocking Rate Limiter
       ↓
[V3-2] Atomic / CAS 기반 Lock-free TokenBucket
       ↓
[V3-3] Hot User JVM 내 Sharding 및 성능 비교
```

---

## 13. V4 개선 방향 (예고) — 분산 환경

V4는 V2·V3가 다루지 않은 **다중 인스턴스·분산 환경**에서의 Rate Limiting을 목표로 한다.  
V1~V3가 "단일 서버 내부의 동시성"을 해결했다면, V4는 "여러 서버 간의 상태 공유"를 해결한다.

| 항목 | V2 / V3 | V4 (예정) |
|------|---------|-----------|
| 저장소 | 인메모리 (`ConcurrentHashMap`) | Redis 등 외부 저장소 |
| 다중 인스턴스 | 인스턴스마다 독립 카운트 → Rate Limit 무력화 | 중앙 집중식 토큰 상태 공유 |
| 서버 재시작 | 토큰 상태 전부 소실 | 외부 저장소에 상태 영속 |
| 원자성 보장 | JVM 내부 락 / CAS | Redis Lua Script, Redisson 등 |
| Hot User (분산) | 단일 JVM 병목 | 분산 카운터, Sharding |
| 장애 대응 | 미지원 | Redis 장애 시 Fallback 정책 |

### V4가 필요한 이유

```
[Client] → [Load Balancer]
              ├── Instance A  (user-1: 10회 허용)
              └── Instance B  (user-1: 10회 허용)
              → 실질적으로 user-1에게 20회 허용 (Rate Limit 무력화)
```

인스턴스가 2대 이상이면, 각 서버가 **독립적인 인메모리 버킷**을 보유하므로 V2의 동기화가 아무리 정확해도 **전역 Rate Limit은 보장되지 않는다.**

### V4 로드맵 (안)

```
[V4-1] Redis 기반 Token Bucket 저장소 설계
       ↓
[V4-2] Lua Script 기반 원자적 토큰 충전/차감
       ↓
[V4-3] 다중 인스턴스 환경 정확성 검증 테스트
       ↓
[V4-4] Redis 장애 시 Fallback 정책 (Fail-open / Fail-close)
```

---

## 14. 결론

V2는 V1의 Token Bucket Rate Limiter에 **전략 패턴 기반 동시성 제어**를 도입한 버전이다.

**핵심 성과:**

1. **Race Condition 재현 및 해결** — V1에서 capacity=1임에도 2개 요청이 허용되는 버그를 `CountDownLatch` 기반 테스트로 재현하고, `synchronized`/`ReentrantLock`으로 정확히 1개만 허용되도록 수정했다.
2. **정량적 성능 비교** — 10만 건 벤치마크에서 NO_LOCK 대비 SYNCHRONIZED 2.1배, REENTRANT_LOCK 3.2배 느림을 측정하여 **정확성과 성능의 트레이드오프**를 수치로 확인했다. 
3. **운영 관점 분석** — Hot User 병목(SYNCHRONIZED 2.5x, REENTRANT_LOCK 1.4x), Fair Lock 성능 저하(43.5x) 등 실서비스에서 고려해야 할 요소를 실험했다.

**권장 운영 전략:** 단일 JVM 환경에서 동시성 정확성이 필요하면 `rate-limiter.strategy=SYNCHRONIZED` 또는 `REENTRANT_LOCK`을 사용한다. 단순 `lock()`/`unlock()` 기준의 처리량만으로 전략을 선택하기보다, **Non-blocking(`tryLock()`) 등 고급 동시성 제어가 필요한 경우** `ReentrantLock`이 더 적합하다(V3에서 활용 예정). **다중 인스턴스 환경**에서는 V4의 Redis 기반 분산 Rate Limiter가 필요하다.

---

## 부록: 프로젝트 구조 (V2)

```
src/main/java/com/api_rate_limiter/
├── ApiRateLimiterApplication.java
├── config/
│   └── RateLimiterStrategy.java
├── controller/
│   └── RequestController.java
├── domain/
│   ├── TokenBucket.java              (interface)
│   ├── AbstractTokenBucket.java
│   ├── V1TokenBucket.java
│   ├── SynchronizedTokenBucket.java
│   └── ReentrantLockTokenBucket.java
├── dto/
│   └── RateLimitResponse.java
├── factory/
│   └── TokenBucketFactory.java
├── manager/
│   └── TokenBucketManager.java
└── service/
    └── RateLimiterService.java

src/test/java/com/api_rate_limiter/
├── ApiRateLimiterApplicationTests.java
├── RateLimiterServiceTest.java
├── ConcurrencyRaceConditionTest.java
├── ConcurrencyBenchmarkTest.java
├── HotUserBottleneckTest.java
└── ReentrantLockFairnessBenchmarkTest.java
```
