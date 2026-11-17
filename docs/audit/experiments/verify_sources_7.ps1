# Seventh pass: read the TimePickerState source of the EXACT material3 artifact used by the project
# (Compose BOM 2024.06.00 -> androidx.compose.material3:material3 1.2.1) from Google Maven.
# Usage:  powershell -NoProfile -File verify_sources_7.ps1
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = 'Continue'
$ua = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AuditBot/1.0'
$work = Join-Path $env:TEMP 'm3src'
if (Test-Path $work) { Remove-Item $work -Recurse -Force }
New-Item -ItemType Directory -Path $work | Out-Null

$candidates = @(
  'https://dl.google.com/dl/android/maven2/androidx/compose/material3/material3/1.2.1/material3-1.2.1-sources.jar',
  'https://dl.google.com/dl/android/maven2/androidx/compose/material3/material3-android/1.2.1/material3-android-1.2.1-sources.jar',
  'https://maven.google.com/androidx/compose/material3/material3/1.2.1/material3-1.2.1-sources.jar'
)
$got = $null
foreach ($u in $candidates) {
    $dst = Join-Path $work 'm3.zip'
    $code = & curl.exe -sL --max-time 180 -A $ua -w '%{http_code}' $u -o $dst
    Write-Output ("HTTP {0} {1}" -f $code, $u)
    if ($code -eq '200' -and (Get-Item $dst).Length -gt 100000) { $got = $dst; Write-Output ("downloaded {0} bytes" -f (Get-Item $dst).Length); break }
}
if (-not $got) { Write-Output 'RESULT: could not download sources'; exit }

Expand-Archive -Path $got -DestinationPath (Join-Path $work 'src') -Force
$tp = Get-ChildItem -Path (Join-Path $work 'src') -Recurse -Filter 'TimePicker.kt' | Select-Object -First 1
if (-not $tp) { Write-Output 'RESULT: TimePicker.kt not in archive'; exit }
Write-Output ("file: " + $tp.FullName.Substring($work.Length))
$src = [IO.File]::ReadAllText($tp.FullName, [Text.Encoding]::UTF8)
foreach ($pat in @('require\(initialHour[^\r\n]*', 'require\(initialMinute[^\r\n]*', 'initialHour should[^\r\n]*', 'initialMinute should[^\r\n]*')) {
    $m = [regex]::Match($src, $pat)
    if ($m.Success) { Write-Output ("FOUND: " + $m.Value.Trim()) } else { Write-Output ("not found: " + $pat) }
}
$dp = Get-ChildItem -Path (Join-Path $work 'src') -Recurse -Filter 'DatePicker.kt' | Select-Object -First 1
if ($dp) {
    $d = [IO.File]::ReadAllText($dp.FullName, [Text.Encoding]::UTF8)
    $m = [regex]::Match($d, 'timestamp that represents the selected date[^\r\n]*[\r\n]+[^\r\n]*')
    if ($m.Success) { Write-Output ("DatePickerState KDoc: " + ($m.Value -replace '\s+', ' ')) }
}
Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue
Write-Output 'DONE'
