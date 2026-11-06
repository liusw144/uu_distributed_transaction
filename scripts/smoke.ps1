param([switch]$IncludeAt,[switch]$IncludeXa)
. "$PSScriptRoot/env.ps1"
function Assert-True($condition,[string]$message) { if(!$condition) { throw "验证失败：$message" } }
function Post-Json([string]$url,$body) { Invoke-RestMethod $url -Method Post -ContentType 'application/json' -Body ($body | ConvertTo-Json -Compress) }
function New-Order([string]$prefix) { return @{orderId="$prefix-$([guid]::NewGuid().ToString('N'))";userId='U1001';productId='SKU-001';quantity=1;unitPrice=10.00;failAt='NONE'} }
function Await-Task([string]$base,[string]$id,[string]$status='DONE') {
    for($i=0;$i -lt 80;$i++) {
        $allTasks=Invoke-RestMethod "$base/tasks"
        $task=$allTasks | Where-Object { $_.id -eq $id }
        if($task.status -eq $status) { return $task }
        if($task.status -eq 'DEAD' -and $status -ne 'DEAD') { throw "任务进入死信：$($task.last_error)" }
        Start-Sleep -Milliseconds 250
    }
    throw "任务 $id 未在期限内进入 $status"
}
function Money($state) { return [decimal]$state.accounts[0].balance }
function Stock($state) { return [int]$state.stock[0].available }
$base='http://127.0.0.1:8082/api/outbox'
$before=Invoke-RestMethod "$base/state"
Invoke-RestMethod 'http://127.0.0.1:8082/demo/faults?loseAckNext=1' -Method Post | Out-Null
$request=New-Order 'smoke-outbox'
Post-Json "$base/orders" $request | Out-Null
$task=Await-Task $base $request.orderId
$after=Invoke-RestMethod "$base/state"
Assert-True ((Money $after) -eq ((Money $before)-10)) 'ACK 丢失重试后只能扣一次款'
Assert-True ((Stock $after) -eq ((Stock $before)-1)) '只预占一次库存'
Assert-True ($task.attempts -ge 2) '应实际发生 ACK 丢失重试'
$duplicate=Post-Json "$base/consume" $request
Assert-True ($duplicate.result -eq 'PAID') '重复消费返回原结果'
$afterDuplicate=Invoke-RestMethod "$base/state"
Assert-True ((Money $afterDuplicate) -eq (Money $after)) '重复消费不再次扣款'
Write-Host 'PASS Outbox：真实 HTTP、丢失 ACK、重试、幂等扣款'
$failed=New-Order 'smoke-rollback';$failed.failAt='AFTER_ACCOUNT'
$status=0
try { Post-Json "$base/orders" $failed | Out-Null } catch { $status=[int]$_.Exception.Response.StatusCode }
Assert-True ($status -eq 409) '故障注入应返回 409'
$state=Invoke-RestMethod "$base/state"
Assert-True (@($state.orders | Where-Object { $_.order_id -eq $failed.orderId }).Count -eq 0) '失败事务不应留下订单'
Assert-True ((Stock $state) -eq (Stock $after)) '失败事务不能扣库存'
$rejected=New-Order 'smoke-rejected';$rejected.unitPrice=1000000.00
Post-Json "$base/orders" $rejected | Out-Null
$task=Await-Task $base $rejected.orderId
$state=Invoke-RestMethod "$base/state"
Assert-True ($task.last_response -eq 'REJECTED') '余额不足应记录确定的拒绝结果'
Assert-True ((Stock $state) -eq (Stock $after)) '拒绝扣款应释放预占库存'
Assert-True ((Money $state) -eq (Money $after)) '拒绝扣款不能修改余额'
Write-Host 'PASS Outbox：本地回滚、余额不足、订单取消和库存补偿'
$base='http://127.0.0.1:8086/api/notify'
Invoke-RestMethod 'http://127.0.0.1:8086/demo/faults?failNext=1&loseAckNext=1' -Method Post | Out-Null
$request=New-Order 'smoke-notify';Post-Json "$base/payments" $request | Out-Null
$task=Await-Task $base $request.orderId
Assert-True ($task.attempts -ge 3) '通知应经历暂时失败、ACK 丢失和成功'
$receipt=Invoke-RestMethod "$base/receipts/$($request.orderId)"
Assert-True ($receipt.result -eq 'ACK') '通知回执必须持久化'
Assert-True ((Post-Json "$base/callback" $request).result -eq 'ACK') '重复通知应返回 ACK'
Write-Host 'PASS 最大努力通知：故障、退避重试、业务 ACK、回执幂等'
foreach($entry in @(@{enabled=$IncludeXa;port=8081;path='xa'},@{enabled=$IncludeAt;port=8083;path='at'})) {
    if(!$entry.enabled) { continue }
    $base="http://127.0.0.1:$($entry.port)/api/$($entry.path)"
    $before=Invoke-RestMethod "$base/state";$request=New-Order "smoke-$($entry.path)"
    Post-Json "$base/orders" $request | Out-Null
    $success=Invoke-RestMethod "$base/state"
    Assert-True ((Money $success) -eq ((Money $before)-10)) '成功事务应扣款'
    Assert-True ((Stock $success) -eq ((Stock $before)-1)) '成功事务应扣库存'
    $request=New-Order "fail-$($entry.path)";$request.failAt='AFTER_ACCOUNT';$status=0
    try { Post-Json "$base/orders" $request | Out-Null } catch { $status=[int]$_.Exception.Response.StatusCode }
    Assert-True ($status -ge 400) '注入失败后应返回错误'
    # AT 二阶段回滚可能稍后完成；轮询确认两库最终恢复。
    $restored=$false
    for($i=0;$i -lt 40;$i++) {
        $state=Invoke-RestMethod "$base/state"
        if((Money $state) -eq (Money $success) -and (Stock $state) -eq (Stock $success)) { $restored=$true;break }
        Start-Sleep -Milliseconds 250
    }
    Assert-True $restored '注入失败后两库应恢复'
    Assert-True (@($state.orders | Where-Object { $_.order_id -eq $request.orderId }).Count -eq 0) '回滚后不能留下订单'
    Write-Host "PASS $($entry.path)：跨库提交和扣款后全局回滚"
}
Write-Host 'HTTP 场景验证完成，演示记录已保留，可通过 state/tasks/receipts 接口检查。'
