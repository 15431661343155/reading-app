import requests, json

BASE = 'http://47.99.126.75:8080'

# Test CUSTOM_48584 explore
print('=== CUSTOM_48584 explore ===')
r = requests.get(BASE + '/api/admin/online-source/explore', params={
    'sourceType': 'CUSTOM_48584', 'page': 0, 'size': 3, 'category': '男生大热榜'
})
d = r.json()
print('code:', d.get('code'))
data = d.get('data', [])
if isinstance(data, list):
    print('count:', len(data))
    for item in data[:3]:
        title = item.get('title', 'N/A')
        sourceUrl = item.get('sourceUrl', 'MISSING')
        bookUrl = item.get('bookUrl', 'N/A')
        print('  title=%s, sourceUrl=%s' % (title, sourceUrl))
        print('    bookUrl=%s' % bookUrl)
else:
    print('data:', str(data)[:500])
