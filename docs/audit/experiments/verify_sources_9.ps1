# Этап 1: первоисточники для PROTO-17 (multipart ItemOperations, GetAttachment), ONB-08 (TOFU) и блокировки.
# Запуск: powershell -File verify_sources_9.ps1 > output_verify_9.txt
$ErrorActionPreference = 'Continue'
$sources = @(
    @{ name = 'MS-ASCMD MultiPartResponse'; url = 'https://learn.microsoft.com/en-us/openspecs/exchange_server_protocols/ms-ascmd/5062896d-cfcd-41b3-ac33-b438ec3d9d8a'; terms = @('PartsCount', 'PartsMetaData', 'Parts') },
    @{ name = 'MS-ASCMD PartMetaData'; url = 'https://learn.microsoft.com/en-us/openspecs/exchange_server_protocols/ms-ascmd/4ea009e4-dbb6-4e57-b238-4886830ff2c7'; terms = @('Offset', 'Length') },
    @{ name = 'MS-ASCMD Delivery of Content Requested by Fetch'; url = 'https://learn.microsoft.com/en-us/openspecs/exchange_server_protocols/ms-ascmd/8487b661-2ecc-4442-aeab-91992796494a'; terms = @('multipart', 'memory', 'inline') },
    @{ name = 'MS-ASHTTP MS-ASAcceptMultiPart'; url = 'https://learn.microsoft.com/en-us/openspecs/exchange_server_protocols/ms-ashttp/4b651a7f-21fe-4f2b-82e7-1764c32b3eaa'; terms = @('MS-ASAcceptMultiPart', 'ItemOperations', 'SHOULD NOT') },
    @{ name = 'MS-ASAIRS Part'; url = 'https://learn.microsoft.com/en-us/openspecs/exchange_server_protocols/ms-asairs/2e08aa0a-d12e-4b9a-b397-9e32ef4186b5'; terms = @('multipart', 'MUST') },
    @{ name = 'MS-ASCMD ItemOperations Status'; url = 'https://learn.microsoft.com/en-us/openspecs/exchange_server_protocols/ms-ascmd/f95a2ed1-2a2c-4e43-9e15-6f1ca7d3b9d4'; terms = @('11', 'too large', 'Status') },
    @{ name = 'MS-ASCMD GetAttachment'; url = 'https://learn.microsoft.com/en-us/openspecs/exchange_server_protocols/ms-ascmd/e9e4c0f8-36ae-4ebf-8b69-3a6d6d6d76c4'; terms = @('GetAttachment', '14.0', 'POST') },
    @{ name = 'EWS GetAttachment operation'; url = 'https://learn.microsoft.com/en-us/exchange/client-developer/web-service-reference/getattachment-operation'; terms = @('GetAttachment', 'Content', 'Exchange 2007') },
    @{ name = 'EWS Content (FileAttachment)'; url = 'https://learn.microsoft.com/en-us/exchange/client-developer/web-service-reference/content'; terms = @('base64', 'Content') },
    @{ name = 'EWS Size (attachment)'; url = 'https://learn.microsoft.com/en-us/exchange/client-developer/web-service-reference/size'; terms = @('Size', 'Exchange 2010') },
    @{ name = 'Android FLAG_SECURE'; url = 'https://developer.android.com/reference/android/view/WindowManager.LayoutParams#FLAG_SECURE'; terms = @('FLAG_SECURE', 'screenshots', 'secure') },
    @{ name = 'Android setRecentsScreenshotEnabled'; url = 'https://developer.android.com/reference/android/app/Activity#setRecentsScreenshotEnabled(boolean)'; terms = @('setRecentsScreenshotEnabled', 'Recents', 'API level 33') },
    @{ name = 'Android Notification VISIBILITY_PRIVATE'; url = 'https://developer.android.com/reference/android/app/Notification#VISIBILITY_PRIVATE'; terms = @('VISIBILITY_PRIVATE', 'publicVersion', 'secure lockscreen') },
    @{ name = 'OWASP MASTG authentication rate limiting'; url = 'https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html'; terms = @('lockout', 'exponential', 'attempts') },
    @{ name = 'Android network security config pinning'; url = 'https://developer.android.com/privacy-and-security/security-config'; terms = @('pin-set', 'SubjectPublicKeyInfo', 'SHA-256') }
)
foreach ($s in $sources) {
    Write-Output "=== $($s.name) ==="
    Write-Output "URL: $($s.url)"
    $tmp = [System.IO.Path]::GetTempFileName()
    $code = & curl.exe -s -L -o $tmp -w '%{http_code}' --max-time 40 $s.url
    $len = (Get-Item $tmp).Length
    Write-Output "HTTP $code, $len bytes"
    $text = [System.IO.File]::ReadAllText($tmp)
    foreach ($t in $s.terms) {
        $i = $text.IndexOf($t, [System.StringComparison]::OrdinalIgnoreCase)
        if ($i -lt 0) { Write-Output "  [$t] not found"; continue }
        $start = [Math]::Max(0, $i - 120)
        $snippet = $text.Substring($start, [Math]::Min(320, $text.Length - $start)) -replace '<[^>]+>', ' ' -replace '\s+', ' '
        Write-Output "  [$t] ...$snippet..."
    }
    Remove-Item $tmp
    Write-Output ''
}
