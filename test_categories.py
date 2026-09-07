import requests, json

BASE = 'http://47.99.126.75:8080'

# Get available sources
print('=== Available sources ===')
r = requests.get(BASE + '/api/admin/online-source/sources')
d = r.json()
for s in d:
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
print(f"code={d2.get('code')}, message={d2.get('message')}")
cats = d2.get('data', [])
if isinstance(cats, list):
    print(f"Found {len(cats)} categories:")
    for c in cats:
        print(f"  - {c}")
else:
    print(f"data: {str(cats)[:300]}")

# Test explore with each category
print('\n=== Test explore per category ===')
if isinstance(cats, list):
    for cat in cats[:3]:
        if isinstance(cat, str) and '::' in cat:
            cat_name = cat.split('::')[0]
            r3 = requests.get(BASE + '/api/admin/online-source/explore', params={
                'sourceType': 'CUSTOM_48584',
                'page': 0,
                'size': 3,
                'category': cat_name
            })
            d3 = r3.json()
            data = d3.get('data', [])
            count = len(data) if isinstance(data, list) else 0
            print(f"  {cat_name}: {count} books")
