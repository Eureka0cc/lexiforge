param([int]$DatabasePort=5432,[string]$DatabaseUser='postgres',[string]$DatabasePassword='postgres')
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'LocalRuntime.ps1')
$suffix=[Guid]::NewGuid().ToString('N').Substring(0,12)
$instance="verify-$suffix"; $database="lexiforge_test_local_$suffix"
$paths=Get-LocalRuntimePaths $instance
$scratch=[IO.Path]::GetFullPath((Join-Path $paths.Repo '../tmp/local-runtime-acceptance'))
[IO.Directory]::CreateDirectory($scratch)|Out-Null
$psql='C:\Program Files\PostgreSQL\16\bin\psql.exe'
$oldPassword=$env:PGPASSWORD; $env:PGPASSWORD=$DatabasePassword
$created=$false; $started=[DateTimeOffset]::UtcNow
function Sql([string]$query) {
    $value=& $psql -h localhost -p $DatabasePort -U $DatabaseUser -d postgres -At -v ON_ERROR_STOP=1 -c $query
    if($LASTEXITCODE -ne 0) { throw '本地启动验收 SQL 失败' }; return "$value".Trim()
}
function Api([string]$path,$body=$null) {
    $arguments=@{ Uri="$($script:runtime.frontendUrl)$path"; Headers=@{ Origin=$script:runtime.frontendUrl }; TimeoutSec=15 }
    if($script:access) { $arguments.Headers.Authorization="Bearer $script:access" }
    if($null -ne $body) { $arguments.Method='POST'; $arguments.Body=ConvertTo-Json -InputObject $body; $arguments.ContentType='application/json' }
    return (Invoke-RestMethod @arguments).data
}
try {
    if($database -notmatch '^lexiforge_test_local_[a-f0-9]{12}$' -or (Sql "SELECT count(*) FROM pg_database WHERE datname='$database'") -ne '0') { throw '验收库名称冲突' }
    Sql "CREATE DATABASE $database TEMPLATE template0 ENCODING 'UTF8'"|Out-Null; $created=$true
    $backendProbe=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0); $backendProbe.Start(); $backendPort=$backendProbe.LocalEndpoint.Port; $backendProbe.Stop()
    do { $frontendProbe=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0); $frontendProbe.Start(); $frontendPort=$frontendProbe.LocalEndpoint.Port; $frontendProbe.Stop() } while($frontendPort -eq $backendPort)
    $arguments=@{ InstanceName=$instance; DatabaseUrl="jdbc:postgresql://localhost:$DatabasePort/$database"; DatabaseUser=$DatabaseUser; DatabasePassword=$DatabasePassword; SkipBuild=$true; DisableAI=$true; BackendPort=$backendPort; FrontendPort=$frontendPort }
    & (Join-Path $PSScriptRoot 'start-local.ps1') @arguments | Out-Null
    $script:runtime=& (Join-Path $PSScriptRoot 'status-local.ps1') -InstanceName $instance -Json | ConvertFrom-Json
    if(!$runtime.running -or !$runtime.healthy) { throw '本地启动未就绪' }
    $firstGeneration=$runtime.generation
    $secretsHash=(Get-FileHash -LiteralPath $paths.Secrets -Algorithm SHA256).Hash
    $account=Api '/api/auth/register/v1' @{ email="local-$suffix@example.test"; password='Acceptance123!'; displayName='本地入口验收' }
    $script:access=$account.accessToken
    if((Api '/api/account/v1').email -ne $account.user.email) { throw '前端代理或认证不可用' }
    & (Join-Path $PSScriptRoot 'start-local.ps1') @arguments | Out-Null
    $same=& (Join-Path $PSScriptRoot 'status-local.ps1') -InstanceName $instance -Json | ConvertFrom-Json
    if($same.generation -ne $firstGeneration) { throw '重复启动不应重建已运行实例' }
    & (Join-Path $PSScriptRoot 'stop-local.ps1') -InstanceName $instance | Out-Null
    & (Join-Path $PSScriptRoot 'stop-local.ps1') -InstanceName $instance | Out-Null
    $stopped=& (Join-Path $PSScriptRoot 'status-local.ps1') -InstanceName $instance -Json | ConvertFrom-Json
    if($stopped.running) { throw '停止后仍报告完整运行' }
    & (Join-Path $PSScriptRoot 'start-local.ps1') @arguments | Out-Null
    $script:runtime=& (Join-Path $PSScriptRoot 'status-local.ps1') -InstanceName $instance -Json | ConvertFrom-Json
    if((Get-FileHash -LiteralPath $paths.Secrets -Algorithm SHA256).Hash -ne $secretsHash) { throw '重启不应轮换本地持久化密钥' }
    if((Api '/api/account/v1').email -ne $account.user.email) { throw '重启后的原有会话不可用' }
    @{ passed=$true; runtimeStarts=2; customPorts=$true; idempotentStart=$true; idempotentStop=$true; frontendProxy=$true; sessionSurvivesRestart=$true;
        secretsPersisted=$true; jarSha256=$runtime.jarSha256; instance=$instance; elapsedSeconds=([DateTimeOffset]::UtcNow-$started).TotalSeconds } |
        ConvertTo-Json | Set-Content -LiteralPath (Join-Path $scratch 'result.json') -Encoding utf8
    Write-Output 'PASS: 本地前后端启动、代理、认证、重复启动/停止与持久化密钥重启均通过。'
} finally {
    & (Join-Path $PSScriptRoot 'stop-local.ps1') -InstanceName $instance | Out-Null
    if($created -and $database -match '^lexiforge_test_local_[a-f0-9]{12}$') { Sql "DROP DATABASE $database"|Out-Null }
    $env:PGPASSWORD=$oldPassword
}
