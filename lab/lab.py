#!/usr/bin/env python3
"""macgl lab: prueba shaders GLSL contra el OpenGL 4.1 / Metal del Mac, con tiempo limite, sin abrir el juego.

    lab.py run   <vertex.glsl> <fragment.glsl> [--timeout 20] [--targets 3] [--format rgba16f]
    lab.py defines <vertex.glsl> <fragment.glsl>      prueba apagando/prendiendo cada #define PIPELINE_* 0/1
    lab.py bisect  <vertex.glsl> <fragment.glsl>      achica main() del fragment hasta la linea que cuelga a Metal
    lab.py stubs   <vertex.glsl> <fragment.glsl>      vacia cada funcion por turno: cual saca el cuelgue
    lab.py stress  <vertex.glsl> <fragment.glsl>      compila N veces en paralelo y cuenta caidas del compilador de Apple
    lab.py compat  <carpeta> [--n 6]                  compatibilidad de esta Mac: estres de TODOS los pares volcados
    lab.py sweep   <carpeta>                          prueba todos los pares volcados del juego y lista los que fallan

Cada prueba corre en un proceso aparte (ShaderLab.java) porque un cuelgue de Metal no se puede cortar desde
adentro. Resultado: ok / FAIL (error de compilacion o GL) / HANG (no termino dentro del tiempo) /
CRASH (el compilador de Metal de Apple se cayo: aparece un MTLCompilerService-*.ips nuevo).
"""
import argparse
import os
import re
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
GAME = os.path.join(os.environ.get('STEAMAPPS') or os.path.expanduser('~/Library/Application Support/Steam/steamapps'),
                    'common/ProjectZomboid/Project Zomboid.app/Contents')
JAVA = os.path.join(GAME, 'PlugIns/jre-aarch64/Contents/Home/bin/java')
CP = os.path.join(HERE, 'build') + os.pathsep + os.path.join(GAME, 'Java/projectzomboid.jar')
WORK = tempfile.mkdtemp(prefix='macgl-lab-')


REPORTS = os.path.expanduser('~/Library/Logs/DiagnosticReports')


def compiler_crashes(since):
    """reportes nuevos del compilador de Metal (MTLCompilerService) desde `since`: [(archivo, motivo)]"""
    out = []
    for f in os.listdir(REPORTS):
        p = os.path.join(REPORTS, f)
        if f.startswith('MTLCompilerService') and os.path.getmtime(p) >= since:
            out.append((f, crash_reason(p)))
    return out


def crash_reason(path):
    try:
        import json
        head, body = open(path).read().split('\n', 1)
        j = json.loads(body)
        sub = j.get('exception', {}).get('subtype', '') or j.get('exception', {}).get('type', '')
        th = [t for t in j.get('threads', []) if t.get('triggered')]
        sym = th[0]['frames'][0].get('symbol', '?') if th else '?'
        return '%s en %s' % (sub.split(' at ')[0], sym.split('(')[0])
    except Exception as e:  # noqa
        return 'reporte ilegible (%s)' % e


def run(vs_text, fs_text, timeout=20, targets=3, fmt='rgba16f', quiet=False, state=None):
    """devuelve (resultado, ms_del_dibujo, detalle). Resultado: ok, FAIL, HANG o CRASH (el compilador de Apple
    se cayo; suele verse como HANG porque el driver reintenta)."""
    import time
    vs, fs = os.path.join(WORK, 'v.glsl'), os.path.join(WORK, 'f.glsl')
    open(vs, 'w').write(vs_text)
    open(fs, 'w').write(fs_text)
    cmd = [JAVA, '-XstartOnFirstThread', '-cp', CP, 'macgl.lab.ShaderLab', vs, fs, str(targets), fmt] + ([state] if state else [])
    t0 = time.time() - 1
    p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    try:
        out, _ = p.communicate(timeout=timeout)
    except subprocess.TimeoutExpired:
        p.kill()
        out, _ = p.communicate()
        time.sleep(1.5)  # el reporte de crash se escribe un poco despues
        crashes = compiler_crashes(t0)
        if crashes:
            return 'CRASH', None, '%d caidas del compilador de Metal: %s' % (len(crashes), crashes[0][1])
        return 'HANG', None, last_stage(out)
    crashes = compiler_crashes(t0)
    if crashes and 'STAGE draw' not in out:
        return 'CRASH', None, '%d caidas del compilador de Metal: %s' % (len(crashes), crashes[0][1])
    if not quiet:
        for line in out.splitlines():
            if line.startswith(('INFO', 'STAGE')):
                print('   ', line[:300])
    m = re.search(r'^STAGE draw ([\d.]+) ok', out, re.M)
    if m:
        return 'ok', float(m.group(1)), ''
    fails = [l for l in out.splitlines() if ' FAIL ' in l]
    return 'FAIL', None, (fails[0] if fails else out[-400:])[:400]


