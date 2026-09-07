#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""为七猫书源添加书城分类（exploreUrl）和解析规则（ruleExplore）。
书城数据来自七猫官网排行榜 API（无需签名），book_id 与 app API 兼容。
"""
import json
import subprocess
import sys

MYSQL = r"D:\phpstudy_pro\Extensions\MySQL8.0.12\bin\mysql.exe"
DB_USER = "reading-app"
DB_PASS = "123456"
DB_NAME = "reading-app"
SOURCE_ID = 27

# 6 个分类：男生/女生 × 大热榜/新书榜/完结榜
# rank_type: 1=大热榜 2=新书榜 3=完结榜；is_girl: 0=男生 1=女生
EXPLORE_URL = (
    "男生大热榜::https://www.qimao.com/api/rank/book-list?is_girl=0&rank_type=1&date_type=1&date=&page={{page}}\n"
    "男生新书榜::https://www.qimao.com/api/rank/book-list?is_girl=0&rank_type=2&date_type=1&date=&page={{page}}\n"
    "男生完结榜::https://www.qimao.com/api/rank/book-list?is_girl=0&rank_type=3&date_type=1&date=&page={{page}}\n"
    "女生大热榜::https://www.qimao.com/api/rank/book-list?is_girl=1&rank_type=1&date_type=1&date=&page={{page}}\n"
    "女生新书榜::https://www.qimao.com/api/rank/book-list?is_girl=1&rank_type=2&date_type=1&date=&page={{page}}\n"
    "女生完结榜::https://www.qimao.com/api/rank/book-list?is_girl=1&rank_type=3&date_type=1&date=&page={{page}}"
)

# 解析规则：官网排行榜 API 返回 $.data.table_data 数组
RULE_EXPLORE = {
    "bookList": "$.data.table_data",
    "name": "$.title",
    "author": "$.author",
    "bookUrl": "@js:qmBookDetailUrl.call(this,{{$.book_id}})",
    "coverUrl": "$.image_link",
    "intro": "$.intro",
    "kind": "$.category1_name",
    "wordCount": "$.words_num",
    "lastChapter": "$.latest_chapter_title",
    "updateTime": "$.update_time"
}


def mysql_query(sql):
    cmd = [MYSQL, "-u", DB_USER, f"-p{DB_PASS}", DB_NAME, "--raw", "-N", "-e", sql]
    result = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8")
    if result.returncode != 0:
        print(f"MySQL error: {result.stderr}", file=sys.stderr)
        sys.exit(1)
    return result.stdout


def mysql_exec(sql):
    cmd = [MYSQL, "-u", DB_USER, f"-p{DB_PASS}", DB_NAME, "-e", sql]
    result = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8")
    if result.returncode != 0:
        print(f"MySQL error: {result.stderr}", file=sys.stderr)
        sys.exit(1)
    return result.stdout


def main():
    # 1. 读取当前 config_json
    config_json = mysql_query(f"SELECT config_json FROM book_source WHERE id={SOURCE_ID};").strip()
    if not config_json:
        print("ERROR: config_json is empty", file=sys.stderr)
        sys.exit(1)
    print(f"Current config_json length: {len(config_json)}")

    # 2. 解析、替换字段
    obj = json.loads(config_json)
    print(f"Old exploreUrl: {obj.get('exploreUrl', '(无)')!r}")
    print(f"Old ruleExplore: {obj.get('ruleExplore', '(无)')!r}")
    print(f"Old enabledExplore: {obj.get('enabledExplore', '(无)')}")

    obj["exploreUrl"] = EXPLORE_URL
    obj["ruleExplore"] = [RULE_EXPLORE]  # 数组形式，兼容 getExploreRuleAsObject
    obj["enabledExplore"] = True

    new_config = json.dumps(obj, ensure_ascii=False, separators=(",", ":"))
    print(f"New config_json length: {len(new_config)}")
    print(f"New exploreUrl lines: {len(EXPLORE_URL.splitlines())}")

    # 3. 写回数据库
    escaped = new_config.replace("\\", "\\\\").replace("'", "\\'")
    sql = f"UPDATE book_source SET config_json = '{escaped}' WHERE id = {SOURCE_ID};"
    mysql_exec(sql)
    print("Database updated successfully!")

    # 4. 验证
    verify = mysql_query(
        f"SELECT JSON_EXTRACT(config_json, '$.exploreUrl'), "
        f"JSON_EXTRACT(config_json, '$.enabledExplore') "
        f"FROM book_source WHERE id={SOURCE_ID};"
    )
    print(f"Verified: {verify.strip()[:200]}")


if __name__ == "__main__":
    main()
