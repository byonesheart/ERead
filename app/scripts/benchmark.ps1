[CmdletBinding()]
param(
    [string]$Serial,
    [switch]$BuildOnly,
    [switch]$Reset
)

$ErrorActionPreference = 'Stop'
$taskAndroidRoot = Split-Path -Parent $PSScriptRoot
$taskAdb = Join-Path $taskAndroidRoot '.tools/android-sdk/platform-tools/adb.exe'
$taskPreviousSerial = $env:ANDROID_SERIAL
$taskBenchmarkPackage = 'com.eyue.reader.benchmark'
$taskTasks = @(':app:assembleBenchmark', ':benchmark:assembleBenchmark')
try {
    if (-not $BuildOnly) {
        if (-not (Test-Path -LiteralPath $taskAdb)) { throw '请先安装项目的 Android 工具链。' }
        if ([string]::IsNullOrWhiteSpace($Serial)) {
            $taskDevices = @(& $taskAdb devices | ForEach-Object {
                if ($_ -match '^(\S+)\s+device$') { $Matches[1] }
            })
            if ($taskDevices.Count -ne 1) { throw '请连接并授权一台真机，或使用 -Serial 指定设备。' }
            $Serial = $taskDevices[0]
        }
        $env:ANDROID_SERIAL = $Serial
        $taskState = (& $taskAdb -s $Serial get-state 2>$null) -join ''
        if ($LASTEXITCODE -ne 0 -or $taskState.Trim() -ne 'device') { throw '指定设备未连接或未授权。' }
        $taskApi = (& $taskAdb -s $Serial shell getprop ro.build.version.sdk) -join ''
        if ([int]$taskApi.Trim() -lt 34) { throw '此基线场景需要 Android 14 或更新系统。' }
        $taskEmulator = (& $taskAdb -s $Serial shell getprop ro.kernel.qemu) -join ''
        if ($taskEmulator.Trim() -eq '1') { throw '模拟器数据不作为真机性能基线，请改用真机。' }
        if ($Reset) {
            $taskInstalled = (& $taskAdb -s $Serial shell pm path $taskBenchmarkPackage) -join ''
            if ($taskInstalled.StartsWith('package:')) {
                $taskCleared = (& $taskAdb -s $Serial shell pm clear $taskBenchmarkPackage) -join ''
                if ($taskCleared.Trim() -ne 'Success') { throw '性能测试专用书库重置失败。' }
            }
        }
        $taskTasks += ':benchmark:connectedBenchmarkAndroidTest'
    }
    & (Join-Path $PSScriptRoot 'build.ps1') -Tasks $taskTasks
    if (-not $?) { throw '性能测试构建或执行失败。' }
    if ($BuildOnly) { Write-Output '仅完成性能测试包构建，尚未生成任何性能基线。' }
    else { Write-Output '请在 app/benchmark/build/outputs/ 中查看 JSON 指标与 Perfetto trace，并记录设备和测试条件。' }
} finally {
    [Environment]::SetEnvironmentVariable('ANDROID_SERIAL', $taskPreviousSerial, 'Process')
}