def last_stage(out):
    lines = [l for l in out.splitlines() if l.startswith(('STAGE', 'BEGIN'))]
    return 'colgado despues de: ' + (lines[-1] if lines else '(nada)')


def cmd_run(a):
    r, ms, d = run(read(a.vs), read(a.fs), a.timeout, a.targets, a.format, state=a.state)
    print('RESULTADO:', r, '' if ms is None else '%.0f ms' % ms, d)


def cmd_defines(a):
    vs, fs = read(a.vs), read(a.fs)
    names = sorted(set(re.findall(r'^#define (PIPELINE_\w+) [01]\s*$', fs, re.M)))
    base = run(vs, fs, a.timeout, quiet=True)
    print('base:', fmt_result(base))
    for n in names:
        cur = re.search(r'^#define %s ([01])' % n, fs, re.M).group(1)
        flip = '0' if cur == '1' else '1'
        f2 = re.sub(r'^#define %s [01]' % n, '#define %s %s' % (n, flip), fs, flags=re.M)
        v2 = re.sub(r'^#define %s [01]' % n, '#define %s %s' % (n, flip), vs, flags=re.M)
        print('%-36s %s->%s: %s' % (n, cur, flip, fmt_result(run(v2, f2, a.timeout, quiet=True))))


def cmd_bisect(a):
    """Corta main() del fragment: deja las primeras k sentencias de nivel superior y fija las salidas al final.
    Busca el k mas chico que ya cuelga."""
    vs, fs = read(a.vs), read(a.fs)
    head, body, tail = split_main(fs)
    stmts = top_statements(body)
    outs = re.findall(r'^\s*layout\s*\(\s*location\s*=\s*\d+\s*\)\s*out\s+\w+\s+(\w+)\s*;', fs, re.M)
    print('main() tiene %d sentencias de nivel superior; salidas: %s' % (len(stmts), ', '.join(outs)))

    def variant(k):
        keep = ''.join(stmts[:k])
        # al cortar, las salidas quedan sin escribir: se completan con 0 para que el shader siga siendo valido
        fill = ''.join('\n  %s = vec4(0.0);' % o for o in outs) if k < len(stmts) else ''
        return head + '{' + fill + keep + '\n}' + tail

    full = run(vs, variant(len(stmts)), a.timeout, quiet=True)
    print('completo:', fmt_result(full))
    if full[0] != 'HANG':
        print('el shader completo no cuelga aqui; nada que buscar')
        return
    lo, hi = 0, len(stmts)  # lo: no cuelga ; hi: cuelga
    if run(vs, variant(0), a.timeout, quiet=True)[0] == 'HANG':
        print('cuelga incluso con main() vacio: el problema esta fuera de main (uniforms, funciones globales)')
        return
    while hi - lo > 1:
        mid = (lo + hi) // 2
        r = run(vs, variant(mid), a.timeout, quiet=True)
        print('  primeras %3d sentencias: %s' % (mid, fmt_result(r)))
        if r[0] == 'HANG':
            hi = mid
        else:
            lo = mid
    print('\nLa sentencia que hace colgar a Metal es la #%d:\n%s' % (hi, stmts[hi - 1].strip()))


