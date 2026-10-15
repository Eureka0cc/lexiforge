param([int]$DatabasePort=5432,[string]$DatabaseUser='postgres',[string]$DatabasePassword='postgres')
$ErrorActionPreference='Stop'
$repoRoot=Split-Path -Parent $PSScriptRoot
$scratch=[IO.Path]::GetFullPath((Join-Path $repoRoot '../tmp/database-recovery'))
[IO.Directory]::CreateDirectory($scratch)|Out-Null
$jar=[IO.Path]::GetFullPath((Join-Path $repoRoot '../tmp/backend/lexiforge-0.1.0-SNAPSHOT.jar'))
$pgBin='C:\Program Files\PostgreSQL\16\bin'
$psql=Join-Path $pgBin 'psql.exe'; $dump=Join-Path $pgBin 'pg_dump.exe'; $restore=Join-Path $pgBin 'pg_restore.exe'
foreach($required in @($jar,$psql,$dump,$restore)) { if(!(Test-Path -LiteralPath $required)) { throw "缺少恢复验收依赖：$required" } }
$suffix=[Guid]::NewGuid().ToString('N').Substring(0,12)
$sourceDatabase="lexiforge_test_recovery_${suffix}_source"
$restoredDatabase="lexiforge_test_recovery_${suffix}_restore"
$created=[Collections.Generic.List[string]]::new()
$probe=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0); $probe.Start(); $port=$probe.LocalEndpoint.Port; $probe.Stop()
$base="http://127.0.0.1:$port"; $script:server=$null; $script:boot=0; $script:access=$null
function Sql([string]$database,[string]$query) {
    $value=& $psql -h localhost -p $DatabasePort -U $DatabaseUser -d $database -At -v ON_ERROR_STOP=1 -c $query
    if($LASTEXITCODE -ne 0) { throw '恢复验收 SQL 失败' }; return "$value".Trim()
}
function Create-FixtureDatabase([string]$name) {
    if($name -notmatch '^lexiforge_test_recovery_[0-9a-f]{12}_(source|restore)$') { throw '拒绝创建非验收数据库' }
    if((Sql 'postgres' "SELECT count(*) FROM pg_database WHERE datname='$name'") -ne '0') { throw '验收数据库重名，保留现有数据库并停止' }
    Sql 'postgres' "CREATE DATABASE $name TEMPLATE template0 ENCODING 'UTF8'" | Out-Null
    $created.Add($name)
}
function Stop-Server {
    if($script:server -and !$script:server.HasExited) { $script:server.Kill($true); if(!$script:server.WaitForExit(10000)) { throw '验收进程未停止' } }
}
function Start-Server([string]$database) {
    $env:DB_URL="jdbc:postgresql://localhost:$DatabasePort/$database"; $script:boot++
    $script:server=Start-Process -FilePath (Get-Command java).Source -ArgumentList @('-jar',$jar,"--server.port=$port",'--server.address=127.0.0.1') -WorkingDirectory $repoRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $scratch "boot-$script:boot.log") -RedirectStandardError (Join-Path $scratch "boot-$script:boot.err.log")
    for($attempt=0;$attempt -lt 90;$attempt++) {
        if($script:server.HasExited) { throw '验收后端提前退出，查看 tmp/database-recovery 日志' }
        try { if((Invoke-RestMethod "$base/actuator/health" -TimeoutSec 2).status -eq 'UP') { return } } catch { }
        Start-Sleep -Seconds 1
    }; throw '验收后端启动超时'
}
function Api([string]$method,[string]$path,$body=$null) {
    $arguments=@{ Uri="$base$path"; Method=$method; TimeoutSec=15; Headers=@{} }
    if($script:access) { $arguments.Headers.Authorization="Bearer $script:access" }
    if($null -ne $body) { $arguments.Body=ConvertTo-Json -InputObject $body -Depth 10 -Compress; $arguments.ContentType='application/json' }
    return (Invoke-RestMethod @arguments).data
}
function Manifest([string]$database) {
    $names=(Sql $database "SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename") -split '\s+'
    $manifest=@{}
    foreach($table in $names) {
        if($table -notmatch '^[a-z0-9_]+$') { throw '验收表名不符合预期' }
        $manifest[$table]=Sql $database "SELECT count(*)||'|'||coalesce(md5(string_agg(md5(row_to_json(r)::text),'' ORDER BY md5(row_to_json(r)::text))),md5('')) FROM public.$table r"
    }; return $manifest
}
$variables=@{ DB_URL=''; DB_USERNAME=$DatabaseUser; DB_PASSWORD=$DatabasePassword; PGPASSWORD=$DatabasePassword;
    SPRING_PROFILES_ACTIVE='test'; AI_ENABLED='false'; AI_CHAT_MODEL='none'; AUTH_MAIL_MODE='disabled';
    JWT_SECRET='fixture-only-database-recovery-jwt-secret-0123456789'; BILLING_MODE='local-sandbox'; BILLING_SECRET='fixture-only-database-recovery-billing-secret-0123456789' }
