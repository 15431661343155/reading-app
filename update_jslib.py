#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""更新七猫书源的 jsLib 字段"""
import json
import subprocess
import sys

MYSQL = r"D:\phpstudy_pro\Extensions\MySQL8.0.12\bin\mysql.exe"
DB_USER = "reading-app"
DB_PASS = "123456"
DB_NAME = "reading-app"
SOURCE_ID = 27
JSLIB_FILE = r"d:\android\reading-app\new_jslib.js"

def mysql_query(sql):
    """Execute SQL and return output"""
    cmd = [MYSQL, "-u", DB_USER, f"-p{DB_PASS}", DB_NAME, "--raw", "-N", "-e", sql]
    result = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8")
    if result.returncode != 0:
        print(f"MySQL error: {result.stderr}", file=sys.stderr)
        sys.exit(1)
    return result.stdout

def mysql_exec(sql):
    """Execute SQL (no output needed)"""
    cmd = [MYSQL, "-u", DB_USER, f"-p{DB_PASS}", DB_NAME, "-e", sql]
    result = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8")
    if result.returncode != 0:
        print(f"MySQL error: {result.stderr}", file=sys.stderr)
        sys.exit(1)
    return result.stdout

def main():
    # 1. Read new jsLib
    with open(JSLIB_FILE, "r", encoding="utf-8") as f:
        new_jslib = f.read()
    print(f"New jsLib length: {len(new_jslib)}")

    # 2. Read current config_json
    config_json = mysql_query(f"SELECT config_json FROM book_source WHERE id={SOURCE_ID};")
    config_json = config_json.strip()
    if not config_json:
        print("ERROR: config_json is empty", file=sys.stderr)
        sys.exit(1)
    print(f"Current config_json length: {len(config_json)}")

    # 3. Parse, replace jsLib, serialize back
    obj = json.loads(config_json)
    old_jslib = obj.get("jsLib", "")
    print(f"Old jsLib length: {len(old_jslib)}")
    obj["jsLib"] = new_jslib
    new_config = json.dumps(obj, ensure_ascii=False, separators=(",", ":"))
    print(f"New config_json length: {len(new_config)}")

    # 4. Update database (escape single quotes for SQL)
    escaped = new_config.replace("\\", "\\\\").replace("'", "\\'")
    sql = f"UPDATE book_source SET config_json = '{escaped}' WHERE id = {SOURCE_ID};"
    mysql_exec(sql)
    print("Database updated successfully!")

    # 5. Verify
    verify = mysql_query(f"SELECT LENGTH(config_json) FROM book_source WHERE id={SOURCE_ID};")
    print(f"Verified config_json length in DB: {verify.strip()}")

if __name__ == "__main__":
    main()
