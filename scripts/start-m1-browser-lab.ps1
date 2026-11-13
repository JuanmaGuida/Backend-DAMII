[CmdletBinding()]
param(
    [switch]$VerifySessions,
    [int]$DatabasePort = 55440,
    [int]$BackendPort = 18081,
    [int]$HttpsPort = 18443
)
$ErrorActionPreference = 'Stop'
$Host.UI.RawUI.WindowTitle = 'M1 - Laboratorio aislado'
$privateRoot = Join-Path $env:LOCALAPPDATA 'M2Lab'
$certificate = Join-Path $privateRoot 'certificates\localhost.pem'
$key = Join-Path $privateRoot 'certificates\localhost-key.pem'
foreach ($path in @($certificate, $key)) {
    if (!(Test-Path -LiteralPath $path -PathType Leaf)) { throw 'Falta el certificado local privado de M2Lab.' }
}
$privateNames = @('APP_JWT_SECRET', 'M1_TEST_CITIZEN1_PASSWORD', 'M1_TEST_CITIZEN2_PASSWORD', 'M1_TEST_EMPLOYEE_PASSWORD')
$introduced = [Collections.Generic.List[string]]::new()
$oldCertificate = $env:M2_LAB_CERT_FILE
$oldKey = $env:M2_LAB_KEY_FILE
try {
    $env:M2_LAB_CERT_FILE = $certificate
    $env:M2_LAB_KEY_FILE = $key
    Write-Host 'Laboratorio M1 real, exclusivamente local. Core y RabbitMQ deshabilitados.'
    Write-Host 'Ingresá únicamente los valores que falten. La entrada es oculta y no se guarda en archivos.'
    foreach ($name in $privateNames) {
        if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name, 'Process'))) {
            $secure = Read-Host $name -AsSecureString
            $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
            try {
                [Environment]::SetEnvironmentVariable($name, [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer), 'Process')
                $introduced.Add($name)
            } finally {
                [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
                $secure.Dispose()
            }
        }
    }
    Write-Host "URL del laboratorio: https://localhost:$HttpsPort"
    Write-Host 'Mantené esta terminal abierta. Para detener backend, proxy y PostgreSQL presioná Ctrl+C.'
    & (Join-Path $PSScriptRoot 'test-m1-isolated.ps1') -RealM1 -ProvisionEmployee -Serve -VerifySessions:$VerifySessions -DatabasePort $DatabasePort -BackendPort $BackendPort -HttpsPort $HttpsPort
} finally {
    foreach ($name in $introduced) { [Environment]::SetEnvironmentVariable($name, $null, 'Process') }
    $env:M2_LAB_CERT_FILE = $oldCertificate
    $env:M2_LAB_KEY_FILE = $oldKey
}
