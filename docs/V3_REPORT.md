# In-Memory Token Bucket Rate Limiter — V3 보고서

| 항목 | 내용 |
|------|------|
| 프로젝트 | API_Rate_Limiter |
| 버전 | V3 |
| 기술 스택 | Spring Boot 4.1.0, Java 17 |
| 작성일 | 2026-06-15 |
| 목적 | 락 기반 동기화(V2)에서 CAS 기반 Lock-Free 동기화로 전환하고, 원자적 변수와 원자적 로직의 차이를 실험으로 검증 |

---

## 1. 개요

V3는 V2의 **비관적 락(Blocking Lock)** 방식을 넘어, **CAS(Compare-And-Swap) 기반 Lock-Free** 동시성 제어를 도입한 버전이다.

단순히 `AtomicLong`을 사용하는 것만으로는 동시성 문제가 해결되지 않음을 **의도적으로 실패 증명(V3-1)** 하고, CAS 루프와 불변 상태 객체를 결합하여 **락 없이 정합성을 보장하는 구현(V3-2/V3-3)** 을 완성했다.

### V3 로드맵 및 달성 현황

```
[V3-1] Naive AtomicLong — Check-Then-Act 실패 증명          ✅
       ↓
[V3-2] CAS Consume — consume 로직 Lock-Free 구현             ✅
       ↓
[V3-3] Refill 해결 — AtomicReference + 불변 BucketState      ✅
       ↓
[V3-4] 정량적 성능·정확성 비교 및 보고서 작성                 ✅ (본 문서)
```

### V3 설계 원칙

- V1/V2의 기능(정책, API, 레이어 구조)은 유지
- `RateLimiterService` 코드는 수정하지 않고 **전략만 교체**
- V1(`NO_LOCK`), V2(`SYNCHRONIZED`, `REENTRANT_LOCK`) 구현체 보존
- 단일 JVM 인메모리 범위 유지 (분산 환경은 V4에서 다룸)

### V3 핵심 학습 키워드

| 영역 | 키워드 |
|------|--------|
| OS/하드웨어 | 원자적 연산, CAS, 하드웨어 버스 락, Lock-Free, ABA 문제 |
| JVM | `AtomicLong`, `AtomicReference`, 메모리 가시성, happens-before |
| 아키텍처 | Hot User 병목, 처리량, 스레드 블록 vs CPU 스핀 |

---

## 2. V2 대비 핵심 변경 사항

| 항목 | V2 | V3 |
|------|-----|-----|
| 동시성 제어 | `synchronized` / `ReentrantLock` (락) | CAS 루프 (Lock-Free) |
| 스레드 동작 | 락 미획득 시 **Blocked** (대기) | CAS 실패 시 **재시도** (스핀) |
| 상태 관리 | 가변 필드 (`tokens`, `lastRefillTime`) | 불변 `BucketState` + `AtomicReference` |
| 구현체 수 | 3개 (NO_LOCK, SYNCHRONIZED, REENTRANT_LOCK) | 5개 (+ NAIVE_ATOMIC, CAS) |
| 실패 증명 | V1 Race Condition 재현 | V3-1 Naive Atomic 실패 재현 |
| 분산 환경 | 미지원 | 미지원 (V4 예정) |

---

## 3. 아키텍처

### 3.1 클래스 다이어그램

```
                    <<interface>>
                     TokenBucket
                          │
    ┌─────────┬───────────┼───────────┬─────────────┐
    ▼         ▼           ▼           ▼             ▼
V1Token-  Synchronized- Reentrant-  NaiveAtomic-  CasToken-
 Bucket    TokenBucket  LockToken   TokenBucket    Bucket
            Bucket                    (V3-1)       (V3-2/3)
    │         │           │             │             │
    └─────────┴───────────┴─────────────┘             │
              AbstractTokenBucket                       │
              (V1/V2 공통 refill)              AtomicReference
                                              <BucketState>
                                              (불변 record)
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
  TokenBucketFactory.create()
        │
        ▼
  RateLimiterStrategy에 따른 구현체 선택
        │
        ▼
  TokenBucket.tryConsume()
```

### 3.3 신규 클래스

