# 书阁阅读

一个面向中文阅读场景的电子书阅读应用，支持**本地导入**（TXT / EPUB）与**在线书源**两大内容来源，配套 Spring Boot 后端提供账号、书架同步、书源分发与应用内更新能力。

- Android 客户端版本：`v2.0.16`（versionCode 2）
- 包名：`com.example.myapplication`
- 后端：`reading-app-backend` 0.0.1-SNAPSHOT

---

## 功能特性

### Android 客户端

| 模块 | 说明 |
| --- | --- |
| **阅读器** | 基于 WebView 的排版引擎，Java ↔ JS 双向桥接实现分页、字号调整、书签定位、页眉页脚 |
| **本地导入** | TXT / EPUB 解析，正则识别中英文章节标记（`第X章`、`Chapter N` 等） |
| **在线书源** | 支持导入 Legado（阅读 3.0）书源规则，在线搜索、目录拉取、正文抓取 |
| **书架** | 本地书架 + 服务端同步，封面加载、阅读进度记录 |
| **阅读设置** | 字号、背景、夜间模式、亮度、页眉页脚，实时生效无需重启 |
| **主题系统** | 4 套运行时主题（海洋 / 紫罗兰 / 森林 / 日落），切换后递归刷新整个视图树 |
| **书签与目录** | 章节列表快速跳转、书签增删、阅读历史 |
| **账号体系** | 注册登录、手机/邮箱绑定、修改密码、头像裁剪、意见反馈、消息中心 |
| **应用内更新** | 检查新版本、下载并请求安装 APK |

共 23 个 Activity、10 个 Fragment，入口为 `SplashActivity` → `LoginActivity` → `MainActivity`（底部导航四个 Tab：书架 / 书城 / 讨论 / 我的）。

### 后端服务

25 个 Controller、16 个 Service、20 个实体，主要能力：

- **认证与用户**：JWT 登录鉴权、注册、手机 / 邮箱绑定、安全管理
- **书籍与章节**：书籍 CRUD、目录（含 `MajorChapter` 卷级结构）、正文抓取与缓存
- **书源管理**：Legado 书源导入与解析（`legado` 包）、在线书源分发
- **阅读数据**：阅读进度同步、书签、阅读时长统计
- **内容运营**：字体资源、封面、公版书导入、反馈与消息
- **运维能力**：APK 上传与版本管理、更新推送、操作日志、Actuator 健康检查

---

## 技术栈

**Android 客户端**

- 语言：Java 8（纯 Java，无 Kotlin）
- Android Gradle Plugin 7.4.1，`compileSdk 34` / `targetSdk 34` / `minSdk 24`
- 网络：Retrofit 2.9 + OkHttp 4.11 + Gson 2.10
- 图片：Glide 4.16
- 本地存储：Room 2.5.2（实体与 DAO 已就绪）
- UI：Material Components 1.11、ViewPager2、RecyclerView FastScroll、ViewPagerIndicator

**后端服务**

- Spring Boot 4.0.5 + Java 17
- Spring WebMVC / Security / Validation / Data JPA
- MySQL（`mysql-connector-j`）+ H2（测试）
- JWT（jjwt 0.12.5）、Lombok、Thymeleaf、Actuator

---

## 项目结构

```
reading-app/
├── app/                              # Android 客户端（Gradle 项目）
│   ├── app/
│   │   ├── build.gradle              # 模块构建配置
│   │   └── src/main/java/com/example/myapplication/
│   │       ├── activity/             # 23 个页面
│   │       ├── fragment/             # 10 个 Fragment（含书签/章节弹层）
│   │       ├── adapter/              # RecyclerView 适配器
│   │       ├── api/                  # RetrofitClient + ApiService
│   │       ├── bean/                 # 数据模型（含 Room 实体 ShelfBook）
│   │       ├── manager/              # ChapterLoader / ProgressTracker /
│   │       │                         # ReadingSettingsManager / ReadingStateManager
│   │       └── utils/                # LocalBookParser / ThemeManager
│   └── *.md                          # 各功能模块的中文开发文档
│
├── reading-app-backend/              # Spring Boot 后端
│   └── src/main/java/.../
│       ├── controller/               # 25 个 REST 控制器
│       ├── service/                  # 16 个业务服务
│       ├── entity/                   # 20 个 JPA 实体
│       ├── repository/               # 数据访问层
│       ├── legado/                   # Legado 书源规则解析
│       ├── config/  dto/  exception/  util/
│       └── resources/
│           ├── application.properties.example   # 配置模板（真实配置不入库）
│           └── templates/  static/  public-domain-books/
│
├── legado-ref/                       # Legado 解析规则参考实现
├── screenshots/                      # 开发过程截图
└── ppt_work/                         # 演示文稿素材
```

