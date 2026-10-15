param([ValidateSet('smoke','quality','load','performance')][string]$Mode='smoke',
      [string]$SceneSnapshotDirectory,
      [int]$DatabasePort=5432,[string]$DatabaseUser='postgres',[string]$DatabasePassword='postgres')
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'LocalRuntime.ps1')
$suffix=[Guid]::NewGuid().ToString('N').Substring(0,12)
$instance="quality-$suffix"; $database="lexiforge_test_quality_$suffix"
$paths=Get-LocalRuntimePaths $instance
$scratch=[IO.Path]::GetFullPath((Join-Path $paths.Repo "../tmp/quality/$suffix-$Mode"))
[IO.Directory]::CreateDirectory($scratch)|Out-Null
$psql='C:\Program Files\PostgreSQL\16\bin\psql.exe'
$oldPassword=$env:PGPASSWORD
$created=$false; $result=1
if($Mode -eq 'performance') {
    if(!$SceneSnapshotDirectory){throw '性能复测需要完整质量运行目录'}
    $snapshotRoot=[IO.Path]::GetFullPath($SceneSnapshotDirectory)
    $tmpRoot=[IO.Path]::GetFullPath((Join-Path $paths.Repo '../tmp'))+[IO.Path]::DirectorySeparatorChar
    if(!$snapshotRoot.StartsWith($tmpRoot,[StringComparison]::OrdinalIgnoreCase)){throw '场景快照必须在工作区 tmp 下'}
    $sourceRuntime=Get-Content -LiteralPath (Join-Path $snapshotRoot 'runtime.json') -Raw|ConvertFrom-Json
    $currentAi=Get-LocalAiMetadata $paths
    if($sourceRuntime.localConfigSha256 -ne $currentAi.localConfigSha256 -or [string]$sourceRuntime.modelEnvironment -ne [string]$env:DEEPSEEK_MODEL -or $sourceRuntime.jarSha256 -ne (Get-FileHash -LiteralPath $paths.Jar -Algorithm SHA256).Hash){throw '模型配置或应用产物已变化，不能冒充同条件性能复测'}
} elseif($SceneSnapshotDirectory) {throw '只允许性能模式重用场景快照；质量模式必须真实生成场景'}
function Sql([string]$query) {
    $value=& $psql -h localhost -p $DatabasePort -U $DatabaseUser -d postgres -At -v ON_ERROR_STOP=1 -c $query
    if($LASTEXITCODE -ne 0) { throw '质量验收 SQL 失败' }; return "$value".Trim()
}
try {
    $env:PGPASSWORD=$DatabasePassword
    & node (Join-Path $paths.Repo 'evaluation/build-corpus.mjs') --check
    if($LASTEXITCODE -ne 0) { throw '固定评测集完整性检查失败' }
    if($database -notmatch '^lexiforge_test_quality_[a-f0-9]{12}$' -or (Sql "SELECT count(*) FROM pg_database WHERE datname='$database'") -ne '0') { throw '验收库名称冲突' }
    Sql "CREATE DATABASE $database TEMPLATE template0 ENCODING 'UTF8'"|Out-Null; $created=$true
    & (Join-Path $PSScriptRoot 'start-local.ps1') -InstanceName $instance -DatabaseUrl "jdbc:postgresql://localhost:$DatabasePort/$database" -DatabaseUser $DatabaseUser -DatabasePassword $DatabasePassword -SkipBuild | Out-Null
    $runtime=& (Join-Path $PSScriptRoot 'status-local.ps1') -InstanceName $instance -Json | ConvertFrom-Json
    if(!$runtime.healthy) { throw '实际模型验收启动失败' }
    $localConfig=Join-Path $paths.Repo 'src/main/resources/application-local.yml'
    $aiMetadata=Get-LocalAiMetadata $paths
    $modelLines=@(Select-String -LiteralPath $localConfig -Pattern '^[ \t]+model[ \t]*:[ \t]*\S+' | ForEach-Object {$_.Line.Trim()})
    $thinkingLines=@(Select-String -LiteralPath $localConfig -Pattern '^\s+type\s*:\s*(disabled|enabled)\s*$' | ForEach-Object {$_.Line.Trim()})
    @{ mode=$Mode; jarSha256=$runtime.jarSha256; sourceCommit=(& git -C $paths.Repo rev-parse HEAD); ai='current local model; no override'; aiConfiguration=$aiMetadata; modelConfig=$modelLines; modelEnvironment=$env:DEEPSEEK_MODEL; thinkingConfig=$thinkingLines; localConfigSha256=(Get-FileHash -LiteralPath $localConfig -Algorithm SHA256).Hash; quota='normal Pro through local simulator'; database='isolated, removed after run' } |
        ConvertTo-Json | Set-Content -LiteralPath (Join-Path $scratch 'runtime.json') -Encoding utf8
    $nodeArguments=@((Join-Path $paths.Repo 'evaluation/run-http.mjs'),"--base=$($runtime.backendUrl)","--mode=$Mode","--output=$scratch")
    if($Mode -eq 'performance'){$nodeArguments+="--scene-results=$(Join-Path $snapshotRoot 'results.jsonl')"}
    & node @nodeArguments
    $result=$LASTEXITCODE
} finally {
    & (Join-Path $PSScriptRoot 'stop-local.ps1') -InstanceName $instance | Out-Null
    if($created -and $database -match '^lexiforge_test_quality_[a-f0-9]{12}$') { Sql "DROP DATABASE $database"|Out-Null }
    $env:PGPASSWORD=$oldPassword
}
if($result -ne 0) { throw "验收存在接口失败，查看 $scratch" }
if($Mode -eq 'performance'){Write-Output "计时报告已保存：$scratch。接口完成不代表时延门槛通过；没有复用或代填人工评分。"}
else {Write-Output "报告已保存：$scratch。语言质量等待人工评分。"}
