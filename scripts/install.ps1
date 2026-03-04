# Convex installer for end users (Windows PowerShell).
# Downloads the latest convex.jar and creates a 'convex' command on PATH.
#
# SYNC: also hosted at convex.world/public/install.ps1 — keep in sync.
#
# Usage:
#   irm https://convex.world/install.ps1 | iex
#
# Options (environment variables):
#   $env:CONVEX_VERSION = "0.8.3"            Install a specific version
#   $env:CONVEX_HOME = "C:\Users\me\.convex"  Installation directory

$ErrorActionPreference = "Stop"

$ConvexHome = if ($env:CONVEX_HOME) { $env:CONVEX_HOME } else { "$HOME\.convex" }
$ConvexJar = "$ConvexHome\convex.jar"
$MinJavaVersion = 21
$Repo = "Convex-Dev/convex"

# ── Helpers ──────────────────────────────────────────────

function Info($msg)  { Write-Host "  $msg" }
function Ok($msg)    { Write-Host "  [OK] $msg" -ForegroundColor Green }
function Warn($msg)  { Write-Host "  [!!] $msg" -ForegroundColor Yellow }
function Fail($msg)  { Write-Host "  [FAIL] $msg" -ForegroundColor Red; exit 1 }

# ── Check Java ───────────────────────────────────────────

function Check-Java {
    $javaCmd = Get-Command java -ErrorAction SilentlyContinue
    if (-not $javaCmd) {
        Fail "Java not found. Please install Java $MinJavaVersion or later."
    }

    $ErrorActionPreference = "Continue"
    $versionOutput = & java -version 2>&1 | Out-String
    $ErrorActionPreference = "Stop"
    if ($versionOutput -match '"(\d+)') {
        $version = [int]$Matches[1]
    }
    else {
        Fail "Could not determine Java version."
    }

    if ($version -lt $MinJavaVersion) {
        Fail "Java $MinJavaVersion+ required, found Java $version."
    }

    Ok "Java $version"
}

# ── Download ─────────────────────────────────────────────

function Download-Convex {
    if ($env:CONVEX_VERSION) {
        $url = "https://github.com/$Repo/releases/download/$env:CONVEX_VERSION/convex.jar"
    }
    else {
        $url = "https://github.com/$Repo/releases/latest/download/convex.jar"
    }

    if (-not (Test-Path $ConvexHome)) {
        New-Item -ItemType Directory -Path $ConvexHome -Force | Out-Null
    }

    Info "Downloading from $url ..."
    Invoke-WebRequest -Uri $url -OutFile $ConvexJar -UseBasicParsing

    if (-not (Test-Path $ConvexJar)) {
        Fail "Download failed."
    }

    Ok "Downloaded convex.jar"
}

# ── Create wrapper script ────────────────────────────────

function Install-Wrapper {
    $wrapper = "$ConvexHome\convex.cmd"

    Set-Content -Path $wrapper -Encoding ASCII -Value @"
@echo off
java -jar "$ConvexJar" %*
"@

    Ok "Created convex.cmd wrapper"

    # Add to PATH if not already there
    $userPath = [Environment]::GetEnvironmentVariable("Path", "User")
    if ($userPath -notlike "*$ConvexHome*") {
        $reply = Read-Host "  Add $ConvexHome to your PATH? [Y/n]"
        if ($reply -eq "" -or $reply -match "^[Yy]") {
            [Environment]::SetEnvironmentVariable("Path", "$userPath;$ConvexHome", "User")
            $env:Path = "$env:Path;$ConvexHome"
            Ok "Added $ConvexHome to user PATH"
            Warn "Restart your terminal for PATH changes to take effect."
        }
        else {
            Info "Skipped. To use 'convex' add $ConvexHome to your PATH manually."
        }
    }
    else {
        Ok "$ConvexHome already on PATH"
    }
}

# ── Verify ───────────────────────────────────────────────

function Verify {
    if (Test-Path $ConvexJar) {
        Ok "Installed to $ConvexJar"
    }
    else {
        Fail "Installation failed - convex.jar not found."
    }
}

# ── Main ─────────────────────────────────────────────────

Write-Host ""
Write-Host "  Convex Installer"
Write-Host "  -----------------"
Write-Host ""

Check-Java
Download-Convex
Install-Wrapper
Verify

Write-Host ""
Info "Run 'convex --help' to get started."
Write-Host ""
