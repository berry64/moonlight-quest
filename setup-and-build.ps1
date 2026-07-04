#requires -Version 5.1
<#
  setup-and-build.ps1

  One-shot Windows setup + build for Moonlight (including the Quest VR flavor).
  It installs/locates JDK 17 and the Android SDK/NDK, checks out the native
  git submodule, writes local.properties, and builds an APK.

  RUN THIS FROM AN ELEVATED (Administrator) POWERSHELL, in the repo root:

      Set-ExecutionPolicy -Scope Process Bypass -Force
      .\setup-and-build.ps1                     # normal phone APK (mobile flavor)
      .\setup-and-build.ps1 -Flavor quest       # Quest VR APK
      .\setup-and-build.ps1 -Flavor quest -BuildType Release
      .\setup-and-build.ps1 -SkipInstall        # skip toolchain install, just build

  Notes:
   - The 'mobile' flavor is the original app and is expected to build cleanly.
   - The 'quest' flavor pulls the Meta Spatial SDK. If it fails on the Spatial
     SDK plugin/dependency, reconcile the versions per docs/VR_ARCHITECTURE.md
     (AGP / Gradle / Kotlin / Spatial SDK) and re-run.
   - If you don't have winget, install Git and Temurin JDK 17 manually first;
     this script will detect and reuse them.
#>

[CmdletBinding()]
param(
    [ValidateSet('mobile','quest')]
    [string]$Flavor = 'mobile',

    [ValidateSet('Debug','Release')]
    [string]$BuildType = 'Debug',

    # Skip all toolchain installation and go straight to the build.
    [switch]$SkipInstall
)

$ErrorActionPreference = 'Stop'
$RepoRoot   = $PSScriptRoot
$Sdk        = "$env:LOCALAPPDATA\Android\Sdk"
$NdkVer     = '27.0.12077973'          # must match app/build.gradle ndkVersion
$Platform   = 'platforms;android-34'   # must match compileSdk/targetSdk
$BuildTools = 'build-tools;34.0.0'
# Latest stable "command line tools only" for Windows. If this 404s, grab the
# current link from https://developer.android.com/studio#command-tools
$CmdlineToolsUrl = 'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip'

function Info($m){ Write-Host "==> $m" -ForegroundColor Cyan }
function Warn($m){ Write-Host "!!  $m" -ForegroundColor Yellow }
function Die ($m){ Write-Host "XX  $m" -ForegroundColor Red; exit 1 }

if (-not $RepoRoot) { $RepoRoot = (Get-Location).Path }
if (-not (Test-Path "$RepoRoot\gradlew.bat")) {
    Die "gradlew.bat not found in '$RepoRoot'. Run this script from the moonlight-android repo root."
}

$haveWinget = [bool](Get-Command winget -ErrorAction SilentlyContinue)

# ---------------------------------------------------------------------------
# 1. Git (needed for the native submodule)
# ---------------------------------------------------------------------------
if (-not $SkipInstall) {
    if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
        if ($haveWinget) {
            Info 'Installing Git via winget...'
            winget install --id Git.Git -e --source winget --accept-package-agreements --accept-source-agreements
            $env:Path += ';C:\Program Files\Git\cmd'
        } else {
            Die 'Git not found and winget unavailable. Install Git from https://git-scm.com/download/win and re-run.'
        }
    }
}
if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
    Die 'Git is required but not on PATH.'
}

# ---------------------------------------------------------------------------
# 2. JDK 17 (Temurin)
# ---------------------------------------------------------------------------
$javaHome = $env:JAVA_HOME
if (-not $javaHome -or -not (Test-Path "$javaHome\bin\javac.exe")) {
    # Try to find an already-installed Temurin 17 before installing.
    $existing = Get-ChildItem 'C:\Program Files\Eclipse Adoptium\jdk-17*' -Directory -ErrorAction SilentlyContinue |
                Select-Object -First 1
    if (-not $existing -and -not $SkipInstall) {
        if ($haveWinget) {
            Info 'Installing Temurin JDK 17 via winget...'
            winget install --id EclipseAdoptium.Temurin.17.JDK -e --accept-package-agreements --accept-source-agreements
        } else {
            Die 'JDK 17 not found and winget unavailable. Install Temurin 17 from https://adoptium.net/ and re-run.'
        }
        $existing = Get-ChildItem 'C:\Program Files\Eclipse Adoptium\jdk-17*' -Directory -ErrorAction SilentlyContinue |
                    Select-Object -First 1
    }
    if ($existing) { $javaHome = $existing.FullName }
}
if (-not $javaHome -or -not (Test-Path "$javaHome\bin\javac.exe")) {
    Die 'Could not locate a JDK 17. Set JAVA_HOME to a JDK 17 install and re-run.'
}
$env:JAVA_HOME = $javaHome
$env:Path = "$javaHome\bin;$env:Path"
Info "JAVA_HOME = $javaHome"

