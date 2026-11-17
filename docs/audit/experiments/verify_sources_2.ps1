# Second pass: re-checks the claims that verify_sources.ps1 could not confirm (wrong pattern, JS-only page, fetch error).
# Usage:  powershell -NoProfile -File verify_sources_2.ps1
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = 'Continue'
$ua = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AuditBot/1.0'
$tmp = Join-Path $env:TEMP 'audit_fetch2.tmp'

function Get-Raw([string]$url, [string]$mode) {
    if (Test-Path $tmp) { Remove-Item $tmp -Force }
    & curl.exe -sL --compressed --max-time 90 -A $ua $url -o $tmp
    if (-not (Test-Path $tmp) -or (Get-Item $tmp).Length -eq 0) { return $null }
    if ($mode -eq 'b64') {
        $b = (Get-Content $tmp -Raw) -replace '\s', ''
        try { return [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($b)) } catch { return $null }
    }
    $t = [IO.File]::ReadAllText($tmp, [Text.Encoding]::UTF8)
    if ($mode -eq 'html') {
        $t = [regex]::Replace($t, '(?is)<(script|style)\b.*?</\1>', ' ')
        $t = [regex]::Replace($t, '(?s)<[^>]+>', ' ')
        $t = $t -replace '&nbsp;', ' ' -replace '&amp;', '&' -replace '&lt;', '<' -replace '&gt;', '>' -replace '&quot;', '"' -replace '&#39;', "'"
        $t = [regex]::Replace($t, '\s+', ' ')
    }
    return $t
}

function Check([string]$id, [string]$claim, [string]$url, [string]$pattern, [int]$ctx = 200, [string]$mode = 'html', [int]$maxHits = 1) {
    Write-Output ("=== {0} | {1}" -f $id, $claim)
    Write-Output ("URL: {0}" -f $url)
    $text = Get-Raw $url $mode
    if ($null -eq $text) { Write-Output "RESULT: FETCH FAILED"; Write-Output ''; return }
    $ms = [regex]::Matches($text, $pattern, 'IgnoreCase')
    if ($ms.Count -gt 0) {
        Write-Output ("RESULT: FOUND ({0} chars fetched, {1} hit(s))" -f $text.Length, $ms.Count)
        $n = 0
        foreach ($m in $ms) {
            if ($n -ge $maxHits) { break }
            $s = [Math]::Max(0, $m.Index - $ctx)
            $e = [Math]::Min($text.Length, $m.Index + $m.Length + $ctx)
            Write-Output ("SNIPPET: ..." + ($text.Substring($s, $e - $s) -replace '\s+', ' ') + "...")
            $n++
        }
    } else {
        Write-Output ("RESULT: PATTERN NOT FOUND ({0} chars fetched)" -f $text.Length)
    }
    Write-Output ''
}

$ax = 'https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3'

Check 'PIM-04' 'TimePickerState validates initialHour range (source)' "$ax/TimePicker.kt?format=TEXT" 'initialHour' 250 'b64' 3
Check 'PIM-04' 'TimePickerState validates initialMinute range (source)' "$ax/TimePicker.kt?format=TEXT" '0\.\.59' 250 'b64' 2
Check 'PIM-03' 'DatePickerState.selectedDateMillis is UTC midnight (source KDoc)' "$ax/DatePicker.kt?format=TEXT" 'UTC' 250 'b64' 3

Check 'NAV-02/SET-04' 'Activity.setRecentsScreenshotEnabled (AOSP source)' `
  'https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/Activity.java?format=TEXT' `
  'public void setRecentsScreenshotEnabled' 400 'b64'

Check 'NAV-02/SET-04' 'FLAG_SECURE hides the app from Recents / screenshots' `
  'https://developer.android.com/reference/android/view/WindowManager.LayoutParams' 'FLAG_SECURE' 260

Check 'NAV-18' 'dataSync foreground service timeout (FGS types page)' `
  'https://developer.android.com/develop/background-work/services/fgs/service-types' '6 hours' 260

Check 'NAV-18' 'Foreground service timeouts (Android 15 behaviour changes)' `
  'https://developer.android.com/about/versions/15/behavior-changes-15' 'dataSync' 260

Check 'ONB-22' 'Compose: WindowInsets.ime / imePadding (insets page)' `
  'https://developer.android.com/develop/ui/compose/system/insets' 'ime' 260 'html' 2

Check 'NAV-19/SET-26' 'Handle configuration changes yourself: android:configChanges' `
  'https://developer.android.com/guide/topics/resources/runtime-changes' 'configChanges' 260

Check 'ONB-03' 'Coroutine cancellation is cooperative (kotlinx.coroutines docs source)' `
  'https://raw.githubusercontent.com/Kotlin/kotlinx.coroutines/master/docs/topics/cancellation-and-timeouts.md' 'cooperative' 220 'raw'

Check 'ONB-03' 'OkHttp Call.cancel() on a running call (source)' `
  'https://raw.githubusercontent.com/square/okhttp/master/okhttp/src/commonJvmAndroid/kotlin/okhttp3/Call.kt' 'fun cancel' 300 'raw'

Check 'MAIL-19/SET-12' 'Touch target size 48dp (Compose accessibility page)' `
  'https://developer.android.com/develop/ui/compose/accessibility/api-defaults' '48' 220

Check 'MAIL-19/SET-12' 'Touch target size 48dp (accessibility help)' `
  'https://support.google.com/accessibility/android/answer/7101858' '48' 220

Check 'CMP-05/NAV-14' 'Intent redirection / unsafe intent launch (Android security guidance)' `
  'https://developer.android.com/privacy-and-security/risks/unsafe-intent-launch' 'intent' 260

Check 'NAV-11/BLD-10' 'Content URI / file:// exposure (FileUriExposed)' `
  'https://developer.android.com/reference/android/os/FileUriExposedException' 'file' 220

Check 'ONB-05' 'ACTION_REQUEST_INSTALL_PACKAGES / canRequestPackageInstalls' `
  'https://developer.android.com/reference/android/content/pm/PackageManager' 'canRequestPackageInstalls' 260

Check 'NAV-25' 'Compose LaunchedEffect: effect cancelled when leaving composition' `
  'https://developer.android.com/develop/ui/compose/side-effects' 'LaunchedEffect' 220

Check 'MAIL-02' 'Compose: avoid heavy work in composition (performance best practices)' `
  'https://developer.android.com/develop/ui/compose/performance/bestpractices' 'expensive' 220

Check 'CMP-12' 'Android: onStop/onPause is the place to persist unsaved user data' `
  'https://developer.android.com/guide/components/activities/activity-lifecycle' 'onStop' 260

Check 'NAV-26' 'android:enableOnBackInvokedCallback manifest attribute (predictive back)' `
  'https://developer.android.com/guide/navigation/custom-back/predictive-back-gesture' 'enableOnBackInvokedCallback="true"' 220

Remove-Item $tmp -Force -ErrorAction SilentlyContinue
Write-Output 'DONE'
