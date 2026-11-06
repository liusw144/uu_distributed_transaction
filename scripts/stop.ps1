param([string]$Name='*')
. "$PSScriptRoot/env.ps1"
# 只停止本项目启动并记录了启动时间的进程，避免 PID 被复用后误杀其他 Java。
Get-ChildItem "$ProjectRoot/.runtime/pids" -Filter '*.json' -ErrorAction SilentlyContinue | Where-Object { $_.BaseName -like $Name } | ForEach-Object {
    $record=Get-Content $_.FullName -Raw | ConvertFrom-Json
    $process=Get-Process -Id $record.Id -ErrorAction SilentlyContinue
    if($process) {
        # PowerShell 7 的 ConvertFrom-Json 会把 ISO 时间自动转成 DateTime，不能再转字符串 Parse。
        $started=([DateTime]$record.StartTime).ToUniversalTime()
        if([Math]::Abs(($process.StartTime.ToUniversalTime()-$started).TotalSeconds) -gt 1) { throw "PID $($record.Id) 已被复用，拒绝停止" }
        if($process.ProcessName -ne 'java') { throw '进程不是 Java，拒绝停止' }
        Stop-Process -Id $record.Id
        Write-Host "已停止 $($record.Name)"
    }
    Remove-Item -LiteralPath $_.FullName
}
