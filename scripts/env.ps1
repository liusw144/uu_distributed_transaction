$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path $PSScriptRoot -Parent
$localConfig = Join-Path $ProjectRoot 'config/local.ps1'
if (Test-Path $localConfig) { . $localConfig }
if ($env:JAVA_HOME) { $env:PATH = "$env:JAVA_HOME/bin;$env:PATH" }
if (!$env:MYSQL_HOST) { $env:MYSQL_HOST = '127.0.0.1' }
if (!$env:MYSQL_PORT) { $env:MYSQL_PORT = '3308' }
if (!$env:MYSQL_USER) { $env:MYSQL_USER = 'root' }
if (!$env:PG_HOST) { $env:PG_HOST = '127.0.0.1' }
if (!$env:PG_PORT) { $env:PG_PORT = '5432' }
if (!$env:PG_USER) { $env:PG_USER = 'postgres' }
if (!$env:MYSQL_BIN) { $env:MYSQL_BIN = 'mysql' }
if (!$env:PG_BIN) { $env:PG_BIN = '' }
$env:MYSQL_URL = "jdbc:mysql://$($env:MYSQL_HOST):$($env:MYSQL_PORT)/dtx_demo_mysql?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true"
$env:PG_URL = "jdbc:postgresql://$($env:PG_HOST):$($env:PG_PORT)/dtx_demo_pg"
function Get-PgTool([string]$name) {
    if ($env:PG_BIN) { return Join-Path $env:PG_BIN "$name.exe" }
    return $name
}
