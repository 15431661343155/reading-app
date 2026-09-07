import re
s = open(r'D:\android\reading-app\app\app\src\main\assets\turn.min.js', encoding='utf-8').read()
pats = ['_showFoldedPage.call', 'flip("peel"', 'flip("hover"', '_isIArea.call', 'trigger("pressed"']
for pat in pats:
    idxs = [m.start() for m in re.finditer(re.escape(pat), s)]
    print(pat, '->', idxs)
    for i in idxs:
        print('   ', s[max(0,i-80):i+100].replace('\n', ' '))
