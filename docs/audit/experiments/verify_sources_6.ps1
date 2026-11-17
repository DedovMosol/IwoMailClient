# Sixth pass: map Compose BOM 2024.06.00 to material3 and check the TimePickerState guard in THAT version.
# Usage:  powershell -NoProfile -File verify_sources_6.ps1
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = 'Continue'
$ua = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AuditBot/1.0'
$tmp = Join-Path $env:TEMP 'audit_fetch6.tmp'

function Fetch([string]$url) {
    if (Test-Path $tmp) { Remove-Item $tmp -Force }
    $code = & curl.exe -sL --compressed --max-time 90 -A $ua -w '%{http_code}' $url -o $tmp
    return $code
}

Write-Output '=== BOM mapping: 2024.06.00 -> material3'
$code = Fetch 'https://developer.android.com/develop/ui/compose/bom/bom-mapping'
Write-Output ("HTTP " + $code)
if (Test-Path $tmp) {
    $t = [IO.File]::ReadAllText($tmp, [Text.Encoding]::UTF8)
    $t = [regex]::Replace($t, '(?is)<(script|style)\b.*?</\1>', ' ')
    $t = [regex]::Replace($t, '(?s)<[^>]+>', ' ')
    $t = [regex]::Replace($t, '\s+', ' ')
    $m = [regex]::Match($t, '2024\.06\.00')
    if ($m.Success) { Write-Output ($t.Substring($m.Index, [Math]::Min(600, $t.Length - $m.Index))) } else { Write-Output 'BOM 2024.06.00 not found on page' }
}
Write-Output ''

$tags = @('androidx-compose-material3-release', 'androidx-compose-material3-1.2.1', 'androidx-compose-material3-1.2.0', 'refs/heads/androidx-compose-release')
$paths = @(
  'compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/TimePicker.kt',
  'compose/material3/material3/src/main/java/androidx/compose/material3/TimePicker.kt'
)
Write-Output '=== TimePickerState guard in the material3 release line used by the project'
foreach ($ref in @('refs/tags/androidx-compose-material3-1.2.1', 'refs/tags/androidx-compose-material3-1.2.0', 'refs/heads/androidx-compose-release')) {
    foreach ($p in $paths) {
        $url = "https://android.googlesource.com/platform/frameworks/support/+/$ref/$p" + '?format=TEXT'
        $code = Fetch $url
        if ($code -eq '200' -and (Test-Path $tmp) -and (Get-Item $tmp).Length -gt 0) {
            $b = (Get-Content $tmp -Raw) -replace '\s', ''
            try {
                $src = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($b))
                $m = [regex]::Match($src, 'require\(initialHour in 0\.\.\w+\)[^\r\n]*')
                if ($m.Success) {
                    Write-Output ("FOUND at {0} :: {1}" -f $ref, $p)
                    Write-Output ("  " + $m.Value)
                    $m2 = [regex]::Match($src, 'require\(initialMinute in 0\.\.\w+\)[^\r\n]*')
                    if ($m2.Success) { Write-Output ("  " + $m2.Value) }
                } else {
                    Write-Output ("fetched {0} :: {1} - guard text not found" -f $ref, $p)
                }
            } catch { Write-Output ("decode failed for {0}" -f $ref) }
        } else {
            Write-Output ("HTTP {0} for {1} :: {2}" -f $code, $ref, $p)
        }
    }
}
Remove-Item $tmp -Force -ErrorAction SilentlyContinue
Write-Output 'DONE'
