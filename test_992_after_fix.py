#!/usr/bin/env python3
"""Test CUSTOM_992 after fixing application-id masking"""
import json, requests

BASE = 'http://47.99.126.75:8080'

# Search
print('=== Search 斗破苍穹 ===')
r = requests.get(f'{BASE}/api/admin/online-source/search', params={
    'sourceType': 'CUSTOM_992', 'keyword': '斗破苍穹', 'page': 1
})
d = r.json()
books = d.get('data', [])
print(f'Found {len(books)} books')
if not books:
    print(f'Response: {json.dumps(d, ensure_ascii=False)[:500]}')
    exit()

book = books[0]
source_url = book.get('sourceUrl', '')
print(f'title: {book.get("title")}')
print(f'sourceUrl: {source_url[:200]}...')

# Book detail
print('\n=== Book detail ===')
r2 = requests.get(f'{BASE}/api/admin/online-source/book', params={
    'sourceType': 'CUSTOM_992', 'sourceBookId': source_url
})
d2 = r2.json()
print(f'code={d2.get("code")}, message={d2.get("message")}')
if d2.get('data'):
    info = d2['data']
    print(f'  title: {info.get("title")}')
    print(f'  author: {info.get("author")}')
    print(f'  tocUrl: {str(info.get("tocUrl",""))[:200]}')
else:
    print(f'  No data returned')

# Chapter list
print('\n=== Chapter list ===')
r3 = requests.get(f'{BASE}/api/admin/online-source/chapters', params={
    'sourceType': 'CUSTOM_992', 'sourceBookId': source_url
})
d3 = r3.json()
print(f'code={d3.get("code")}, message={d3.get("message")}')
chapters = d3.get('data', [])
if isinstance(chapters, list):
    print(f'  Chapters: {len(chapters)}')
    if chapters:
        print(f'  First: {chapters[0]}')
else:
    print(f'  data: {str(chapters)[:300]}')
