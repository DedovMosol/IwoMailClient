# Verifies audit claims against primary documentation using curl.exe.
# Usage (Windows PowerShell 5.1):  powershell -NoProfile -File verify_sources.ps1
# The script is ASCII-only on purpose (PowerShell 5.1 reads BOM-less files as ANSI).
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = 'Continue'
$ua = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AuditBot/1.0'
$tmp = Join-Path $env:TEMP 'audit_fetch.tmp'

function Get-Text([string]$url, [string]$mode) {
    if (Test-Path $tmp) { Remove-Item $tmp -Force }
    & curl.exe -sL --compressed --max-time 90 -A $ua $url -o $tmp
    if (-not (Test-Path $tmp) -or (Get-Item $tmp).Length -eq 0) { return $null }
    if ($mode -eq 'b64') {
        $b = (Get-Content $tmp -Raw) -replace '\s', ''
        try { return [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($b)) } catch { return $null }
    }
    $html = [IO.File]::ReadAllText($tmp, [Text.Encoding]::UTF8)
    $html = [regex]::Replace($html, '(?is)<(script|style)\b.*?</\1>', ' ')
    $html = [regex]::Replace($html, '(?s)<[^>]+>', ' ')
    $html = $html -replace '&nbsp;', ' ' -replace '&amp;', '&' -replace '&lt;', '<' -replace '&gt;', '>' -replace '&quot;', '"' -replace '&#39;', "'"
    return [regex]::Replace($html, '\s+', ' ')
}

function Check([string]$id, [string]$claim, [string]$url, [string]$pattern, [int]$ctx = 160, [string]$mode = 'html') {
    Write-Output ("=== {0} | {1}" -f $id, $claim)
    Write-Output ("URL: {0}" -f $url)
    $text = Get-Text $url $mode
    if ($null -eq $text) { Write-Output "RESULT: FETCH FAILED"; Write-Output ''; return }
    $m = [regex]::Match($text, $pattern, 'IgnoreCase')
    if ($m.Success) {
        $s = [Math]::Max(0, $m.Index - $ctx)
        $e = [Math]::Min($text.Length, $m.Index + $m.Length + $ctx)
        Write-Output "RESULT: FOUND"
        Write-Output ("SNIPPET: ..." + $text.Substring($s, $e - $s) + "...")
    } else {
        Write-Output ("RESULT: PATTERN NOT FOUND ({0} chars fetched)" -f $text.Length)
    }
    Write-Output ''
}

