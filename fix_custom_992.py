#!/usr/bin/env python3
"""
Fix CUSTOM_992 (🎉 七猫小说) book source on remote server.

Issues:
1. application-id 'com.****.reader' is masked → fix to 'com.kmxs.reader'
2. Test full reading chain: search → detail → toc → content
"""
import json
import requests
import time

BASE_URL = "http://47.99.126.75:8080"

# Step 1: Get current config
print("Fetching CUSTOM_992 config...")
resp = requests.get(f"{BASE_URL}/api/admin/book-source/list")
resp.raise_for_status()
sources = resp.json()["data"]
source = None
for s in sources:
    if s["sourceType"] == "CUSTOM_992":
        source = s
        break

if not source:
    print("ERROR: CUSTOM_992 not found!")
    exit(1)

config = json.loads(source["configJson"])

# Step 2: Fix application-id masking in all JS rules
config_str = json.dumps(config, ensure_ascii=False)
fixed_count = config_str.count("com.****.reader")
config_str = config_str.replace("com.****.reader", "com.kmxs.reader")
config = json.loads(config_str)
print(f"Fixed {fixed_count} occurrences of masked application-id")

# Also fix in ruleExplore if it has inline JS
rule_explore_str = json.dumps(config.get("ruleExplore", {}), ensure_ascii=False)
if "com.****.reader" in rule_explore_str:
    rule_explore_str = rule_explore_str.replace("com.****.reader", "com.kmxs.reader")
    config["ruleExplore"] = json.loads(rule_explore_str)
    print("Fixed application-id in ruleExplore")

# Same for ruleSearch, ruleBookInfo, ruleContent, ruleToc, searchUrl
for key in ["ruleSearch", "ruleBookInfo", "ruleContent", "ruleToc"]:
    if key in config:
        val_str = json.dumps(config[key], ensure_ascii=False)
        if "com.****.reader" in val_str:
            val_str = val_str.replace("com.****.reader", "com.kmxs.reader")
            config[key] = json.loads(val_str)
            print(f"Fixed application-id in {key}")

if "searchUrl" in config and isinstance(config["searchUrl"], str):
    if "com.****.reader" in config["searchUrl"]:
        config["searchUrl"] = config["searchUrl"].replace("com.****.reader", "com.kmxs.reader")
        print("Fixed application-id in searchUrl")

# Step 3: Push updated config
source["configJson"] = json.dumps(config, ensure_ascii=False)
print("\nPushing fixed config...")
update_resp = requests.put(
    f"{BASE_URL}/api/admin/book-source/update",
    json=source,
    headers={"Content-Type": "application/json"}
)
update_resp.raise_for_status()
result = update_resp.json()
if result.get("success"):
    print("SUCCESS: CUSTOM_992 config updated!")
else:
    print(f"FAILED: {result.get('message')}")
    exit(1)

# Step 4: Verify no masked application-id remains
time.sleep(1)
verify_resp = requests.get(f"{BASE_URL}/api/admin/book-source/list")
verify_resp.raise_for_status()
verify_sources = verify_resp.json()["data"]
verify_source = [s for s in verify_sources if s["sourceType"] == "CUSTOM_992"][0]
verify_config = json.loads(verify_source["configJson"])
verify_str = json.dumps(verify_config, ensure_ascii=False)
if "com.****.reader" in verify_str:
    print("WARNING: masked application-id still present!")
else:
    print("Verified: no masked application-id")

# Step 5: Test search
print("\n=== Test search: 斗破苍穹 ===")
r = requests.get(f"{BASE_URL}/api/admin/online-source/search", params={
    "sourceType": "CUSTOM_992", "keyword": "斗破苍穹", "page": 1
})
d = r.json()
print(f"code={d.get('code')}, message={d.get('message')}")
books = d.get("data", [])
if isinstance(books, list) and books:
    print(f"Found {len(books)} books")
    book = books[0]
    source_url = book.get("sourceUrl", "")
    print(f"  title: {book.get('title')}")
    print(f"  sourceUrl: {source_url[:150]}...")
    
    # Step 6: Test book detail
    if source_url:
        print("\n=== Test book detail ===")
        r2 = requests.get(f"{BASE_URL}/api/admin/online-source/book", params={
            "sourceType": "CUSTOM_992", "sourceBookId": source_url
        })
        d2 = r2.json()
        print(f"code={d2.get('code')}, message={d2.get('message')}")
        if d2.get("data"):
            info = d2["data"]
            print(f"  title: {info.get('title')}")
            print(f"  author: {info.get('author')}")
            toc = info.get("tocUrl", "")
            print(f"  tocUrl: {toc[:150] if toc else 'N/A'}...")
        
        # Step 7: Test chapter list
        print("\n=== Test chapter list ===")
        r3 = requests.get(f"{BASE_URL}/api/admin/online-source/chapters", params={
            "sourceType": "CUSTOM_992", "sourceBookId": source_url
        })
        d3 = r3.json()
        print(f"code={d3.get('code')}, message={d3.get('message')}")
        chapters = d3.get("data", [])
        if isinstance(chapters, list) and chapters:
            print(f"  Found {len(chapters)} chapters")
            ch0 = chapters[0]
            print(f"  First: {ch0[0] if ch0 else 'N/A'}")
        
        # Step 8: Test chapter content
        if isinstance(chapters, list) and chapters and len(chapters[0]) >= 2:
            print("\n=== Test chapter content ===")
            ch_url = chapters[0][1]
            r4 = requests.get(f"{BASE_URL}/api/admin/online-source/content", params={
                "sourceType": "CUSTOM_992",
                "sourceBookId": source_url,
                "chapterUrl": ch_url
            })
            d4 = r4.json()
            print(f"code={d4.get('code')}, message={d4.get('message')}")
            content = d4.get("data", "")
            if content and isinstance(content, str):
                print(f"  Content length: {len(content)} chars")
                print(f"  Preview: {content[:200]}...")
            else:
                print(f"  data: {str(content)[:300]}")
    else:
        print("ERROR: sourceUrl is empty!")
else:
    print("No books found!")
    print(f"  Response: {json.dumps(d, ensure_ascii=False)[:500]}")
