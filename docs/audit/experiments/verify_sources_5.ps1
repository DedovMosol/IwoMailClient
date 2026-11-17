# Fifth pass: testing best-practice sources used in the test plan (HTTP status + keyword).
# Usage:  powershell -NoProfile -File verify_sources_5.ps1
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = 'Continue'
$ua = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AuditBot/1.0'
$tmp = Join-Path $env:TEMP 'audit_fetch5.tmp'

function Probe([string]$id, [string]$claim, [string]$url, [string]$keyword) {
    if (Test-Path $tmp) { Remove-Item $tmp -Force }
    $code = & curl.exe -sL --compressed --max-time 90 -A $ua -w '%{http_code}' $url -o $tmp
    $hit = 'n/a'
    if ((Test-Path $tmp) -and (Get-Item $tmp).Length -gt 0) {
        $t = [IO.File]::ReadAllText($tmp, [Text.Encoding]::UTF8)
        $hit = if ($t -match [regex]::Escape($keyword)) { 'keyword found' } else { 'keyword NOT found' }
    }
    Write-Output ("{0} | HTTP {1} | {2} | {3} | {4}" -f $id, $code, $hit, $claim, $url)
}

Probe 'TEST-01' 'Compose UI testing (semantics, createComposeRule)' 'https://developer.android.com/develop/ui/compose/testing' 'createComposeRule'
Probe 'TEST-02' 'Compose accessibility testing' 'https://developer.android.com/develop/ui/compose/accessibility/testing' 'accessib'
Probe 'TEST-03' 'Macrobenchmark overview' 'https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview' 'Macrobenchmark'
Probe 'TEST-04' 'Local (JVM) tests' 'https://developer.android.com/training/testing/local-tests' 'local'
Probe 'TEST-05' 'Instrumented tests' 'https://developer.android.com/training/testing/instrumented-tests' 'instrumented'
Probe 'TEST-06' 'Room migration testing' 'https://developer.android.com/training/data-storage/room/migrating-db-versions' 'MigrationTestHelper'
Probe 'TEST-07' 'Testing coroutines on Android' 'https://developer.android.com/kotlin/coroutines/test' 'runTest'
Probe 'TEST-08' 'Baseline Profiles overview' 'https://developer.android.com/topic/performance/baselineprofiles/overview' 'Baseline'
Probe 'TEST-09' 'Android Lint: baseline and severity' 'https://developer.android.com/studio/write/lint' 'baseline'
Probe 'TEST-10' 'Fundamentals of testing Android apps' 'https://developer.android.com/training/testing/fundamentals' 'test'
Probe 'TEST-11' 'OWASP MASVS (storage / platform / resilience)' 'https://mas.owasp.org/MASVS/' 'MASVS'
Probe 'TEST-12' 'Paparazzi screenshot tests (project page)' 'https://github.com/cashapp/paparazzi' 'Paparazzi'
Probe 'TEST-13' 'Roborazzi screenshot tests (project page)' 'https://github.com/takahirom/roborazzi' 'Roborazzi'
Probe 'TEST-14' 'Kover coverage verification rules' 'https://kotlin.github.io/kotlinx-kover/gradle-plugin/' 'verif'
Probe 'TEST-15' 'detekt static analysis' 'https://detekt.dev/docs/intro' 'detekt'
Probe 'TEST-16' 'Gradle dependency verification' 'https://docs.gradle.org/current/userguide/dependency_verification.html' 'verification'
Probe 'TEST-17' 'Android app signing in CI (apksigner / signingConfigs)' 'https://developer.android.com/build/building-cmdline#sign_cmdline' 'sign'
Probe 'TEST-18' 'GitHub Actions: least-privilege GITHUB_TOKEN permissions' 'https://docs.github.com/en/actions/security-for-github-actions/security-guides/automatic-token-authentication' 'permissions'

Remove-Item $tmp -Force -ErrorAction SilentlyContinue
Write-Output 'DONE'
