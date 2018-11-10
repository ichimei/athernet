#!/usr/bin/env python3

import sys
argv = sys.argv
assert(len(argv) == 3)

f1 = open(argv[1], 'rb')
f2 = open(argv[2], 'rb')
s1 = f1.read()
s2 = f2.read()
f1.close()
f2.close()

dif = 0

print('f1 has length', len(s1))
print('f2 has length', len(s2))

for i in range(min(len(s1), len(s2))):
    if s1[i] != s2[i]:
        dif += 1

print(dif, 'bytes differ')
