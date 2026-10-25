#!/usr/bin/env python3
"""Convierte salida de terminal (texto) en una imagen PNG con aspecto de terminal, para la documentacion.
   termshot.py entrada.txt salida.png ["titulo"]
Lineas que empiezan con '$ ' se dibujan como comando (verde); 'OK'/'ok' en verde, HANG/CRASH/FAIL en rojo."""
import sys
from PIL import Image, ImageDraw, ImageFont

src, out = sys.argv[1], sys.argv[2]
title = sys.argv[3] if len(sys.argv) > 3 else ''
lines = open(src, encoding='utf-8').read().rstrip('\n').split('\n')
font = None
for f in ('/System/Library/Fonts/Menlo.ttc', '/System/Library/Fonts/Monaco.ttf'):
    try:
        font = ImageFont.truetype(f, 22)
        break
    except Exception:
        pass
font = font or ImageFont.load_default()
cw = font.getbbox('M')[2]
lh = 30
cols = min(max(len(l) for l in lines + [title]) + 2, 150)
w, h = cols * cw + 48, len(lines) * lh + 90
img = Image.new('RGB', (w, h), (24, 26, 33))
d = ImageDraw.Draw(img)
d.rectangle((0, 0, w, 44), fill=(44, 47, 58))
for i, c in enumerate(((255, 95, 87), (254, 188, 46), (40, 200, 64))):
    d.ellipse((18 + i * 26, 14, 34 + i * 26, 30), fill=c)
d.text((110, 10), title, font=font, fill=(170, 175, 190))
y = 62
for l in lines:
    l = l[:cols]
    color = (215, 218, 225)
    if l.startswith('$ '):
        color = (120, 220, 140)
    elif any(k in l for k in ('HANG', 'CRASH', 'FAIL', 'FALLA', 'PROBLEMA')):
        color = (255, 120, 110)
    elif l.strip().startswith(('OK', 'ok ', 'RESULTADO: ok')) or ' ok (' in l or 'distintos 0' in l:
        color = (140, 225, 150)
    d.text((24, y), l, font=font, fill=color)
    y += lh
img.save(out)
print(out, img.size)
