# Token generation + brute-force demo (educational / defensive)
# Demonstrates how a secrets.token_urlsafe(32) token is generated and why
# brute-forcing it is computationally impossible.

function Generate-AdminToken {
    $bytes = New-Object byte[] 32
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $rng.GetBytes($bytes)
    $rng.Dispose()
    return [Convert]::ToBase64String($bytes) -replace '\+', '-' -replace '/', '_' -replace '=', ''
}

function Show-TokenGeneration {
    Write-Host "=" * 60
    Write-Host "STEP 1: Token generation"
    Write-Host "=" * 60
    for ($i = 1; $i -le 5; $i++) {
        $token = Generate-AdminToken
        Write-Host "  token $i`: $token (length: $($token.Length))"
    }
    Write-Host ""
}

function Show-BruteforceDemo {
    param([int]$Attempts = 100000)

    Write-Host "=" * 60
    Write-Host "STEP 2: Brute-force demonstration"
    Write-Host "=" * 60

    $realToken = Generate-AdminToken
    Write-Host "Real token:    $realToken"
    Write-Host "Token length:  $($realToken.Length) chars"
    Write-Host "Alphabet size: 64 characters (A-Z, a-z, 0-9, -, _)"
    Write-Host ""

    $possibleCombinations = [math]::Pow(64, $realToken.Length)
    $bits = [math]::Log($possibleCombinations, 2)
    Write-Host "Possible combinations: ~$([decimal]::new($possibleCombinations).ToString('E'))"
    Write-Host "That is roughly 2^$([math]::Round($bits))"
    Write-Host ""

    Write-Host "Trying $([int]$Attempts).ToString('N0') random guesses..."
    $start = Get-Date
    $found = $false

    for ($i = 1; $i -le $Attempts; $i++) {
        $guess = Generate-AdminToken
        if ($guess -eq $realToken) {
            $found = $true
            Write-Host "FOUND after $i attempts! (extremely unlikely)"
            break
        }
    }

    $elapsed = (Get-Date) - $start
    Write-Host "Done. Found: $found. Time: $($elapsed.TotalSeconds.ToString('F2'))s"
    Write-Host ""

    Write-Host "=" * 60
    Write-Host "CONCLUSION"
    Write-Host "=" * 60
    Write-Host "A token_urlsafe-style token cannot be brute-forced in practice."
    Write-Host "An attacker would need to try ~2^192 combinations on average."
    Write-Host "Even with billions of attempts per second, this takes far longer"
    Write-Host "than the age of the universe."
    Write-Host ""
    Write-Host "BUT: if the token is weak (like 'admin123'), it is guessed in seconds."
    Write-Host "Always use a 32-byte cryptographically random token for admin tokens."
}

function Show-WeakTokenComparison {
    Write-Host "=" * 60
    Write-Host "STEP 3: Comparison with weak token"
    Write-Host "=" * 60

    $weakTokens = @(
        "admin", "admin123", "password", "123456", "secret",
        "token", "cap_admin", "anthropic", "claude", "claude_admin"
    )
    $realWeakToken = "admin123"

    Write-Host "Real weak token: $realWeakToken"
    Write-Host "Dictionary size: $($weakTokens.Length)"

    $start = Get-Date
    for ($i = 0; $i -lt $weakTokens.Length; $i++) {
        if ($weakTokens[$i] -eq $realWeakToken) {
            $elapsed = (Get-Date) - $start
            Write-Host "FOUND: '$($weakTokens[$i])' after $($i + 1) attempts ($($elapsed.TotalSeconds.ToString('F4'))s)"
            Write-Host "This is why admin123 must never be used."
            return
        }
    }
}

# Main
Show-TokenGeneration
Show-BruteforceDemo -Attempts 100000
Show-WeakTokenComparison