# ---------------------------------------------------------------------------
# 3. Android command-line tools
# ---------------------------------------------------------------------------
$cmdlineBin = "$Sdk\cmdline-tools\latest\bin"
if (-not (Test-Path "$cmdlineBin\sdkmanager.bat")) {
    if ($SkipInstall) { Die "Android cmdline-tools missing at $cmdlineBin and -SkipInstall was set." }
    Info 'Downloading Android command-line tools...'
    $zip = "$env:TEMP\cmdline-tools.zip"
    try {
        Invoke-WebRequest $CmdlineToolsUrl -OutFile $zip
    } catch {
        Die "Failed to download command-line tools from $CmdlineToolsUrl. Get the current link from https://developer.android.com/studio#command-tools and set `$CmdlineToolsUrl."
    }
    $tmp = "$env:TEMP\cmdline-extract"
    Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
    Expand-Archive $zip -DestinationPath $tmp -Force
    New-Item -ItemType Directory -Force -Path "$Sdk\cmdline-tools\latest" | Out-Null
    # The zip extracts to '<tmp>\cmdline-tools\*' — move its contents into 'latest'.
    Copy-Item "$tmp\cmdline-tools\*" "$Sdk\cmdline-tools\latest\" -Recurse -Force
}
$env:ANDROID_HOME     = $Sdk
$env:ANDROID_SDK_ROOT = $Sdk
$env:Path = "$cmdlineBin;$Sdk\platform-tools;$env:Path"

# ---------------------------------------------------------------------------
# 4. SDK packages + licenses
# ---------------------------------------------------------------------------
if (-not $SkipInstall) {
    Info 'Accepting SDK licenses...'
    # sdkmanager --licenses asks for several licenses in sequence, so a single 'y'
    # is not enough. Feed it a stream of 'y' answers to accept them all.
    (1..50 | ForEach-Object { 'y' }) | & "$cmdlineBin\sdkmanager.bat" --licenses | Out-Null

    Info 'Installing platform-tools, platform (android-34), build-tools, NDK...'
    & "$cmdlineBin\sdkmanager.bat" "platform-tools" $Platform $BuildTools "ndk;$NdkVer"
    if ($LASTEXITCODE -ne 0) { Die "sdkmanager failed (exit $LASTEXITCODE)." }
}

# ---------------------------------------------------------------------------
# 5. Native submodule (moonlight-common-c) — required for any build
# ---------------------------------------------------------------------------
Info 'Checking out git submodules (moonlight-common-c)...'
Push-Location $RepoRoot
try {
    git submodule update --init --recursive
    if ($LASTEXITCODE -ne 0) { Die "git submodule update failed (exit $LASTEXITCODE)." }
} finally { Pop-Location }

# ---------------------------------------------------------------------------
# 6. local.properties (tells Gradle where the SDK is)
# ---------------------------------------------------------------------------
$sdkDirEscaped = $Sdk -replace '\\','\\'
Set-Content -Path "$RepoRoot\local.properties" -Value "sdk.dir=$sdkDirEscaped" -Encoding ASCII
Info "Wrote local.properties -> sdk.dir=$Sdk"

# ---------------------------------------------------------------------------
# 7. Build
# ---------------------------------------------------------------------------
# Variant name is <root><Device><BuildType>, e.g. nonRootMobileDebug / nonRootQuestDebug.
$deviceCap = (Get-Culture).TextInfo.ToTitleCase($Flavor)
$task = "assembleNonRoot$deviceCap$BuildType"

Info "Building Gradle task: $task"
Push-Location $RepoRoot
try {
    & ".\gradlew.bat" $task --stacktrace
    $code = $LASTEXITCODE
} finally { Pop-Location }

if ($code -ne 0) {
    Warn "Build FAILED (exit $code)."
    if ($Flavor -eq 'quest') {
        Warn "If this is a Meta Spatial SDK plugin/dependency error, reconcile the"
        Warn "AGP / Gradle / Kotlin / Spatial SDK versions per docs/VR_ARCHITECTURE.md,"
        Warn "then re-run:  .\setup-and-build.ps1 -Flavor quest -SkipInstall"
    }
    exit $code
}

$apkDir = "$RepoRoot\app\build\outputs\apk\nonRoot$deviceCap\$($BuildType.ToLower())"
Info "BUILD SUCCEEDED. APK output directory:"
Write-Host "  $apkDir" -ForegroundColor Green
if (Test-Path $apkDir) {
    Get-ChildItem $apkDir -Filter *.apk | ForEach-Object { Write-Host "  $($_.Name)" -ForegroundColor Green }
}
Write-Host ""
Write-Host "To install to a Quest/phone over USB (developer mode enabled):" -ForegroundColor Green
Write-Host "  adb install -r `"$apkDir\<apk-name>.apk`""
