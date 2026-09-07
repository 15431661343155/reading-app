import requests, json

BASE = 'http://47.99.126.75:8080'
r = requests.get(BASE + '/api/admin/book-source/list')
r.raise_for_status()
sources = r.json()['data']
qimao = [s for s in sources if s['sourceType'] == 'CUSTOM_48584'][0]
c = json.loads(qimao['configJson'])

print('=== Full CUSTOM_48584 Config ===')
print(json.dumps(c, indent=2, ensure_ascii=False))
