#!/usr/bin/env python3
"""Lee `jfr print --json --events jdk.ExecutionSample` por stdin y resume donde se va el CPU de Java.

Por hilo: % de muestras; y para los hilos de render/juego, los metodos mas calientes (propio = el metodo de
arriba de la pila; total = aparece en cualquier parte de la pila), marcando de quien es cada uno:
[VP] Viewpoint, [M41] imitaciones de xiveboy, [VPMac] lo nuestro, [PZ] el juego, [GL] LWJGL/OpenGL.
"""
import json
import sys
from collections import Counter, defaultdict


def owner(cls):
    if cls.startswith('viewpoint.mac41'):
        return 'M41'
    if cls.startswith('viewpoint'):
        return 'VP'
    if cls.startswith('vpmac'):
        return 'VPMac'
    if cls.startswith('zombie'):
        return 'PZ'
    if cls.startswith('org.lwjgl'):
        return 'GL'
    return cls.split('.')[0] if '.' in cls else cls


def name(frame):
    m = frame['method']
    cls = m['type']['name'].replace('/', '.')
    return '[%s] %s.%s' % (owner(cls), cls.split('.')[-1], m['name']), owner(cls)


data = json.load(sys.stdin)
events = data['recording']['events']
threads = Counter()
self_t = defaultdict(Counter)
total_t = defaultdict(Counter)
owner_t = defaultdict(Counter)
for e in events:
    v = e['values']
    t = (v.get('sampledThread') or {}).get('javaName') or '?'
    frames = (v.get('stackTrace') or {}).get('frames') or []
    if not frames:
        continue
    threads[t] += 1
    top, own = name(frames[0])
    self_t[t][top] += 1
    owner_t[t][own] += 1
    seen = set()
    for f in frames:
        n, _ = name(f)
        if n not in seen:
            total_t[t][n] += 1
            seen.add(n)

all_n = sum(threads.values())
print('muestras: %d' % all_n)
for t, c in threads.most_common(8):
    print('\n== hilo %s: %.1f%% de las muestras' % (t, 100.0 * c / all_n))
    print('   por dueno (propio):', ', '.join('%s %.0f%%' % (o, 100.0 * k / c) for o, k in owner_t[t].most_common(6)))
    print('   propio:')
    for n, k in self_t[t].most_common(12):
        print('     %5.1f%%  %s' % (100.0 * k / c, n))
    print('   total (incluye lo que llama):')
    for n, k in total_t[t].most_common(14):
        print('     %5.1f%%  %s' % (100.0 * k / c, n))
