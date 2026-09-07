import requests, json

BASE = 'http://47.99.126.75:8080'

# Test CUSTOM_48585 search
print('=== Test CUSTOM_48585 search ===')
r = requests.get(BASE + '/api/admin/online-source/search', params={
    'sourceType': 'CUSTOM_48585',
    'keyword': '斗破苍穹',
    'page': 1
})
d = r.json()
print(f"code={d.get('code')}, message={d.get('message')}")
data = d.get('data', [])
if isinstance(data, list):
    print(f'Results: {len(data)}')
    for b in data[:2]:
        print(f"  - {b.get('title')}")
else:
    print(f"data: {str(data)[:300]}")
