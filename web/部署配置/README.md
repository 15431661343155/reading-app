# 书阁 · 管理后台（独立静态站点，子路径部署）

后台管理已从 Spring Boot 后端**彻底拆分**为纯静态网站，后端只保留 `/api/*` 接口与鉴权。
本目录（`web/后台/`）即为独立部署的后台前端，不依赖 Thymeleaf、不含任何服务端渲染。
站点使用**相对链接**（如 `books.html`、资源 `assets/...`），因此既可放在域名根、
也可放在 `web/后台/` 这样的子路径下访问，无需改动链接。

## 目录结构

```
web/后台/                       ← 后台站点根（web/ 整体作为站点静态根，后台在其子目录 后台/ 下）
├── index.html              # 管理概览
├── books.html              # 书籍管理
├── categories.html         # 分类管理
├── chapters.html           # 章节管理（从书籍管理「章节」入口带 ?bookId= 进入）
├── users.html              # 用户管理
├── user-reading.html       # 用户阅读数据
├── import.html             # 数据导入
├── online-source.html      # 在线书源
├── book-source.html        # 书源管理
├── resource.html           # 资源管理
├── feedback.html           # 意见反馈
├── login.html              # 登录页（独立，POST /api/admin/auth/login）
├── assets/
│   ├── css/admin-theme.css
│   ├── js/admin-auth.js        # 401 跳登录、填充用户名、退出登录
│   └── js/admin-category-picker.js
└── nginx.conf              # 子路径同源反代部署示例
```

## 鉴权说明（重要）

- 后台使用 **HttpOnly Cookie `ADMIN_TOKEN`（JWT）** 鉴权，`same-origin` 携带。
- 后端 `SecurityConfig` 的 CORS **故意不开 credentials**，所以后台与后端必须**同源**。
- 因此请使用下面的 Nginx 反代部署；不要直接把本目录丢到另一个域名下（Cookie 带不过去会全部 401）。
- 页面/资源链接均为**相对路径**，`/api` 保持根相对（同源反代依赖它），所以放在 `web/后台/` 子路径下可正常打开。

## 部署（推荐：同源反代，子路径）

见 `nginx.conf`：把 **整个 `web/`** 作为静态根（`root /var/www/web`），由 `/后台/` 独立兜底，
把 `/api/` 反代到后端。后台通过 `http://域名/后台/` 访问。

```bash
# 1) 把整个 web/ 目录传到服务器，如 /var/www/web（后台即 /var/www/web/后台）
# 2) 复制 nginx.conf 到 conf.d/，改 server_name / proxy_pass 端口
# 3) 校验并重载
nginx -t && nginx -s reload
```

访问 `http://你的域名/后台/` → `后台/index.html`；登录成功后跳转 `后台/index.html`。

> 注意：`nginx.conf` 的 `location /后台/` 含中文，务必保留 `charset utf-8;`；
> 若 nginx 对中文 location 有编码/匹配问题，可把 `后台/` 改名为 ASCII（如 `admin/`），
> 访问路径随之改变即可（站点用相对链接，无需改页面内容）。

## 本地预览（开发期）

纯静态打开即可看页面，但接口需要同源才能带 Cookie。最简单是在本机用 Nginx 按
`nginx.conf` 起一个同源反代（后端跑在 8081），或直接用 `python -m http.server` 仅做
页面预览（此时 `/api` 会跨域失败，仅用于检查布局）。

```bash
# 仅看页面布局（接口不可用）：
cd web/后台 && python -m http.server 5500
# 浏览器打开 http://127.0.0.1:5500/后台/
```

> 说明：`web/` 根下另有一个独立的「书阁 Web 端」（Vue3，SPA）。本后台站点作为
> `web/后台/` 子目录与 Vue 前端并列，两者相互独立；部署时 `web/` 作为整体静态根，
> `/后台/` 走后台的兜底、`/` 走 Vue 的 SPA 兜底。

## 后端侧已做的改动

- 删除 `AdminController.java`（不再需要渲染后台视图）。
- 删除 `src/main/resources/templates/admin/`（含 `fragments/nav.html`）。
- 删除后台专用静态资源 `static/css/admin-theme.css`、`static/js/admin-auth.js`、`static/js/admin-category-picker.js`（已迁移到本项目 `assets/`）。
- `SecurityConfig` 中 `/admin/**` 的页面规则现为**死代码**（无害）：Nginx 已直接托管后台页，
  后端不会再收到 `/admin/**` 页面请求；如需整洁可一并删除，但属于可选清理，不影响运行。

## 回退

若需把后台重新并回后端：从 git 历史恢复上述被删文件，并恢复 `AdminController` 即可
（本拆分未改动任何 `/api` 业务逻辑）。
