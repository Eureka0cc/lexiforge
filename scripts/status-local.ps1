param([string]$InstanceName='default',[switch]$Json)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'LocalRuntime.ps1')
$paths=Get-LocalRuntimePaths $InstanceName
$status=Get-LocalRuntimeStatus (Read-LocalRuntimeState $paths) $paths
if($Json) { $status | ConvertTo-Json -Depth 5 }
elseif(!$status.running) { Write-Output '本地实例未完整运行' }
else { Write-Output "前端：$($status.frontendUrl)`n后端：$($status.backendUrl)`n健康检查：$($status.healthy)" }
