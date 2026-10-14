# web-user 端到端核验：前端 → 后端（经 Vite 代理）
#
# 为什么要有这个脚本：只验证"能构建"不够 —— 构建通过但代理配错、
# 或者前端把绝对地址写死，都只在真正联调时才暴露，而那时人会先怀疑后端。
#
# ⛔⛔ 本脚本第一版有两个致命问题，已修（都由独立审计发现）：
#   1. 文件是 UTF-8【无 BOM】→ PowerShell 5.1 按 GBK 解码 → 语法错、解析期即死
#      ⇒ 【这个脚本从来没有成功跑过一次】。而当时 README 里贴了"实测结果"，
#        那段输出其实来自手工内联命令 —— 属于假证据。
#   2. 断言只判 HTTP 200 ⇒ 毫无意义。Vite 的 SPA fallback 对【任何未知路径】
#      都返回 index.html + 200，所以【代理完全没配】时断言照样全 PASS。
#      实测：把那套断言打向没有配 proxy 的 dev server，它打印
#      "端到端通过：前端能经代理取到后端数据"，而响应是 text/html。
#
# ⇒ 所以现在断言【三件事】，缺一不可：
#      ① HTTP 状态码  ② Content-Type 是 application/json  ③ 信封里的 code
#    并额外做一次【反证】：打一个不存在的接口，必须返回 JSON（= 请求确实到了后端）。
#    若返回 HTML，说明落到了 SPA fallback ⇒ 代理没生效。
#
# 用法：powershell -File scripts/verify-e2e.ps1
# 前置：后端已 package（mvn -pl platform-app -am package -DskipTests）

$ErrorActionPreference = 'Stop'
# scripts → web-user → apps → 仓库根（少退一层会把 jar 路径算到 apps\ 下面）
$root = Split-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) -Parent
$jar  = Join-Path $root 'platform-app\target\platform-app-1.0.0-SNAPSHOT.jar'
$java = 'C:\Program Files\Java\jdk-21.0.11\bin\java.exe'
$FE_PORT = 5174
$BE = 'http://127.0.0.1:8080'

if (-not (Test-Path $jar)) { Write-Host "找不到 jar（先 package）：$jar" -ForegroundColor Red; exit 1 }

$startedBe = $null
$startedFe = $null
$script:fail = 0

function Assert-Api {
    param([string]$Name, [string]$Url, [int]$WantHttp = 200, [int]$WantCode = 0)
    $text = (& curl.exe -s -i $Url | Out-String)
    $head = ($text -split "`r?`n")[0]
    $http = if ($head -match 'HTTP/\d\.\d (\d{3})') { [int]$Matches[1] } else { 0 }
    $isJson = $text -match 'Content-Type:\s*application/json'
    $body = ($text -split "`r?`n`r?`n", 2)[1]
    $gotCode = $null
    if ($body -match '"code"\s*:\s*(-?\d+)') { $gotCode = [int]$Matches[1] }

    $ok = ($http -eq $WantHttp) -and $isJson -and ($gotCode -eq $WantCode)
    if (-not $ok) { $script:fail++ }
    $mark = if ($ok) { '[OK]  ' } else { '[FAIL]' }
    Write-Host ("  $mark $Name -> HTTP $http / JSON=$isJson / code=$gotCode  (期望 $WantHttp / JSON / $WantCode)")
}

try {
    '=== 1) 后端 ==='
    $beUp = $false
    try { if ((Invoke-RestMethod "$BE/actuator/health" -TimeoutSec 3).status -eq 'UP') { $beUp = $true } } catch {}
    if ($beUp) {
        '  复用已在运行的实例'
    } else {
        $startedBe = Start-Process -FilePath $java -ArgumentList '-jar', $jar -PassThru -WindowStyle Hidden
        for ($i = 1; $i -le 40; $i++) {
            Start-Sleep -Seconds 2
            try { if ((Invoke-RestMethod "$BE/actuator/health" -TimeoutSec 3).status -eq 'UP') { $beUp = $true; break } } catch {}
        }
        if (-not $beUp) { Write-Host '  后端未起' -ForegroundColor Red; exit 1 }
        '  已启动后端'
    }

    '=== 2) 前端 dev server ==='
    $feUp = $false
    try { if ((Invoke-WebRequest "http://127.0.0.1:$FE_PORT/" -TimeoutSec 3 -UseBasicParsing).StatusCode -eq 200) { $feUp = $true } } catch {}
    if (-not $feUp) {
        $startedFe = Start-Process -FilePath 'node' -ArgumentList 'node_modules\vite\bin\vite.js', '--port', "$FE_PORT", '--host', '127.0.0.1' -WorkingDirectory (Join-Path $root 'apps\web-user') -PassThru -WindowStyle Hidden
        for ($i = 1; $i -le 25; $i++) {
            Start-Sleep -Seconds 2
            try { if ((Invoke-WebRequest "http://127.0.0.1:$FE_PORT/" -TimeoutSec 3 -UseBasicParsing).StatusCode -eq 200) { $feUp = $true; break } } catch {}
        }
    }
    if (-not $feUp) { Write-Host '  前端未起' -ForegroundColor Red; exit 1 }
    "  前端 UP（:$FE_PORT）"

    '=== 3) 经代理取后端数据（三件事一起校验）==='
    Assert-Api -Name 'GET /api/v1/tasks?pageSize=2'   -Url "http://127.0.0.1:$FE_PORT/api/v1/tasks?pageSize=2"   -WantHttp 200 -WantCode 0
    Assert-Api -Name 'GET /api/v1/categories?level=1' -Url "http://127.0.0.1:$FE_PORT/api/v1/categories?level=1" -WantHttp 200 -WantCode 0
    # 受保护接口应为 401 + code 20001 —— 证明【鉴权也在链路上】，不只是能连通
    Assert-Api -Name 'GET /api/v1/account/profile'    -Url "http://127.0.0.1:$FE_PORT/api/v1/account/profile"    -WantHttp 401 -WantCode 20001

    '=== 4) 反证：SPA fallback 不能把"代理没配"伪装成通过 ==='
    $probe = (& curl.exe -s -i "http://127.0.0.1:$FE_PORT/api/v1/__no_such_endpoint__" | Out-String)
    if ($probe -match 'Content-Type:\s*application/json') {
        '  [OK]   未映射接口返回 JSON（说明请求确实经代理到了后端）'
    } else {
        $script:fail++
        '  [FAIL] 未映射接口返回 HTML —— 请求落到了 Vite 的 SPA fallback，代理【没有生效】'
    }

    ''
    if ($script:fail -gt 0) { Write-Host "有 $script:fail 项失败" -ForegroundColor Red; exit 1 }
    Write-Host '端到端通过：HTTP / Content-Type / 信封 code 三项全部符合，且代理确实生效' -ForegroundColor Green
}
finally {
    # ⛔ 只停【自己启动的】进程 —— 早先版本无条件 Stop-Process，会误杀别人的后端
    if ($startedFe) { Stop-Process -Id $startedFe.Id -Force -ErrorAction SilentlyContinue }
    if ($startedBe) { Stop-Process -Id $startedBe.Id -Force -ErrorAction SilentlyContinue }
    '  已停止本脚本启动的进程（复用的实例不动）'
}
