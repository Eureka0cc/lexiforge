param(
    [string]$InstanceName='default',
    [string]$DatabaseUrl=$(if($env:DB_URL) { $env:DB_URL } else { 'jdbc:postgresql://localhost:5432/lexiforge' }),
    [string]$DatabaseUser=$(if($env:DB_USERNAME) { $env:DB_USERNAME } else { 'postgres' }),
    [string]$DatabasePassword=$(if($env:DB_PASSWORD) { $env:DB_PASSWORD } else { 'postgres' }),
    [ValidateRange(1024,65535)][int]$BackendPort=8080,
    [ValidateRange(1024,65535)][int]$FrontendPort=5173,
    [switch]$SkipBuild,[switch]$DisableAI,[switch]$UnlimitedUsage
)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'LocalRuntime.ps1')
$paths=Get-LocalRuntimePaths $InstanceName
$mutex=New-LocalRuntimeMutex $paths
$previous=@{}; $state=$null; $started=@(); $success=$false
$backend=$null; $frontend=$null
$settings=ConvertTo-Json -Compress -InputObject @($DatabaseUrl,$DatabaseUser,$DisableAI.IsPresent,$UnlimitedUsage.IsPresent)
$settingsHash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($settings)))
function Available-Port([int]$preferred,[bool]$allowFallback) {
    $listener=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,$preferred)
    try { $listener.Start(); return $preferred }
    catch {
        if(!$allowFallback) { throw "端口 $preferred 已占用，请指定其他端口" }
        $fallback=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0)
        try { $fallback.Start(); return $fallback.LocalEndpoint.Port } finally { $fallback.Stop() }
    } finally { $listener.Stop() }
}
function Random-Secret { return [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(48)) }
Push-Location $paths.Repo
try {
    $state=Read-LocalRuntimeState $paths
    if($state) {
        $status=Get-LocalRuntimeStatus $state $paths
        if($status.running -and $status.healthy) {
            if($state.settingsHash -ne $settingsHash -or ($PSBoundParameters.ContainsKey('BackendPort') -and $state.backendUrl -ne "http://127.0.0.1:$BackendPort") -or ($PSBoundParameters.ContainsKey('FrontendPort') -and $state.frontendUrl -ne "http://127.0.0.1:$FrontendPort")) {
                throw '当前实例使用其他配置，请先 stop-local.ps1 再启动'
            }
            Write-Output "实例已运行：$($status.frontendUrl)"; $success=$true; return
        }
        Stop-OwnedLocalRuntime $state $paths
        Remove-Item -LiteralPath $paths.State
        $state=$null
    }
    [IO.Directory]::CreateDirectory($paths.Directory)|Out-Null
    $BackendPort=Available-Port $BackendPort (!$PSBoundParameters.ContainsKey('BackendPort'))
    $FrontendPort=Available-Port $FrontendPort (!$PSBoundParameters.ContainsKey('FrontendPort'))
    if($BackendPort -eq $FrontendPort) { throw '前后端端口不能相同' }
    if(!(Test-Path -LiteralPath $paths.Secrets)) {
        @{ jwt=(Random-Secret); mail=(Random-Secret); billing=(Random-Secret) } | ConvertTo-Json |
            Set-Content -LiteralPath $paths.Secrets -Encoding utf8
    }
    $secrets=Get-Content -LiteralPath $paths.Secrets -Raw | ConvertFrom-Json
    foreach($key in @('jwt','mail','billing')) { if(([string]$secrets.$key).Length -lt 32) { throw '本地持久化密钥格式不正确，请检查 tmp 中的 secrets.json' } }
    if(!$SkipBuild) {
        & mvn -B -DskipTests package *> (Join-Path $paths.Directory 'build.log')
        if($LASTEXITCODE -ne 0) { throw '后端构建失败，查看实例 build.log' }
    }
    if(!(Test-Path -LiteralPath $paths.Jar)) { throw '未找到 jar，请去掉 -SkipBuild 重新启动' }
    if(!(Test-Path -LiteralPath $paths.Vite)) {
        Push-Location (Join-Path $paths.Repo 'frontend')
        try { & npm ci *> (Join-Path $paths.Directory 'dependencies.log'); if($LASTEXITCODE -ne 0) { throw '前端依赖安装失败' } }
        finally { Pop-Location }
    }
    $frontendUrl="http://127.0.0.1:$FrontendPort"; $backendUrl="http://127.0.0.1:$BackendPort"
    $variables=@{ DB_URL=$DatabaseUrl; DB_USERNAME=$DatabaseUser; DB_PASSWORD=$DatabasePassword;
        SPRING_PROFILES_ACTIVE='dev,local'; FRONTEND_ORIGIN=$frontendUrl; JWT_SECRET=$secrets.jwt;
        AUTH_MAIL_MODE='file'; AUTH_MAIL_SECRET=$secrets.mail; BILLING_MODE='local-sandbox'; BILLING_SECRET=$secrets.billing;
        VITE_API_PROXY_TARGET=$backendUrl }
    foreach($name in $variables.Keys) { $previous[$name]=[Environment]::GetEnvironmentVariable($name,'Process'); [Environment]::SetEnvironmentVariable($name,$variables[$name],'Process') }
    $arguments=@('-jar',('"'+$paths.Jar+'"'),"--server.port=$BackendPort",'--server.address=127.0.0.1',"--app.development-unlimited-usage=$($UnlimitedUsage.IsPresent.ToString().ToLowerInvariant())")
    if($DisableAI) { $arguments+=@('--app.ai.enabled=false','--spring.ai.model.chat=none') }
    $backend=Start-Process -FilePath (Get-Command java).Source -ArgumentList $arguments -WorkingDirectory $paths.Repo -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $paths.Directory 'backend.log') -RedirectStandardError (Join-Path $paths.Directory 'backend.err.log')
    $started+=New-LocalProcessRecord 'backend' $backend
    $frontend=Start-Process -FilePath (Get-Command node).Source -ArgumentList @(('"'+$paths.Vite+'"'),'--configLoader','runner','--host','127.0.0.1','--port',"$FrontendPort",'--strictPort') -WorkingDirectory (Join-Path $paths.Repo 'frontend') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $paths.Directory 'frontend.log') -RedirectStandardError (Join-Path $paths.Directory 'frontend.err.log')
    $started+=New-LocalProcessRecord 'frontend' $frontend
    $state=@{ version=1; repo=$paths.Repo; instance=$InstanceName; generation=[Guid]::NewGuid().ToString();
        frontendUrl=$frontendUrl; backendUrl=$backendUrl; settingsHash=$settingsHash; processes=$started; jarSha256=(Get-FileHash -LiteralPath $paths.Jar -Algorithm SHA256).Hash }
    $state|ConvertTo-Json -Depth 5|Set-Content -LiteralPath $paths.State -Encoding utf8
    for($attempt=0;$attempt -lt 90;$attempt++) {
        if($backend.HasExited -or $frontend.HasExited) { throw '本地进程提前退出，查看实例日志' }
        try {
            if((Invoke-RestMethod "$backendUrl/actuator/health" -TimeoutSec 2).status -eq 'UP' -and
                (Invoke-WebRequest $frontendUrl -TimeoutSec 2).StatusCode -eq 200) { $success=$true; break }
        } catch { }
        Start-Sleep -Seconds 1
    }
    if(!$success) { throw '本地启动超时，查看实例日志和 PostgreSQL 状态' }
    Write-Output "本地实例已就绪：$frontendUrl`n后端：$backendUrl`n日志和状态：$($paths.Directory)"
} finally {
    if(!$success -and ($backend -or $frontend)) {
        # These are the original handles created in this invocation, before state-file publication.
        foreach($process in @($frontend,$backend)) { if($process -and !$process.HasExited) { $process.Kill($true); $process.WaitForExit(10000)|Out-Null } }
        if(Test-Path -LiteralPath $paths.State) { Remove-Item -LiteralPath $paths.State }
    }
    foreach($name in $previous.Keys) { [Environment]::SetEnvironmentVariable($name,$previous[$name],'Process') }
    Pop-Location
    $mutex.ReleaseMutex(); $mutex.Dispose()
}
