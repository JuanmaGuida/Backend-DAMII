[CmdletBinding()]
param(
    [switch]$RealM1,
    [switch]$ProvisionEmployee,
    [switch]$Serve,
    [switch]$VerifySessions,
    [string]$PostgresBin = 'C:\Program Files\PostgreSQL\18\bin',
    [string]$JavaHome = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.4.7-hotspot',
    [int]$DatabasePort = 55439,
    [int]$BackendPort = 18081,
    [int]$HttpsPort = 18443,
    [string]$FrontendPath = (Join-Path $PSScriptRoot '..\..\..\Frontend\front-desarrollo-apps-2\Front\frontend-atencion-ciudadana')
)
$ErrorActionPreference = 'Stop'
if (($ProvisionEmployee -or $Serve) -and !$RealM1) { throw '-ProvisionEmployee and -Serve require -RealM1' }
if ($VerifySessions -and !$Serve) { throw '-VerifySessions requires -Serve' }
if (@($DatabasePort, $BackendPort, $HttpsPort | Where-Object { $_ -lt 1024 -or $_ -gt 65535 }).Count) { throw 'Use unprivileged valid ports' }
if (@($DatabasePort, $BackendPort, $HttpsPort | Select-Object -Unique).Count -ne 3) { throw 'Use three distinct ports' }

