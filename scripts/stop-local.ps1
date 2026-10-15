param([string]$InstanceName='default')
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'LocalRuntime.ps1')
$paths=Get-LocalRuntimePaths $InstanceName
$mutex=New-LocalRuntimeMutex $paths
try {
    $state=Read-LocalRuntimeState $paths
    if(!$state) { Write-Output '本地实例未运行'; return }
    Stop-OwnedLocalRuntime $state $paths
    Remove-Item -LiteralPath $paths.State
    Write-Output '本地实例已停止；数据库和持久化本地密钥保留。'
} finally { $mutex.ReleaseMutex(); $mutex.Dispose() }
