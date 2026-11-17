# Fourth pass: ContentResolver file:// handling and a few remaining API facts.
# Usage:  powershell -NoProfile -File verify_sources_4.ps1
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = 'Continue'
$ua = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AuditBot/1.0'
$tmp = Join-Path $env:TEMP 'audit_fetch4.tmp'

function Get-Text([string]$url, [string]$mode) {
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

function Check([string]$id, [string]$claim, [string]$url, [string]$pattern, [int]$ctx = 220, [string]$mode = 'html') {
    Write-Output ("=== {0} | {1}" -f $id, $claim)
    Write-Output ("URL: {0}" -f $url)
    $text = Get-Text $url $mode
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

Check 'NAV-11' 'ContentResolver.openInputStream supports content, android.resource and file schemes' `
  'https://developer.android.com/reference/android/content/ContentResolver' 'file \(SCHEME_FILE\)|SCHEME_FILE' 420

Check 'NAV-11' 'ContentResolver.openInputStream (AOSP source: file scheme branch)' `
  'https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/content/ContentResolver.java?format=TEXT' `
  'SCHEME_FILE\.equals\(scheme\)' 260 'b64'

Check 'MAIL-01' 'AOSP: MediaStore.Downloads declared with @SdkConstant / since Q' `
  'https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/provider/MediaStore.java?format=TEXT' `
  'public static final class Downloads' 260 'b64'

Check 'NAV-19' 'Bundle/Saved state size guidance (activity state saving)' `
  'https://developer.android.com/topic/libraries/architecture/saving-states' '1MB|1 MB|50k|large' 260

Check 'CMP-09' 'AlarmManager: setExactAndAllowWhileIdle and SCHEDULE_EXACT_ALARM' `
  'https://developer.android.com/develop/background-work/services/alarms' 'SCHEDULE_EXACT_ALARM' 260

Remove-Item $tmp -Force -ErrorAction SilentlyContinue
Write-Output 'DONE'
