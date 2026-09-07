#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""测试七猫搜索 API，查看原始响应"""
import hashlib
import json
import urllib.parse
import urllib.request

SIGN_KEY = "d3dGiJc651gSQ8w1"
APP_VERSION = "73720"
APP_ID = "com.kmxs.reader"
CHANNEL = "unknown"

def sign_params(params):
    keys = sorted(params.keys())
    sign_str = "".join(k + "=" + str(params[k]) for k in keys) + SIGN_KEY
    params["sign"] = hashlib.md5(sign_str.encode()).hexdigest()
    return params

def make_headers():
    headers = {
        "AUTHORIZATION": "",
        "app-version": APP_VERSION,
        "application-id": APP_ID,
        "channel": CHANNEL,
        "net-env": "1",
        "platform": "android",
        "qm-params": "",
        "reg": "0",
    }
    keys = sorted(headers.keys())
    sign_str = "".join(k + "=" + str(headers[k]) for k in keys) + SIGN_KEY
    headers["sign"] = hashlib.md5(sign_str.encode()).hexdigest()
    return headers

def search_books(keyword, page=1):
    params = sign_params({
        "extend": "",
        "tab": "0",
        "gender": "0",
        "refresh_state": "8",
        "page": str(page),
        "wd": keyword,
        "is_short_story_user": "0",
    })
    headers = make_headers()
    headers["user-agent"] = "webviewversion/0"
    url = "https://api-bc.wtzw.com/search/v1/words?" + "&".join(
        urllib.parse.urlencode({k: v}) for k, v in params.items()
    )
    print(f"URL: {url}")
    print(f"Headers: {json.dumps(headers, indent=2)}")
    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req, timeout=15) as resp:
        data = resp.read().decode("utf-8")
        print(f"\nResponse length: {len(data)}")
        print(f"Response: {data[:2000]}")
        return json.loads(data)

if __name__ == "__main__":
    result = search_books("斗破苍穹")
    if "data" in result and "books" in result.get("data", {}):
        books = result["data"]["books"]
        print(f"\nBooks found: {len(books)}")
        for b in books[:3]:
            print(f"  - {b.get('title', '?')} / {b.get('author', '?')} (id={b.get('id', '?')})")
    else:
        print(f"\nNo books in response. Full response keys: {list(result.keys())}")
        if "data" in result:
            print(f"data keys: {list(result['data'].keys()) if isinstance(result['data'], dict) else type(result['data'])}")
