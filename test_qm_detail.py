import json, urllib.parse, urllib.request

BASE = "http://localhost:8080"

# 从上一次搜索得到的 sourceUrl（含 ,{...} 选项）
source_url = 'https://api-bc.wtzw.com/api/v1/reader/detail?id=210863&sign=2e8e1fb2b57ccc949c268353b84f7fa2,{"headers":{"authorization":"","app-version":"73720","application-id":"com.kmxs.reader","channel":"unknown","net-env":"1","platform":"android","qm-params":"","reg":"0","sign":"b90d4260bfa0fd3df36bfdb60bb19631","user-agent":"webviewversion/0"}}'

# 1) 测试详情页
def call(path, **params):
    qs = urllib.parse.urlencode(params)
    url = f"{BASE}/api/admin/online-source/{path}?{qs}"
    print(f"\n=== GET {path} ===")
    print(f"URL: {url[:200]}{'...' if len(url) > 200 else ''}")
    try:
        with urllib.request.urlopen(url, timeout=30) as resp:
            body = resp.read().decode("utf-8", errors="replace")
            print(f"HTTP {resp.status}, length={len(body)}")
            try:
                obj = json.loads(body)
                print(json.dumps(obj, ensure_ascii=False, indent=2)[:3000])
            except Exception:
                print(body[:2000])
    except Exception as e:
        print(f"ERR: {e}")

call("book", sourceType="CUSTOM_48584", sourceBookId=source_url)
call("chapters", sourceType="CUSTOM_48584", sourceBookId=source_url)
