#!/usr/bin/env python3
"""Verify CUSTOM_992 chapter content (在线阅读) works end-to-end."""
import json
import requests

BASE = 'http://47.99.126.75:8080'

# 1. Search
print('=== Search 斗破苍穹 ===')
r = requests.get(f'{BASE}/api/admin/online-source/search', params={
    'sourceType': 'CUSTOM_992', 'keyword': '斗破苍穹', 'page': 1
})
d = r.json()
books = d.get('data', [])
print(f'Found {len(books)} books')
assert books, 'No books returned'
source_url = books[0].get('sourceUrl', '')
print(f'sourceUrl (tail): ...{source_url[-80:]}')

# 2. Book detail
print('\n=== Book detail ===')
r2 = requests.get(f'{BASE}/api/admin/online-source/book', params={
    'sourceType': 'CUSTOM_992', 'sourceBookId': source_url
})
d2 = r2.json()
print(f'code={d2.get("code")}, message={d2.get("message")}')
assert d2.get('code') == 200, 'Book detail failed'
info = d2['data']
print(f'  title: {info.get("title")}')
print(f'  author: {info.get("author")}')

# 3. Chapter list
print('\n=== Chapter list ===')
r3 = requests.get(f'{BASE}/api/admin/online-source/chapters', params={
    'sourceType': 'CUSTOM_992', 'sourceBookId': source_url
})
d3 = r3.json()
print(f'code={d3.get("code")}, message={d3.get("message")}')
chapters = d3.get('data', [])
assert isinstance(chapters, list) and chapters, 'No chapters'
print(f'  Chapters: {len(chapters)}')
ch0 = chapters[0]
print(f'  First chapter: {ch0}')

# 4. Chapter content (在线阅读)
print('\n=== Chapter content (在线阅读) ===')
ch_url = ch0[1]
r4 = requests.get(f'{BASE}/api/admin/online-source/content', params={
    'sourceType': 'CUSTOM_992',
    'sourceBookId': source_url,
    'chapterUrl': ch_url
}, timeout=30)
d4 = r4.json()
print(f'code={d4.get("code")}, message={d4.get("message")}')
content = d4.get('data', '')
if content and isinstance(content, str):
    print(f'  Content length: {len(content)} chars')
    print(f'  Preview: {content[:300]}...')
else:
    print(f'  data: {str(content)[:300]}')

# 5. Also test last chapter to ensure robustness
print('\n=== Last chapter content ===')
chN = chapters[-1]
ch_urlN = chN[1]
r5 = requests.get(f'{BASE}/api/admin/online-source/content', params={
    'sourceType': 'CUSTOM_992',
    'sourceBookId': source_url,
    'chapterUrl': ch_urlN
}, timeout=30)
d5 = r5.json()
print(f'code={d5.get("code")}, message={d5.get("message")}')
contentN = d5.get('data', '')
if contentN and isinstance(contentN, str):
    print(f'  Content length: {len(contentN)} chars')
    print(f'  Preview: {contentN[:300]}...')
else:
    print(f'  data: {str(contentN)[:300]}')

print('\n=== Summary ===')
print(f'Search: OK ({len(books)} books)')
print(f'Detail: OK (code={d2.get("code")})')
print(f'TOC:    OK ({len(chapters)} chapters)')
print(f'Content (first): {"OK" if content else "FAIL"} ({len(content) if content else 0} chars)')
print(f'Content (last):  {"OK" if contentN else "FAIL"} ({len(contentN) if contentN else 0} chars)')
