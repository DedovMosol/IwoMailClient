# Third pass: precise re-checks for claims whose first-pass match was a false positive or missing.
# Usage:  powershell -NoProfile -File verify_sources_3.ps1
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = 'Continue'
$ua = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AuditBot/1.0'
$tmp = Join-Path $env:TEMP 'audit_fetch3.tmp'

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

function Check([string]$id, [string]$claim, [string]$url, [string]$pattern, [int]$ctx = 200, [string]$mode = 'html') {
    Write-Output ("=== {0} | {1}" -f $id, $claim)
    Write-Output ("URL: {0}" -f $url)
    $text = Get-Raw $url $mode
    if ($null -eq $text) { Write-Output "RESULT: FETCH FAILED"; Write-Output ''; return }
    $m = [regex]::Match($text, $pattern, 'IgnoreCase')
    if ($m.Success) {
        $s = [Math]::Max(0, $m.Index - $ctx)
        $e = [Math]::Min($text.Length, $m.Index + $m.Length + $ctx)
        Write-Output ("RESULT: FOUND ({0} chars fetched)" -f $text.Length)
        Write-Output ("SNIPPET: ..." + ($text.Substring($s, $e - $s) -replace '\s+', ' ') + "...")
    } else {
        Write-Output ("RESULT: PATTERN NOT FOUND ({0} chars fetched)" -f $text.Length)
    }
    Write-Output ''
}

Check 'MAIL-06' 'Lazy lists: provide stable item keys' `
  'https://developer.android.com/develop/ui/compose/lists' 'key parameter|provide a stable|item keys|stable key' 260

Check 'NAV-21' 'android.util.Base64.decode throws IllegalArgumentException (AOSP source)' `
  'https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/util/Base64.java?format=TEXT' `
  'throws IllegalArgumentException|IllegalArgumentException\("bad base-64"\)' 220 'b64'

Check 'ONB-03' 'Kotlin coroutines: cancellation is cooperative (official guide)' `
  'https://kotlinlang.org/docs/cancellation-and-timeouts.html' 'cooperative' 220

Check 'ONB-03' 'Kotlin coroutines guide (GitHub source, current path)' `
  'https://raw.githubusercontent.com/Kotlin/kotlinx.coroutines/master/docs/topics/cancellation-and-timeouts.md' 'cooperative' 220 'raw'

Check 'ONB-03' 'kotlinx.coroutines Job.cancel KDoc (source)' `
  'https://raw.githubusercontent.com/Kotlin/kotlinx.coroutines/master/kotlinx-coroutines-core/common/src/Job.kt' 'cooperative' 260 'raw'

Check 'CMP-05/NAV-14' 'Android unsafe intent launch guidance' `
  'https://developer.android.com/privacy-and-security/risks/unsafe-intent-launch' 'Intent' 260

Check 'NAV-11' 'Intent.EXTRA_STREAM / content URI sharing guidance (FileProvider)' `
  'https://developer.android.com/training/secure-file-sharing' 'content://' 260

Check 'MAIL-10' 'WebViewClient.shouldOverrideUrlLoading request.hasGesture / isRedirect' `
  'https://developer.android.com/reference/android/webkit/WebResourceRequest' 'hasGesture' 220

Check 'MAIL-08' 'OWASP XSS Prevention: use an HTML sanitizer / allow-list, not a block-list' `
  'https://cheatsheetseries.owasp.org/cheatsheets/XSS_Filter_Evasion_Cheat_Sheet.html' 'filter' 200

Check 'MAIL-03' 'OWASP: regular expression denial of service (ReDoS)' `
  'https://owasp.org/www-community/attacks/Regular_expression_Denial_of_Service_-_ReDoS' 'catastrophic|exponential|evil regex' 220

Remove-Item $tmp -Force -ErrorAction SilentlyContinue
Write-Output 'DONE'
