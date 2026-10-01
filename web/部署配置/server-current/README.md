# server-current（线上配置权威存档）

本目录是 **2026-10-01 23:19 从服务器拉取的、正在运行中的 nginx 配置原件**：

- `nginx.main.conf` ← 服务器 `/www/server/nginx/conf/nginx.conf`（含 `limit_req_zone api_limit rate=30r/s`）
- `book.*.conf` / `admin.*.conf` / `xn--*.conf`（官网）← `/www/server/panel/vhost/nginx/`
- `java_reading-app-backend.conf` ← 后端 IP 直连 vhost（含下载反代）

## 为什么存这份

`nginx.conf` / `nginx-baota.conf`（本目录上级）是**手写参考模板**，与宝塔服务器实际配置
结构不同（证书路径、301 写法、extension 目录等）。以后排查线上行为或重建环境，
**以本目录为准**；改线上配置先在这里更新存档。

## 线上关键点速查

- book 站 CSP `script-src` 含 `'unsafe-eval'`（vue.global.prod.js 运行时编译模板必需，缺了白屏）
- 限流：`rate=30r/s burst=60 nodelay`（10r/s 会误伤书城 SPA 并发）
- admin/book HTML 禁缓存：`location ~* \.html$ { expires -1; }`（勿用 add_header 实现，会丢 server 级安全头）
- 官网 301 为 scheme+host 双条件（保留 8089 IP 直连 http）
- 回滚备份：服务器 `/root/nginx_bak_20261001_203617/`
