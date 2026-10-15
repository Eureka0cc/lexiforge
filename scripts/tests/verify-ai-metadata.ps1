$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot '../LocalRuntime.ps1')
$repo=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$root=[IO.Path]::GetFullPath((Join-Path $repo ("../tmp/metadata-tests/"+[Guid]::NewGuid().ToString('N'))))
$directory=Join-Path $root 'src/main/resources'
[IO.Directory]::CreateDirectory($directory)|Out-Null
$file=Join-Path $directory 'application-local.yml'
@'
spring:
  ai:
    model:
      chat: openai
    openai:
      base-url: https://fixture-user:fixture-password@example.test:8443/v1?token=fixture-query
      api-key: fixture-key-must-not-appear
      chat:
        options:
          model: ${LEXIFORGE_META_TEST_MODEL:fallback-model}
          extra-body:
            thinking:
              type: disabled
'@|Set-Content -LiteralPath $file -Encoding utf8
$previous=[Environment]::GetEnvironmentVariable('LEXIFORGE_META_TEST_MODEL','Process')
try {
    $env:LEXIFORGE_META_TEST_MODEL='selected-fixture-model'
    $value=Get-LocalAiMetadata @{Repo=$root}
    if($value.model -ne 'selected-fixture-model' -or $value.providerOrigin -ne 'https://example.test:8443' -or $value.thinkingMode -ne 'disabled') { throw '模型元数据解析不正确' }
    $json=$value|ConvertTo-Json
    foreach($secret in @('fixture-user','fixture-password','fixture-query','fixture-key-must-not-appear')) { if($json.Contains($secret)){throw '模型元数据不应包含连接凭据或查询串'} }
    [Environment]::SetEnvironmentVariable('LEXIFORGE_META_TEST_MODEL',$null,'Process')
    if((Get-LocalAiMetadata @{Repo=$root}).model -ne 'fallback-model'){throw '模型默认表达式未解析'}
    Write-Output 'PASS: 元数据解析实际模型与供应商来源，不输出密钥、URI 凭据或查询参数。'
} finally { [Environment]::SetEnvironmentVariable('LEXIFORGE_META_TEST_MODEL',$previous,'Process') }
