import json
import requests

BASE = 'http://47.99.126.75:8080'
r = requests.get(BASE + '/api/admin/online-source/search', params={
    'sourceType': 'CUSTOM_48584', 'keyword': '斗破苍穹', 'page': 1
})
d = r.json()
books = d.get('data', [])
if books:
    print(json.dumps(books[0], indent=2, ensure_ascii=False))