| 클래스 | 패키지 | 역할 |
|--------|--------|------|
| `NaiveAtomicTokenBucket` | domain | V3-1: `AtomicLong` + Check-Then-Act (실패 증명) |
| `CasTokenBucket` | domain | V3-2/3: CAS + 불변 `BucketState` (Lock-Free) |
| `RateLimiterStrategy` | config | `NAIVE_ATOMIC`, `CAS` enum 추가 |

### 3.4 전략 설정

`application.properties`:

```properties
# NO_LOCK | SYNCHRONIZED | REENTRANT_LOCK | NAIVE_ATOMIC | CAS
rate-limiter.strategy=CAS
```

---

## 4. 구현체 상세

### 4.1 NaiveAtomicTokenBucket (V3-1) — 실패 증명

`AtomicLong`으로 변수만 바꾸고, V1과 동일한 Check-Then-Act 로직을 유지한다.

```java
@Override
public boolean tryConsume() {
    refill();

    if (tokens.get() >= 1) {       // Check
        tokens.decrementAndGet();  // Act — 두 연산 사이 원자성 없음
        return true;
    }
    return false;
}
```

**핵심 명제:** 개별 연산(`get`, `decrementAndGet`)은 원자적이지만, **로직 전체는 원자적이지 않다.**

#### Race Condition 발생 원리

```
Thread A: get() → 1 이상 확인
Thread B: get() → 1 이상 확인   ← 둘 다 통과 판단
Thread A: decrementAndGet()
Thread B: decrementAndGet()   ← capacity=1인데 2개 허용
```

V1의 Check-Then-Act 문제가 `AtomicLong`을 써도 **그대로 재현**된다.

---

### 4.2 CasTokenBucket (V3-2/V3-3) — Lock-Free 최종 구현

#### V3-2: CAS Consume

```java
while (true) {
    BucketState current = state.get();
    BucketState refilled = refill(current);

    if (refilled.tokens() < 1.0) {
        if (!refilled.equals(current) && !state.compareAndSet(current, refilled)) {
            continue;
        }
        return false;
    }

    BucketState next = new BucketState(refilled.tokens() - 1.0, refilled.lastRefillTime());
    if (state.compareAndSet(current, next)) {
        return true;
    }
}
```

- CAS 실패 시 루프 재시도 → 스레드를 **블록하지 않음**
- 성공 시에만 상태 전환 → consume이 원자적으로 완료

#### V3-3: Refill 문제 해결

`tokens`와 `lastRefillTime` 두 필드를 단일 CAS로 동시 갱신하기 어렵다는 난제가 있었다.

| 선택지 | 방식 | 채택 |
|--------|------|------|
| 하이브리드 | consume은 CAS, refill만 `synchronized` | ❌ |
| 순수 Lock-Free | 불변 `BucketState` + `AtomicReference` | ✅ |

```java
private record BucketState(double tokens, long lastRefillTime) {}

private final AtomicReference<BucketState> state;
```

상태가 바뀔 때마다 **새 불변 객체**를 만들고, `compareAndSet`으로 참조 전체를 교체한다.  
`tokens`와 `lastRefillTime`이 항상 **한 쌍**으로 갱신된다.

#### ABA 문제와의 관계

`AtomicReference.compareAndSet()`은 **참조(==)** 를 비교한다. 매 변경마다 새 `BucketState` 객체를 생성하므로, 값이 같아도 참조가 다르면 CAS가 실패한다. 이 설계에서는 **클래식 ABA 위험이 낮다.**

---

## 5. V3-1 — Naive Atomic 실패 증명

### 5.1 실험 설계

| 항목 | 값 |
|------|-----|
| 전략 | `NAIVE_ATOMIC` |
| capacity | 1 |
| refillRate | 0.0 (충전 없음) |
| 스레드 수 | 500 |
| 반복 횟수 | 50회 (매 회 새 버킷) |
| 동기화 | `CountDownLatch`로 동시 시작 |
| 테스트 | `ConcurrencyRaceConditionTest.v3_naiveAtomic_capacityOne_exceedsAllowedLimit` |
| 태그 | `v3-demo` |

### 5.2 실행 결과

```
[V3-1 DEMO] NAIVE_ATOMIC - max allowed across 50 attempts: 3 (expected 1, check-then-act race reproduced)
PASSED
```

