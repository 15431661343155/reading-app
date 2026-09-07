var QM_SECRET = 'd3dGiJc651gSQ8w1';
var QM_AES_KEY = '242ccb8230d709e1';
var QM_APP_VERSION = '73720';
var QM_APP_ID = 'com.kmxs.reader';
var QM_CHANNEL = 'unknown';

function qmMd5(value) {
  return String(java.md5Encode(String(value)));
}

function qmUrlSign(data) {
  var keys = [];
  for (var k in data) keys.push(k);
  keys.sort();
  var text = '';
  for (var i = 0; i < keys.length; i++) text += keys[i] + '=' + String(data[keys[i]]);
  return qmMd5(text + QM_SECRET);
}

function qmMakeHeaders() {
  var signHeaders = {
    'AUTHORIZATION': '',
    'app-version': QM_APP_VERSION,
    'application-id': QM_APP_ID,
    'channel': QM_CHANNEL,
    'net-env': '1',
    'platform': 'android',
    'qm-params': '',
    'reg': '0'
  };
  var keys = [];
  for (var k in signHeaders) keys.push(k);
  keys.sort();
  var text = '';
  for (var i = 0; i < keys.length; i++) text += keys[i] + '=' + String(signHeaders[keys[i]]);
  var sign = qmMd5(text + QM_SECRET);
  return {
    'authorization': '',
    'app-version': QM_APP_VERSION,
    'application-id': QM_APP_ID,
    'channel': QM_CHANNEL,
    'net-env': '1',
    'platform': 'android',
    'qm-params': '',
    'reg': '0',
    'sign': sign,
    'user-agent': 'webviewversion/0'
  };
}

function qmSignUrl(url, params) {
  var query = {};
  for (var k in params) query[k] = String(params[k]);
  var keys = [];
  for (var k in query) keys.push(k);
  keys.sort();
  var parts = [];
  for (var i = 0; i < keys.length; i++) {
    parts.push(encodeURIComponent(keys[i]) + '=' + encodeURIComponent(query[keys[i]]));
  }
  parts.push('sign=' + encodeURIComponent(qmUrlSign(query)));
  var headers = qmMakeHeaders();
  return url + '?' + parts.join('&') + ',' + JSON.stringify({ headers: headers });
}

function qmSearchUrl(key, page) {
  key = String(key || '').trim();
  page = String(Number(page || 1) || 1);
  return qmSignUrl.call(this, 'https://api-bc.wtzw.com/search/v1/words', {
    extend: '',
    tab: '0',
    gender: '0',
    refresh_state: '8',
    page: page,
    wd: key,
    is_short_story_user: '0'
  });
}

function qmBookDetailUrl(id) {
  return qmSignUrl.call(this, 'https://api-bc.wtzw.com/api/v1/reader/detail', {
    id: String(id)
  });
}

function qmTocUrl(id) {
  return qmSignUrl.call(this, 'https://api-ks.wtzw.com/api/v1/chapter/chapter-list', {
    chapter_ver: '0',
    id: String(id)
  });
}

function qmContentUrl(bid, cid) {
  return qmSignUrl.call(this, 'https://api-ks.wtzw.com/api/v1/chapter/content', {
    id: String(bid),
    chapterId: String(cid)
  });
}

function qmDecryptContent(text) {
  var d = JSON.parse(text);
  var b64 = String(d.data && d.data.content || '');
  if (!b64) return '';
  var raw = Packages.android.util.Base64.decode(b64, 0);
  if (!raw || raw.length <= 16) return '';
  var iv = Packages.java.util.Arrays.copyOfRange(raw, 0, 16);
  var encrypted = Packages.java.util.Arrays.copyOfRange(raw, 16, raw.length);
  var key = new Packages.javax.crypto.spec.SecretKeySpec(
    new Packages.java.lang.String(QM_AES_KEY).getBytes('UTF-8'), 'AES');
  var ivSpec = new Packages.javax.crypto.spec.IvParameterSpec(iv);
  var cipher = Packages.javax.crypto.Cipher.getInstance('AES/CBC/PKCS5Padding');
  cipher.init(2, key, ivSpec);
  return String(new Packages.java.lang.String(cipher.doFinal(encrypted), 'UTF-8'));
}

