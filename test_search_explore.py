#!/usr/bin/env python3
"""
Deploy updated backend to remote server and test.
"""
import json
import requests
import time

BASE_URL = "http://47.99.126.75:8080"

# Test search with CUSTOM_48584
print("=== Testing CUSTOM_48584 search ===")
r = requests.get(f"{BASE_URL}/api/admin/online-source/search", params={
    "sourceType": "CUSTOM_48584",
    "keyword": "斗破苍穹",
    "page": 1
})
d = r.json()
print(f"code={d.get('code')}, message={d.get('message')}")
data = d.get('data', [])
if isinstance(data, list):
    print(f"Found {len(data)} books")
    for book in data[:3]:
        title = book.get('title', 'N/A')
        source_url = book.get('sourceUrl', 'MISSING')
        author = book.get('author', 'N/A')
        print(f"  - {title} by {author}")
        if source_url:
            url_part = source_url.split(',')[0] if ',' in source_url else source_url
            print(f"    sourceUrl: {url_part[:120]}")
        else:
            print(f"    sourceUrl: MISSING!")
else:
    print(f"data: {str(data)[:500]}")

# Test explore again
print("\n=== Testing CUSTOM_48584 explore ===")
r2 = requests.get(f"{BASE_URL}/api/admin/online-source/explore", params={
    "sourceType": "CUSTOM_48584",
    "page": 0,
    "size": 3,
    "category": "男生大热榜"
})
d2 = r2.json()
print(f"code={d2.get('code')}")
data2 = d2.get('data', [])
if isinstance(data2, list):
    print(f"Found {len(data2)} books")
    for book in data2[:3]:
        title = book.get('title', 'N/A')
        source_url = book.get('sourceUrl', 'MISSING')
        print(f"  - {title}")
        if source_url:
            url_part = source_url.split(',')[0] if ',' in source_url else source_url
            has_headers = ',' in source_url
            print(f"    sourceUrl: {url_part[:120]}")
            print(f"    hasHeadersFormat: {has_headers}")
        else:
            print(f"    sourceUrl: MISSING!")
else:
    print(f"data: {str(data2)[:500]}")
