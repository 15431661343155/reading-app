import re
s = open(r'D:\android\reading-app\app\app\src\main\assets\turn.min.js', encoding='utf-8').read()
for pat in ['pressed', 'released', 'flip("point"', 'flip("event"', '_eventMove', '_isIArea', 'trigger("start"']:
    idxs = [m.start() for m in re.finditer(re.escape(pat), s)]
    print(pat, '->', idxs[:12])
