# 本地开发环境

> ⚠️ **本文件记录的是本机（开发机）的实际情况**，与计划里的假设有偏差，均已在此说明。

## 一、与计划的环境偏差

| 项 | 计划假设 | 本机实际 | 处理 |
|---|---|---|---|
| Docker | 用 `docker-compose.dev.yml` 起全套中间件 | ❌ **未安装 Docker** | 本地改用**已在运行的 MySQL 8.4 服务**；`docker-compose.dev.yml` 保留给 CI / 有 Docker 的机器 |
| Maven | 已有 | ❌ 未安装 | 已装 **3.9.16** 到 `项目\.tools\apache-maven-3.9.16\`，并配置阿里云镜像 |
| Redis | 由 Docker 提供 | ❌ 未安装 | 已装 **Redis 5.0.14.1 for Windows** 到 `项目\.tools\redis\`，用 `start-local-redis.ps1` 启动 |
| MySQL | 8.0.36（Docker） | **8.4**（本机服务 `MySQL84`，正在运行） | 直接用；Flyway 9 会警告"未测过 8.4"，功能正常。生产目标仍是 8.0.36 |

## 二、启动步骤

### 1. 中间件

```powershell
# Redis（无密码；⛔ 配置文件里不能写 password: ""）
powershell -File deploy\start-local-redis.ps1

# MySQL：本机服务已随系统启动，确认即可
Get-Service MySQL84
```

> 有 Docker 的机器：`docker compose -f deploy/docker-compose.dev.yml up -d`，并把
> `application-dev.yml` 的 profile 换成 `dev,docker`。

### 2. 数据库

```powershell
$mysql = "C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe"
& $mysql -uroot -p123456 -e "CREATE DATABASE IF NOT EXISTS task_platform DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
& $mysql -uroot -p123456 -e "CREATE DATABASE IF NOT EXISTS task_platform_test DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
```

⛔ **不要手工建表** —— 表结构由 Flyway 迁移（`db/migration/V1–V6`）在应用启动时创建，保证 DDL 单一来源。

### 3. 构建与运行

```powershell
$mvn = "项目\.tools\apache-maven-3.9.16\bin\mvn.cmd"

# 测试（20 个）
& $mvn -pl platform-app -am test

# 打包（59.9 MB fat jar）
& $mvn -pl platform-app -am clean package -DskipTests

# 运行
& "C:\Program Files\Java\jdk-21.0.11\bin\java.exe" -jar platform-app\target\platform-app-1.0.0-SNAPSHOT.jar
```

### 4. 验证

```powershell
curl.exe -i http://127.0.0.1:8080/api/v1/health
curl.exe -i http://127.0.0.1:8080/api/v1/account/profile   # 期望 401 + 统一响应体
```

---

## 三、⛔ 本机踩过的坑（改代码前先看）

### 3.1 测量工具类（最容易误判）

| 坑 | 现象 | 正确做法 |
|---|---|---|
| **PowerShell `Invoke-WebRequest` 会吞掉错误响应体** | 401/403 看起来 body 是空的，让人以为服务端没写响应体 | ⛔ 验错误响应**一律用 `curl.exe -i`** 看原始文本 |
| **控制台中文是 mojibake** | Maven/Javac 的中文报错读不出来 | 把输出 `Out-File -Encoding UTF8` 写文件后用 `read` 工具读 |
| **PowerShell 5.1 不支持 `??`**，且对 `${` 解析脆弱 | 脚本解析报奇怪错误 | 复杂文本处理改用 Node |

### 3.2 配置类

| 坑 | 现象 | 修法 |
|---|---|---|
| **JDBC `characterEncoding=utf8mb4`** | `Unsupported character encoding 'utf8mb4'`（这是**服务端**字符集名，不是 Java 字符集名） | 写 `characterEncoding=UTF-8` |
| **`spring.data.redis.password: ""`** | `ERR Client sent AUTH, but no password is set` —— **空字符串 ≠ 无密码**，Redisson 会发 AUTH | 无密码就**不写这一行** |
| **Flyway 默认做占位符替换** | 种子数据里通知模板合法含 `${taskTitle}` → `No value provided for placeholder: ${var}` | `spring.flyway.placeholder-replacement: false` |
| **Flyway 会建 `flyway_schema_history`** | 统计表数得 72 而非 71（用 `mysql source` 时没有这张表） | 统计时排除它 |
| **自建父 pom 不会自动绑 `repackage`** | `package` 只出瘦 jar → `没有主清单属性` | 显式声明 `spring-boot-maven-plugin` 的 `repackage` execution |
| **Windows 文件锁** | 应用在跑时重新 `package` → 覆盖不了 jar → 出瘦 jar | 打包前先停应用 |

### 3.3 Maven 命令类

| 坑 | 现象 | 修法 |
|---|---|---|
| `-pl platform-app` 不带 `-am` | `Could not find artifact com.dsh.platform:platform-common` | 加 `-am`（同时构建依赖模块） |
| `-Dtest=X` 被应用到所有模块 | `platform-common` 报 `No tests matching pattern` | 加 `-Dsurefire.failIfNoSpecifiedTests=false` |
| `spring-boot:run` 配合 `-am` | 跑到父工程上报 `Unable to find a suitable main class` | 打包后用 `java -jar`，或在 `install` 后单独 `-pl platform-app` |

### 3.4 测试类

| 坑 | 现象 | 修法 |
|---|---|---|
| **只有 `slf4j-api` 时 MDC 退化为 NOP** | `MDC.put()` 静默无效 → traceId 恒空，测试假失败 | 测试作用域加 `logback-classic` |
| **每个测试各调一次 `migrate()`** | 测试之间互相依赖执行顺序，没调的那个看到空库 | `@BeforeAll` 里 `clean` + `migrate` **一次**，其余测试只断言 |
| **MockMvc 通过 ≠ 真实 HTTP 通过** | 两者对错误响应/容器错误页的处理不同 | 关键端点补一次真实 HTTP 验证（用 curl） |
