import json, urllib.request, ssl

url = "https://api-bc.wtzw.com/api/v1/reader/detail?id=210863&sign=2e8e1fb2b57ccc949c268353b84f7fa2"
headers = {
    "authorization": "",
    "app-version": "73720",
    "application-id": "com.kmxs.reader",
    "channel": "unknown",
    "net-env": "1",
    "platform": "android",
    "qm-params": "",
    "reg": "0",
    "sign": "b90d4260bfa0fd3df36bfdb60bb19631",
    "user-agent": "webviewversion/0",
}

req = urllib.request.Request(url, headers=headers)
ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE

with urllib.request.urlopen(req, context=ctx, timeout=15) as resp:
    body = resp.read().decode("utf-8", errors="replace")
    print("HTTP", resp.status, "len", len(body))
    try:
        obj = json.loads(body)
        print("top keys:", list(obj.keys()))
        print("data keys:", list(obj.get("data", {}).keys()) if isinstance(obj.get("data"), dict) else "data is not dict")
        print(json.dumps(obj, ensure_ascii=False, indent=2)[:2500])
    except Exception as e:
        print("parse err:", e)
        print(body[:2000])
