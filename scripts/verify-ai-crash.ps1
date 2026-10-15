param([string]$DatabaseUrl=$env:LEXIFORGE_TEST_DB_URL,[string]$DatabaseUser='postgres',[string]$DatabasePassword='postgres')
$ErrorActionPreference='Stop'
if($DatabaseUrl -notmatch '^jdbc:postgresql://localhost:(\d+)/(lexiforge_(?:acceptance|test)[a-zA-Z0-9_]*)$') { throw '只允许明确命名的独立本机验收库' }
$databasePort=$Matches[1]; $databaseName=$Matches[2]
$repoRoot=Split-Path -Parent $PSScriptRoot
$scratch=[IO.Path]::GetFullPath((Join-Path $repoRoot '../tmp/ai-crash'))
[IO.Directory]::CreateDirectory($scratch)|Out-Null
$jar=[IO.Path]::GetFullPath((Join-Path $repoRoot '../tmp/backend/lexiforge-0.1.0-SNAPSHOT.jar'))
$fixture=Join-Path $PSScriptRoot 'fixtures/slow-ai-provider.mjs'
$receivedMarker=Join-Path $scratch ("request-received-"+[Guid]::NewGuid().ToString('N')+'.txt')
$psql='C:\Program Files\PostgreSQL\16\bin\psql.exe'
if(!(Test-Path -LiteralPath $jar) -or !(Test-Path -LiteralPath $psql)) { throw '需要先构建 jar 并安装本机 PostgreSQL 16 客户端' }
function Free-Port {
    $listener=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0)
    $listener.Start(); $selected=$listener.LocalEndpoint.Port; $listener.Stop(); return $selected
}
$port=Free-Port; do { $providerPort=Free-Port } while($providerPort -eq $port)
$base="http://127.0.0.1:$port"
$script:server=$null; $script:boot=0; $provider=$null; $userId=$null; $response=$null
function Stop-Server {
    if($script:server -and !$script:server.HasExited) { $script:server.Kill($true); if(!$script:server.WaitForExit(10000)) { throw '验收进程未停止' } }
}
function Start-Server {
    $script:boot++
    $script:server=Start-Process -FilePath (Get-Command java).Source -ArgumentList @('-jar',$jar,"--server.port=$port",'--server.address=127.0.0.1','--app.ai.reservation-cleanup-ms=1000','--app.ai.pending-cleanup-ms=1000') -WorkingDirectory $repoRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $scratch "boot-$script:boot.log") -RedirectStandardError (Join-Path $scratch "boot-$script:boot.err.log")
    for($attempt=0;$attempt -lt 90;$attempt++) {
        if($script:server.HasExited) { throw '后端提前退出，查看 tmp/ai-crash 日志' }
        try { if((Invoke-RestMethod "$base/actuator/health" -TimeoutSec 2).status -eq 'UP') { return } } catch { }
        Start-Sleep -Seconds 1
    }
    throw '后端启动超时'
}
function Sql([string]$query) {
    $value=& $psql -h localhost -p $databasePort -U $DatabaseUser -d $databaseName -At -v ON_ERROR_STOP=1 -c $query
    if($LASTEXITCODE -ne 0) { throw '验收数据库查询失败' }
    return "$value".Trim()
}
$variables=@{ DB_URL=$DatabaseUrl; DB_USERNAME=$DatabaseUser; DB_PASSWORD=$DatabasePassword; PGPASSWORD=$DatabasePassword;
    SPRING_PROFILES_ACTIVE='test'; AI_ENABLED='true'; AI_CHAT_MODEL='openai'; AI_TIMEOUT_SECONDS='90'; AUTH_MAIL_MODE='disabled'; BILLING_MODE='disabled';
    JWT_SECRET='fixture-only-ai-crash-jwt-secret-0123456789'; SPRING_AI_OPENAI_API_KEY='fixture-only-no-external-key';
    SPRING_AI_OPENAI_BASE_URL="http://127.0.0.1:$providerPort/v1"; SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL='fault-fixture'; AI_PROVIDER_STRUCTURED_OUTPUT='false' }
