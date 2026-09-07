#!/usr/bin/env python3
"""
Update CUSTOM_48584 (七猫) book source on remote server.
Switch explore from broken qimao.com API to wtzw.com API with proper authentication.

Key fix: qmExploreUrl must call java.ajax() to fetch the actual response body,
not just return a URL string. The Legado JS engine evaluates the bookList JS
rule and passes the return value as content to the next rule (JSONPath).
If the JS returns a URL string, JSONPath parsing will fail.

Escaping notes:
- Python \\d → string \d → JSON \\d → Java string \d → JS regex \d (digit) ✓
- Python \n → actual newline → JSON \n → Java newline → JS newline ✓
"""
import json
import requests

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
print(f"Current exploreUrl: {config.get('exploreUrl', '')[:100]}...")

# Step 2: Build qmExploreUrl function and add to jsLib
jsLib = config.get("jsLib", "")

# qmExploreUrl: fetches category book list from wtzw.com API
# MUST call java.ajax() to return response body, not just URL
qm_explore_func = """
function qmExploreUrl(gender, categoryId, page) {
  var token = qmLogin.call(this);
  var params = {
    'gender': String(gender),
    'category_id': String(categoryId),
    'need_filters': '0',
    'page': String(page),
    'need_category': '0'
  };
  var keys = [];
  for (var k in params) keys.push(k);
  keys.sort();
  var parts = [];
  for (var i = 0; i < keys.length; i++) {
    parts.push(encodeURIComponent(keys[i]) + '=' + encodeURIComponent(params[keys[i]]));
  }
  parts.push('sign=' + encodeURIComponent(qmUrlSign(params)));
  var headers = qmHeaders(token, qmBuildParams.call(this));
  var url = 'https://api-bc.wtzw.com/api/v4/category/get-list?' + parts.join('&') + ',' + JSON.stringify({ headers: headers });
  return java.ajax(url);
}
"""

if "qmExploreUrl" not in jsLib:
    jsLib = jsLib.rstrip() + "\n" + qm_explore_func
    config["jsLib"] = jsLib
    print("Added qmExploreUrl function to jsLib")
else:
    # Remove old version and add new one
    import re
    jsLib = re.sub(r'function qmExploreUrl\([^)]*\)\{[^}]*\}', '', jsLib, flags=re.DOTALL)
    jsLib = jsLib.rstrip() + "\n" + qm_explore_func
    config["jsLib"] = jsLib
    print("Updated qmExploreUrl function in jsLib")

# Step 3: Build the bookList rule with proper escaping
# JS part extracts params from baseUrl and calls qmExploreUrl
# Python \\d → string \d → JSON \\d → JS regex \d (digit) ✓
js_part = (
    "var gender = baseUrl.match(/gender=(\\d+)/)?baseUrl.match(/gender=(\\d+)/)[1]:'0';\n"
    "var categoryId = baseUrl.match(/category_id=(\\d+)/)?baseUrl.match(/category_id=(\\d+)/)[1]:'1';\n"
    "var page = baseUrl.match(/page=(\\d+)/)?baseUrl.match(/page=(\\d+)/)[1]:'1';\n"
    "qmExploreUrl.call(this, gender, categoryId, page)"
)

bookList = "<js>\n" + js_part + "\n</js>\n$.data.books"

print(f"BookList rule (first 200 chars): {bookList[:200]}")

# Step 4: Update ruleExplore
new_rule_explore = [{
    "bookList": bookList,
    "name": "$.title",
    "author": "$.author",
    "bookUrl": "@js:qmBookDetailUrl.call(this,{{$.book_id}})",
    "coverUrl": "$.image_link",
    "intro": "$.intro",
    "kind": "$.category1_name",
    "wordCount": "$.words_num",
    "lastChapter": "$.latest_chapter_title",
    "updateTime": "$.update_time"
}]

config["ruleExplore"] = new_rule_explore
print("Updated ruleExplore")

# Step 5: Update exploreUrl with wtzw.com parameter URLs
new_explore_url = (
    "男生大热榜::https://api-bc.wtzw.com/category?gender=0&category_id=1&page={{page}}\n"
    "男生新书榜::https://api-bc.wtzw.com/category?gender=0&category_id=2&page={{page}}\n"
    "男生完结榜::https://api-bc.wtzw.com/category?gender=0&category_id=3&page={{page}}\n"
    "女生大热榜::https://api-bc.wtzw.com/category?gender=1&category_id=1&page={{page}}\n"
    "女生新书榜::https://api-bc.wtzw.com/category?gender=1&category_id=2&page={{page}}\n"
    "女生完结榜::https://api-bc.wtzw.com/category?gender=1&category_id=3&page={{page}}"
)

config["exploreUrl"] = new_explore_url
config["bookSourceUrl"] = "https://api-bc.wtzw.com"
print("Updated exploreUrl with wtzw.com URLs")

# Step 6: Save and push
source["configJson"] = json.dumps(config, ensure_ascii=False)
source["baseUrl"] = "https://api-bc.wtzw.com"

print("Pushing updated config to remote server...")
update_resp = requests.put(
    f"{BASE_URL}/api/admin/book-source/update",
    json=source,
    headers={"Content-Type": "application/json"}
)
update_resp.raise_for_status()
result = update_resp.json()

if result.get("success"):
    print("SUCCESS: CUSTOM_48584 updated successfully!")
else:
    print(f"FAILED: {result.get('message')}")
    exit(1)

# Step 7: Verify the stored config has correct escaping
import time
time.sleep(2)

# Fetch back the config to verify
print("\nVerifying stored config...")
verify_resp = requests.get(f"{BASE_URL}/api/admin/book-source/list")
verify_resp.raise_for_status()
verify_sources = verify_resp.json()["data"]
verify_source = None
for s in verify_sources:
    if s["sourceType"] == "CUSTOM_48584":
        verify_source = s
        break
verify_config = json.loads(verify_source["configJson"])
verify_rule = verify_config["ruleExplore"][0]
print(f"Stored bookList (first 200 chars): {verify_rule['bookList'][:200]}")

# Check escaping
bl = verify_rule["bookList"]
# Check that regex has \d (not \\d) and there are actual newlines
has_d = 'gender=(\\d' in bl
has_newlines = chr(10) in bl
print(f"Has proper \\d in regex: {has_d}")
print(f"Has actual newlines: {has_newlines}")
print(f"BookList preview:\n{bl[:300]}")

# Step 8: Test explore endpoint
print("\nTesting explore endpoint...")
test_resp = requests.get(
    f"{BASE_URL}/api/admin/online-source/explore",
    params={
        "sourceType": "CUSTOM_48584",
        "page": 0,
        "size": 5,
        "category": "男生大热榜"
    }
)
test_resp.raise_for_status()
test_result = test_resp.json()
print(f"Explore result: code={test_result.get('code')}, message={test_result.get('message')}")
print(f"Books returned: {len(test_result.get('data', []))}")
if test_result.get("data"):
    for book in test_result["data"][:3]:
        print(f"  - {book.get('title')} by {book.get('author')}")
else:
    print("  No books returned!")
    print("  Response:", json.dumps(test_result, ensure_ascii=False)[:500])