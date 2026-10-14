# web-admin 端到端核验：登录链路的可达性。
#
# 为什么管理端要单独验这条：用户端大厅是【匿名】的，管理端【没有匿名态】。
# 登录链路不通，后面的审核台全是摆设。
#
# 用法：powershell -File scripts/verify-e2e.ps1
#      （本机 pwsh 不在 PATH，用 powershell 或完整路径）

$ErrorActionPreference = 'Stop'
$root = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$jar  = Join-Path $root 'platform-app\target\platform-app-1.0.0-SNAPSHOT.jar'
$java = 'C:\Program Files\Java\jdk-21.0.11\bin\java.exe'

if (-not (Test-Path $jar)) { Write-Host '找不到 jar（先 package）' -ForegroundColor Red; exit 1 }

'=== 起后端 ==='
$be = Start-Process -FilePath $java -ArgumentList '-jar', $jar -PassThru -WindowStyle Hidden
$ok = $false
for ($i=1; $i -le 40; $i++) {
  Start-Sleep -Seconds 2
  try { if ((Invoke-RestMethod 'http://127.0.0.1:8080/actuator/health' -TimeoutSec 3).status -eq 'UP') { $ok=$true; break } } catch {}
}
if (-not $ok) { Stop-Process -Id $be.Id -Force; Write-Host '后端未起' -ForegroundColor Red; exit 1 }
'  后端 UP'

$fe = $null
try {
  '=== 起管理端 ==='
  $fe = Start-Process -FilePath 'node' -ArgumentList 'node_modules\vite\bin\vite.js','--port','5175','--host','127.0.0.1' -PassThru -WindowStyle Hidden
  $ok = $false
  for ($i=1; $i -le 25; $i++) {
    Start-Sleep -Seconds 2
    try { if ((Invoke-WebRequest 'http://127.0.0.1:5175/' -TimeoutSec 3 -UseBasicParsing).StatusCode -eq 200) { $ok=$true; break } } catch {}
  }
  if (-not $ok) { Write-Host '管理端未起' -ForegroundColor Red; exit 1 }
  '  管理端 UP'

  $fail = 0
  '=== 登录链路可达性（从管理端端口打，验证代理）==='
  foreach ($case in @(
    @{ n='/account/profile 无令牌';  u='/api/v1/account/profile';            w='401' },
    @{ n='/op 域 无令牌';            u='/api/v1/op/real-name-audits/1';      w='401' },
    @{ n='/admin 域 无令牌';         u='/api/v1/admin/sensitive-words';      w='401' }
  )) {
    $c = & curl.exe -s -o NUL -w '%{http_code}' ('http://127.0.0.1:5175' + $case.u)
    if ($c -eq $case.w) { "  [OK] $($case.n) -> HTTP $c" }
    else { $fail++; "  [FAIL] $($case.n) -> HTTP $c (期望 $($case.w))" }
  }
  # 登录接口本身必须【可达】：错误凭据应是业务失败(200+success=false)，
  # 而不是 404/401 路由不存在 —— 后者说明接口没注册或没放行。
  $lc = & curl.exe -s -o NUL -w '%{http_code}' -X POST -H 'Content-Type: application/json' -d '{}' 'http://127.0.0.1:5175/api/v1/auth/login'
  if ($lc -eq '200') { '  [OK] 登录接口可达 -> HTTP 200' }
  else { $fail++; "  [FAIL] 登录接口 -> HTTP $lc" }

  if ($fail -gt 0) { Write-Host "有 $fail 项失败" -ForegroundColor Red; exit 1 }
  Write-Host '管理端端到端通过：登录链路可达，且运营/管理域确实受保护' -ForegroundColor Green
}
finally {
  if ($fe) { Stop-Process -Id $fe.Id -Force -ErrorAction SilentlyContinue }
  Stop-Process -Id $be.Id -Force -ErrorAction SilentlyContinue
  '  已停止'
}
