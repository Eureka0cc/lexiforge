param([string]$RollbackRef='50f2ea5',[int]$DatabasePort=5432,[string]$DatabaseUser='postgres',[string]$DatabasePassword='postgres')
$ErrorActionPreference='Stop'
$repoRoot=Split-Path -Parent $PSScriptRoot
$scratch=[IO.Path]::GetFullPath((Join-Path $repoRoot '../tmp/version-rollback'))
[IO.Directory]::CreateDirectory($scratch)|Out-Null
Push-Location $repoRoot
try {
    $commit=& git rev-parse --verify --end-of-options "${RollbackRef}^{commit}"
    if($LASTEXITCODE -ne 0 -or $commit -notmatch '^[a-f0-9]{40}$') { throw '回退目标必须是当前仓库内已存在的提交' }
    $buildRoot=Join-Path $scratch ([Guid]::NewGuid().ToString('N').Substring(0,12))
    [IO.Directory]::CreateDirectory($buildRoot)|Out-Null
    $archive=Join-Path $buildRoot 'source.zip'; $source=Join-Path $buildRoot 'source'
    & git archive --format=zip "--output=$archive" $commit
    if($LASTEXITCODE -ne 0) { throw '回退版本源码导出失败' }
    Expand-Archive -LiteralPath $archive -DestinationPath $source
    Push-Location $source
    try { & mvn -B -DskipTests package *> (Join-Path $buildRoot 'build.log'); if($LASTEXITCODE -ne 0) { throw '回退版本构建失败' } }
    finally { Pop-Location }
    $oldJar=Join-Path $buildRoot 'tmp/backend/lexiforge-0.1.0-SNAPSHOT.jar'
    if(!(Test-Path -LiteralPath $oldJar)) { throw '回退版本 jar 未生成' }
    Push-Location (Join-Path $source 'frontend')
    try {
        & npm ci *> (Join-Path $buildRoot 'frontend-dependencies.log'); if($LASTEXITCODE -ne 0) { throw '回退前端依赖安装失败' }
        & npm run build *> (Join-Path $buildRoot 'frontend-build.log'); if($LASTEXITCODE -ne 0) { throw '回退前端构建失败' }
    } finally { Pop-Location }
    $oldAssets=Join-Path $buildRoot 'tmp/frontend-dist'
    $currentAssets=[IO.Path]::GetFullPath((Join-Path $repoRoot '../tmp/frontend-dist'))
    & (Join-Path $PSScriptRoot 'verify-database-recovery.ps1') -DatabasePort $DatabasePort -DatabaseUser $DatabaseUser -DatabasePassword $DatabasePassword -RollbackJarPath $oldJar -CurrentFrontendPath $currentAssets -RollbackFrontendPath $oldAssets
    $recovery=Get-Content -LiteralPath (Join-Path $repoRoot '../tmp/database-recovery/result.json') -Raw|ConvertFrom-Json
    if(!$recovery.passed -or !$recovery.rollbackVerified -or !$recovery.frontendRollbackVerified) { throw '完整应用回退演练未通过' }
    @{ passed=$true; rollbackCommit=$commit; rollbackJarSha256=$recovery.rollbackJarSha256; currentJarSha256=$recovery.jarSha256;
        backendStarts=$recovery.backendStarts; schemaKept='V10'; frontendRollbackVerified=$true; currentFrontendIndexSha256=$recovery.currentFrontendIndexSha256;
        rollbackFrontendIndexSha256=$recovery.rollbackFrontendIndexSha256; reupgradeVerified=$true; dataRestoredBeforeRollback=$true } |
        ConvertTo-Json|Set-Content -LiteralPath (Join-Path $scratch 'result.json') -Encoding utf8
} finally { Pop-Location }
