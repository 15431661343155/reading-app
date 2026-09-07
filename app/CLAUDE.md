# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Android book reader app (Chinese language) supporting local (TXT, EPUB) and online books. Package: `com.example.myapplication`. Java only — no Kotlin.

## Build & Run

```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Run unit tests (placeholder only — no real tests exist)
./gradlew test

# Run instrumented tests (requires device/emulator)
./gradlew connectedAndroidTest

# Clean build
./gradlew clean
```

Output APKs go to `app/build/outputs/apk/`.

## Architecture

Single-module Gradle project (`:app`). AGP 7.4.1, compileSdk 34, minSdk 24, Java 8.

### Source packages (`app/src/main/java/com/example/myapplication/`)

- **`activity/`** — All Activities. `LoginActivity` is the launcher. `ReadActivity` (~96KB) is the core reading screen using a WebView with JS bridge for pagination and rendering. `BaseActivity` applies the current theme before `super.onCreate()`.
- **`fragment/`** — `MainActivity` hosts 4 bottom-nav fragments: `BookShelfFragment`, `BookStoreFragment`, `DiscussionFragment`, `MineFragment`. Popup fragments (`PopupBookmarkFragment`, `PopupChapterFragment`) overlay `ReadActivity`.
- **`adapter/`** — RecyclerView adapters for books, chapters, bookmarks, reading records.
- **`api/`** — `RetrofitClient` (singleton, base URL `http://47.98.102.24:8080/`) and `ApiService` (Retrofit interface for all REST endpoints: auth, books, chapters, bookshelf, progress, bookmarks, read time).
- **`bean/`** — Data models. `ShelfBook` is the Room entity for the local bookshelf table.
- **`dao/`** — `BookDao` is the Room DAO for `shelf_books`. Note: no `RoomDatabase` subclass exists in the `database/` package (empty directory) — Room integration is incomplete.
- **`manager/`** — Key reading subsystem managers:
  - `ChapterLoader` — chapter data loading, caching, and prefetching
  - `ProgressTracker` — reading progress save/load/sync (SharedPreferences)
  - `ReadingSettingsManager` — font size, background, night mode, brightness, header/footer (SharedPreferences + JS calls to WebView)
  - `ReadingStateManager` — centralizes `ReadActivity` state (chapter/page indices, WebView readiness, loading flags)
- **`utils/`** — `LocalBookParser` (~49KB) parses TXT and EPUB with regex-based chapter detection for Chinese/English markers. `ThemeManager` is a singleton managing 4 runtime-switchable themes (Ocean, Violet, Forest, Sunset) with recursive view-tree color updates.
- **`view/`** — Empty directory.

### Reading Engine

`ReadActivity` renders book content in a `WebView`. Java↔JS communication via `@JavascriptInterface` methods handles pagination, font changes, bookmark positions, and header/footer. `ReadingSettingsManager.applyToWebView()` calls JS functions like `applyFontSize()`, `applyBgColor()`.

### Theme System

4 themes defined in XML resources, applied at runtime by `ThemeManager` without restart. Each theme defines: primary, primary_dark, accent, background, card_bg, text_primary, text_secondary, nav_bg, nav_selected, nav_unselected. `BaseActivity` applies the current theme before calling `super.onCreate()`.

## Key Notes

- **API server is HTTP** (`usesCleartextTraffic=true`). Base URL in `RetrofitClient.java` points to a cloud server; local testing requires changing it to the machine's LAN IP.
- **Storage permission**: App uses `MANAGE_EXTERNAL_STORAGE` for local book file access.
- **No real test coverage**: Only auto-generated stub tests exist.
- **Room is partially implemented**: Entity and DAO exist but no `RoomDatabase` class — the `database/` package is empty.
- **Large files**: `ReadActivity.java` (~96KB) and `LocalBookParser.java` (~49KB) are the two largest source files and contain most of the app's complexity.
- **Root directory clutter**: ~40+ Chinese-named markdown files at project root are AI-generated development logs, not part of the build. A `ThemeSettingActivity示例代码.java` snippet file also sits outside the source tree.
- **Repository mirrors**: Gradle uses Aliyun (Google, JCenter) and JitPack — important if dependency resolution fails.
- **ProGuard disabled** for release builds (`minifyEnabled false`).
