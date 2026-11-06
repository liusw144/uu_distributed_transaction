param([switch]$RestartService,[string]$ServiceName='postgresql-x64-16')
. "$PSScriptRoot/env.ps1"
if($RestartService -and $env:PG_HOST -notin @('127.0.0.1','localhost')) { throw '此脚本只能重启本机 PostgreSQL 服务' }
$env:PGPASSWORD=$env:PG_PASSWORD
try {
    # 这是显式调用的运维脚本，init-db/start/build 都不会自动执行它。
    # ALTER SYSTEM 修改整个 PG 实例；需要重启生效，会短暂中断该实例其他连接。
    & (Get-PgTool 'psql') -h $env:PG_HOST -p $env:PG_PORT -U $env:PG_USER -d postgres -v ON_ERROR_STOP=1 -c 'ALTER SYSTEM SET max_prepared_transactions = 100'
    if($LASTEXITCODE -ne 0) { throw '设置失败，需要数据库超级用户权限' }
    if($RestartService) {
        Restart-Service -Name $ServiceName -ErrorAction Stop
        Write-Host 'PostgreSQL 已重启，请执行 scripts/check-env.ps1 确认参数。'
    } else { Write-Host '参数已写入，尚未重启。请在适当时机重启 PostgreSQL，或在管理员终端使用 -RestartService。' }
} finally { Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue }
