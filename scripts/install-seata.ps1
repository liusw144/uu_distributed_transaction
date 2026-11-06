. "$PSScriptRoot/env.ps1"
$downloadDir=Join-Path $ProjectRoot '.runtime/downloads'
New-Item -ItemType Directory -Force $downloadDir | Out-Null
$base='https://archive.apache.org/dist/incubator/seata/2.2.0/apache-seata-2.2.0-incubating-bin.tar.gz'
$archive=Join-Path $downloadDir 'seata-2.2.0.tar.gz'
$checksum=Join-Path $downloadDir 'seata-2.2.0.sha512'
function Download-Official([string]$url,[string]$file) {
    for($i=0;$i -lt 3;$i++) {
        & (Join-Path $env:JAVA_HOME 'bin/java.exe') '-Dfile.encoding=UTF-8' "$PSScriptRoot/Download.java" $url $file
        if($LASTEXITCODE -eq 0) { return }
    }
    throw "下载失败：$url。可以手动下载官方包，并在 local.ps1 设置 SEATA_HOME。"
}
if(!(Test-Path $archive)) { Download-Official $base $archive }
if(!(Test-Path $checksum)) { Download-Official "$base.sha512" $checksum }
$expected=([regex]::Match((Get-Content $checksum -Raw),'[a-fA-F0-9]{128}')).Value
$actual=(Get-FileHash $archive -Algorithm SHA512).Hash
if($expected.Length -ne 128 -or $expected -ne $actual) { throw 'Seata SHA512 校验失败，请重新下载归档' }
$destination=Join-Path $ProjectRoot '.runtime/seata'
if(Test-Path (Join-Path $destination 'seata-server/target/seata-server.jar')) {
    Write-Host "官方归档 SHA512 校验通过，Seata 已安装在 $destination/seata-server"
    return
}
New-Item -ItemType Directory -Force $destination | Out-Null
& tar -xzf $archive -C $destination
if($LASTEXITCODE -ne 0) { throw 'Seata 解压失败' }
Write-Host "Seata 2.2.0 已校验并安装到 $destination/seata-server"
