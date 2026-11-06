param([switch]$DatabaseTests,[switch]$XaTests)
. "$PSScriptRoot/env.ps1"
Push-Location $ProjectRoot
try {
    if ($DatabaseTests -or $XaTests) { $env:DEMO_DB_TESTS = 'true' }
    if ($XaTests) { $env:DEMO_XA_TESTS = 'true' }
    # 缓存放在项目内，避免依赖对用户全局 .m2 目录的写权限。
    & mvn -B -s "$PSScriptRoot/maven-settings.xml" "-Dmaven.repo.local=$ProjectRoot/.runtime/m2" verify
    if ($LASTEXITCODE -ne 0) { throw 'Maven 构建失败' }
} finally { Pop-Location; Remove-Item Env:DEMO_DB_TESTS,Env:DEMO_XA_TESTS -ErrorAction SilentlyContinue }
