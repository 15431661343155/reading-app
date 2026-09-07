import requests, json

BASE = 'http://47.99.126.75:8080'
r = requests.get(BASE + '/api/admin/book-source/list')
r.raise_for_status()
sources = r.json()['data']

# Find ALL sources containing 七猫 or qimao
for s in sources:
    st = s.get('sourceType', '')
    name = s.get('bookSourceName', '')
    config_str = s.get('configJson', '')
    if '七猫' in name or 'qimao' in st.lower() or 'QIMAO' in st or 'CUSTOM_4858' in st or 'CUSTOM_992' in st:
        enabled = s.get('enabled', True)
        print(f"sourceType={st}, name={name}, enabled={enabled}")
        try:
            config = json.loads(config_str)
            has_jslib = 'jsLib' in config and len(config.get('jsLib', '')) > 0
            has_explore = bool(config.get('exploreUrl', ''))
            has_rule_explore = 'ruleExplore' in config
            print(f"  has jsLib: {has_jslib}, has exploreUrl: {has_explore}, has ruleExplore: {has_rule_explore}")
        except:
            print(f"  config parse error")
        print()

# Also check if there's a source with 七猫 in the comment
for s in sources:
    st = s.get('sourceType', '')
    config_str = s.get('configJson', '')
    try:
        config = json.loads(config_str)
        comment = config.get('bookSourceComment', '')
        if '七猫' in comment or 'QIMAO' in st:
            name = s.get('bookSourceName', '')
            enabled = s.get('enabled', True)
            print(f"BY COMMENT: sourceType={st}, name={name}, enabled={enabled}")
            print(f"  comment: {comment[:200]}")
    except:
        pass
