. "$PSScriptRoot/env.ps1"
if(!$env:SEATA_HOME) { $env:SEATA_HOME=Join-Path $ProjectRoot '.runtime/seata/seata-server' }
$jar=Join-Path $env:SEATA_HOME 'target/seata-server.jar'
if(!(Test-Path $jar)) { throw '找不到 Seata Server，请执行 scripts/install-seata.ps1 或配置 SEATA_HOME' }
$tcp=[System.Net.Sockets.TcpClient]::new()
try { $tcp.Connect('127.0.0.1',8091);throw '8091 已占用，请检查是否已有 TC' }
catch [System.Net.Sockets.SocketException] { } finally { $tcp.Dispose() }
New-Item -ItemType Directory -Force "$ProjectRoot/.runtime/logs","$ProjectRoot/.runtime/pids" | Out-Null
$env:SEATA_STORE_DIR=Join-Path $ProjectRoot '.runtime/seata-data'
# 直接启动官方发行包的 jar，并保留独立 Java PID；512MB 堆适合本地演示。
$argsList=@('-Xms128m','-Xmx512m','-Dfile.encoding=UTF-8',
    ('-Dloader.path="'+$env:SEATA_HOME+'/lib"'),
    ('-Dlog.home="'+$ProjectRoot+'/.runtime/logs/seata"'),
    ('-Dspring.config.location="file:'+$ProjectRoot+'/config/seata-server.yml"'),
    ('-Dlogging.config="'+$env:SEATA_HOME+'/conf/logback-spring.xml"'),
    '-jar',('"'+$jar+'"'),'-h','127.0.0.1','-p','8091')
$process=Start-Process (Join-Path $env:JAVA_HOME 'bin/java.exe') -ArgumentList $argsList -WorkingDirectory $ProjectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput "$ProjectRoot/.runtime/logs/seata.log" -RedirectStandardError "$ProjectRoot/.runtime/logs/seata.err.log"
@{Id=$process.Id;StartTime=$process.StartTime.ToUniversalTime().ToString('o');Name='seata'} | ConvertTo-Json | Set-Content "$ProjectRoot/.runtime/pids/seata.json" -Encoding utf8
for($i=0;$i -lt 80;$i++) {
    if($process.HasExited) { throw 'Seata TC 启动失败，请查看 .runtime/logs/seata.log' }
    $probe=[System.Net.Sockets.TcpClient]::new()
    try { $probe.Connect('127.0.0.1',8091);Write-Host 'Seata TC 已启动：127.0.0.1:8091；控制台：http://127.0.0.1:7091';return }
    catch [System.Net.Sockets.SocketException] { } finally { $probe.Dispose() }
    Start-Sleep -Milliseconds 500
}
throw 'Seata TC 启动超时，请查看日志'