**해석:** capacity=1인데 **최대 3개 요청이 허용**됨. `AtomicLong` 사용만으로는 V1과 동일한 Race Condition이 발생한다.

### 5.3 V1 대비 비교

| 구현체 | max allowed (capacity=1) | 정확성 |
|--------|--------------------------|--------|
| NO_LOCK (V1) | 2 | ❌ |
| NAIVE_ATOMIC (V3-1) | 2~3 | ❌ |
| CAS (V3-2/3) | 1 | ✅ |

Naive Atomic은 **정합성이 보장되지 않는다.**

---

## 6. V3-2/V3-3 — CAS 정확성 검증

### 6.1 실험 설계

V3-1과 동일한 조건(capacity=1, 500 스레드)에서 `CAS` 전략을 검증한다.

### 6.2 실행 결과

```
[CAS] allowed=1   PASSED
```

**해석:** 500개 스레드가 동시에 요청해도 **정확히 1개만 허용**. Lock-Free CAS 구현이 정합성을 보장함을 확인.

### 6.3 정확성 종합 매트릭스

| 구현체 (Strategy) | 정확성 (capacity=1, 500 스레드) |
| --- | --- |
| NO_LOCK (V1) | ❌ (2~10개 허용) |
| NAIVE_ATOMIC (V3-1) | ❌ (2~3개 허용) |
| SYNCHRONIZED (V2) | ✅ (1개) |
| REENTRANT_LOCK (V2) | ✅ (1개) |
| **CAS (V3)** | ✅ (1개) |

---

## 7. V3-4 — 성능 및 정확성 비교

### 7.1 실험 환경

| 항목 | 값 |
|------|-----|
| JVM | OpenJDK 17.0.17 |
| OS | Windows 10 |
| 총 요청 수 | 100,000 |
| 스레드 수 | 100 |
| 스레드당 요청 | 1,000 |
| 측정 도구 | `System.nanoTime()` |

> 벤치마크 수치는 실행 환경(CPU, 부하)에 따라 달라질 수 있다. 본 보고서의 값은 2026-06-15 측정 기준이다.

> **측정 한계:** 본 벤치마크는 애플리케이션 레벨 측정으로, JIT, GC, CPU 스케줄링의 영향을 완전히 배제하지 못한다. **상대적 경향성** 확인용으로 해석한다.

### 7.2 전략별 처리량 비교 (`ConcurrencyBenchmarkTest`)

단일 버킷 1개에 10만 건 요청 (극심한 경합).

| 구현체 | elapsed (ms) | throughput (req/s) | 정확성 | V2 대비 |
|--------|-------------|-------------------|--------|---------|
| **NO_LOCK** | 6.20 | 16,116,555 | ❌ | — |
| **NAIVE_ATOMIC** | 10.12 | 9,879,275 | ❌ | — |
| **SYNCHRONIZED** | 13.73 | 7,285,762 | ✅ | 기준 |
| **REENTRANT_LOCK** | 17.06 | 5,860,359 | ✅ | 1.2x 느림 |
| **CAS** | 22.93 | 4,361,536 | ✅ | **1.7x 느림** |

**분석:**

- **Lock-Free ≠ 더 빠름:** 본 실험 조건(단일 버킷, 100 스레드 극심 경합)에서 CAS가 SYNCHRONIZED보다 **약 1.7배 느리게** 측정됨
- NAIVE_ATOMIC은 빠르지만 정합성이 깨짐 (V3-1 실패 증명과 일치)
- **본 실험 환경 및 설정된 워크로드 조건 하에서는** 정확성을 보장하는 구현 중 CAS 구현체의 처리 속도가 **가장 느리게** 측정되었다

> **해석 주의:** 위 결과는 단일 버킷에 10만 건이 집중되는 고경합 워크로드에서 도출된 수치이다. 경합이 낮거나 버킷이 분산된 환경에서는 상대적 우열이 달라질 수 있다.

### 7.3 Hot User 병목 분석 (`HotUserBottleneckTest`)

동일 10만 건을 **Hot User(버킷 1개)** vs **Distributed(버킷 100개)** 로 비교.

#### SYNCHRONIZED (V2)