---

## 快速开始

### 一、启动后端

```bash
cd reading-app-backend

# 1. 复制配置模板并填入你的真实配置
cp src/main/resources/application.properties.example \
   src/main/resources/application.properties

# 2. 准备 MySQL 数据库（库名与 application.properties 中的 URL 保持一致）
#    ddl-auto=update 会自动建表

# 3. 启动
./mvnw spring-boot:run          # Linux / macOS
mvnw.cmd spring-boot:run        # Windows
```

服务默认运行在 `http://localhost:8080`。

> ⚠️ `application.properties` 已加入 `.gitignore`，**真实配置不会提交**。必须填写的项：数据库连接、`jwt.secret`、邮件账号密码、短信密钥（模板中以 `<CHANGE_ME>` 标注）。`jwt.secret` 请自行生成，**不要复用示例值**。

### 二、构建 Android 客户端

```bash
cd app

./gradlew assembleDebug      # 调试包
./gradlew assembleRelease    # 发布包（未开启混淆）
./gradlew clean              # 清理
```

产物输出到 `app/app/build/outputs/apk/`，文件名格式为 `书阁阅读_v{版本}_{日期}.apk`。

**连接后端**：修改 `api/RetrofitClient.java` 中的 `BASE_URL`。真机调试填电脑的局域网 IP，模拟器可用 `10.0.2.2`。后端为 HTTP 协议，Manifest 已开启 `usesCleartextTraffic`。

---

## 核心模块说明

**阅读引擎** — `ReadActivity` 用 WebView 渲染正文，通过 `@JavascriptInterface` 暴露分页、字号、书签定位等能力给 JS 调用；`ReadingSettingsManager.applyToWebView()` 驱动 JS 侧的 `applyFontSize()`、`applyBgColor()` 等方法，实现设置即时生效。

**章节加载** — `ChapterLoader` 负责章节数据的加载、缓存与预加载；`ReadingStateManager` 集中管理章节/页码索引、WebView 就绪状态与加载标志，规避翻页竞态。

**主题系统** — `ThemeManager` 单例维护 4 套主题，每套定义 primary、background、text_primary 等 10 个色值；`BaseActivity` 在 `super.onCreate()` 之前应用当前主题，切换后递归遍历视图树刷新颜色，无需重启 Activity。

**书源解析** — 后端 `legado` 包实现 Legado 书源的规则解析（搜索、目录、正文、详情四类规则），客户端通过接口获取解析后的内容。

---

## 注意事项

- **存储权限**：应用申请 `MANAGE_EXTERNAL_STORAGE` 以读取本地书籍文件，Android 11+ 需用户手动授权"所有文件访问权限"。
- **Room 尚未完整接入**：`ShelfBook` 实体与 `BookDao` 已实现，但 `database/` 包下缺少 `RoomDatabase` 子类，本地书架持久化实际依赖服务端同步。
- **测试覆盖不足**：仅有自动生成的占位测试，无实际单元测试。
- **大文件**：`ReadActivity.java`（约 96 KB）与 `LocalBookParser.java`（约 49 KB）承载了主要复杂度，修改前建议先通读。
- **依赖仓库**：Gradle 使用阿里云与 JitPack 镜像，若依赖解析失败请检查网络或镜像配置。
- **发布包未混淆**：`minifyEnabled false`，正式发布前建议配置 ProGuard 规则。

---

## 开发文档

`app/` 目录下有 40+ 篇中文开发笔记，记录各功能的实施方案与问题排查过程，涵盖 EPUB 解析、主题系统、网络书籍加载、页眉页脚、书签管理等。建议使用前先按关键字检索。

---

## 许可

本项目为个人项目，未声明开源许可，默认保留所有权利。