$previous=@{}
foreach($name in $variables.Keys) { $previous[$name]=[Environment]::GetEnvironmentVariable($name,'Process'); [Environment]::SetEnvironmentVariable($name,$variables[$name],'Process') }
Push-Location $repoRoot
$started=[DateTimeOffset]::UtcNow
try {
    Create-FixtureDatabase $sourceDatabase; Create-FixtureDatabase $restoredDatabase
    Start-Server $sourceDatabase
    $account=Api 'POST' '/api/auth/register/v1' @{ email="recovery-$suffix@example.test"; password='Acceptance123!'; displayName='数据库恢复验收' }
    $script:access=$account.accessToken; $uid=$account.user.id
    $scene=Api 'POST' '/api/domains/v1' @{ name='恢复验收场景'; description='数据库恢复验收'; configuration=@{ domain='IT'; documentType='说明文档'; audience='工程师'; style='简洁'; preserveRules=@('保留标识符'); explanationPreference='不额外解释' } }
    $word=Api 'POST' '/api/vocabulary/v1' @{ word='recovery'; chineseMeaning='恢复'; sourceType='MANUAL' }
    $order=Api 'POST' '/api/billing/orders/v1' @{ requestId=[Guid]::NewGuid().ToString(); cycle='MONTH'; channel='ALIPAY_SIMULATOR' }
    $paid=Api 'POST' "/api/billing/orders/$($order.id)/sandbox-pay/v1"
    if($paid.status -ne 'PAID') { throw '源库模拟支付未成功' }
    # Synthetic history tests database fidelity only; this is not an AI translation quality sample.
    if($uid -notmatch '^\d+$' -or $scene.id -notmatch '^\d+$') { throw '无效验收实体编号' }
    Sql $sourceDatabase "INSERT INTO translation(id,user_id,domain_id,source_language,target_language,source_text,translated_text,detected_language,prompt_version,model_version) VALUES($uid,$uid,$($scene.id),'EN','ZH','database restore fixture','database recovery test data','EN','restore-fixture','fixture')" | Out-Null
    Stop-Server
    $before=Manifest $sourceDatabase
    $archive=Join-Path $scratch 'database.dump'
    & $dump -h localhost -p $DatabasePort -U $DatabaseUser -d $sourceDatabase --format=custom --no-owner --no-acl --file=$archive
    if($LASTEXITCODE -ne 0) { throw 'pg_dump 失败' }
    & $restore -h localhost -p $DatabasePort -U $DatabaseUser -d $restoredDatabase --exit-on-error --no-owner --no-acl $archive
    if($LASTEXITCODE -ne 0) { throw 'pg_restore 失败' }
    $after=Manifest $restoredDatabase
    if($before.Count -ne $after.Count) { throw '恢复后的表数量不同' }
    foreach($table in $before.Keys) { if($before[$table] -ne $after[$table]) { throw "恢复后的行数/全列校验不一致：$table" } }
    $before | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $scratch 'source-manifest.json') -Encoding utf8
    $after | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $scratch 'restored-manifest.json') -Encoding utf8
    Start-Server $restoredDatabase
    if((Api 'GET' '/api/account/v1').email -ne $account.user.email) { throw '恢复后的会话/账户不可用' }
    if((Api 'GET' '/api/subscriptions/v1').planCode -ne 'PRO') { throw '恢复后的订阅权益不正确' }
    $restoredScene=(Api 'GET' '/api/domains/v1') | Where-Object id -eq $scene.id
    if($restoredScene.configuration.style -ne '简洁') { throw '恢复后的场景配置不一致' }
    if(!((Api 'GET' '/api/vocabulary/v1') | Where-Object id -eq $word.id)) { throw '恢复后的词库不可用' }
    if((Api 'GET' "/api/translations/$uid/v1").sourceText -ne 'database restore fixture') { throw '恢复后的历史不可用' }
    $refunded=Api 'POST' "/api/billing/orders/$($order.id)/sandbox-refund/v1"
    if($refunded.status -ne 'REFUNDED' -or (Api 'GET' '/api/subscriptions/v1').planCode -ne 'FREE') { throw '恢复后的退款与权益撤销不可用' }
    @{ passed=$true; tablesChecked=$before.Count; jarSha256=(Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash;
        archiveSha256=(Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash; backendStarts=$script:boot;
        checked=@('schema','all table row counts and full-row checksums','restored authenticated session','scene configuration','vocabulary','synthetic history','Sandbox subscription and refund');
        elapsedSeconds=([DateTimeOffset]::UtcNow-$started).TotalSeconds } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $scratch 'result.json') -Encoding utf8
    Write-Output "PASS: $($before.Count) 张表行数和全列校验一致，恢复后的账户、场景、词库、历史与 Sandbox 退款均可用。"
} finally {
    Stop-Server
    foreach($name in $created) {
        if($name -notmatch '^lexiforge_test_recovery_[0-9a-f]{12}_(source|restore)$') { throw '拒绝清理非本脚本创建的验收库' }
        Sql 'postgres' "DROP DATABASE $name" | Out-Null
    }
    foreach($name in $variables.Keys) { [Environment]::SetEnvironmentVariable($name,$previous[$name],'Process') }
    Pop-Location
}
