# Shared local process ownership checks. Runtime data belongs only in workspace tmp/.
function Get-LocalRuntimePaths([string]$InstanceName) {
    if($InstanceName -notmatch '^[a-z0-9-]{1,40}$') { throw '实例名仅允许小写字母、数字和短横线' }
    $repo=[IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
    $directory=[IO.Path]::GetFullPath((Join-Path $repo "../tmp/local/$InstanceName"))
    return @{ Repo=$repo; Directory=$directory; State=(Join-Path $directory 'runtime.json'); Secrets=(Join-Path $directory 'secrets.json');
        Jar=[IO.Path]::GetFullPath((Join-Path $repo '../tmp/backend/lexiforge-0.1.0-SNAPSHOT.jar'));
        Vite=(Join-Path $repo 'frontend/node_modules/vite/bin/vite.js'); Instance=$InstanceName }
}
function Read-LocalRuntimeState($paths) {
    if(!(Test-Path -LiteralPath $paths.State)) { return $null }
    $state=Get-Content -LiteralPath $paths.State -Raw | ConvertFrom-Json
    if($state.version -ne 1 -or $state.repo -ne $paths.Repo -or $state.instance -ne $paths.Instance) { throw '运行状态不属于当前项目/实例，拒绝操作进程' }
    foreach($url in @($state.backendUrl,$state.frontendUrl)) { if($url -notmatch '^http://127\.0\.0\.1:[0-9]{4,5}$') { throw '运行状态中的地址必须为本机地址' } }
    return $state
}
function Get-OwnedLocalProcess($record,$paths) {
    if($record.role -notin @('backend','frontend') -or [int]$record.processId -le 0) { throw '无效的本地进程记录' }
    $process=Get-Process -Id ([int]$record.processId) -ErrorAction SilentlyContinue
    if(!$process) { return $null }
    if($process.StartTime.ToUniversalTime().Ticks.ToString() -ne [string]$record.startedUtcTicks -or $process.Path -ne $record.executable) {
        throw '进程编号已被复用，拒绝停止不属于本实例的进程'
    }
    $expected=if($record.role -eq 'backend') { $paths.Jar } else { $paths.Vite }
    $expectedName=if($record.role -eq 'backend') { 'java' } else { 'node' }
    if($process.ProcessName -ne $expectedName) { throw '进程类型与本地运行记录不一致' }
    $command=(Get-CimInstance Win32_Process -Filter "ProcessId = $($process.Id)" -ErrorAction Stop).CommandLine
    if(!$command -or !$command.Contains($expected,[StringComparison]::OrdinalIgnoreCase)) { throw '进程命令不属于本项目，拒绝操作' }
    return $process
}
function New-LocalProcessRecord([string]$role,$process) {
    # Start-Process may return an object whose Path has not been populated on Windows.
    for($attempt=0;$attempt -lt 20;$attempt++) {
        $live=Get-Process -Id $process.Id -ErrorAction Stop
        if($live.Path) { return @{ role=$role; processId=$live.Id; startedUtcTicks=$live.StartTime.ToUniversalTime().Ticks.ToString(); executable=$live.Path } }
        Start-Sleep -Milliseconds 100
    }
    throw '无法读取新进程的可执行文件路径'
}
function Stop-OwnedLocalRuntime($state,$paths) {
    # Verify every record before stopping any process.
    $owned=@($state.processes | ForEach-Object { Get-OwnedLocalProcess $_ $paths })
    foreach($process in $owned) {
        if($process -and !$process.HasExited) { $process.Kill($true); if(!$process.WaitForExit(10000)) { throw '本地进程未能停止' } }
    }
}
function New-LocalRuntimeMutex($paths) {
    $bytes=[Text.Encoding]::UTF8.GetBytes($paths.Repo+'|'+$paths.Instance)
    $key=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).Substring(0,24)
    $mutex=[Threading.Mutex]::new($false,"Local\LexiForge-$key")
    if(!$mutex.WaitOne(0)) { $mutex.Dispose(); throw '当前实例已有启动/停止操作正在进行' }
    return $mutex
}
function Get-LocalRuntimeStatus($state,$paths) {
    if(!$state) { return @{ running=$false; healthy=$false; instance=$paths.Instance } }
    $alive=@($state.processes | ForEach-Object { $process=Get-OwnedLocalProcess $_ $paths; if($process -and !$process.HasExited) { $_ } })
    $healthy=$false
    if($alive.Count -eq 2) {
        try { $healthy=(Invoke-RestMethod "$($state.backendUrl)/actuator/health" -TimeoutSec 2).status -eq 'UP' -and (Invoke-WebRequest $state.frontendUrl -TimeoutSec 2).StatusCode -eq 200 } catch { }
    }
    return @{ running=($alive.Count -eq 2); healthy=$healthy; instance=$paths.Instance; generation=$state.generation;
        frontendUrl=$state.frontendUrl; backendUrl=$state.backendUrl; processes=$alive; jarSha256=$state.jarSha256 }
}
