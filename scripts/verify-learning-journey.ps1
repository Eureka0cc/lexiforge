param([int]$DatabasePort=5432,[string]$DatabaseUser='postgres',[string]$DatabasePassword='postgres')
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'LocalRuntime.ps1')
$suffix=[Guid]::NewGuid().ToString('N').Substring(0,12)
$instance="journey-$suffix"; $database="lexiforge_test_journey_$suffix"
$paths=Get-LocalRuntimePaths $instance
$scratch=[IO.Path]::GetFullPath((Join-Path $paths.Repo "../tmp/learning-journey/$suffix"))
$assets=[IO.Path]::GetFullPath((Join-Path $paths.Repo '../tmp/frontend-dist'))
[IO.Directory]::CreateDirectory($scratch)|Out-Null
$psql='C:\Program Files\PostgreSQL\16\bin\psql.exe'
$oldPassword=$env:PGPASSWORD; $created=$false; $frontend=$null
$result=@{passed=$false; languageQualityAcceptance=$false; directory=$scratch; database='isolated; removed after run'; cleaned=$false}
function Sql([string]$db,[string]$query) {
    $value=& $psql -h localhost -p $DatabasePort -U $DatabaseUser -d $db -At -v ON_ERROR_STOP=1 -c $query
    if($LASTEXITCODE -ne 0){throw '学习闭环验收 SQL 失败'}; return "$value".Trim()
}
try {
    if(!(Test-Path -LiteralPath (Join-Path $assets 'index.html'))){throw '先生成生产前端构建'}
    $env:PGPASSWORD=$DatabasePassword
    if($database -notmatch '^lexiforge_test_journey_[a-f0-9]{12}$' -or (Sql 'postgres' "SELECT count(*) FROM pg_database WHERE datname='$database'") -ne '0'){throw '验收库名称冲突'}
    Sql 'postgres' "CREATE DATABASE $database TEMPLATE template0 ENCODING 'UTF8'"|Out-Null; $created=$true
    & (Join-Path $PSScriptRoot 'start-local.ps1') -InstanceName $instance -DatabaseUrl "jdbc:postgresql://localhost:$DatabasePort/$database" -DatabaseUser $DatabaseUser -DatabasePassword $DatabasePassword -SkipBuild | Out-Null
    $runtime=& (Join-Path $PSScriptRoot 'status-local.ps1') -InstanceName $instance -Json | ConvertFrom-Json
    if(!$runtime.healthy){throw '学习闭环后端启动失败'}
    # Serve the production bundle on the configured frontend origin, preserving actual CORS rules.
    $state=Read-LocalRuntimeState $paths
    $vite=Get-OwnedLocalProcess ($state.processes|Where-Object {$_.role -eq 'frontend'}) $paths
    if(!$vite){throw '本实例前端进程未运行'}
    $vite.Kill($true); if(!$vite.WaitForExit(10000)){throw '本实例开发前端未停止'}
    $base=$runtime.frontendUrl; $port=([Uri]$base).Port
    $hostScript=Join-Path $PSScriptRoot 'fixtures/static-app-server.mjs'
    $frontend=Start-Process -FilePath (Get-Command node).Source -ArgumentList @(('"'+$hostScript+'"'),('"'+$assets+'"'),$runtime.backendUrl,$port) -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $scratch 'frontend.log') -RedirectStandardError (Join-Path $scratch 'frontend.err.log')
    $ready=$false
    for($attempt=0;$attempt -lt 20;$attempt++) {
        try { $health=Invoke-RestMethod "$base/__fixture-health" -TimeoutSec 1; if($health.ready -and $health.indexHash -eq (Get-FileHash -LiteralPath (Join-Path $assets 'index.html') -Algorithm SHA256).Hash.ToLowerInvariant()){$ready=$true;break} }catch{}
        Start-Sleep -Seconds 1
    }
    if(!$ready){throw '生产前端未就绪或指纹错误'}
    $result.sourceCommit=(& git -C $paths.Repo rev-parse HEAD)
    $result.sourceDirty=[bool](& git -C $paths.Repo status --porcelain)
    $result.jarSha256=$runtime.jarSha256; $result.indexSha256=$health.indexHash
    $result.ai=Get-LocalAiMetadata $paths
    & node (Join-Path $paths.Repo 'frontend/tests/runtime/learning-journey.mjs') $base $scratch
    if($LASTEXITCODE -ne 0){throw '真实浏览器学习闭环失败，保留现场报告'}
    # Keep native psql arguments ASCII: Windows client code pages can corrupt literal Chinese.
    $styleHex=[Convert]::ToHexString([Text.Encoding]::UTF8.GetBytes('简洁的中文工程说明，保持原文事实。'))
    $stats=Sql $database @"
SELECT json_build_object(
 'users',(SELECT count(*) FROM app_user),
 'translations',(SELECT count(*) FROM translation),
 'configuredScenes',(SELECT count(*) FROM translation_domain WHERE built_in=false AND configuration_json::jsonb->>'style'=convert_from(decode('$styleHex','hex'),'UTF8')),
 'sceneSnapshots',(SELECT count(*) FROM translation WHERE scene_snapshot_json IS NOT NULL),
 'lookupsWithDetails',(SELECT count(*) FROM translation_lookup WHERE details_loaded=true),
 'collected',(SELECT count(*) FROM vocabulary_item WHERE source_type='LOOKUP'),
 'finishedSessions',(SELECT count(*) FROM review_session WHERE status='FINISHED' AND finished_at IS NOT NULL),
 'attempts',(SELECT count(*) FROM review_attempt),
 'answeredItems',(SELECT count(*) FROM review_session_item WHERE answered_at IS NOT NULL),
 'advancedSchedules',(SELECT count(*) FROM review_schedule WHERE direction='EN_TO_ZH' AND due_at>now()),
 'pendingAi',(SELECT count(*) FROM ai_request WHERE status='PENDING'),
 'reservedAi',(SELECT count(*) FROM ai_usage_reservation WHERE status='RESERVED'),
 'aiOperations',(SELECT json_agg(x) FROM (SELECT operation,status,count(*) AS count FROM ai_request GROUP BY operation,status ORDER BY operation,status)x)
)::text;
"@
    $result.databaseStats=$stats|ConvertFrom-Json
    foreach($field in @('users','translations','configuredScenes','sceneSnapshots','lookupsWithDetails','collected','finishedSessions','attempts','answeredItems','advancedSchedules')) {
        if($result.databaseStats.$field -ne 2){throw "数据库闭环数量不一致：$field"}
    }
    if($result.databaseStats.pendingAi -ne 0 -or $result.databaseStats.reservedAi -ne 0){throw 'AI 预占或请求残留'}
    $result.passed=$true
} catch {
    $result.failure=$_.Exception.Message
    throw
} finally {
    try {
        if($frontend -and !$frontend.HasExited){$frontend.Kill($true);$frontend.WaitForExit(10000)|Out-Null}
        & (Join-Path $PSScriptRoot 'stop-local.ps1') -InstanceName $instance | Out-Null
        if($created -and $database -match '^lexiforge_test_journey_[a-f0-9]{12}$'){Sql 'postgres' "DROP DATABASE $database"|Out-Null}
        $result.cleaned=$true
    } finally {
        $env:PGPASSWORD=$oldPassword
        $result|ConvertTo-Json -Depth 12|Set-Content -LiteralPath (Join-Path $scratch 'result.json') -Encoding utf8
    }
}
Write-Output "真实学习闭环报告：$scratch；不替代人工语言质量评分。"
