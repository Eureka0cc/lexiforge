param([ValidateSet('smoke','quality','load')][string]$Mode='smoke',
      [int]$DatabasePort=5432,[string]$DatabaseUser='postgres',[string]$DatabasePassword='postgres')
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'LocalRuntime.ps1')
$suffix=[Guid]::NewGuid().ToString('N').Substring(0,12)
$instance="quality-$suffix"; $database="lexiforge_test_quality_$suffix"
$paths=Get-LocalRuntimePaths $instance
$scratch=[IO.Path]::GetFullPath((Join-Path $paths.Repo "../tmp/quality/$suffix-$Mode"))
[IO.Directory]::CreateDirectory($scratch)|Out-Null
$psql='C:\Program Files\PostgreSQL\16\bin\psql.exe'
$oldPassword=$env:PGPASSWORD; $env:PGPASSWORD=$DatabasePassword
$created=$false; $result=1
function Sql([string]$query) {
    $value=& $psql -h localhost -p $DatabasePort -U $DatabaseUser -d postgres -At -v ON_ERROR_STOP=1 -c $query
    if($LASTEXITCODE -ne 0) { throw '质量验收 SQL 失败' }; return "$value".Trim()
}
try {
    & node (Join-Path $paths.Repo 'evaluation/build-corpus.mjs') --check
    if($LASTEXITCODE -ne 0) { throw '固定评测集完整性检查失败' }
    if($database -notmatch '^lexiforge_test_quality_[a-f0-9]{12}$' -or (Sql "SELECT count(*) FROM pg_database WHERE datname='$database'") -ne '0') { throw '验收库名称冲突' }
    Sql "CREATE DATABASE $database TEMPLATE template0 ENCODING 'UTF8'"|Out-Null; $created=$true
    & (Join-Path $PSScriptRoot 'start-local.ps1') -InstanceName $instance -DatabaseUrl "jdbc:postgresql://localhost:$DatabasePort/$database" -DatabaseUser $DatabaseUser -DatabasePassword $DatabasePassword -SkipBuild | Out-Null
    $runtime=& (Join-Path $PSScriptRoot 'status-local.ps1') -InstanceName $instance -Json | ConvertFrom-Json
    if(!$runtime.healthy) { throw '实际模型验收启动失败' }
    @{ mode=$Mode; jarSha256=$runtime.jarSha256; sourceCommit=(& git -C $paths.Repo rev-parse HEAD); ai='current local model; no override'; quota='normal Pro through local simulator'; database='isolated, removed after run' } |
        ConvertTo-Json | Set-Content -LiteralPath (Join-Path $scratch 'runtime.json') -Encoding utf8
    & node (Join-Path $paths.Repo 'evaluation/run-http.mjs') "--base=$($runtime.backendUrl)" "--mode=$Mode" "--output=$scratch"
    $result=$LASTEXITCODE
} finally {
    & (Join-Path $PSScriptRoot 'stop-local.ps1') -InstanceName $instance | Out-Null
    if($created -and $database -match '^lexiforge_test_quality_[a-f0-9]{12}$') { Sql "DROP DATABASE $database"|Out-Null }
    $env:PGPASSWORD=$oldPassword
}
if($result -ne 0) { throw "验收存在接口失败，查看 $scratch" }
Write-Output "报告已保存：$scratch。语言质量等待人工评分。"
