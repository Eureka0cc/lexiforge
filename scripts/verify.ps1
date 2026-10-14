$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
Push-Location $repoRoot
try {
    if ($env:LEXIFORGE_TEST_DB_URL) { & mvn -B -Pintegration verify }
    else { & mvn -B test }
    if ($LASTEXITCODE -ne 0) { throw '后端测试失败' }
    Push-Location (Join-Path $repoRoot 'frontend')
    try {
        & npm run test
        if ($LASTEXITCODE -ne 0) { throw '前端测试失败' }
        & npm run build
        if ($LASTEXITCODE -ne 0) { throw '前端构建失败' }
        & npm run test:e2e
        if ($LASTEXITCODE -ne 0) { throw '浏览器验收失败' }
    } finally { Pop-Location }
} finally { Pop-Location }
