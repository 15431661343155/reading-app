import requests, json

BASE = 'http://47.99.126.75:8080'
r = requests.get(BASE + '/api/admin/book-source/list')
r.raise_for_status()
sources = r.json()['data']

# Find all 七猫-related sources
for s in sources:
    st = s.get('sourceType', '')
    name = s.get('bookSourceName', '')
    enabled = s.get('enabled', True)
    if 'QIMAO' in st or 'CUSTOM_4858' in st or '七猫' in name:
        print(f"sourceType={st}, name={name}, enabled={enabled}")
        config = json.loads(s.get('configJson', '{}'))
        print(f"  searchUrl: {config.get('searchUrl', 'N/A')[:100]}")
        print(f"  exploreUrl: {str(config.get('exploreUrl', ''))[:100]}")
        print(f"  has ruleExplore: {'ruleExplore' in config}")
        print(f"  has jsLib: {'jsLib' in config and len(config.get('jsLib', '')) > 0}")
        print()
