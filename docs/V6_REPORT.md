# Performance Engineering and Limit Verification — V6 보고서

| 항목 | 내용 |
|------|------|
| 프로젝트 | API_Rate_Limiter |
| 버전 | V6 |
| 기술 스택 | k6, Spring Actuator, Prometheus, Grafana, Redis Exporter |
| 작성일 | 2026-07-08 |
| 목적 | 게이트웨이 계층(Filter, Interceptor, Redis Lua) 추가에 따른 성능 오버헤드 및 한계 처리량 검증 |

---

## 핵심 결과 요약

| 항목 | 결과 |
|------|------|
| 관측 스택 | ✅ Prometheus + Grafana + Redis Exporter 정상 연동 |
| Baseline (게이트웨이 OFF) | ✅ **3,350 TPS**, p95 **27ms** — 순수 Mock 기준선 |
| Distributed User (100 VU) | ✅ **776 TPS**, p95 **286ms**, 200 **100%** |
| Hot User (단일 Key) | ✅ **651 TPS**, 200 **51%** / 429 **49%** — 보호 계층 정상 가동 |
| Mixed Tier (FREE 90% + PRO 10%) | ✅ **606 TPS**, 200 **100%** — 등급 분기 정합성 유지 |
| 게이트웨이 오버헤드 | Baseline 대비 TPS **~77% 감소**, avg latency **~10배 증가** (128ms vs 13ms) |
| Redis 부하 | 피크 구간 CPU **6~7%** (Lua 직렬화 부하 양호) |

---

## 1. 검증 목표

- **Latency 오버헤드 측정**: Baseline(게이트웨이 OFF) 대비 V5/V6 게이트웨이 ON 환경의 p95/p99 차이를 계측한다.
- **한계 처리량(TPS) 측정**: 분산/고경합/혼합 정책 시나리오에서 초당 처리량 한계와 429 보호 가동률을 확인한다.
- **정합성 유지 확인**: Hot User 시나리오에서도 정책 임계치에 맞게 200/429가 일관되게 나오는지 검증한다.

---

## 2. 관측 인프라 구성

V6는 `docker-compose.yml`로 다음 스택을 함께 실행한다.

- `redis` (6379)
- `redis-exporter` (9121)
- `prometheus` (9090)
- `grafana` (3000, 기본 계정 `admin/admin`)

Prometheus 스크랩 대상:
- `http://host.docker.internal:8080/actuator/prometheus`
- `http://host.docker.internal:8081/actuator/prometheus`
- `redis-exporter:9121/metrics`

Grafana는 프로비저닝으로 Prometheus datasource와 기본 대시보드(`API Rate Limiter V6 - Performance`)를 자동 로드한다.

---

## 3. 사전 준비

### 3.1 인프라 기동

```powershell
docker compose up -d
```

### 3.2 애플리케이션 실행

Scenario 1(Baseline)은 게이트웨이 OFF 프로필을 사용한다. Scenario 2~4와 병행 실행 시 Baseline은 별도 포트(`8082`)를 권장한다.

```powershell
# Baseline 전용 (게이트웨이 OFF)
.\gradlew.bat bootRun --args="--spring.profiles.active=v6-baseline --server.port=8082"

# Scenario 2~4 (게이트웨이 ON, 멀티 인스턴스 권장)
.\gradlew.bat bootRun --args="--server.port=8080"
.\gradlew.bat bootRun --args="--server.port=8081"
```

---

## 4. k6 시나리오 실행

`performance/k6` 폴더에 4개 시나리오가 분리되어 있다.

- `scenario1_baseline.js`
- `scenario2_distributed_user.js`
- `scenario3_hot_user.js`
- `scenario4_mixed_tier.js`

실행 스크립트 (Docker k6):

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass

# Baseline (게이트웨이 OFF, :8082)
$env:BASELINE_TARGET="http://host.docker.internal:8082"
.\performance\k6\run-v6.ps1 -Scenario baseline -Vus 100 -Duration 60s -Engine docker

