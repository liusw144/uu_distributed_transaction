. "$PSScriptRoot/env.ps1"
$env:MYSQL_PWD = $env:MYSQL_PASSWORD
$env:PGPASSWORD = $env:PG_PASSWORD
try {
    $mysqlArgs = @('--protocol=TCP',"--host=$env:MYSQL_HOST","--port=$env:MYSQL_PORT","--user=$env:MYSQL_USER",'--default-character-set=utf8mb4','--batch','--skip-column-names')
    $version = & $env:MYSQL_BIN @mysqlArgs --execute='SELECT VERSION()'
    if ($LASTEXITCODE -ne 0) { throw 'MySQL 连接失败' }
    if ($version -notmatch '^8\.0\.') { throw "期望 MySQL 8.0，实际为 $version，请检查端口" }
    & $env:MYSQL_BIN @mysqlArgs --execute='CREATE DATABASE IF NOT EXISTS dtx_demo_mysql CHARACTER SET utf8mb4 COLLATE utf8mb4_bin'
    if ($LASTEXITCODE -ne 0) { throw 'MySQL 建库失败' }
    # source 由客户端读取 UTF-8 文件，避免 PowerShell 管道按系统代码页转码中文。
    $sqlPath = (Join-Path $ProjectRoot 'sql/mysql.sql').Replace('\','/')
    & $env:MYSQL_BIN @mysqlArgs dtx_demo_mysql "--execute=source $sqlPath"
    if ($LASTEXITCODE -ne 0) { throw 'MySQL 建表失败' }
    $pgArgs = @('-h',$env:PG_HOST,'-p',$env:PG_PORT,'-U',$env:PG_USER,'-v','ON_ERROR_STOP=1')
    $exists = & (Get-PgTool 'psql') @pgArgs -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='dtx_demo_pg'"
    if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL 连接失败' }
    if ($exists -ne '1') {
        & (Get-PgTool 'createdb') -h $env:PG_HOST -p $env:PG_PORT -U $env:PG_USER -T template0 -E UTF8 dtx_demo_pg
        if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL 建库失败' }
    }
    & (Get-PgTool 'psql') @pgArgs -d dtx_demo_pg -f (Join-Path $ProjectRoot 'sql/postgresql.sql')
    if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL 建表失败' }
    & (Get-PgTool 'psql') @pgArgs -d dtx_demo_pg -c 'SHOW max_prepared_transactions'
    Write-Host "初始化完成：MySQL $version / dtx_demo_mysql；PostgreSQL / dtx_demo_pg。未清空已有数据。"
} finally { Remove-Item Env:MYSQL_PWD,Env:PGPASSWORD -ErrorAction SilentlyContinue }
