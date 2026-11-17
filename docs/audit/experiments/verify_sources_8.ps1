# Eighth pass: CSP fallback rules that decide how bad the sanitizer gaps (MAIL-08, MAIL-09) really are.
# Usage:  powershell -NoProfile -File verify_sources_8.ps1
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = 'Continue'
$ua = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AuditBot/1.0'
$tmp = Join-Path $env:TEMP 'audit_fetch8.tmp'

function Check([string]$id, [string]$claim, [string]$url, [string]$pattern, [int]$ctx = 240) {
    Write-Output ("=== {0} | {1}" -f $id, $claim)
    Write-Output ("URL: {0}" -f $url)
    if (Test-Path $tmp) { Remove-Item $tmp -Force }
    & curl.exe -sL --compressed --max-time 90 -A $ua $url -o $tmp
    if (-not (Test-Path $tmp) -or (Get-Item $tmp).Length -eq 0) { Write-Output 'RESULT: FETCH FAILED'; Write-Output ''; return }
    $t = [IO.File]::ReadAllText($tmp, [Text.Encoding]::UTF8)
    $t = [regex]::Replace($t, '(?is)<(script|style)\b.*?</\1>', ' ')
    $t = [regex]::Replace($t, '(?s)<[^>]+>', ' ')
    $t = $t -replace '&nbsp;', ' ' -replace '&amp;', '&' -replace '&lt;', '<' -replace '&gt;', '>' -replace '&quot;', '"' -replace '&#39;', "'"
    $t = [regex]::Replace($t, '\s+', ' ')
    $m = [regex]::Match($t, $pattern, 'IgnoreCase')
    if ($m.Success) {
        $s = [Math]::Max(0, $m.Index - $ctx); $e = [Math]::Min($t.Length, $m.Index + $m.Length + $ctx)
        Write-Output ("RESULT: FOUND ({0} chars)" -f $t.Length)
        Write-Output ("SNIPPET: ..." + $t.Substring($s, $e - $s) + "...")
    } else {
        Write-Output ("RESULT: PATTERN NOT FOUND ({0} chars)" -f $t.Length)
    }
    Write-Output ''
}

Check 'MAIL-08' 'CSP frame-src falls back to child-src, then default-src' `
  'https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Content-Security-Policy/frame-src' 'child-src.*default-src|default-src.*fallback|fallback'

Check 'MAIL-09' 'CSP base-uri does NOT fall back to default-src' `
  'https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Content-Security-Policy/base-uri' 'default-src fallback'

Check 'MAIL-09' 'CSP form-action does NOT fall back to default-src' `
  'https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Content-Security-Policy/form-action' 'default-src fallback'

Check 'MAIL-08' 'CSP via <meta>: supported directives and limits' `
  'https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Content-Security-Policy' 'meta'

Check 'MAIL-08' 'CSP default-src is a fallback for fetch directives (script-src, img-src, frame-src, ...)' `
  'https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Content-Security-Policy/default-src' 'fallback'

Remove-Item $tmp -Force -ErrorAction SilentlyContinue
Write-Output 'DONE'