# (tipo, nombre, tamano de arreglo): para emparejar no basta el nombre (SMAA usa vOffset[3] y vOffset[2])
VARYING_OUT = re.compile(r'^\s*(?:flat\s+|smooth\s+|noperspective\s+)*out\s+(\w+)\s+(\w+)\s*(\[\s*\w+\s*\])?\s*;', re.M)
VARYING_IN = re.compile(r'^\s*(?:flat\s+|smooth\s+|noperspective\s+)*in\s+(\w+)\s+(\w+)\s*(\[\s*\w+\s*\])?\s*;', re.M)


def pair_shaders(folder):
    """empareja vertex y fragment volcados: el vertex cuyas salidas cubren justo las entradas del fragment"""
    verts, frags = {}, {}
    for f in sorted(os.listdir(folder)):
        if not f.endswith('.glsl'):
            continue
        src = read(os.path.join(folder, f))
        if 'gl_Position' in src:
            verts[f] = set((t, n, a.replace(' ', '')) for t, n, a in VARYING_OUT.findall(src))
        elif re.search(r'\bvoid\s+main\s*\(', src) and 'gl_GlobalInvocationID' not in src:
            frags[f] = set((t, n, a.replace(' ', '')) for t, n, a in VARYING_IN.findall(src))
    pairs = []
    for f, ins in frags.items():
        cands = [v for v, outs in verts.items() if ins <= outs]
        if cands:
            # el que sobra menos (el vertex "propio" de ese fragment)
            pairs.append((min(cands, key=lambda v: len(verts[v] - ins)), f))
    return pairs, len(frags)


def cmd_sweep(a):
    """Prueba todos los pares vertex/fragment de una carpeta de volcados y lista los que fallan."""
    pairs, nfrag = pair_shaders(a.folder)
    print('%d fragment shaders, %d emparejados con su vertex' % (nfrag, len(pairs)))
    bad = []
    for i, (v, f) in enumerate(pairs, 1):
        r = run(read(os.path.join(a.folder, v)), read(os.path.join(a.folder, f)), a.timeout, quiet=True)
        slow = r[0] == 'ok' and r[1] is not None and r[1] > 1000
        if r[0] != 'ok' or slow or a.verbose:
            print('  [%3d/%d] %s + %s: %s' % (i, len(pairs), v, f, fmt_result(r)))
        if r[0] != 'ok' or slow:
            bad.append((v, f, r))
    print('\n%d con problemas de %d' % (len(bad), len(pairs)))
    for v, f, r in bad:
        print('  %s %s  %s' % (v, f, r[0] + ('' if r[1] is None else ' %.0f ms' % r[1])))


def stress(vs_text, fs_text, n=12, parallel=4, state=None, timeout=60):
    """compila el par n veces (de a `parallel` procesos a la vez): (ok, fallidos, caidas_del_compilador)"""
    import time
    from concurrent.futures import ThreadPoolExecutor
    folder = tempfile.mkdtemp(prefix='macgl-stress-')
    vs, fs = os.path.join(folder, 'v.glsl'), os.path.join(folder, 'f.glsl')
    open(vs, 'w').write(vs_text)
    open(fs, 'w').write(fs_text)
    cmd = [JAVA, '-XstartOnFirstThread', '-cp', CP, 'macgl.lab.ShaderLab', vs, fs, '3', 'rgba16f'] + ([state] if state else [])
    t0 = time.time() - 1

    def one(_):
        try:
            out = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout).stdout
            return 'STAGE draw' in out and ' ok' in out.split('STAGE draw')[1].split('\n')[0]
        except subprocess.TimeoutExpired:
            return False
    with ThreadPoolExecutor(parallel) as ex:
        results = list(ex.map(one, range(n)))
    time.sleep(2)
    return sum(results), n - sum(results), len(compiler_crashes(t0))


