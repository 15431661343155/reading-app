#!/usr/bin/env python3
"""
Test full reading chain for CUSTOM_48584 with correct API params.
"""
import json
import requests
from urllib.parse import quote

BASE_URL = "http://47.99.126.75:8080"

# Step 1: Search
print("=== Step 1: Search for 斗破苍穹 ===")
r = requests.get(f"{BASE_URL}/api/admin/online-source/search", params={
    "sourceType": "CUSTOM_48584",
    "keyword": "斗破苍穹",
    "page": 1
})
d = r.json()
books = d.get('data', [])
if not books:
    print("No books found!")
    exit(1)

book = books[0]
source_url = book.get('sourceUrl', '')
title = book.get('title')
print(f"Found: {title}")
print(f"  sourceUrl: {source_url[:200]}...")

if not source_url:
    print("ERROR: sourceUrl is empty!")
    exit(1)

# URL encode the sourceUrl for use as sourceBookId
encoded_id = quote(source_url, safe='')
print(f"  encoded sourceBookId: {encoded_id[:100]}...")

# Step 2: Get book detail (use /book endpoint with sourceBookId)
print("\n=== Step 2: Get book detail ===")
r2 = requests.get(f"{BASE_URL}/api/admin/online-source/book", params={
    "sourceType": "CUSTOM_48584",
    "sourceBookId": source_url  # pass the raw sourceUrl
})
d2 = r2.json()
print(f"code={d2.get('code')}, message={d2.get('message')}")
if d2.get('data'):
    info = d2['data']
    print(f"  title: {info.get('title')}")
    print(f"  author: {info.get('author')}")
    toc = info.get('tocUrl', '')
    print(f"  tocUrl: {toc[:200] if toc else 'N/A'}...")
else:
    print(f"  Full response: {json.dumps(d2, ensure_ascii=False)[:500]}")

# Step 3: Get chapter list
print("\n=== Step 3: Get chapter list ===")
r3 = requests.get(f"{BASE_URL}/api/admin/online-source/chapters", params={
    "sourceType": "CUSTOM_48584",
    "sourceBookId": source_url
})
d3 = r3.json()
print(f"code={d3.get('code')}, message={d3.get('message')}")
chapters = d3.get('data', [])
if isinstance(chapters, list) and chapters:
    print(f"  Found {len(chapters)} chapters")
    if len(chapters) > 0:
        print(f"  First: {chapters[0]}")
    if len(chapters) > 1:
        print(f"  Last: {chapters[-1]}")
else:
    print(f"  data: {json.dumps(d3, ensure_ascii=False)[:500]}")

# Step 4: Get first chapter content
if isinstance(chapters, list) and chapters:
    print("\n=== Step 4: Get first chapter content ===")
    # chapters[0] is typically [chapterTitle, chapterUrl]
    ch = chapters[0]
    if isinstance(ch, list) and len(ch) >= 2:
        ch_title = ch[0]
        ch_url = ch[1]
        print(f"  Chapter: {ch_title}")
        print(f"  Chapter URL: {ch_url[:150]}...")
        
        r4 = requests.get(f"{BASE_URL}/api/admin/online-source/content", params={
            "sourceType": "CUSTOM_48584",
            "sourceBookId": source_url,
            "chapterUrl": ch_url
        })
        d4 = r4.json()
        print(f"code={d4.get('code')}, message={d4.get('message')}")
        content = d4.get('data', '')
        if content and isinstance(content, str) and len(content) > 0:
            print(f"  Content length: {len(content)} chars")
            print(f"  Preview: {content[:300]}...")
        else:
            print(f"  data: {str(content)[:300]}")
    else:
        print(f"  Unexpected chapter format: {ch}")
else:
    print("No chapters to test content")

print("\n=== Done ===")
