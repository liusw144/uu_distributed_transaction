# 复制为 local.ps1，填写本地密码；local.ps1 已被 .gitignore 排除。
$env:JAVA_HOME = 'D:/software/java/jdk17/jdk17.0.15_6'
$env:MYSQL_HOST = '127.0.0.1'
$env:MYSQL_PORT = '3308'
$env:MYSQL_USER = 'root'
$env:MYSQL_PASSWORD = ''
$env:PG_HOST = '127.0.0.1'
$env:PG_PORT = '5432'
$env:PG_USER = 'postgres'
$env:PG_PASSWORD = ''
$env:MYSQL_BIN = 'D:/software/mysql8.0.46/bin/mysql.exe'
$env:PG_BIN = 'D:/software/pgsql16/bin'
# 解压官方 Seata 2.2.0 二进制包后填写；仅 AT 需要。
$env:SEATA_HOME = ''
