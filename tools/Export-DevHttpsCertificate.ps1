<#
.SYNOPSIS
  Exports this PC's ASP.NET Core HTTPS development certificate (public part only) for the Android debug build.

.DESCRIPTION
  The ASP.NET Core development certificate is not a CA certificate, so a physical Android device
  cannot install it as a trusted CA from Settings. Instead the debug build trusts this exported
  certificate directly (app/dev-https/res/xml/network_security_config.xml, debug-overrides only).

  - Only the public certificate is exported; the private key never leaves the Windows certificate store.
  - The output file is ignored by Git and only included in debug builds when it exists.
  - Release builds are not affected and trust system CAs only.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\Export-DevHttpsCertificate.ps1
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
$targetDirectory = Join-Path $repoRoot 'app\dev-https\res\raw'
$targetFile = Join-Path $targetDirectory 'dev_https_certificate.cer'

# Extension present on certificates created by `dotnet dev-certs https`.
$aspNetDevelopmentCertificateOid = '1.3.6.1.4.1.311.84.1.1'

$certificate = Get-ChildItem Cert:\CurrentUser\My |
    Where-Object {
        $_.Subject -eq 'CN=localhost' -and
        $_.NotAfter -gt (Get-Date) -and
        ($_.Extensions | Where-Object { $_.Oid.Value -eq $aspNetDevelopmentCertificateOid })
    } |
    Sort-Object NotAfter -Descending |
    Select-Object -First 1

if (-not $certificate) {
    throw "No valid ASP.NET Core development certificate found. Run 'dotnet dev-certs https --trust' first."
}

New-Item -ItemType Directory -Force -Path $targetDirectory | Out-Null
$publicCertificate = $certificate.Export([System.Security.Cryptography.X509Certificates.X509ContentType]::Cert)
[System.IO.File]::WriteAllBytes($targetFile, $publicCertificate)

Write-Host "Exported public certificate $($certificate.Thumbprint) (valid until $($certificate.NotAfter)) to $targetFile"
Write-Host 'Rebuild and reinstall the debug build (gradlew.bat assembleDebug) for the change to take effect.'