$previous=@{}
foreach($name in $variables.Keys) { $previous[$name]=[Environment]::GetEnvironmentVariable($name,'Process'); [Environment]::SetEnvironmentVariable($name,$variables[$name],'Process') }
Push-Location $repoRoot
$client=[Net.Http.HttpClient]::new(); $client.Timeout=[Threading.Timeout]::InfiniteTimeSpan
try {
    $provider=Start-Process -FilePath (Get-Command node).Source -ArgumentList @($fixture,$providerPort,$receivedMarker) -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $scratch 'provider.log') -RedirectStandardError (Join-Path $scratch 'provider.err.log')
    for($attempt=0;$attempt -lt 20;$attempt++) {
        try { if((Invoke-WebRequest "http://127.0.0.1:$providerPort/health" -TimeoutSec 1).StatusCode -eq 200) { break } } catch { }
        if($attempt -eq 19) { throw '本地故障注入服务未启动' }; Start-Sleep -Seconds 1
    }
    Start-Server
    $account=(Invoke-RestMethod "$base/api/auth/register/v1" -Method POST -ContentType 'application/json' -Body (ConvertTo-Json @{ email="crash-$([Guid]::NewGuid().ToString('N'))@example.test"; password='Acceptance123!'; displayName='中断验收' })).data
    $userId=$account.user.id
    if($userId -notmatch '^\d+$') { throw '无效验收用户编号' }
    $request=[Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::Post,"$base/api/translations/stream/v1")
    $request.Headers.Authorization=[Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer',$account.accessToken)
    $request.Content=[Net.Http.StringContent]::new((ConvertTo-Json @{ requestId=[Guid]::NewGuid().ToString(); sourceText='Preserve register 0x10.'; sourceLanguage='EN'; targetLanguage='ZH'; forceRefresh=$false }),[Text.Encoding]::UTF8,'application/json')
    $response=$client.SendAsync($request,[Net.Http.HttpCompletionOption]::ResponseHeadersRead).GetAwaiter().GetResult()
    if(!$response.IsSuccessStatusCode) { throw '流式请求未被接受' }
    $started=[DateTimeOffset]::UtcNow
    for($attempt=0;$attempt -lt 30;$attempt++) {
        $active=Sql "SELECT count(*) FROM ai_usage_reservation WHERE user_id=$userId AND status='RESERVED'"
        $providerEntered=Test-Path -LiteralPath $receivedMarker
        if($active -eq '1' -and $providerEntered) { break }
        if($attempt -eq 29) { throw '未观察到真实预占和进行中的上游 HTTP 请求' }; Start-Sleep -Seconds 1
    }
    if((Sql "SELECT translation_count FROM daily_usage WHERE user_id=$userId") -ne '1') { throw '崩溃前预占不正确' }
    Stop-Server; $response.Dispose(); $response=$null
    if((Sql "SELECT status FROM ai_usage_reservation WHERE user_id=$userId") -ne 'RESERVED') { throw '进程终止没有留下待恢复预占' }
    $env:AI_ENABLED='false'; $env:AI_CHAT_MODEL='none'
    Start-Server
    if((Sql "SELECT status FROM ai_usage_reservation WHERE user_id=$userId") -ne 'RESERVED') { throw '过期前预占被提前释放' }
    Write-Output '已验证：上游 HTTP 处理中强制终止后，重启保留未过期预占。等待真实数据库时钟过期和补偿，不修改时钟或期限。'
    for($attempt=0;$attempt -lt 120;$attempt++) {
        $state=Sql "SELECT status FROM ai_usage_reservation WHERE user_id=$userId"
        $requestState=Sql "SELECT status FROM ai_request WHERE user_id=$userId"
        if($state -eq 'RELEASED' -and $requestState -eq 'FAILED') { break }
        if($attempt -eq 119) { throw '重启后的额度/请求补偿超时' }; Start-Sleep -Seconds 2
    }
    if((Sql "SELECT translation_count FROM daily_usage WHERE user_id=$userId") -ne '0') { throw '补偿后额度不正确' }
    if((Sql "SELECT count(*) FROM translation WHERE user_id=$userId") -ne '0') { throw '中断结果不应保存为成功翻译' }
    Start-Sleep -Seconds 3
    if((Sql "SELECT translation_count FROM daily_usage WHERE user_id=$userId") -ne '0') { throw '重复恢复改变了额度' }
    @{ passed=$true; jarSha256=(Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash; processStarts=$script:boot; provider='local HTTP fault fixture';
        clockModified=$false; reservationState=$state; requestState=$requestState; chargedAfterRecovery=0; successfulTranslations=0; elapsedSeconds=([DateTimeOffset]::UtcNow-$started).TotalSeconds } |
        ConvertTo-Json | Set-Content -LiteralPath (Join-Path $scratch 'result.json') -Encoding utf8
    Write-Output 'PASS: 真实进程中断后，过期预占仅退回一次，请求标记失败，没有成功历史。'
} finally {
    if($response) { $response.Dispose() }; $client.Dispose(); Stop-Server
    if($provider -and !$provider.HasExited) { $provider.Kill($true); $provider.WaitForExit(10000)|Out-Null }
    if($userId -match '^\d+$') { Sql "DELETE FROM app_user WHERE id=$userId" | Out-Null }
    foreach($name in $variables.Keys) { [Environment]::SetEnvironmentVariable($name,$previous[$name],'Process') }
    Pop-Location
}