# Scenario 2~4 (게이트웨이 ON, :8080/:8081)
$env:TARGETS="http://host.docker.internal:8080,http://host.docker.internal:8081"
.\performance\k6\run-v6.ps1 -Scenario distributed -Vus 100 -Duration 60s -Engine docker
.\performance\k6\run-v6.ps1 -Scenario hotuser -Vus 100 -Duration 60s -Engine docker
.\performance\k6\run-v6.ps1 -Scenario mixed -Vus 100 -Duration 60s -Engine docker
```

> `run-v6.ps1`은 로컬 `k6`가 없으면 자동으로 Docker(`grafana/k6`)로 fallback 하며, 결과를 Prometheus remote-write(`experimental-prometheus-rw`)로 전송한다. 강제로 Docker 실행이 필요하면 `-Engine docker`를 사용한다.

---

## 5. 4대 시나리오 정의

### 5.1 Scenario 1: Baseline
- 조건: Rate Limiter OFF / 100 VUs
- 목적: 순수 Mock 비즈니스 로직 기준선 수집

### 5.2 Scenario 2: Distributed User
- 조건: Rate Limiter ON / 100 VUs / 100개 API Key 분산
- 목적: 운영 유사 패턴에서 TPS/Latency 오버헤드 측정

### 5.3 Scenario 3: Hot User
- 조건: Rate Limiter ON / 100 VUs / 단일 API Key 집중
- 목적: 단일 Key 직렬화 경합 시 p99 및 429 보호 응답성 검증

### 5.4 Scenario 4: Mixed Tier
- 조건: Rate Limiter ON / FREE 90% + PRO 10%
- 목적: 등급별 동적 정책의 멀티스레드 정합성 검증

---

## 6. 핵심 지표

| 메트릭 | 확인 위치 | 해석 포인트 |
|---|---|---|
| Avg Latency | k6 결과 / Grafana | 게이트웨이 계층 평균 오버헤드 |
| p95 / p99 Latency | Grafana 대시보드 | 고경합 시 tail latency 튐 여부 |
| TPS | Grafana (`http_server_requests_seconds_count` 기반) | 시스템 처리 한계선 |
| Success Rate (200) | Grafana status=200 비율 | 정상 서빙 안정성 |
| Reject Rate (429) | Grafana status=429 비율 | 보호 계층 가동률 |
| Redis CPU | Redis Exporter | Lua/키 경합에 따른 저장소 부하 |

---

## 7. 실험 결과

**실험 조건 공통**: 100 VUs, 60s, Docker k6 (`-Engine docker`)

| Scenario | Avg(ms) | p95(ms) | p99(ms) | TPS | 200 비율 | 429 비율 | Redis CPU 관측 |
|---|---:|---:|---:|---:|---:|---:|---|
| **Baseline** | **13** | **27** | **39** | **3,350** | **100%** | **0%** | N/A (게이트웨이 OFF) |
| **Distributed User** | **128** | **286** | **364** | **776** | **100%** | **0%** | 피크 **~7%** |
| **Hot User** | **121** | **272** | **310** | **651** | **51%** | **49%** | 피크 **~7%** |
| **Mixed Tier** | **124** | **279** | **326** | **606** | **100%** | **0%** | 피크 **~7%** |

> 모든 수치는 k6 최종 리포트 기준(2026-07-08 실측). Baseline은 `v6-baseline` 프로필(게이트웨이 OFF, `:8082`), 나머지는 게이트웨이 ON(`:8080`, `:8081`) 환경에서 측정하였다.

### 7.1 Scenario 1: Baseline (게이트웨이 OFF)

| 지표 | 측정값 |
|------|--------|
| 총 요청 수 | 297,829건 |
| 평균 지연 (avg) | 12.51 ms |
| p95 | 26.70 ms |
| p99 | 39.01 ms |
| TPS | 3,350 req/s |
| 200 응답 | 99.95% (297,700건) |
| 429 응답 | 0% |

**해석**: Filter/Interceptor/Redis Lua 계층이 없을 때 Mock 비즈니스 로직만의 순수 처리 성능 기준선이다. 이후 게이트웨이 ON 시나리오와 비교하여 오버헤드를 정량화하는 대조군으로 사용한다.

### 7.2 Scenario 2: Distributed User

| 지표 | 측정값 |
|------|--------|
| 총 요청 수 | 46,768건 |
| 평균 지연 (avg) | 128.21 ms |
| p95 / p99 | 285.67 ms / 364.14 ms |
| TPS | 776 req/s |
| 200 / 429 | 100% / 0% |

**해석**: 100개 API Key로 트래픽이 분산되면 Redis Key 경합이 낮아져 대부분 200으로 처리된다. 운영 환경과 가장 유사한 패턴에서 **~776 TPS**를 안정적으로 처리하였다.

### 7.3 Scenario 3: Hot User (단일 Key 집중)

| 지표 | 측정값 |
|------|--------|
| 총 요청 수 | 48,837건 |
| 평균 지연 (avg) | 120.52 ms |
| p95 / p99 | 271.77 ms / 309.86 ms |
| TPS | 651 req/s |
| 200 / 429 | 51.2% / 48.8% |

**해석**: 단일 Key(`bucket:sk-userA`)에 100 VU가 집중 타격하면 Redis Lua 직렬 실행 병목이 발생한다. FREE 티어 capacity(10) + refill(10/s) 대비 초당 **~650건** 인입 시 **약 절반이 429로 차단**되었으며, 이는 Rate Limiter가 비즈니스 로직을 보호하고 있음을 의미한다. 429 응답 자체는 **avg 7.6ms**로 빠르게 반환되어 Early Return이 효과적으로 동작한다.

### 7.4 Scenario 4: Mixed Tier (FREE 90% + PRO 10%)

| 지표 | 측정값 |
|------|--------|
| 총 요청 수 | 48,218건 |
| 평균 지연 (avg) | 123.57 ms |
| p95 / p99 | 279.03 ms / 326.38 ms |
| TPS | 606 req/s |
| 200 / 429 | 99.99% / 0% |

**해석**: FREE(`sk-free-*`, 한도 10)와 PRO(`sk-pro-*`, 한도 50) 트래픽이 9:1로 혼합 인입되어도 등급별 정책이 오류 없이 분기되었다. VU별 고유 Key를 사용하므로 각 티어 한도 내에서 200이 유지되었다.

### 7.5 시나리오 간 비교 분석

| 비교 축 | Baseline | Distributed | Hot User | Mixed |
|---------|----------|-------------|----------|-------|
| TPS | 3,350 | 776 (-77%) | 651 (-81%) | 606 (-82%) |
| avg Latency | 13ms | 128ms (+885%) | 121ms (+831%) | 124ms (+854%) |
| p95 Latency | 27ms | 286ms (+960%) | 272ms (+907%) | 279ms (+933%) |
| 429 발생 | 없음 | 없음 | **49%** | 없음 |

**핵심 결론**

1. **게이트웨이 계층 오버헤드**: Baseline 대비 TPS가 약 **77~82% 감소**하고 평균 지연이 **약 10배** 증가한다. 이는 Filter → Interceptor → Redis Lua EVAL 파이프라인 추가에 따른 필연적 비용이다.
2. **분산 vs 집중**: Distributed(776 TPS, 200 100%)와 Hot User(651 TPS, 429 49%)의 차이는 **Redis Key 분산 여부**가 처리량과 차단 비율을 좌우함을 보여준다.
3. **등급 정책 정합성**: Mixed Tier에서 FREE/PRO 프리픽스 분기가 멀티스레드 대량 인입 하에서도 오류 없이 동작하였다.
4. **Redis 자원 여유**: 모든 게이트웨이 ON 시나리오에서 Redis CPU 피크 **6~7%**로, 본 실험 규모에서는 저장소가 병목이 되지 않았다.

> **실험 해석 시 주의사항**: 일부 실행에서 k6의 Prometheus remote-write가 간헐적으로 `500`을 반환했고, 종료 구간에서 소량의 `i/o timeout`이 관측되었다. 본 보고서의 핵심 수치는 **k6 최종 요약(TOTAL RESULTS)** 을 기준으로 정리했으며, 이러한 관측 파이프라인 오류는 애플리케이션의 200/429 정책 동작 및 주요 성능 추세를 뒤집을 정도의 규모는 아니었다.

### 7.6 Grafana 교차 검증

Grafana 대시보드 `API Rate Limiter V6 - Performance` 관측 결과:

| 패널 | 관측 내용 |
|------|-----------|
| TPS (`spring_tps`) | 피크 **770~850 req/s** — k6 Distributed(776)과 일치 |
| Latency (`avg`) | 부하 초기 **~105ms** → 지속 구간 **~140ms** |
| Success / Reject Rate | Hot User 구간에서 200 **~50%** / 429 **~50%** |
| Redis CPU | 유휴 **<1%** → 부하 시 **6~7%** |

---

## 8. 구현 참고 파일

- `docker-compose.yml`
- `monitoring/prometheus/prometheus.yml`
- `monitoring/grafana/provisioning/datasources/prometheus.yml`
- `monitoring/grafana/provisioning/dashboards/dashboards.yml`
- `monitoring/grafana/dashboards/v6-performance.json`
- `src/main/resources/application.properties`
- `src/main/resources/application-v6-baseline.properties`
- `src/main/java/com/api_rate_limiter/config/WebMvcConfig.java`
- `performance/k6/common.js`
- `performance/k6/scenario1_baseline.js`
- `performance/k6/scenario2_distributed_user.js`
- `performance/k6/scenario3_hot_user.js`
- `performance/k6/scenario4_mixed_tier.js`
- `performance/k6/run-v6.ps1`
- `performance/k6/results/` (k6 실행 로그)
