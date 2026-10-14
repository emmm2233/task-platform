# 启动本地 Redis（开发用）
# 用法: powershell -File deploy\start-local-redis.ps1
$tools = Join-Path (Split-Path $PSScriptRoot -Parent) "..\.tools"
& "$tools\redis\redis-server.exe" --port 6379 --save "" --appendonly no