import requests, json

BASE = 'http://47.99.126.75:8080'

# Get available sources
print('=== Available sources ===')
r = requests.get(BASE + '/api/admin/online-source/sources')
d = r.json()
data = d.get('data', [])
for s in data:
    if isinstance(s, dict):
        print(f"  type={s.get('type')}, name={s.get('name')}")
    else:
        print(f"  {s}")

# Test CUSTOM_48584 explore categories
print('\n=== CUSTOM_48584 explore categories ===')
r2 = requests.get(BASE + '/api/admin/online-source/explore-categories', params={
    'sourceType': 'CUSTOM_48584'
})
d2 = r2.json()
print(f"code={d2.get('code')}")
cats = d2.get('data', [])
if isinstance(cats, list):
    print(f"Found {len(cats)} categories")
    for c in cats:
        if isinstance(c, list) and len(c) >= 1:
            cat_name = c[0]
            r3 = requests.get(BASE + '/api/admin/online-source/explore', params={
                'sourceType': 'CUSTOM_48584',
                'page': 0,
                'size': 3,
                'category': cat_name
            })
            d3 = r3.json()
            books = d3.get('data', [])
            count = len(books) if isinstance(books, list) else 0
            first_title = books[0].get('title', 'N/A') if isinstance(books, list) and books else 'N/A'
            print(f"  {cat_name}: {count} books, first={first_title}")
else:
    print(f"data: {str(cats)[:300]}")
