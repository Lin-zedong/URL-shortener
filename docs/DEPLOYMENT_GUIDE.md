# URL_Shortener 分布式落地部署说明

本说明对应课程作业中 low-level design 所要求的运行边界：
- reverse proxy
- management-service
- redirect-service
- background-worker
- PostgreSQL
- Redis

项目中保留了两种运行方式：
1. `demo`：单进程、文件快照、进程内缓存和队列，仅用于快速演示。
2. `distributed-idea` / `distributed-container`：外接 PostgreSQL、外接 Redis、独立 worker，符合 low-level design 的物理边界。

## 一、需要安装的软件

### 方案 A：推荐，适合答辩
- IntelliJ IDEA
- JDK 21
- Docker Desktop（需要支持 Docker Compose V2）

此方案下：
- PostgreSQL、Redis、Nginx 由 Docker 提供
- Java 代码由 IDEA 运行

### 方案 B：全容器部署
- Docker Desktop（需要支持 Docker Compose V2）

此方案下：
- Java 服务、PostgreSQL、Redis、Nginx 全部由 Docker Compose 启动

## 二、项目目录中与部署相关的关键文件

- `deploy/docker-compose-infra.yml`
  - 只启动 PostgreSQL、Redis、Nginx
  - 适合在 IDEA 中分别运行 Java 服务

- `deploy/docker-compose-full.yml`
  - 启动完整栈：PostgreSQL、Redis、management-service、redirect-service、background-worker、Nginx

- `deploy/initdb/01-schema.sql`
  - PostgreSQL 初始化表结构

- `deploy/nginx/nginx-idea.conf`
  - Nginx 反向代理到宿主机上的 IDEA 进程

- `deploy/nginx/nginx-full.conf`
  - Nginx 反向代理到 Docker 中的 Java 容器

- `deploy/certs/localhost.crt`
- `deploy/certs/localhost.key`
  - 本地 HTTPS 证书

- `urlshortener/bootstrap/ManagementMain.java`
- `urlshortener/bootstrap/RedirectMain.java`
- `urlshortener/bootstrap/WorkerMain.java`
  - 三个独立 Java 启动入口

- `urlshortener/loadtest/RunDistributedLoadTestsInIdea.java`
- `urlshortener/loadtest/RunDistributedCapacityTestsInIdea.java`
  - 面向外部部署的压测入口

## 三、方案 A：IDEA + Docker 基础设施

### 第 1 步：启动基础设施
在项目根目录执行：

Windows PowerShell：
```powershell
.\scripts\start_infra.ps1
```

Windows CMD：
```cmd
scripts\start_infra.bat
```

macOS / Linux：
```bash
./scripts/start_infra.sh
```

启动成功后：
- PostgreSQL 监听 `localhost:5432`
- Redis 监听 `localhost:6379`
- Nginx 监听 `https://localhost`

### 第 2 步：在 IDEA 中打开工程
打开项目根目录 `URL_Shortener`。

### 第 3 步：运行 3 个 Java 进程
IDEA 中依次运行：
- `Management Service URL Shortener`
- `Redirect Service URL Shortener`
- `Background Worker URL Shortener`

这三个 Run Configuration 已经放在 `.idea/runConfigurations/` 中。

### 第 4 步：访问系统
浏览器打开：
```text
https://localhost
```

如果浏览器提示证书不受信任，选择继续访问即可。该证书仅用于本地演示。

### 第 5 步：运行分布式压测
在 IDEA 中运行：
- `Распределённое нагрузочное тестирование URL Shortener`
- `Распределённые capacity-сценарии URL Shortener`

压测结果会生成到：
```text
loadtest-results/
```

## 四、方案 B：完整容器化部署

在项目根目录执行：

Windows PowerShell：
```powershell
.\scripts\start_full_stack.ps1
```

Windows CMD：
```cmd
scripts\start_full_stack.bat
```

macOS / Linux：
```bash
./scripts/start_full_stack.sh
```

启动后浏览器访问：
```text
https://localhost
```

停止完整栈：

Windows PowerShell：
```powershell
.\scripts\stop_full_stack.ps1
```

Windows CMD：
```cmd
scripts\stop_full_stack.bat
```

macOS / Linux：
```bash
./scripts/stop_full_stack.sh
```

## 五、如果你想继续保留原来的单进程 demo 方式

直接运行：
```text
urlshortener.Main
```

这是 demo 模式，内部仍然使用：
- `FileDataStore`
- `HotLinkCache`
- `ClickEventQueue`

仅用于快速演示，不作为与 low-level design 严格对应的最终部署方式。

## 六、如果你想在一个 JVM 中连外部 PostgreSQL / Redis 进行联调

设置环境变量：
- `APP_MODE=distributed-all-in-one`
- `APP_DB_HOST=localhost`
- `APP_REDIS_HOST=localhost`

然后运行：
```text
urlshortener.Main
```

这会启动：
- edge
- management
- redirect
- worker

但底层使用外部 PostgreSQL 和 Redis。它适合本地调试，不作为最终严格答辩形态。

## 七、答辩时建议使用的口径

建议向老师说明：
1. 当前最终落地版本采用物理分离部署。
2. 浏览器通过 Nginx reverse proxy 进入系统。
3. management-service、redirect-service、background-worker 为独立运行边界。
4. 数据持久化落在 PostgreSQL。
5. hot-link cache 与 click event stream 落在 Redis。
6. 单进程 `demo` 模式仅保留为快速演示和回归调试用途，不作为严格 low-level design 的最终交付形态。
