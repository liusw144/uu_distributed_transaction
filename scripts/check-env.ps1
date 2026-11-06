. "$PSScriptRoot/env.ps1"
& java -version
& mvn -version
$env:MYSQL_PWD=$env:MYSQL_PASSWORD;$env:PGPASSWORD=$env:PG_PASSWORD
try {
    & $env:MYSQL_BIN --protocol=TCP "--host=$env:MYSQL_HOST" "--port=$env:MYSQL_PORT" "--user=$env:MYSQL_USER" --execute='SELECT VERSION(),@@port; SELECT COUNT(*) AS orders FROM dtx_demo_mysql.demo_orders'
    if($LASTEXITCODE -ne 0) { throw 'MySQL 检查失败' }
    & (Get-PgTool 'psql') -h $env:PG_HOST -p $env:PG_PORT -U $env:PG_USER -d dtx_demo_pg -v ON_ERROR_STOP=1 -c 'SELECT version(); SHOW max_prepared_transactions; SELECT gid,prepared FROM pg_prepared_xacts'
    if($LASTEXITCODE -ne 0) { throw 'PostgreSQL 检查失败' }
} finally { Remove-Item Env:MYSQL_PWD,Env:PGPASSWORD -ErrorAction SilentlyContinue }