| 시나리오 | elapsed (ms) | throughput (req/s) |
|----------|-------------|-------------------|
| Hot User | 13.07 | 7,651,109 |
| Distributed | 4.46 | 22,413,484 |
| **Hot User 느림 비율** | **2.9x** | |

#### REENTRANT_LOCK (V2)

| 시나리오 | elapsed (ms) | throughput (req/s) |
|----------|-------------|-------------------|
| Hot User | 8.62 | 11,602,543 |
| Distributed | 6.60 | 15,153,811 |
| **Hot User 느림 비율** | **1.3x** | |

#### CAS (V3)

| 시나리오 | elapsed (ms) | throughput (req/s) |
|----------|-------------|-------------------|
| Hot User | 15.44 | 6,476,432 |
| Distributed | 5.15 | 19,433,702 |
| **Hot User 느림 비율** | **3.0x** | |

**분석:**

- Hot User 시나리오에서 **CAS가 가장 큰 병목**(3.0x)을 보임
- V2 SYNCHRONIZED(2.9x)와 유사한 수준이나, 절대 처리량은 CAS가 더 낮음
- 버킷 1개에 몰리는 구조적 한계는 V3에서도 해결되지 않음

### 7.4 V2 vs V3 동시성 모델 비교

```
[V2 - 락 기반]
스레드 A: ████████ (작업)
스레드 B:         ░░░░░░ (Blocked) ████
스레드 C:                 ░░░░░░░░ (Blocked) ██
         → 나머지 스레드는 OS가 멈춤 (문맥 교환)

[V3 - CAS Lock-Free]
스레드 A: ████████████████████ (CAS 성공/실패 반복)
스레드 B: ████████████████████ (동시 스핀)
스레드 C: ████████████████████ (동시 스핀)
         → 블록 없음, CPU는 바쁘나 경합만 반복
```

| 항목 | V2 (락) | V3 (CAS) |
|------|---------|----------|
| 스레드 블록 | 있음 | 없음 |
| 경합 시 동작 | 대기 (Blocked) | 재시도 (Spin) |
| CPU 사용 (고경합) | 대기 중 낮음 | 높음 (스핀) |
| 객체 생성 | 가변 필드 재사용 | 매 변경마다 `new BucketState()` |
| 임계 영역 | 짧음 (refill + 차감) | refill + consume 통합 CAS 루프 |

### 7.5 종합 실험 매트릭스

| 구현체 (Strategy) | 정확성 | Throughput (10만 건) | Hot User 대응 | 기술적 특징 |
| --- | --- | --- | --- | --- |
| **NO_LOCK (V1)** | ❌ | 최고 (~16.1M) | 최고 (정합성 파괴) | 동기화 없음 |
| **NAIVE_ATOMIC (V3-1)** | ❌ | 높음 (~9.9M) | 높음 (정합성 파괴) | Atomic 변수, 로직 비원자적 |
| **SYNCHRONIZED (V2)** | ✅ | 중간 (~7.3M) | 느림 (2.9x) | Monitor Lock, Blocked |
| **REENTRANT_LOCK (V2)** | ✅ | 중간 (~5.9M) | 중간 (1.3x) | AQS, barging |
| **CAS (V3)** | ✅ | 낮음 (~4.4M) | 느림 (3.0x, CPU 스핀) | Lock-Free, 불변 상태 |

---

## 8. 왜 Lock-Free가 더 느린가

### 8.1 CAS 재시도 (Spin) 비용

경합이 심할 때 여러 스레드가 동시에 CAS를 시도하고 실패한다.  
실패할 때마다 처음부터 다시 읽고 계산하므로, **CPU는 바쁘지만 처리량은 오르지 않는다.**

### 8.2 불변 객체 생성 비용

매 성공적인 상태 전환마다 `new BucketState(...)`가 발생한다.

- GC 부담 증가
- V2의 가변 필드 수정보다 메모리 할당 비용이 큼

### 8.3 짧은 임계 영역과 본 실험 조건에서의 측정 결과

