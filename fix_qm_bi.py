import json, subprocess

out = subprocess.run(
    [r"D:\phpstudy_pro\Extensions\MySQL8.0.12\bin\mysql.exe", "-ureading-app", "-p123456", "reading-app", "--raw", "-N", "-B",
     "-e", "SELECT config_json FROM book_source WHERE source_type='CUSTOM_48584'"],
    capture_output=True
)
data = out.stdout.decode("utf-8", errors="replace").strip()
obj = json.loads(data)

# 修正 ruleBookInfo：去掉中间的 .book 层（旧 API 用 data.book.xxx，新 API 直接 data.xxx）
bi = obj.get("ruleBookInfo") or {}
for k, v in list(bi.items()):
    if isinstance(v, str):
        bi[k] = v.replace("$.data.book.", "$.data.").replace("d.data.book.", "d.data.")

# 修正 ruleToc.chapterList：可能是 chapter_lists（响应里有 chapter_lists 字段名吗？需先探测）
# 从前面 Python 探测响应没看到 chapter_lists，可能是分页接口才返回。先保留原样

obj["ruleBookInfo"] = bi
new_json = json.dumps(obj, ensure_ascii=False)

# 写回数据库
sql = "UPDATE book_source SET config_json = %s WHERE source_type = 'CUSTOM_48584'"
# 注意 mysql CLI 不支持参数化，必须把 JSON 转义后嵌入 SQL
escaped = new_json.replace("\\", "\\\\").replace("'", "''")
full_sql = f"UPDATE book_source SET config_json = '{escaped}' WHERE source_type = 'CUSTOM_48584'"

# 用 mysql 执行
r = subprocess.run(
    [r"D:\phpstudy_pro\Extensions\MySQL8.0.12\bin\mysql.exe", "-ureading-app", "-p123456", "reading-app", "-e", full_sql],
    capture_output=True
)
print("UPDATE stderr:", r.stderr.decode("utf-8", errors="replace"))

# 复查
out = subprocess.run(
    [r"D:\phpstudy_pro\Extensions\MySQL8.0.12\bin\mysql.exe", "-ureading-app", "-p123456", "reading-app", "--raw", "-N", "-B",
     "-e", "SELECT config_json FROM book_source WHERE source_type='CUSTOM_48584'"],
    capture_output=True
)
verify = json.loads(out.stdout.decode("utf-8", errors="replace").strip())
print("after update ruleBookInfo:", json.dumps(verify.get("ruleBookInfo"), ensure_ascii=False, indent=2))