def cmd_stress(a):
    ok, bad, crashes = stress(read(a.vs), read(a.fs), a.n, a.parallel, a.state)
    print('compilaciones: %d ok, %d fallidas; caidas del compilador de Metal: %d' % (ok, bad, crashes))


def cmd_compat(a):
    """Prueba de compatibilidad de ESTA Mac: cada par vertex/fragment volcado se compila `--n` veces en paralelo
    (con su foto de estado si existe). Lista los que hacen caer o colgar al compilador de Metal de esta GPU."""
    import platform, time
    pairs, nfrag = pair_shaders(a.folder)
    chip = subprocess.run(['/usr/sbin/sysctl', '-n', 'machdep.cpu.brand_string'], capture_output=True, text=True).stdout.strip()
    print('Mac: %s, macOS %s | %d pares, %d compilaciones c/u' % (chip, platform.mac_ver()[0], len(pairs), a.n))
    bad, t0 = [], time.time()
    for i, (v, f) in enumerate(pairs, 1):
        st = os.path.join(a.folder, 'state_%s+%s.json' % (v[:-5], f[:-5]))
        ok, failed, crashes = stress(read(os.path.join(a.folder, v)), read(os.path.join(a.folder, f)), a.n, a.parallel,
                                     st if os.path.exists(st) else None, a.timeout)
        line = '[%3d/%d] %s + %s: %d ok, %d fallidas, %d caidas del compilador%s' % (
            i, len(pairs), v, f, ok, failed, crashes, '' if os.path.exists(st) else ' (sin foto de estado)')
        if failed or crashes:
            bad.append(line)
            print('  PROBLEMA', line)
        elif a.verbose:
            print('  ', line)
    print('\n%s: %d de %d pares con problemas (%.0f min)' % ('OK' if not bad else 'REVISAR', len(bad), len(pairs),
                                                            (time.time() - t0) / 60))
    for line in bad:
        print('  ', line)


FUNC = re.compile(r'^(\w+)\s+(\w+)\s*\(([^)]*)\)\s*\{', re.M)
GLSL_TYPES = {'float', 'int', 'uint', 'bool', 'vec2', 'vec3', 'vec4', 'ivec2', 'ivec3', 'ivec4', 'uvec2', 'uvec3',
              'uvec4', 'bvec2', 'bvec3', 'bvec4', 'mat2', 'mat3', 'mat4'}


def functions(src):
    """[(nombre, inicio_cuerpo, fin_cuerpo, tipo)] de cada funcion global salvo main"""
    out = []
    for m in FUNC.finditer(src):
        ret, name = m.group(1), m.group(2)
        if name == 'main' or ret in ('if', 'for', 'while', 'else', 'return', 'switch'):
            continue
        start, depth, i = m.end() - 1, 0, m.end() - 1
        while True:
            depth += src[i] == '{'
            depth -= src[i] == '}'
            if depth == 0:
                break
            i += 1
        out.append((name, start, i + 1, ret))
    return out


def stub_body(ret):
    if ret == 'void':
        return '{}'
    if ret == 'bool':
        return '{ return false; }'
    if ret in GLSL_TYPES:
        return '{ return %s(0); }' % ret
    return '{ %s _z; return _z; }' % ret  # struct: valor sin inicializar, sirve para compilar


def cmd_stubs(a):
    """Vacia cada funcion del fragment (una a la vez) y dice cuales, vaciadas, sacan el cuelgue."""
    vs, fs = read(a.vs), read(a.fs)
    base = run(vs, fs, a.timeout, quiet=True)
    print('base:', fmt_result(base))
    if base[0] != 'HANG':
        print('no cuelga: nada que buscar')
        return
    fns = functions(fs)
    if a.only:
        fns = [f for f in fns if f[0] in a.only.split(',')]
    print('%d funciones a probar' % len(fns))
    culprits = []
    for name, s, e, ret in fns:
        r = run(vs, fs[:s] + stub_body(ret) + fs[e:], a.timeout, quiet=True)
        mark = '  <== sin esta, NO cuelga' if r[0] == 'ok' else ''
        print('  %-28s %s%s' % (name, fmt_result(r), mark))
        if r[0] == 'ok':
            culprits.append(name)
    print('\nfunciones que sacan el cuelgue al vaciarlas:', ', '.join(culprits) or '(ninguna sola)')


