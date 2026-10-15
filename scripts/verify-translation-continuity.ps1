param([ValidateRange(1,60)][int]$DurationMinutes=30,
      [ValidateRange(10,300)][int]$IntervalSeconds=60,
      [int]$DatabasePort=5432,[string]$DatabaseUser='postgres',[string]$DatabasePassword='postgres')
$ErrorActionPreference='Stop'
if([Math]::Ceiling($DurationMinutes*60/$IntervalSeconds) -gt 90) { throw '每个正常 Pro 测试账户最多安排 90 次，请增加间隔或缩短时长；不使用无限额度' }
. (Join-Path $PSScriptRoot 'LocalRuntime.ps1')
$suffix=[Guid]::NewGuid().ToString('N').Substring(0,12)
$instance="continuity-$suffix"; $database="lexiforge_test_continuity_$suffix"
$paths=Get-LocalRuntimePaths $instance
$scratch=[IO.Path]::GetFullPath((Join-Path $paths.Repo "../tmp/continuity/$suffix"))
[IO.Directory]::CreateDirectory($scratch)|Out-Null
$psql='C:\Program Files\PostgreSQL\16\bin\psql.exe'
$oldPassword=$env:PGPASSWORD; $env:PGPASSWORD=$DatabasePassword
$created=$false; $failure=$null; $runtime=$null; $counts=$null; $watch=[Diagnostics.Stopwatch]::new()
$rounds=[Collections.Generic.List[object]]::new(); $allRows=[Collections.Generic.List[object]]::new(); $cleanupErrors=[Collections.Generic.List[string]]::new()
function Sql([string]$query,[string]$target='postgres') {
    $value=& $psql -h localhost -p $DatabasePort -U $DatabaseUser -d $target -At -v ON_ERROR_STOP=1 -c $query
    if($LASTEXITCODE -ne 0) { throw '连续使用验收 SQL 失败' }; return "$value".Trim()
}
function Counters {
    $query=@'
SELECT json_build_object(
 'users',(SELECT count(*) FROM app_user WHERE email LIKE 'eval-%@example.test'),
 'histories',(SELECT count(*) FROM translation),
 'committed',(SELECT count(*) FROM ai_usage_reservation WHERE status='COMMITTED'),
 'reserved',(SELECT count(*) FROM ai_usage_reservation WHERE status='RESERVED'),
 'pending',(SELECT count(*) FROM ai_request WHERE status='PENDING'),
 'succeeded',(SELECT count(*) FROM ai_request WHERE status='SUCCEEDED'),
 'dailyUsed',(SELECT coalesce(sum(translation_count),0) FROM daily_usage),
 'maxDailyUsed',(SELECT coalesce(max(translation_count),0) FROM daily_usage)
)::text
'@
    return (Sql $query $database)|ConvertFrom-Json
}
function P95($values) {
    $sorted=@($values|Sort-Object); if(!$sorted.Count){return $null}; return $sorted[[Math]::Ceiling($sorted.Count*.95)-1]
}
function Resources($record) {
    $owner=Get-OwnedLocalProcess $record $paths
    if(!$owner) { throw '连续使用期间后端进程已退出' }
    # Oracle javapath may be a small launcher whose child is the actual JVM.
    # Keep ownership checks/stop on the recorded root, but measure its complete Java tree.
    $snapshot=@(Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'")
    $ids=[Collections.Generic.HashSet[int]]::new(); $ids.Add($owner.Id)|Out-Null
    do {
        $added=$false
        foreach($item in $snapshot) { if($ids.Contains([int]$item.ParentProcessId) -and $ids.Add([int]$item.ProcessId)){$added=$true} }
    } while($added)
    $memory=0L; $cpu=0.0; $measured=[Collections.Generic.List[int]]::new()
    foreach($processId in $ids) {
        $live=Get-Process -Id $processId -ErrorAction SilentlyContinue
        if($live -and !$live.HasExited) { $live.Refresh(); $memory+=$live.WorkingSet64; $cpu+=$live.CPU; $measured.Add($live.Id) }
    }
    return @{ processId=$owner.Id; processIds=$measured.ToArray(); workingSetBytes=$memory; cpuSeconds=$cpu; resourceScope='owned backend Java process tree including any javapath launcher' }
}
try {
    & node (Join-Path $paths.Repo 'evaluation/build-corpus.mjs') --check
    if($LASTEXITCODE -ne 0) { throw '固定语料完整性校验失败' }
    if($database -notmatch '^lexiforge_test_continuity_[a-f0-9]{12}$' -or (Sql "SELECT count(*) FROM pg_database WHERE datname='$database'") -ne '0') { throw '验收库名称冲突' }
    Sql "CREATE DATABASE $database TEMPLATE template0 ENCODING 'UTF8'"|Out-Null; $created=$true
    & (Join-Path $PSScriptRoot 'start-local.ps1') -InstanceName $instance -DatabaseUrl "jdbc:postgresql://localhost:$DatabasePort/$database" -DatabaseUser $DatabaseUser -DatabasePassword $DatabasePassword -SkipBuild | Out-Null
    $runtime=& (Join-Path $PSScriptRoot 'status-local.ps1') -InstanceName $instance -Json|ConvertFrom-Json
    if(!$runtime.healthy) { throw '连续使用实例没有就绪' }
    $localConfig=Join-Path $paths.Repo 'src/main/resources/application-local.yml'
    @{ mode='real-model-continuity'; durationMinutes=$DurationMinutes; intervalSeconds=$IntervalSeconds; burstConcurrency=20;
        accountMode='same 20 normal Pro accounts; fresh login each round; local simulated payment once';
        modelSelection='unchanged local configuration'; aiConfiguration=(Get-LocalAiMetadata $paths); localConfigSha256=(Get-FileHash -LiteralPath $localConfig -Algorithm SHA256).Hash;
        sourceCommit=(& git -C $paths.Repo rev-parse HEAD); jarSha256=$runtime.jarSha256; instance=$instance }|
        ConvertTo-Json | Set-Content -LiteralPath (Join-Path $scratch 'runtime.json') -Encoding utf8
    $state=Read-LocalRuntimeState $paths
    $backendRecord=$state.processes|Where-Object {$_.role -eq 'backend'}
    $usersFile=Join-Path $scratch 'fixture-users.private.json'
    $watch.Start(); $next=0.0; $round=0
    while($watch.Elapsed.TotalSeconds -lt $DurationMinutes*60) {
        while($watch.Elapsed.TotalSeconds -lt $next -and $watch.Elapsed.TotalSeconds -lt $DurationMinutes*60) { Start-Sleep -Seconds 1 }
        if($watch.Elapsed.TotalSeconds -ge $DurationMinutes*60) { break }
        $round++; $directory=Join-Path $scratch ("round-{0:D3}" -f $round)
        [IO.Directory]::CreateDirectory($directory)|Out-Null
        & node (Join-Path $paths.Repo 'evaluation/run-http.mjs') "--base=$($runtime.backendUrl)" --mode=load "--users-file=$usersFile" "--output=$directory" *> (Join-Path $directory 'runner.log')
        $exitCode=$LASTEXITCODE
        if(Test-Path -LiteralPath (Join-Path $directory 'results.jsonl')) {
            foreach($line in Get-Content -LiteralPath (Join-Path $directory 'results.jsonl')) { if($line.Trim()){$allRows.Add(($line|ConvertFrom-Json))} }
        }
        $sample=Resources $backendRecord
        $sample.round=$round; $sample.elapsedSeconds=$watch.Elapsed.TotalSeconds; $sample.exitCode=$exitCode
        $rounds.Add($sample)
        $sample|ConvertTo-Json -Compress|Add-Content -LiteralPath (Join-Path $scratch 'resources.jsonl') -Encoding utf8
        Write-Output ("第 {0} 批完成；已运行 {1:N0} 秒；后端进程树内存 {2:N0} MiB" -f $round,$watch.Elapsed.TotalSeconds,($sample.workingSetBytes/1MB))
        if($exitCode -ne 0) { throw '该批真实请求失败，终止当前验收并保留全部失败记录；不自动重跑挑选成功结果' }
        $next=[Math]::Max($next+$IntervalSeconds,$watch.Elapsed.TotalSeconds)
    }
    $watch.Stop(); $counts=Counters
} catch {
    $failure=$_.Exception.Message; $watch.Stop()
    if($created) { try { $counts=Counters } catch { $cleanupErrors.Add('未能读取最终计数') } }
} finally {
    try { & (Join-Path $PSScriptRoot 'stop-local.ps1') -InstanceName $instance|Out-Null } catch { $cleanupErrors.Add('实例停止失败，请检查该实例状态') }
    if($created -and $database -match '^lexiforge_test_continuity_[a-f0-9]{12}$') { try { Sql "DROP DATABASE $database"|Out-Null } catch { $cleanupErrors.Add('测试库清理失败') } }
    $env:PGPASSWORD=$oldPassword
    $success=@($allRows|Where-Object {$_.success})
    $operational=(!$failure -and $cleanupErrors.Count -eq 0 -and $watch.Elapsed.TotalSeconds -ge $DurationMinutes*60 -and $allRows.Count -gt 0 -and $allRows.Count -eq $success.Count -and
        $counts.users -eq 20 -and $counts.histories -eq $success.Count -and $counts.committed -eq $success.Count -and $counts.succeeded -eq $success.Count -and $counts.dailyUsed -eq $success.Count -and
        $counts.reserved -eq 0 -and $counts.pending -eq 0 -and $counts.maxDailyUsed -le 100)
    $first=P95 @($success|ForEach-Object {$_.firstVisibleMs}); $complete=P95 @($success|ForEach-Object {$_.completedMs})
    $latency=($success.Count -gt 0 -and $first -le 3000 -and @($success|Where-Object {$_.completedMs -gt 8000}).Count -eq 0)
    @{ passed=($operational -and $latency); operationalPassed=$operational; latencyPassed=$latency; failure=$failure; cleanupErrors=@($cleanupErrors);
        intendedDurationSeconds=($DurationMinutes*60); elapsedSeconds=$watch.Elapsed.TotalSeconds; rounds=$rounds.Count; total=$allRows.Count; successful=$success.Count;
        firstVisibleP95Ms=$first; completedP95Ms=$complete; overCompletionBudget=@($success|Where-Object {$_.completedMs -gt 8000}).Count;
        counters=$counts; resources=@($rounds); scope='20 simultaneous fresh generations per burst; same application/database across rounds, normal Pro quotas; successful-only latency statistics with failures counted separately' }|
        ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $scratch 'result.json') -Encoding utf8
}
Write-Output "连续使用报告：$scratch"
if(!$operational -or !$latency) { throw '连续使用或响应时延门槛未通过，查看报告；不将工程一致性通过等同于速度通过' }
