param(
    [ValidateSet("baseline", "distributed", "hotuser", "mixed")]
    [string]$Scenario = "distributed",
    [int]$Vus = 100,
    [string]$Duration = "60s",
    [ValidateSet("auto", "local", "docker")]
    [string]$Engine = "auto"
)

$scriptPath = switch ($Scenario) {
    "baseline" { "scenario1_baseline.js" }
    "distributed" { "scenario2_distributed_user.js" }
    "hotuser" { "scenario3_hot_user.js" }
    "mixed" { "scenario4_mixed_tier.js" }
}

$targetFile = ".\performance\k6\$scriptPath"
$dockerTargetFile = "./performance/k6/$scriptPath"
$promRwUrl = "http://localhost:9090/api/v1/write"
$targets = if ($env:TARGETS) { $env:TARGETS } else { "http://host.docker.internal:8080,http://host.docker.internal:8081" }
$baselineTarget = if ($env:BASELINE_TARGET) { $env:BASELINE_TARGET } else { "http://host.docker.internal:8080" }

Write-Host "Running V6 scenario=$Scenario vus=$Vus duration=$Duration engine=$Engine"

$hasLocalK6 = $null -ne (Get-Command k6 -ErrorAction SilentlyContinue)
$useDocker = $false

if ($Engine -eq "docker") {
    $useDocker = $true
} elseif ($Engine -eq "local") {
    if (-not $hasLocalK6) {
        throw "k6 로컬 실행 파일을 찾을 수 없습니다. -Engine docker 또는 -Engine auto를 사용하세요."
    }
} elseif (-not $hasLocalK6) {
    $useDocker = $true
}

if (-not $useDocker) {
    $env:VUS = "$Vus"
    $env:DURATION = $Duration
    $env:TARGETS = $targets
    $env:K6_PROMETHEUS_RW_SERVER_URL = $promRwUrl
    $env:K6_PROMETHEUS_RW_TREND_AS_NATIVE_HISTOGRAM = "true"
    k6 run -o experimental-prometheus-rw $targetFile
    exit $LASTEXITCODE
}

$workspace = (Resolve-Path ".").Path
docker run --rm -i `
  --add-host host.docker.internal:host-gateway `
  -e VUS="$Vus" `
  -e DURATION="$Duration" `
  -e TARGETS="$targets" `
  -e BASELINE_TARGET="$baselineTarget" `
  -e K6_PROMETHEUS_RW_SERVER_URL="http://host.docker.internal:9090/api/v1/write" `
  -e K6_PROMETHEUS_RW_TREND_AS_NATIVE_HISTOGRAM="true" `
  -v "${workspace}:/work" -w /work `
  grafana/k6 run -o experimental-prometheus-rw $dockerTargetFile
exit $LASTEXITCODE
