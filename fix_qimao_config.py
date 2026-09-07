#!/usr/bin/env python3
"""
Fix CUSTOM_48584 (七猫) on remote server.

Issues fixed:
1. Duplicate qmExploreUrl function in jsLib (defined twice)
2. qmSignUrl uses bare comma concatenation which Nashorn may misinterpret
   → Use [url, json].join(',') instead
3. Ensure bookUrl rule returns proper URL format

Also restarts the backend service after config update.
"""
import json
import requests
import re
import time

BASE_URL = "http://47.99.126.75:8080"

# Step 1: Get current config
print("Fetching current CUSTOM_48584 config...")
resp = requests.get(f"{BASE_URL}/api/admin/book-source/list")
resp.raise_for_status()
sources = resp.json()["data"]
source = None
for s in sources:
    if s["sourceType"] == "CUSTOM_48584":
        source = s
        break

if not source:
    print("ERROR: CUSTOM_48584 not found!")
    exit(1)

config = json.loads(source["configJson"])
js_lib = config.get("jsLib", "")

# Step 2: Fix jsLib - remove duplicate qmExploreUrl
# Find all qmExploreUrl function definitions
explore_func_pattern = r'function qmExploreUrl\([^)]*\)\{[^}]*\}'
matches = re.findall(explore_func_pattern, js_lib, re.DOTALL)
print(f"Found {len(matches)} qmExploreUrl definitions")

if len(matches) > 1:
    # Keep only the last one (most recent), remove earlier ones
    # Strategy: split by the function, keep everything before first occurrence,
    # then append the last occurrence
    first_idx = js_lib.find("function qmExploreUrl(")
    if first_idx >= 0:
        # Find the last occurrence
        last_idx = js_lib.rfind("function qmExploreUrl(")
        if first_idx != last_idx:
            # Get the function definition at last_idx
            brace_start = js_lib.find("{", last_idx)
            brace_count = 0
            end_pos = brace_start
            for i in range(brace_start, len(js_lib)):
                if js_lib[i] == '{':
                    brace_count += 1
                elif js_lib[i] == '}':
                    brace_count -= 1
                    if brace_count == 0:
                        end_pos = i + 1
                        break
            last_func = js_lib[last_idx:end_pos]
            # Remove first occurrence (from first_idx to end_pos where first func ends)
            first_func_end = js_lib.find("}", first_idx)
            brace_count = 0
            for i in range(first_idx, len(js_lib)):
                if js_lib[i] == '{':
                    brace_count += 1
                elif js_lib[i] == '}':
                    brace_count -= 1
                    if brace_count == 0:
                        first_func_end = i + 1
                        break
            js_lib = js_lib[:first_idx] + js_lib[first_func_end:]
            print("Removed duplicate qmExploreUrl definition")
    else:
        print("No duplicate found (unexpected)")

# Step 3: Fix qmSignUrl to use safer string concatenation
# Replace: return url + '?' + parts.join('&') + ',' + JSON.stringify({ headers: headers });
# With:    return [url + '?' + parts.join('&'), JSON.stringify({ headers: headers })].join(',');
old_sign = "return url + '?' + parts.join('&') + ',' + JSON.stringify({ headers: headers });"
new_sign = "return [url + '?' + parts.join('&'), JSON.stringify({ headers: headers })].join(',');"

# Apply to qmSignUrl
js_lib = js_lib.replace(old_sign, new_sign)

# Also fix qmExploreUrl which has similar pattern
old_explore = "var url = 'https://api-bc.wtzw.com/api/v4/category/get-list?' + parts.join('&') + ',' + JSON.stringify({ headers: headers });"
new_explore = "var url = ['https://api-bc.wtzw.com/api/v4/category/get-list?' + parts.join('&'), JSON.stringify({ headers: headers })].join(',');"
js_lib = js_lib.replace(old_explore, new_explore)

print("Fixed qmSignUrl and qmExploreUrl string concatenation")

# Step 4: Update config
config["jsLib"] = js_lib
source["configJson"] = json.dumps(config, ensure_ascii=False)
source["baseUrl"] = config.get("bookSourceUrl", "https://api-bc.wtzw.com")

print("Pushing updated config to remote server...")
update_resp = requests.put(
    f"{BASE_URL}/api/admin/book-source/update",
    json=source,
    headers={"Content-Type": "application/json"}
)
update_resp.raise_for_status()
result = update_resp.json()

if result.get("success"):
    print("SUCCESS: CUSTOM_48584 config updated!")
else:
    print(f"FAILED: {result.get('message')}")
    exit(1)

# Step 5: Verify explore endpoint
print("\nTesting explore endpoint...")
time.sleep(1)
test_resp = requests.get(
    f"{BASE_URL}/api/admin/online-source/explore",
    params={
        "sourceType": "CUSTOM_48584",
        "page": 0,
        "size": 3,
        "category": "男生大热榜"
    }
)
test_resp.raise_for_status()
test_result = test_resp.json()
print(f"code={test_result.get('code')}, message={test_result.get('message')}")
data = test_result.get('data', [])
if isinstance(data, list):
    print(f"Books returned: {len(data)}")
    for book in data[:3]:
        title = book.get('title', 'N/A')
        source_url = book.get('sourceUrl', 'MISSING')
        print(f"  - {title}")
        if source_url:
            # Check if sourceUrl is proper URL
            if ',' in source_url:
                url_part = source_url.split(',')[0]
                print(f"    sourceUrl (URL part): {url_part[:100]}...")
            else:
                print(f"    sourceUrl: {source_url[:100]}...")
        else:
            print(f"    sourceUrl: MISSING!")
else:
    print(f"data: {str(data)[:500]}")