Write-Output ("Run at: " + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'))
Write-Output ''

Check 'MAIL-01' 'MediaStore.Downloads requires API 29 (minSdk is 26)' `
  'https://developer.android.com/reference/android/provider/MediaStore.Downloads' 'Added in API level 29'

Check 'NAV-02/SET-04' 'Activity.setRecentsScreenshotEnabled exists (API 33)' `
  'https://developer.android.com/reference/android/app/Activity' 'setRecentsScreenshotEnabled\(boolean\)'

Check 'NAV-13' 'Uri.getQueryParameter throws on non-hierarchical (opaque) URI such as mailto:' `
  'https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/net/Uri.java?format=TEXT' `
  'getQueryParameter\(String key\)\s*\{\s*if \(isOpaque\(\)\)\s*\{\s*throw new UnsupportedOperationException' 300 'b64'

Check 'MAIL-09' 'WebViewClient.shouldOverrideUrlLoading is not called for POST requests' `
  'https://developer.android.com/reference/android/webkit/WebViewClient' 'POST request' 200

Check 'NAV-01/SET-02' 'PBKDF2-HMAC-SHA256 needs 600,000 iterations (OWASP)' `
  'https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html' '600,000 iterations'

Check 'NAV-01/SET-02' 'Authentication needs account lockout or throttling (OWASP)' `
  'https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html' 'lockout'

Check 'PIM-03' 'M3 DatePicker selectedDateMillis is UTC midnight' `
  'https://developer.android.com/reference/kotlin/androidx/compose/material3/DatePickerState' 'UTC'

Check 'PIM-04' 'M3 TimePickerState rejects hour outside 0..23 (source)' `
  'https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/TimePicker.kt?format=TEXT' `
  'require\(initialHour in 0\.\.23\)' 200 'b64'

Check 'PIM-01' 'java.util.Calendar is lenient by default (31 Feb rolls to 3 Mar)' `
  'https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Calendar.html' 'lenient' 200

Check 'ONB-19' 'Compose: defer state reads / use lambda modifiers for frequently changing state' `
  'https://developer.android.com/develop/ui/compose/performance/bestpractices' 'Defer reads' 260

Check 'MAIL-06' 'Compose: use keys in lazy lists' `
  'https://developer.android.com/develop/ui/compose/performance/bestpractices' 'keys' 200

Check 'NAV-18' 'Foreground service type dataSync has a time limit (Android 15)' `
  'https://developer.android.com/develop/background-work/services/fgs/timeout' '6 hours' 220

Check 'NAV-05/SET-03' 'BIOMETRIC_STRONG and CryptoObject for sensitive actions' `
  'https://developer.android.com/identity/sign-in/biometric-auth' 'BIOMETRIC_STRONG' 220

Check 'PIM-14' 'CSV injection: cells starting with = + - @ (OWASP)' `
  'https://owasp.org/www-community/attacks/CSV_Injection' 'formula' 200

Check 'NAV-10/CMP-13' 'Saved instance state size limit and TransactionTooLargeException' `
  'https://developer.android.com/guide/components/activities/parcelables-and-bundles' 'TransactionTooLargeException' 220

Check 'BLD-04' 'Room: migrations are tested with MigrationTestHelper' `
  'https://developer.android.com/training/data-storage/room/migrating-db-versions' 'MigrationTestHelper' 200

Check 'ONB-22' 'Compose insets: imePadding for the on-screen keyboard' `
  'https://developer.android.com/develop/ui/compose/system/insets' 'imePadding' 220

Check 'NAV-26' 'Predictive back needs android:enableOnBackInvokedCallback' `
  'https://developer.android.com/guide/navigation/custom-back/predictive-back-gesture' 'enableOnBackInvokedCallback' 200

Check 'NAV-17/BLD-09' 'Android 12: components with intent filters need explicit android:exported' `
  'https://developer.android.com/about/versions/12/behavior-changes-12' 'android:exported' 200

Check 'NAV-19/SET-26' 'Configuration changes recreate the Activity unless declared (uiMode, locale, fontScale)' `
  'https://developer.android.com/guide/topics/resources/runtime-changes' 'uiMode' 200

Check 'MAIL-09' 'CSP: form-action does not fall back to default-src' `
  'https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Content-Security-Policy/form-action' 'default-src' 200

Check 'PIM-16' 'vCard 4.0: lines folded at 75 octets' `
  'https://www.rfc-editor.org/rfc/rfc6350.html' '75 octets' 200

Check 'ONB-03' 'Coroutine cancellation is cooperative' `
  'https://kotlinlang.org/docs/cancellation-and-timeouts.html' 'cooperative' 200

Check 'ONB-03' 'OkHttp Call.cancel() aborts a blocking execute()' `
  'https://square.github.io/okhttp/5.x/okhttp/okhttp3/-call/index.html' 'cancel' 200

Check 'NAV-21' 'android.util.Base64.decode throws IllegalArgumentException on bad input' `
  'https://developer.android.com/reference/android/util/Base64' 'IllegalArgumentException' 200

Check 'BLD-14' 'security-crypto releases page: stable 1.1.0' `
  'https://developer.android.com/jetpack/androidx/releases/security' '1\.1\.0' 200

Check 'MAIL-19/SET-12' 'Accessibility: touch targets at least 48dp' `
  'https://developer.android.com/guide/topics/ui/accessibility/principles' '48dp' 200

Check 'SET-08/SET-09' 'WCAG 1.4.3: contrast ratio of at least 4.5:1' `
  'https://www.w3.org/TR/WCAG22/' 'at least 4\.5:1' 200

Check 'SET-09' 'WCAG 1.4.11: non-text contrast of at least 3:1' `
  'https://www.w3.org/TR/WCAG22/' 'at least 3:1 against adjacent' 200

Check 'ONB-01' 'PackageManager.GET_SIGNING_CERTIFICATES (APK signature check before install)' `
  'https://developer.android.com/reference/android/content/pm/PackageManager' 'GET_SIGNING_CERTIFICATES' 200

Check 'CMP-05' 'MASVS/OWASP: mailto/EXTRA_TEXT are untrusted input' `
  'https://developer.android.com/privacy-and-security/risks/unsafe-intent-launch' 'untrusted' 200

Remove-Item $tmp -Force -ErrorAction SilentlyContinue
Write-Output 'DONE'
