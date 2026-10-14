# web-user 端到端核验：后端 jar + Vite dev server 同时起，从【前端端口】打后端接口。
#
# ⭐ 为什么要有这个脚本：只验证"能构建"是不够的 —— 构建通过但代理配错、
#    或者前端把绝对地址写死，都会在真正联调时才暴露。本脚本把这条链路
#    变成一条命令，任何人随时可复跑。
#
# 用法：pwsh -File scripts/verify-e2e.ps1
# 前置：后端已 package（mvn -pl platform-app -am package -DskipTests）

$ErrorActionPreference = 'Stop'
$root = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent   # 任务平台/
$jar  = Join-Path $root 'platform-app\target\platform-app-1.0.0-SNAPSHOT.jar'
$java = 'C:\Program Files\Java\jdk-21.0.11\bin\java.exe'

if (-not (Test-Path $jar)) { Write-Host "❌ 找不到 jar：$jar（先 package）" -ForegroundColor Red; exit 1 }

"=== 起后端 ==="
$be = Start-Process -FilePath $java -ArgumentList '-jar', $jar -PassThru -WindowStyle Hidden
$ok = $false
for ($i=1; $i -le 40; $i++) {
  Start-Sleep -Seconds 2
  try { if ((Invoke-RestMethod 'http://127.0.0.1:8080/actuator/health' -TimeoutSec 3).status -eq 'UP') { $ok=$true; break } } catch {}
}
if (-not $ok) { Stop-Process -Id $be.Id -Force; Write-Host "❌ 后端未起" -ForegroundColor Red; exit 1 }
"  后端 UP"

try {
  "=== 起前端（直接调 vite，绕开 pnpm 前置检查）==="
  $fe = Start-Process -FilePath 'node' -ArgumentList 'node_modules\vite\bin\vite.js','--port','5174','--host','127.0.0.1' -PassThru -WindowStyle Hidden
  $ok = $false
  for ($i=1; $i -le 25; $i++) {
    Start-Sleep -Seconds 2
    try { if ((Invoke-WebRequest 'http://127.0.0.1:5174/' -TimeoutSec 3 -UseBasicParsing).StatusCode -eq 200) { $ok=$true; break } } catch {}
  }
  if (-not $ok) { Write-Host "❌ 前端未起" -ForegroundColor Red; exit 1 }
  "  前端 UP"

  "=== ⭐ 从【前端端口】打后端接口（验证代理）==="
  $fail = 0
  foreach ($u in @('/api/v1/tasks?pageSize=2', '/api/v1/categories?level=1', '/src/main.ts')) {
    $c = & curl.exe -s -o NUL -w "%{http_code}" "http://127.0.0.1:5174$u"
    $mark = if ($c -eq '200') { '✅' } else { $fail++; '❌' }
    "  $mark GET :5174$u → HTTP $c"
  }
  if ($fail -gt 0) { Write-Host "❌ 有 $fail 项失败" -ForegroundColor Red; exit 1 }
  Write-Host "✅ 端到端通过：前端能经代理取到后端数据" -ForegroundColor Green
}
finally {
  if ($fe) { Stop-Process -Id $fe.Id -Force -ErrorAction SilentlyContinue }
  Stop-Process -Id $be.Id -Force -ErrorAction SilentlyContinue
  "  已停止前后端"
}
