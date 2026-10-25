[CmdletBinding()]
param(
    [string[]]$Tasks = @('testDebugUnitTest', 'assembleDebug', 'lintDebug')
)

$ErrorActionPreference = 'Stop'
$buildProjectRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot)).TrimEnd('\')
$buildRepositoryRoot = Split-Path -Parent $buildProjectRoot
$buildRoot = $buildProjectRoot
$aliasDrive = $null
$aliasCreated = $false
$locationPushed = $false
$originalEnvironment = @{}
foreach ($name in @('JAVA_HOME', 'ANDROID_HOME', 'ANDROID_SDK_ROOT', 'GRADLE_USER_HOME', 'PATH')) {
    $originalEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
$localPropertiesPath = Join-Path $buildProjectRoot 'local.properties'
$originalLocalProperties = if (Test-Path -LiteralPath $localPropertiesPath) {
    [IO.File]::ReadAllBytes($localPropertiesPath)
} else { $null }

# Java worker argument files on Windows need an ASCII build/cache path.
# QueryDosDeviceW verifies an existing SUBST target without code-page conversion.
if (-not ('EReadBuildDriveNative' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.Text;
using System.Runtime.InteropServices;
public static class EReadBuildDriveNative {
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    public static extern uint QueryDosDevice(string device, StringBuilder target, int capacity);
}
'@
}
function Get-DriveTarget([string]$Drive) {
    $buffer = [Text.StringBuilder]::new(32768)
    if ([EReadBuildDriveNative]::QueryDosDevice($Drive, $buffer, $buffer.Capacity) -eq 0) {
        return $null
    }
    return $buffer.ToString()
}

try {
    if ($buildProjectRoot -match '[^\x00-\x7F]') {
        $expectedTarget = '\??\' + $buildProjectRoot
        foreach ($letter in @('R', 'S', 'T', 'U', 'V', 'X', 'Y', 'Z', 'Q', 'P', 'O', 'N', 'M', 'L', 'K', 'J', 'I', 'F')) {
            $drive = "${letter}:"
            $target = Get-DriveTarget $drive
            if ($target -eq $expectedTarget) {
                $aliasDrive = $drive
                break
            }
            if ($null -eq $target -and -not [IO.Directory]::Exists("$drive\")) {
                & subst.exe $drive $buildProjectRoot
                if ($LASTEXITCODE -ne 0) { throw "Cannot create temporary build alias $drive" }
                if ((Get-DriveTarget $drive) -ne $expectedTarget) {
                    throw "Temporary build alias $drive did not resolve to this project."
                }
                $aliasDrive = $drive
                $aliasCreated = $true
                break
            }
        }
        if ($null -eq $aliasDrive) { throw 'No free drive letter is available for an ASCII build path.' }
        $buildRoot = "$aliasDrive\"
    }

    Push-Location -LiteralPath $buildRoot
    $locationPushed = $true
    & (Join-Path $buildRoot 'scripts\setup-android.ps1')
    $sdkProperty = 'sdk.dir=' + $env:ANDROID_HOME.Replace('\', '/').Replace(':', '\:')
    [IO.File]::WriteAllText($localPropertiesPath, $sdkProperty + "`n", [Text.UTF8Encoding]::new($false))
    & (Join-Path $buildRoot '.tools\gradle-9.7.0\bin\gradle.bat') @Tasks `
        --no-daemon --console=plain --max-workers=2 `
        --project-prop 'org.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8' `
        --project-prop 'kotlin.compiler.execution.strategy=in-process'
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }

    $apkPath = Join-Path $buildProjectRoot 'app\build\outputs\apk\debug\app-debug.apk'
    if ('assembleDebug' -in $Tasks -and (Test-Path -LiteralPath $apkPath)) {
        $artifactRoot = Join-Path $buildRepositoryRoot 'release\android'
        New-Item -ItemType Directory -Path $artifactRoot -Force | Out-Null
        $versionSource = [IO.File]::ReadAllText((Join-Path $buildProjectRoot 'app\build.gradle.kts'))
        $artifactVersion = [regex]::Match($versionSource, 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
        if ([string]::IsNullOrWhiteSpace($artifactVersion)) { throw 'Cannot read the APK version name.' }
        Copy-Item -LiteralPath $apkPath -Destination (Join-Path $artifactRoot "ERead-$artifactVersion-debug.apk") -Force
    }
} finally {
    if ($null -eq $originalLocalProperties) {
        if (Test-Path -LiteralPath $localPropertiesPath) {
            Remove-Item -LiteralPath $localPropertiesPath -Force
        }
    } else {
        [IO.File]::WriteAllBytes($localPropertiesPath, $originalLocalProperties)
    }
    foreach ($name in $originalEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $originalEnvironment[$name], 'Process')
    }
    if ($locationPushed) { Pop-Location }
    if ($aliasCreated) {
        if ((Get-DriveTarget $aliasDrive) -eq ('\??\' + $buildProjectRoot)) {
            & subst.exe $aliasDrive /D
        } else {
            Write-Warning "Build alias $aliasDrive changed; leaving it in place."
        }
    }
}
