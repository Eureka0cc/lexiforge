param(
    [string]$DatabaseUrl = $env:LEXIFORGE_TEST_DB_URL,
    [string]$DatabaseUser = 'postgres',
    [string]$DatabasePassword = 'postgres'
)
$ErrorActionPreference = 'Stop'
if ($DatabaseUrl -notmatch '^jdbc:postgresql://localhost:(\d+)/(lexiforge_(?:acceptance|test)[a-zA-Z0-9_]*)$') {
    throw '只允许连接 localhost 上明确命名的独立验收数据库'
}
$databasePort = $Matches[1]
$databaseName = $Matches[2]
$repoRoot = Split-Path -Parent $PSScriptRoot
$scratch = [IO.Path]::GetFullPath((Join-Path $repoRoot '../tmp/billing-restart'))
[IO.Directory]::CreateDirectory($scratch) | Out-Null
$jar = [IO.Path]::GetFullPath((Join-Path $repoRoot '../tmp/backend/lexiforge-0.1.0-SNAPSHOT.jar'))
if (-not (Test-Path -LiteralPath $jar)) { throw '请先运行 mvn -B -Pintegration verify 生成当前版本 jar' }
$java = (Get-Command java).Source
$probe = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0)
$probe.Start(); $port = $probe.LocalEndpoint.Port; $probe.Stop()
$base = "http://127.0.0.1:$port"
$script:server = $null
$script:boot = 0
$userId = $null
function Stop-FixtureServer {
    if ($script:server -and -not $script:server.HasExited) {
        $script:server.Kill($true)
        if (-not $script:server.WaitForExit(10000)) { throw '验收后端进程未能停止' }
    }
}
function Start-FixtureServer {
    $script:boot++
    $script:server = Start-Process -FilePath $java -ArgumentList @('-jar', $jar, "--server.port=$port", '--server.address=127.0.0.1', '--app.billing.reconcile-ms=3600000') -WorkingDirectory $repoRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $scratch "boot-$script:boot.log") -RedirectStandardError (Join-Path $scratch "boot-$script:boot.err.log")
    for ($attempt=0; $attempt -lt 90; $attempt++) {
        if ($script:server.HasExited) { throw "验收后端提前退出，查看 tmp/billing-restart/boot-$script:boot.log" }
        try {
            $health = Invoke-RestMethod "$base/actuator/health" -TimeoutSec 2
            if ($health.status -eq 'UP') { return }
        } catch { }
        Start-Sleep -Seconds 1
    }
    throw '验收后端启动超时'
}
function Api([string]$method,[string]$path,$body=$null) {
    $arguments = @{ Uri="$base$path"; Method=$method; TimeoutSec=15; Headers=@{} }
    if ($script:access) { $arguments.Headers.Authorization="Bearer $script:access" }
    if ($null -ne $body) { $arguments.Body=ConvertTo-Json -InputObject $body -Compress; $arguments.ContentType='application/json' }
    return (Invoke-RestMethod @arguments).data
}
$variables = @{ DB_URL=$DatabaseUrl; DB_USERNAME=$DatabaseUser; DB_PASSWORD=$DatabasePassword;
    SPRING_PROFILES_ACTIVE='test'; AI_ENABLED='false'; AI_CHAT_MODEL='none';
    JWT_SECRET='fixture-only-restart-jwt-secret-0123456789'; AUTH_MAIL_MODE='disabled';
    BILLING_MODE='local-sandbox'; BILLING_SECRET='fixture-only-restart-billing-secret-0123456789'; PGPASSWORD=$DatabasePassword }
$previous = @{}
foreach ($name in $variables.Keys) { $previous[$name]=[Environment]::GetEnvironmentVariable($name,'Process'); [Environment]::SetEnvironmentVariable($name,$variables[$name],'Process') }
Push-Location $repoRoot
try {
    Start-FixtureServer
    $account = Api 'POST' '/api/auth/register/v1' @{ email="restart-$([Guid]::NewGuid().ToString('N'))@example.test"; password='Acceptance123!'; displayName='重启验收' }
    $script:access=$account.accessToken; $userId=$account.user.id
    $order = Api 'POST' '/api/billing/orders/v1' @{ requestId=[Guid]::NewGuid().ToString(); cycle='MONTH'; channel='ALIPAY_SIMULATOR' }
    $id=$order.id
    $pending = Api 'POST' "/api/billing/orders/$id/sandbox-pay/v1?notify=false"
    if ($pending.status -ne 'PENDING') { throw '丢失支付通知前置条件不成立' }
    Stop-FixtureServer
    Start-FixtureServer
    $paid = Api 'GET' "/api/billing/orders/$id/v1"
    $pro = Api 'GET' '/api/subscriptions/v1'
    if ($paid.status -ne 'PAID' -or $pro.status -ne 'ACTIVE' -or $pro.planCode -ne 'PRO') { throw '重启后未恢复已支付权益' }
    $refund = Api 'POST' "/api/billing/orders/$id/sandbox-refund/v1?notify=false"
    if ($refund.status -ne 'PAID') { throw '丢失退款通知前置条件不成立' }
    Stop-FixtureServer
    Start-FixtureServer
    $refunded = Api 'GET' "/api/billing/orders/$id/v1"
    $free = Api 'GET' '/api/subscriptions/v1'
    if ($refunded.status -ne 'REFUNDED' -or $free.planCode -ne 'FREE') { throw '重启后未恢复退款与权益撤销' }
    @{ passed=$true; jarSha256=(Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash; processStarts=$script:boot; orderId=$id; paymentRecovered=$paid.status; refundRecovered=$refunded.status; finalPlan=$free.planCode; checkedAt=[DateTimeOffset]::UtcNow.ToString('O') } |
        ConvertTo-Json | Set-Content -LiteralPath (Join-Path $scratch 'result.json') -Encoding utf8
    Write-Output 'PASS: 三次独立后端启动，支付与退款通知丢失均恢复，最终 FREE。'
} finally {
    Stop-FixtureServer
    if ($userId -match '^\d+$') {
        $psql = 'C:\Program Files\PostgreSQL\16\bin\psql.exe'
        if (Test-Path -LiteralPath $psql) {
            & $psql -h localhost -p $databasePort -U $DatabaseUser -d $databaseName -v ON_ERROR_STOP=1 -c "DELETE FROM app_user WHERE id=$userId" | Out-Null
            if ($LASTEXITCODE -ne 0) { Write-Warning '验收用户清理失败，仅影响独立验收数据库' }
        }
    }
    foreach ($name in $variables.Keys) { [Environment]::SetEnvironmentVariable($name,$previous[$name],'Process') }
    Pop-Location
}