# Missing private material stops BEFORE database creation or any M1 request. Values are never printed.
if ($RealM1) {
    $missing = @('APP_JWT_SECRET', 'M1_TEST_CITIZEN1_PASSWORD', 'M1_TEST_CITIZEN2_PASSWORD', 'M1_TEST_EMPLOYEE_PASSWORD') |
        Where-Object { [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($_, 'Process')) }
    if ($missing) { throw ('Missing private environment variables: ' + ($missing -join ', ') + '. No M1 requests made.') }
    try {
        $decoded = [Convert]::FromBase64String($env:APP_JWT_SECRET)
        if ($decoded.Length -lt 32) { throw 'short key' }
    } catch { throw 'APP_JWT_SECRET must be Base64 with at least 32 decoded bytes; value omitted.' }
    finally { if ($decoded) { [Array]::Clear($decoded, 0, $decoded.Length) } }
}
if ($Serve) {
    foreach ($name in @('M2_LAB_CERT_FILE', 'M2_LAB_KEY_FILE')) {
        $path = [Environment]::GetEnvironmentVariable($name, 'Process')
        if (!$path -or !(Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing local HTTPS file variable: $name" }
    }
    if (!(Test-Path -LiteralPath (Join-Path $FrontendPath 'node_modules\vite\bin\vite.js'))) { throw 'Frontend dependencies must already be installed' }
}
foreach ($binary in @('initdb.exe', 'pg_ctl.exe', 'createdb.exe')) {
    if (!(Test-Path -LiteralPath (Join-Path $PostgresBin $binary))) { throw "PostgreSQL binary missing: $binary" }
}
if (!(Test-Path -LiteralPath (Join-Path $JavaHome 'bin\java.exe'))) { throw 'Set -JavaHome to an installed Java 21 JDK' }
foreach ($port in @($DatabasePort, $BackendPort, $HttpsPort)) {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $port)
    try { $listener.Start() } finally { $listener.Stop() }
}
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$lab = Join-Path ([IO.Path]::GetTempPath()) ('m2-m1-lab-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $lab | Out-Null
$database = 'm2_m1_lab_' + [Guid]::NewGuid().ToString('N')
$saved = @{}
function Set-LabEnv([string]$Name, [string]$Value) {
    if (!$saved.ContainsKey($Name)) { $saved[$Name] = [Environment]::GetEnvironmentVariable($Name, 'Process') }
    [Environment]::SetEnvironmentVariable($Name, $Value, 'Process')
}
function Invoke-Checked([string]$Program, [string[]]$Arguments) {
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) {
        $code = $LASTEXITCODE
        $hex = '{0:X8}' -f ([long]$code -band 4294967295)
        throw "Local command failed: $Program . Exit code: $code (0x$hex). Credential values omitted."
    }
}
function Invoke-PgControl([string]$Operation) {
    $controlArgs = @('-D', ('"' + "$lab\pgdata" + '"'), '-w')
    if ($Operation -eq 'start') {
        $controlArgs += @('-l', ('"' + "$lab\postgres.log" + '"'), '-o', ('"' + "-h 127.0.0.1 -p $DatabasePort" + '"'))
    } else { $controlArgs += @('-m', 'fast') }
    $controlArgs += $Operation
    # Wait only for pg_ctl itself. Waiting for its process tree would also wait for the database server.
    $control = Start-Process -FilePath (Join-Path $PostgresBin 'pg_ctl.exe') -ArgumentList $controlArgs -WindowStyle Hidden -PassThru -RedirectStandardOutput "$lab\pgctl-$Operation.log" -RedirectStandardError "$lab\pgctl-$Operation-error.log"
    if (!$control.WaitForExit(60000) -or $control.ExitCode -ne 0) { throw "Disposable PostgreSQL $Operation failed" }
}
$started = $false
$backend = $null
Push-Location $repo
try {
    Set-LabEnv 'JAVA_HOME' $JavaHome
    Set-LabEnv 'TEMP' $lab
    Set-LabEnv 'TMP' $lab
    # A random disposable DB password is the only transient password file. M1 secrets remain in environment only.
    $dbPassword = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
    $passwordFile = Join-Path $lab 'initdb-password'
    try {
        [IO.File]::WriteAllText($passwordFile, $dbPassword + [Environment]::NewLine)
        & (Join-Path $PostgresBin 'initdb.exe') -D "$lab\pgdata" -U m2_lab --auth=scram-sha-256 "--pwfile=$passwordFile" --no-locale -E UTF8 > "$lab\initdb.log" 2>&1
        if ($LASTEXITCODE -ne 0) { throw 'Disposable initdb failed' }
    } finally { if (Test-Path -LiteralPath $passwordFile) { Remove-Item -LiteralPath $passwordFile } }
    Invoke-PgControl 'start'
    $started = $true
    Write-Host 'Disposable PostgreSQL started on loopback.'
    Set-LabEnv 'PGPASSWORD' $dbPassword
    Invoke-Checked (Join-Path $PostgresBin 'createdb.exe') @('--no-password', '-h', '127.0.0.1', '-p', "$DatabasePort", '-U', 'm2_lab', $database)
    Set-LabEnv 'PGPASSWORD' ''
    Set-LabEnv 'SPRING_DATASOURCE_URL' "jdbc:postgresql://127.0.0.1:$DatabasePort/$database"
    Set-LabEnv 'SPRING_DATASOURCE_USERNAME' 'm2_lab'
    Set-LabEnv 'SPRING_DATASOURCE_PASSWORD' $dbPassword
    Set-LabEnv 'CORE_OUTBOX_ENABLED' 'false'
    Set-LabEnv 'CORE_OUTBOX_AUTO_APPROVE_NEW_EVENTS' 'false'
    Set-LabEnv 'CORE_OUTBOX_APPROVED_EVENT_IDS' ''
    Set-LabEnv 'CORE_INBOX_ENABLED' 'false'
    Set-LabEnv 'SIMULATOR_ENABLED' 'false'
    Set-LabEnv 'AI_ENABLED' 'false'
    Set-LabEnv 'SPRING_JPA_SHOW_SQL' 'false'
    Set-LabEnv 'LOGGING_LEVEL_ROOT' $(if ($RealM1) { 'WARN' } else { 'INFO' })
    Set-LabEnv 'SPRING_SECURITY_USER_PASSWORD' ([Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)))
    Set-LabEnv 'TRACKING_CODE_ENCRYPTION_KEY_BASE64' ([Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)))
    Set-LabEnv 'TRACKING_CODE_ENCRYPTION_KEY_ID' 'isolated-lab'
    Set-LabEnv 'ATTACHMENT_STORAGE_ROOT' "$lab\attachments"
    Set-LabEnv 'M2_AUTH_BROWSER_ORIGIN' "https://localhost:$HttpsPort"
    Set-LabEnv 'M2_PUBLIC_BASE_URL' $(if ($RealM1) { "https://localhost:$HttpsPort" } else { 'http://localhost:8080' })
    Set-LabEnv 'M1_BASE_URL' 'https://ciudadanos-m1-backend.onrender.com'
    Set-LabEnv 'M1_REAL_TESTS' $(if ($RealM1) { 'YES' } else { '' })
    Set-LabEnv 'M1_REAL_DISPOSABLE' $(if ($RealM1) { 'YES' } else { '' })
    Set-LabEnv 'M1_TEST_PROVISION_EMPLOYEE' $(if ($ProvisionEmployee) { 'YES' } else { '' })
    Set-LabEnv 'IDENTITY_PROVIDER' $(if ($RealM1) { 'M1' } else { 'MOCK' })
    Set-LabEnv 'SPRING_PROFILES_ACTIVE' $(if ($RealM1) { '' } else { 'dev' })
    foreach ($name in @('MOCK_CITIZEN_PASSWORD', 'MOCK_AGENT_PASSWORD', 'MOCK_AREA_RESPONSIBLE_PASSWORD', 'MOCK_ADMIN_PASSWORD')) {
        Set-LabEnv $name 'synthetic-lab-only'
    }
    # Offline: this runner never fetches packages or calls remote CI/CD. Flyway runs all migrations at context startup.
    if ($RealM1) { Invoke-Checked '.\mvnw.cmd' @('-o', '-B', '-Dtest=M1RealAuthenticationTest', 'test') }
    else { Invoke-Checked '.\mvnw.cmd' @('-o', '-B', 'clean', 'verify') }
    if ($Serve) {
        Set-LabEnv 'VITE_IDENTITY_PROVIDER' 'M1'
        Set-LabEnv 'VITE_API_BASE_URL' '/api'
        Push-Location $FrontendPath
        try { Invoke-Checked 'node' @('node_modules/vite/bin/vite.js', 'build') } finally { Pop-Location }
        Invoke-Checked '.\mvnw.cmd' @('-o', '-B', '-DskipTests', 'package')
        Set-LabEnv 'SERVER_ADDRESS' '127.0.0.1'
        Set-LabEnv 'SERVER_PORT' "$BackendPort"
        Set-LabEnv 'M2_LAB_BACKEND_PORT' "$BackendPort"
        Set-LabEnv 'M2_LAB_HTTPS_PORT' "$HttpsPort"
        Set-LabEnv 'M2_LAB_FRONTEND_DIST' ([IO.Path]::GetFullPath((Join-Path $FrontendPath 'dist')))
        Set-LabEnv 'M2_LAB_RUN_ID' ([Guid]::NewGuid().ToString('N'))
        Set-LabEnv 'M2_LAB_VERIFY_SESSIONS' $(if ($VerifySessions) { 'YES' } else { '' })
        Set-LabEnv 'M2_SESSION_CHECK_ORIGIN' "https://localhost:$HttpsPort"
        Set-LabEnv 'M2_SESSION_CHECK_FRONTEND' ([IO.Path]::GetFullPath($FrontendPath))
        Set-LabEnv 'M2_SESSION_CHECK_REPORT' $(if ($VerifySessions) { Join-Path $lab 'session-check.json' } else { '' })
        $backend = Start-Process -FilePath (Join-Path $JavaHome 'bin\java.exe') -ArgumentList @('-jar', 'target/backend-0.0.1-SNAPSHOT.jar') -WorkingDirectory $repo -WindowStyle Hidden -PassThru -RedirectStandardOutput "$lab\backend.log" -RedirectStandardError "$lab\backend-error.log"
        Set-LabEnv 'M2_LAB_BACKEND_PID' "$($backend.Id)"
        # A native V8 fail-fast on Windows build 26200 cannot be caught in JavaScript.
        # Keep this workaround local to the long-lived proxy; do not alter system Node or TLS.
        $proxyArguments = @('scripts/m1-lab-https.mjs')
        $nodeMajor = [int]((& node --version).TrimStart('v').Split('.')[0])
        if ($IsWindows -and [Environment]::OSVersion.Version.Build -eq 26200 -and $nodeMajor -ge 24) {
            $proxyArguments = @('--no-maglev') + $proxyArguments
            Write-Host 'Local proxy: Maglev disabled for the Windows 26200 native-crash workaround.'
        }
        $backendReady = $false
        for ($attempt = 0; $attempt -lt 60; $attempt++) {
            if ($backend.HasExited) { throw "Local backend exited during startup. Check $lab\backend-error.log" }
            $listener = Get-NetTCPConnection -State Listen -LocalAddress 127.0.0.1 -LocalPort $BackendPort -ErrorAction SilentlyContinue
            if ($listener -and $listener.OwningProcess -ne $backend.Id) { throw 'Backend port was taken by another process; refusing to verify it.' }
            try {
                if (!$listener) { throw 'Backend not listening yet' }
                $health = Invoke-RestMethod -Uri "http://127.0.0.1:$BackendPort/actuator/health" -TimeoutSec 1
                if ($health.status -eq 'UP') { $backendReady = $true; break }
            } catch { }
            Start-Sleep -Seconds 1
        }
        if (!$backendReady) { throw 'Local backend did not become ready; lab startup failed.' }
        Write-Host "Local lab only: https://localhost:$HttpsPort . Stop with Ctrl+C. No request bodies or tokens are logged."
        Write-Host "Proxy diagnostics: $lab\proxy-stderr.log"
        if ($VerifySessions) { Write-Host "Session checks start only after this proxy binds. Results: $lab\session-check.json" }
        & node @proxyArguments 2> "$lab\proxy-stderr.log"
        $proxyCode = $LASTEXITCODE
        if ($proxyCode -notin @(0, 130, -1073741510)) {
            $hex = '{0:X8}' -f ([long]$proxyCode -band 4294967295)
            throw "Local HTTPS proxy exited: $proxyCode (0x$hex). See $lab\proxy-stderr.log . Credential values omitted."
        }
    }
} finally {
    try {
        if ($backend -and !$backend.HasExited) {
            Write-Host 'Stopping the backend started by this lab runner.'
            Stop-Process -Id $backend.Id
        }
    } finally {
        try {
            if ($started -or (Test-Path -LiteralPath "$lab\pgdata\postmaster.pid")) { Invoke-PgControl 'stop' }
        } finally {
            foreach ($name in $saved.Keys) { [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process') }
            Pop-Location
            Write-Host "Local disposable files retained at: $lab . They may contain test profile data; no M1 tokens are intentionally written."
        }
    }
}
