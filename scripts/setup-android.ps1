[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$toolsRoot = Join-Path $projectRoot '.tools'
$downloadsRoot = Join-Path $toolsRoot 'downloads'
$jdkRoot = Join-Path $toolsRoot 'jdk'
$sdkRoot = Join-Path $toolsRoot 'android-sdk'
$gradleRoot = Join-Path $toolsRoot 'gradle-8.11.1'

# All tools stay in the project. Environment variables apply to this process only.
foreach ($directory in @($toolsRoot, $downloadsRoot, $jdkRoot, $sdkRoot)) {
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
}

function Assert-ToolPath([string]$Path) {
    $absolutePath = [IO.Path]::GetFullPath($Path)
    $absoluteRoot = [IO.Path]::GetFullPath($toolsRoot) + [IO.Path]::DirectorySeparatorChar
    if ($absolutePath -ne [IO.Path]::GetFullPath($toolsRoot) -and
        -not $absolutePath.StartsWith($absoluteRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Tool path is outside the workspace tool directory: $absolutePath"
    }
}

function Get-VerifiedArchive([string]$Url, [string]$Filename, [string]$Sha256) {
    $archivePath = Join-Path $downloadsRoot $Filename
    if (Test-Path -LiteralPath $archivePath) {
        if ((Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash -eq $Sha256) {
            return $archivePath
        }
    }
    Write-Host "Downloading $Filename from its official source..."
    & curl.exe --fail --location --retry 3 --output $archivePath $Url
    if ($LASTEXITCODE -ne 0) { throw "Download failed: $Filename" }
    if ((Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash -ne $Sha256) {
        throw "SHA-256 verification failed: $Filename"
    }
    return $archivePath
}

function Expand-ToolArchive([string]$Archive, [string]$Destination) {
    Assert-ToolPath $Destination
    if (Test-Path -LiteralPath 'D:\Software\7-Zip\7z.exe') {
        & 'D:\Software\7-Zip\7z.exe' x $Archive "-o$Destination" -y | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Archive extraction failed: $Archive" }
    } else {
        Expand-Archive -LiteralPath $Archive -DestinationPath $Destination -Force
    }
}

if (-not (Test-Path -LiteralPath (Join-Path $jdkRoot 'bin\java.exe'))) {
    $jdkArchive = Get-VerifiedArchive `
        'https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20.1%2B1/OpenJDK17U-jdk_x64_windows_hotspot_17.0.20.1_1.zip' `
        'temurin17.zip' 'e53a79c3c3d86865bd7e787903884331068e71321714ffd44f145785affc7cb0'
    $jdkStaging = Join-Path $toolsRoot 'unpacked-jdk'
    Expand-ToolArchive $jdkArchive $jdkStaging
    $jdkContents = Join-Path $jdkStaging 'jdk-17.0.20.1+1'
    Assert-ToolPath $jdkContents
    Assert-ToolPath $jdkRoot
    Get-ChildItem -LiteralPath $jdkContents -Force | ForEach-Object {
        Copy-Item -LiteralPath $_.FullName -Destination $jdkRoot -Recurse -Force
    }
}

if (-not (Test-Path -LiteralPath (Join-Path $gradleRoot 'bin\gradle.bat'))) {
    $gradleArchive = Get-VerifiedArchive `
        'https://downloads.gradle.org/distributions/gradle-8.11.1-bin.zip' `
        'gradle-8.11.1-bin.zip' 'f397b287023acdba1e9f6fc5ea72d22dd63669d59ed4a289a29b1a76eee151c6'
    Expand-ToolArchive $gradleArchive $toolsRoot
}

$sdkManager = Join-Path $sdkRoot 'cmdline-tools\latest\bin\sdkmanager.bat'
if (-not (Test-Path -LiteralPath $sdkManager)) {
    $sdkArchive = Get-VerifiedArchive `
        'https://dl.google.com/android/repository/commandlinetools-win-15859902_latest.zip' `
        'commandlinetools-win.zip' '90ae805d20434428bffcb699c290860f19bb5f66a67e6b330067e3de801fb04a'
    $sdkStaging = Join-Path $toolsRoot 'unpacked-sdk'
    Expand-ToolArchive $sdkArchive $sdkStaging
    $commandLineSource = Join-Path $sdkStaging 'cmdline-tools'
    $commandLineDestination = Join-Path $sdkRoot 'cmdline-tools\latest'
    Assert-ToolPath $commandLineSource
    Assert-ToolPath $commandLineDestination
    New-Item -ItemType Directory -Path $commandLineDestination -Force | Out-Null
    Get-ChildItem -LiteralPath $commandLineSource -Force | ForEach-Object {
        Copy-Item -LiteralPath $_.FullName -Destination $commandLineDestination -Recurse -Force
    }
}

$env:JAVA_HOME = $jdkRoot
$env:ANDROID_HOME = $sdkRoot
$env:ANDROID_SDK_ROOT = $sdkRoot
$env:GRADLE_USER_HOME = Join-Path $toolsRoot 'gradle-home'
$env:PATH = "$jdkRoot\bin;$sdkRoot\platform-tools;$gradleRoot\bin;$env:PATH"

$sdkFiles = @('platforms\android-35\android.jar', 'build-tools\35.0.0\aapt2.exe', 'platform-tools\adb.exe')
$missingSdkFiles = $sdkFiles | Where-Object { -not (Test-Path -LiteralPath (Join-Path $sdkRoot $_)) }
if ($missingSdkFiles) {
    1..20 | ForEach-Object { 'y' } | & $sdkManager "--sdk_root=$sdkRoot" --licenses |
        Out-File -LiteralPath (Join-Path $toolsRoot 'sdk-licenses.log') -Encoding utf8
    if ($LASTEXITCODE -ne 0) { throw 'Android SDK license setup failed; see .tools/sdk-licenses.log.' }
    & $sdkManager "--sdk_root=$sdkRoot" 'platforms;android-35' 'build-tools;35.0.0' 'platform-tools'
    if ($LASTEXITCODE -ne 0) { throw 'Android SDK installation failed.' }
}

foreach ($stagingName in @('unpacked-jdk', 'unpacked-sdk')) {
    $stagingPath = Join-Path $toolsRoot $stagingName
    Assert-ToolPath $stagingPath
    if (Test-Path -LiteralPath $stagingPath) {
        Remove-Item -LiteralPath $stagingPath -Recurse -Force
    }
}

Write-Host "Android toolchain ready. JAVA_HOME=$jdkRoot"
Write-Host "ANDROID_HOME=$sdkRoot"
Write-Host "GRADLE_USER_HOME=$env:GRADLE_USER_HOME"
Write-Host 'Run . ./scripts/setup-android.ps1 to keep these variables in the current PowerShell session.'
