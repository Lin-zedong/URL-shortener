# 原有新版代码需要添加/修改的关键点

## 1. 新增抽象层，去掉业务层对文件快照的强耦合

新增：
- `urlshortener/store/DataStore.java`
- `urlshortener/service/LinkCache.java`
- `urlshortener/service/ClickEventBus.java`

作用：
- 让 `UserService`、`SessionService`、`ShortLinkService`、`RedirectService`、`AnalyticsService` 不再直接绑定 `FileDataStore`、`HotLinkCache`、`ClickEventQueue`
- 同一套业务逻辑既可以跑 demo，也可以跑分布式部署

## 2. 保留 demo 实现，同时引入严格部署实现

保留：
- `FileDataStore`
- `HotLinkCache`
- `ClickEventQueue`

新增：
- `urlshortener/store/PostgresDataStore.java`
- `urlshortener/store/pg/*`
- `urlshortener/service/redis/*`

作用：
- `FileDataStore` 继续用于单进程 demo
- `PostgresDataStore` 对应正式部署中的 PostgreSQL
- `RedisLinkCache` 对应 hot-link cache
- `RedisClickEventBus` 对应 click event stream

## 3. 新增独立启动入口

新增：
- `urlshortener/bootstrap/ManagementMain.java`
- `urlshortener/bootstrap/RedirectMain.java`
- `urlshortener/bootstrap/WorkerMain.java`
- `urlshortener/bootstrap/DistributedAllInOneMain.java`
- `urlshortener/bootstrap/DistributedComponentFactory.java`

作用：
- 满足 low-level design 中“独立运行边界”的要求
- 让 management、redirect、worker 可以分别启动
- 也保留一个连接外部 PostgreSQL / Redis 的联调入口

## 4. 修改 Main 的语义

修改：
- `urlshortener/Main.java`

现在：
- 默认仍是 demo 模式
- 当 `APP_MODE=distributed-all-in-one` 时，切换为外接 PostgreSQL / Redis 的联调模式

## 5. 让负载测试既能跑 demo，也能跑外部部署

新增：
- `urlshortener/loadtest/RunDistributedLoadTestsInIdea.java`
- `urlshortener/loadtest/RunDistributedCapacityTestsInIdea.java`

修改：
- `urlshortener/loadtest/LoadTestRunner.java`

现在：
- 默认仍可跑自包含 demo 压测
- 加上 `--external=true` 后，可以直接压外部部署好的 PostgreSQL + Redis + Nginx 环境

## 6. 补充部署文件

新增：
- `deploy/docker-compose-infra.yml`
- `deploy/docker-compose-full.yml`
- `deploy/Dockerfile`
- `deploy/initdb/01-schema.sql`
- `deploy/nginx/nginx-idea.conf`
- `deploy/nginx/nginx-full.conf`
- `deploy/certs/localhost.crt`
- `deploy/certs/localhost.key`

作用：
- 支持基础设施先启动、Java 代码在 IDEA 中运行
- 也支持全容器部署

## 7. 补充 IDEA 运行配置

新增：
- `Management Service URL Shortener`
- `Redirect Service URL Shortener`
- `Background Worker URL Shortener`
- `Распределённое нагрузочное тестирование URL Shortener`
- `Распределённые capacity-сценарии URL Shortener`

## 8. 补充浏览器和反向代理 HTTPS 配合

修改：
- `urlshortener/util/HttpUtils.java`

作用：
- 在 `X-Forwarded-Proto=https` 场景下，为 Cookie 自动加上 `Secure`
