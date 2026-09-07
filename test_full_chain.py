#!/usr/bin/env python3
"""
Test full reading chain for CUSTOM_48584:
search → detail → toc → chapter content
"""
import json
import requests

BASE_URL = "http://47.99.126.75:8080"

# Step 1: Search for a book
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
book_id = book.get('id')
source_url = book.get('sourceUrl', '')
title = book.get('title')
print(f"Found: {title}")
print(f"  id: {book_id}")
print(f"  sourceUrl: {source_url[:150]}...")

if not source_url:
    print("ERROR: sourceUrl is empty!")
    exit(1)

# Step 2: Get book info (detail page)
print("\n=== Step 2: Get book info ===")
r2 = requests.get(f"{BASE_URL}/api/admin/online-source/detail", params={
    "sourceType": "CUSTOM_48584",
    "bookId": book_id
})
d2 = r2.json()
print(f"code={d2.get('code')}")
if d2.get('data'):
    info = d2['data']
    print(f"  title: {info.get('title')}")
    print(f"  author: {info.get('author')}")
    print(f"  tocUrl: {info.get('tocUrl', 'N/A')[:150] if info.get('tocUrl') else 'N/A'}...")
else:
    print(f"  message: {d2.get('message')}")

# Step 3: Get chapter list
print("\n=== Step 3: Get chapter list ===")
r3 = requests.get(f"{BASE_URL}/api/admin/online-source/toc", params={
    "sourceType": "CUSTOM_48584",
    "bookId": book_id
})
d3 = r3.json()
print(f"code={d3.get('code')}")
chapters = d3.get('data', [])
if isinstance(chapters, list) and chapters:
    print(f"  Found {len(chapters)} chapters")
    # Show first and last chapter
    if len(chapters) > 0:
        ch0 = chapters[0]
        print(f"  First: {ch0.get('title', 'N/A')} (id={ch0.get('id', 'N/A')})")
    if len(chapters) > 1:
        chN = chapters[-1]
        print(f"  Last: {chN.get('title', 'N/A')} (id={chN.get('id', 'N/A')})")
else:
    print(f"  chapters: {str(chapters)[:300]}")
    # Print full response for debugging
    print(f"  Full response: {json.dumps(d3, ensure_ascii=False)[:500]}")

# Step 4: Get first chapter content
if isinstance(chapters, list) and chapters:
    print("\n=== Step 4: Get first chapter content ===")
    ch0 = chapters[0]
    ch_id = ch0.get('id')
    if ch_id:
        r4 = requests.get(f"{BASE_URL}/api/admin/online-source/content", params={
            "sourceType": "CUSTOM_48584",
            "bookId": book_id,
            "chapterId": ch_id
        })
        d4 = r4.json()
        print(f"code={d4.get('code')}")
        content = d4.get('data', '')
        if content and isinstance(content, str):
            print(f"  Content length: {len(content)} chars")
            print(f"  Preview: {content[:200]}...")
        else:
            print(f"  data: {str(content)[:300]}")
    else:
        print("  No chapter ID found")

print("\n=== Done ===")