def split_main(src):
    m = re.search(r'\bvoid\s+main\s*\(\s*\)\s*\{', src)
    start = m.end() - 1
    depth, i = 0, start
    while True:
        c = src[i]
        depth += c == '{'
        depth -= c == '}'
        if depth == 0:
            break
        i += 1
    return src[:start], src[start + 1:i], src[i + 1:]


def top_statements(body):
    """divide el cuerpo en sentencias de nivel superior (respetando llaves, parentesis y #if/#endif)"""
    out, cur, depth, par, pp = [], '', 0, 0, 0
    for line in body.splitlines(keepends=True):
        s = line.strip()
        if s.startswith('#'):
            cur += line
            if s.startswith('#if'):
                pp += 1
            elif s.startswith('#endif'):
                pp -= 1
                if pp == 0 and depth == 0 and par == 0:
                    out.append(cur)
                    cur = ''
            continue
        for ch in line:
            cur += ch
            if ch == '(':
                par += 1
            elif ch == ')':
                par -= 1
            elif ch == '{':
                depth += 1
            elif ch == '}':
                depth -= 1
            elif ch == ';' and depth == 0 and par == 0 and pp == 0:
                out.append(cur)
                cur = ''
        if depth == 0 and par == 0 and pp == 0 and cur.strip().endswith('}'):
            out.append(cur)
            cur = ''
    if cur.strip():
        out.append(cur)
    # un "else" suelto pertenece al if anterior
    merged = []
    for st in out:
        if merged and re.match(r'\s*else\b', st):
            merged[-1] += st
        else:
            merged.append(st)
    return merged


def fmt_result(r):
    res, ms, d = r
    return res + ('' if ms is None else ' (%.0f ms)' % ms) + ('' if not d else '  ' + d)


def read(p):
    return open(p).read()


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest='cmd', required=True)
    for name, fn in (('run', cmd_run), ('defines', cmd_defines), ('bisect', cmd_bisect), ('stubs', cmd_stubs)):
        s = sub.add_parser(name)
        s.add_argument('vs')
        s.add_argument('fs')
        s.add_argument('--timeout', type=float, default=20)
        s.add_argument('--targets', type=int, default=3)
        s.add_argument('--format', default='rgba16f')
        s.add_argument('--only', default='', help='stubs: solo estas funciones (coma)')
        s.add_argument('--state', default=None, help='foto de estado del juego (state_*.json de _vpmac/dump)')
        s.set_defaults(fn=fn)
    s = sub.add_parser('stress')
    s.add_argument('vs')
    s.add_argument('fs')
    s.add_argument('--n', type=int, default=12)
    s.add_argument('--parallel', type=int, default=4)
    s.add_argument('--state', default=None)
    s.set_defaults(fn=cmd_stress)
    s = sub.add_parser('compat')
    s.add_argument('folder')
    s.add_argument('--n', type=int, default=6)
    s.add_argument('--parallel', type=int, default=3)
    s.add_argument('--timeout', type=float, default=60)
    s.add_argument('--verbose', action='store_true')
    s.set_defaults(fn=cmd_compat)
    s = sub.add_parser('sweep')
    s.add_argument('folder')
    s.add_argument('--timeout', type=float, default=15)
    s.add_argument('--verbose', action='store_true')
    s.set_defaults(fn=cmd_sweep)
    a = ap.parse_args()
    if not os.path.exists(os.path.join(HERE, 'build', 'macgl', 'lab', 'ShaderLab.class')):
        sys.exit('falta compilar: corre lab/build.sh')
    a.fn(a)


if __name__ == '__main__':
    main()
