param([ValidateSet('all','xa','outbox','at','notify')][string]$Mode='all')
. "$PSScriptRoot/env.ps1"
New-Item -ItemType Directory -Force "$ProjectRoot/.runtime/logs","$ProjectRoot/.runtime/pids" | Out-Null
function Start-Demo([string]$name,[string]$module,[int]$port,[string[]]$extra=@()) {
    $tcp=[System.Net.Sockets.TcpClient]::new()
    try { $tcp.Connect('127.0.0.1',$port); throw "端口 $port 已占用，请先检查对应进程" }
    catch [System.Net.Sockets.SocketException] { } finally { $tcp.Dispose() }
    $jar=Join-Path $ProjectRoot "$module/target/$module-1.0.0.jar"
    if (!(Test-Path $jar)) { throw "缺少 $jar，请先执行 scripts/build.ps1" }
    $arguments=@('-Dfile.encoding=UTF-8','-jar',('"'+$jar+'"'),"--server.port=$port")+$extra
    $process=Start-Process -FilePath (Join-Path $env:JAVA_HOME 'bin/java.exe') -ArgumentList $arguments -WorkingDirectory $ProjectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput "$ProjectRoot/.runtime/logs/$name.log" -RedirectStandardError "$ProjectRoot/.runtime/logs/$name.err.log"
    @{Id=$process.Id;StartTime=$process.StartTime.ToUniversalTime().ToString('o');Name=$name} | ConvertTo-Json | Set-Content "$ProjectRoot/.runtime/pids/$name.json" -Encoding utf8
    for($i=0;$i -lt 45;$i++) {
        if($process.HasExited) { throw "$name 启动失败，查看 .runtime/logs/$name.log 和 .err.log" }
        try { $health=Invoke-RestMethod "http://127.0.0.1:$port/demo/health" -TimeoutSec 1; if($health.status -eq 'UP') { Write-Host "$name 已启动：http://127.0.0.1:$port";return } } catch { }
        Start-Sleep -Milliseconds 500
    }
    throw "$name 尚未就绪，请查看日志"
}
if($Mode -in @('all','outbox')) { Start-Demo 'outbox' 'outbox-demo' 8082 }
if($Mode -in @('all','notify')) { Start-Demo 'notify' 'notification-demo' 8086 }
if($Mode -in @('all','at')) {
    # TC 需要先通过 start-seata.ps1 启动，避免客户端长时间重试。
    $tcp=[System.Net.Sockets.TcpClient]::new()
    try { $tcp.Connect('127.0.0.1',8091) } catch { throw 'Seata TC 未就绪，请先执行 scripts/start-seata.ps1' } finally { $tcp.Dispose() }
    Start-Demo 'at-stock' 'seata-demo' 8084 @('--demo.role=stock','--spring.application.name=seata-stock')
    Start-Demo 'at-account' 'seata-demo' 8085 @('--demo.role=account','--spring.application.name=seata-account')
    Start-Demo 'at-order' 'seata-demo' 8083 @('--demo.role=order','--spring.application.name=seata-order')
}
if($Mode -in @('all','xa')) { Start-Demo 'xa' 'xa-demo' 8081 }
