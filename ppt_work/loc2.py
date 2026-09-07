import re
s = open(r'D:\android\reading-app\app\app\src\main\assets\turn.min.js', encoding='utf-8').read()
# find assignments to .point
for m in re.finditer(r'\.point\s*=', s):
    print('point= at', m.start())
# find .point usages
idxs = [m.start() for m in re.finditer(r'\.point', s)]
print('total .point usages:', len(idxs))
for i in idxs:
    print(i, s[max(0,i-60):i+60].replace('\n',' '))