Rate Limiter의 임계 영역은 `refill()` + 토큰 1 차감으로 **매우 짧다.**  
[7.2절](#72-전략별-처리량-비교-concurrencybenchmarktest) 벤치마크는 이 짧은 임계 영역에 **단일 버킷·10만 건·100 스레드**가 집중되는 고경합 조건으로 수행되었다.

- JDK 17 `synchronized`는 경량 락, 적응형 스피닝 등으로 짧은 임계 영역에 최적화됨
- Lock-Free의 이점(블록 회피, 긴 임계 영역에서의 대기 비용 절감)이 **본 워크로드에서는** 잘 발휘되지 않은 것으로 측정됨

**본 실험에서 CAS가 가장 느리게 측정된 이유 (면접용 방어 논리):**

임계 영역이 매우 짧고 단일 버킷에 10만 건이 집중되는 고경합 워크로드 특성상, CAS 실패로 인한 **스핀 루프 비용**과 불변 객체(`BucketState`) **생성·할당 오버헤드**가 JDK 17 `synchronized`의 내부 최적화 비용을 상회했기 때문으로 해석할 수 있다. 경합이 낮거나 버킷이 사용자별로 분산된 환경에서는 이 결과가 **역전될 수 있으며**, 본 수치를 일반 환경의 절대 성능으로 일반화해서는 안 된다.

### 8.4 Lock-Free가 유리할 수 있는 경우

동시성 제어 전략의 실제 성능은 **워크로드의 특성, 경합의 밀도, 객체 생성 오버헤드, JVM 버전 및 내부 구현 최적화**에 따라 완전히 달라지므로, 특정 알고리즘이 항상 우위라고 **단정할 수 없다.** 다만 일반적인 경향성은 다음과 같이 분류할 수 있다.

| Lock-Free가 유리할 수 있는 경우 | 락(Lock)이 유리할 수 있는 경우 |
|------------------------------|------------------------------|
| 경합이 비교적 낮거나 분산되어 있을 때 | 특정 자원에 경합이 극심하게 몰릴 때 (Hot User) |
| 스레드가 차단(Blocked)되는 비용이 매우 클 때 | 임계 영역(Critical Section)이 극도로 짧을 때 |
| 우선순위 역전이나 응답 지연(Latency) 관리가 핵심일 때 | 메모리 할당 및 단순함이 더 중요할 때 |

> **고찰:** "실제 동시성 제어의 효율성은 단순히 알고리즘의 종류뿐만 아니라, 경합 수준, 객체 생성 비용, 임계 영역의 길이, 그리고 실행 시점의 JVM 최적화 상태에 따라 동적으로 결정된다."

**V3의 가치는 "최고 처리량"이 아니라, 단일 JVM에서 락 없이 정합성을 맞추는 방법을 완성한 것이다.**

---

## 9. 핵심 개념 정리

### 9.1 원자적 변수 vs 원자적 로직

| | 원자적 변수 | 원자적 로직 |
|--|-----------|-----------|
| 의미 | 개별 연산이 원자적 | 전체 흐름이 하나의 단위로 원자적 |
| V3 예 | `tokens.get()`, `decrementAndGet()` 각각은 원자적 | Check-Then-Act 전체는 비원자적 |
| 결과 | NaiveAtomic은 여전히 깨짐 | CAS 루프로 해결 |

### 9.2 불변 객체를 쓰는 이유

가변 객체의 필드를 `AtomicReference`에 넣고 setter로 수정하면, 참조 교체 없이 내부만 바뀌어 Race Condition이 발생한다.

불변 `BucketState`를 쓰면 **반드시 새 객체 + CAS**로만 상태를 바꿀 수 있어, 복합 필드를 한 번에 안전하게 갱신한다.

### 9.3 ABA 문제 (ABA Problem) 고찰

CAS 연산은 메모리 주소가 가리키는 **참조(Reference) 자체**가 기대값과 동일한지(Identity Comparison, `==`) 확인한다. 따라서 중간에 **A → B → A**로 상태가 변조되어도, 최종적으로 **동일한 참조 주소**가 재등장하면 이를 감지하지 못하는 한계가 있다. 이것이 클래식 ABA 문제이다.

#### 본 프로젝트의 안정성 근거

`CasTokenBucket`은 상태가 변경될 때마다 가변 필드를 제자리에서 수정하는 것이 아니라, 매번 `new BucketState(...)`를 통해 **힙(Heap) 영역에 새로운 불변 객체**를 할당하고, 그 **참조 주소**를 `AtomicReference.compareAndSet(expected, update)`로 검증한다.

```java
private record BucketState(double tokens, long lastRefillTime) {}

// 상태 전환 시마다 새 record 인스턴스 할당 → 새 참조 주소
BucketState next = new BucketState(refilled.tokens() - 1.0, refilled.lastRefillTime());
state.compareAndSet(current, next);  // current와 next는 항상 서로 다른 참조(==)
```

Java `record`는 호출마다 별도의 객체 인스턴스가 생성되며, `compareAndSet`의 `expected` 인자로 전달된 `current` 참조는 이후 다른 스레드의 성공적인 CAS에 의해 `state`가 가리키는 대상에서 **이탈**한다. 설령 두 `BucketState`의 `tokens`와 `lastRefillTime` 필드 값이 우연히 완벽히 일치하더라도, **매번 새로운 객체 참조**가 생성·교체되므로 동일한 주소(참조)가 재등장하여 원자성을 깨뜨리는 **클래식 ABA 위험은 극히 낮다.**

> **한계 인식:** 객체 풀링(Object Pooling) 등으로 동일 인스턴스를 재사용하는 설계로 변경하면 ABA 위험이 다시 높아질 수 있다. 그 경우 `AtomicStampedReference`로 버전(Stamp)을 함께 관리하는 방안을 고려해야 한다.

---

## 10. V3 한계 및 잔존 문제

| 문제 | 설명 | V3에서의 상태 |
|------|------|---------------|
| **Hot User 병목** | 한 버킷에 요청 집중 시 직렬화 | ❌ 미해결 (CAS 3.0x) |
| **CAS 스핀 부하** | 고경합 시 CPU 낭비 | ⚠️ Lock-Free 트레이드오프 |
| **처리량** | 본 고경합 벤치마크에서 가장 느리게 측정 | ⚠️ 워크로드·경합 조건에 따라 달라짐 |
| **단일 JVM 한계** | 다중 인스턴스 간 공유 불가 | ❌ V4에서 Redis 분산 예정 |
| **메모리** | 불변 객체 생성 + 사용자별 버킷 | ⚠️ V2 대비 GC 부담 증가 |

---

## 11. 테스트 체계

### 11.1 테스트 파일 목록

| 테스트 클래스 | 태그 | 목적 |
|--------------|------|------|
| `RateLimiterServiceTest` | (기본) | V1 기능 시나리오 4종 |
| `ConcurrencyRaceConditionTest` | `v2-demo` / `v3-demo` / (기본) | V1/V3-1 실패 재현 + V2/V3 정확성 |
| `ConcurrencyBenchmarkTest` | `benchmark` | 전략별 10만 건 처리량 |
| `HotUserBottleneckTest` | `benchmark` | Hot User vs Distributed (CAS 포함) |
| `ReentrantLockFairnessBenchmarkTest` | `benchmark` | fair vs unfair 성능 |

### 11.2 실행 명령

```powershell
# 기본 테스트 (V1 시나리오 + V2/V3 동기화 정확성)
.\gradlew.bat test

# V2-1 Race Condition 데모 (V1 버그 재현)
.\gradlew.bat testV2Demo

# V3-1 Naive Atomic 실패 데모
.\gradlew.bat testV3Demo

# 성능 벤치마크 전체
.\gradlew.bat testBenchmark

# 한 번에 전부
.\gradlew.bat test testV2Demo testV3Demo testBenchmark
```

### 11.3 HTML 리포트

| 테스트 | 리포트 경로 |
|--------|-------------|
| 기본 | `build/reports/tests/test/index.html` |
| V2 데모 | `build/reports/tests/testV2Demo/index.html` |
| V3 데모 | `build/reports/tests/testV3Demo/index.html` |
| 벤치마크 | `build/reports/tests/testBenchmark/index.html` |

> `build/test-results/` 폴더의 XML은 CI용 JUnit 결과이다. 브라우저에서 볼 HTML 리포트는 `build/reports/tests/` 아래에 생성된다.

### 11.4 테스트와 설정의 관계

JUnit 테스트는 `application.properties`를 읽지 않는다.  
테스트 코드에서 `new TokenBucketFactory(RateLimiterStrategy.XXX)`로 전략을 직접 지정한다.

---

## 12. V3 성공 기준 달성 여부

| 기준 | 달성 | 검증 방법 |
|------|------|-----------|
| Naive Atomic 실패 증명 | ✅ | `testV3Demo` — max allowed=3 |
| CAS로 정확성 보장 | ✅ | 500 스레드, allowed=1 |
| Lock-Free consume 구현 | ✅ | `CasTokenBucket` CAS 루프 |
| Refill + consume 통합 CAS | ✅ | `AtomicReference<BucketState>` |
| V2 대비 성능 비교 | ✅ | `testBenchmark` |
| Hot User 병목 분석 (CAS) | ✅ | `HotUserBottleneckTest` |
| V1/V2 기능 회귀 없음 | ✅ | `RateLimiterServiceTest` 4종 통과 |

---

## 13. V4 개선 방향 (예고) — 분산 Rate Limiter

V3까지 **단일 JVM 내 Lock-Free** 동시성 제어에 도달했다.  
V4는 **다중 인스턴스 환경**으로 확장한다.

| 항목 | V3 | V4 (예정) |
|------|-----|-----------|
| 저장소 | 인메모리 (단일 JVM) | Redis |
| 동시성 | JVM 내 CAS | Lua 스크립트 |
| Hot User | JVM 내 버킷 병목 | Redis Sharding |
| 서버 재시작 | 상태 소실 | Redis 영속 |

### V4 로드맵 (안)

```
[V4-1] Redis 연동 및 Token Bucket 상태 외부화
       ↓
[V4-2] Lua 스크립트 기반 원자적 consume
       ↓
[V4-3] 다중 인스턴스 정합성 및 성능 비교
```

---

## 14. 결론

V3는 V2의 락 기반 Rate Limiter에 **CAS 기반 Lock-Free 동시성 제어**를 도입한 버전이다.

**핵심 성과:**

1. **원자적 변수 ≠ 원자적 로직** — `NaiveAtomicTokenBucket`으로 `AtomicLong`만으로는 Check-Then-Act Race Condition이 해결되지 않음을 증명했다 (max allowed=3).
2. **Lock-Free 정합성 달성** — 불변 `BucketState` + `AtomicReference` CAS로 refill과 consume을 락 없이 안전하게 처리했다 (500 스레드, allowed=1).
3. **V2 대비 성능 특성 확인** — Lock-Free가 항상 빠른 것은 아님을 실측했다. CAS(~4.4M req/s)는 SYNCHRONIZED(~7.3M req/s)보다 느렸으며, Hot User에서 CPU 스핀 병목(3.0x)이 관측되었다.

**권장 운영 전략:**

- 단일 JVM에서 **처리량 우선**이면 `SYNCHRONIZED` 또는 `REENTRANT_LOCK`
- **락 없는 정합성** 학습·실험 목적이면 `CAS`
- **다중 인스턴스** 환경에서는 V4 Redis 분산 Rate Limiter 필요

V3 완료로 단일 JVM에서 구현 가능한 동시성 제어(락 → Lock-Free) 학습 사이클이 마무리되었으며, V4 분산 환경으로 넘어갈 준비가 되었다.

---

## 부록: 프로젝트 구조 (V3)

```
src/main/java/com/api_rate_limiter/
├── ApiRateLimiterApplication.java
├── config/
│   └── RateLimiterStrategy.java          (+ NAIVE_ATOMIC, CAS)
├── controller/
│   └── RequestController.java
├── domain/
│   ├── TokenBucket.java                  (interface)
│   ├── AbstractTokenBucket.java
│   ├── V1TokenBucket.java
│   ├── SynchronizedTokenBucket.java
│   ├── ReentrantLockTokenBucket.java
│   ├── NaiveAtomicTokenBucket.java       ← V3-1 신규
│   └── CasTokenBucket.java               ← V3-2/3 신규
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
├── ConcurrencyRaceConditionTest.java     (+ v3-demo)
├── ConcurrencyBenchmarkTest.java         (+ NAIVE_ATOMIC, CAS)
├── HotUserBottleneckTest.java            (+ CAS)
└── ReentrantLockFairnessBenchmarkTest.java

docs/
├── V1_REPORT.md
├── V2_REPORT.md
└── V3_REPORT.md                          ← 본 문서
```
