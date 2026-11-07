# 旧版集中验证工具保留；当前验证入口为四个 Demo 下的 README.md / requests.http / JUnit。
param([int]$TimeoutSeconds=180)
. "$PSScriptRoot/env.ps1"
$base='http://127.0.0.1:8086/api/notify'
function Wait-Status([string]$id,[string]$status) {
    $deadline=[DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $all=Invoke-RestMethod "$base/tasks";$task=$all | Where-Object { $_.id -eq $id }
        if($task.status -eq $status) { return $task }
        Start-Sleep -Milliseconds 300
    } while([DateTime]::UtcNow -lt $deadline)
    throw "任务未按预期进入 $status"
}
function Set-Fault([int]$failures,[int]$lostAcks) {
    Invoke-RestMethod "http://127.0.0.1:8086/demo/faults?failNext=$failures&loseAckNext=$lostAcks" -Method Post | Out-Null
}
try {
    foreach($lostAck in @($false,$true)) {
        if($lostAck) { Set-Fault 0 100 } else { Set-Fault 100 0 }
        $id="dead-$([guid]::NewGuid().ToString('N'))"
        $body=@{orderId=$id;userId='U1001';productId='SKU-001';quantity=1;unitPrice=10;failAt='NONE'} | ConvertTo-Json
        Invoke-RestMethod "$base/payments" -Method Post -ContentType 'application/json' -Body $body | Out-Null
        $dead=Wait-Status $id 'DEAD'
        $receipt=Invoke-RestMethod "$base/receipts/$id"
        $expected=if($lostAck){'ACK'}else{'NOT_FOUND'}
        if($receipt.result -ne $expected) { throw "死信对账结果不符：期望 $expected，实际 $($receipt.result)" }
        Set-Fault 0 0
        Invoke-RestMethod "$base/tasks/$id/replay" -Method Post | Out-Null
        $done=Wait-Status $id 'DONE'
        if($done.attempts -ne 1 -or $done.last_response -ne 'ACK') { throw '重放后没有正确确认 ACK' }
        Write-Host "PASS 死信及重放：ACK丢失=$lostAck，原投递次数=$($dead.attempts)，原回执=$expected"
    }
} finally { Set-Fault 0 0 }
