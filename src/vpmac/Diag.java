package vpmac;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Diagnostico del render en el Mac:
 *  - "primer uso del programa": nombre de Viewpoint + hashes de sus shaders (= archivos de _vpmac/dump/, para
 *    _macgl/lab). Se anota a nivel OpenGL (glUseProgram), asi aparecen tambien los que no pasan por GlProgram.use.
 *  - un vigilante en otro hilo avisa si el render se queda pegado (Metal compilando / su compilador cayendose).
 */
public final class Diag {
    private static final Map<Integer, String> shaderHash = new HashMap<>();   // shader -> hash de su fuente
    private static final Map<Integer, String> programShaders = new HashMap<>(); // programa -> "vs+fs"
    private static final Map<Integer, String> programName = new HashMap<>();
    private static final Set<Integer> seen = new HashSet<>();
    private static final ArrayDeque<String> recent = new ArrayDeque<>();
    private static volatile long lastUse;
    private static Thread watchdog;

    private Diag() {}

    static synchronized void source(int shader, String hash) {
        shaderHash.put(shader, hash);
    }

    public static void glAttachShader(int program, int shader) {
        synchronized (Diag.class) {
            String h = shaderHash.getOrDefault(shader, "?");
            programShaders.merge(program, h, (a, b) -> a + "+" + b);
        }
        viewpoint.mac41.Pages41.glAttachShader(program, shader);
    }

    /** desde GlProgram.use(): pone nombre al programa */
    public static synchronized void use(String name, int program) {
        programName.put(program, name);
    }

    private static final Set<Integer> drawn = new HashSet<>();
    private static volatile int current;

    /** antes de cada dibujo de Viewpoint: el primer dibujo de un programa es cuando Metal compila de verdad */
    public static void draw() {
        int p = current;
        if (p == 0 || drawn.contains(p)) return;
        synchronized (Diag.class) {
            if (!drawn.add(p)) return;
            String shaders = programShaders.getOrDefault(p, "?");
            Log.info("first draw with " + programName.getOrDefault(p, "#" + p) + " [" + shaders + "]");
            if (Config.DUMP) StateSnap.save(Config.DUMP_DIR, p, shaders);
        }
        System.out.flush();
    }

    // empaquetados de Pages41 (texturas copiadas a la tabla de texels con glGetTexImage), por programa
    private static final Map<String, int[]> packs = new HashMap<>();
    private static long packWindow;

    /** antes de cada glGetTexImage de Pages41.pack: cuenta por programa y resume cada 10 s */
    public static void pageRead() {
        long now = System.nanoTime();
        synchronized (Diag.class) {
            String label = programName.getOrDefault(current, "#" + current);
            packs.computeIfAbsent(label, k -> new int[1])[0]++;
            if (packWindow == 0) packWindow = now;
            if (now - packWindow >= 10_000_000_000L) {
                StringBuilder b = new StringBuilder();
                packs.entrySet().stream().sorted((x, y) -> y.getValue()[0] - x.getValue()[0])
                        .forEach(e -> b.append(e.getKey()).append('=').append(e.getValue()[0]).append(' '));
                Log.info("Pages41 glGetTexImage in 10 s: " + b.toString().trim());
                packs.clear();
                packWindow = now;
            }
        }
    }

    public static void glUseProgram(int program) {
        current = program;
        lastUse = System.nanoTime();
        if (program != 0) {
            String label;
            synchronized (Diag.class) {
                label = programName.getOrDefault(program, "#" + program);
                if (!label.equals(recent.peekLast())) {
                    recent.addLast(label);
                    if (recent.size() > 6) recent.removeFirst();
                }
                if (seen.add(program)) {
                    Log.info("first use of program " + label + " [" + programShaders.getOrDefault(program, "?") + "]");
                }
            }
            if (watchdog == null) startWatchdog();
        }
        viewpoint.mac41.Ranges41.glUseProgram(program);
    }

    private static synchronized void startWatchdog() {
        if (watchdog != null) return;
        watchdog = new Thread(Diag::watch, "VPMac watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private static void watch() {
        boolean reported = false;
        while (true) {
            try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
            long idle = (System.nanoTime() - lastUse) / 1_000_000_000L;
            if (idle >= 5 && !reported) {
                String last;
                synchronized (Diag.class) { last = String.join(" -> ", recent); }
                Log.warn("render has not advanced for " + idle + " s; last programs: " + last);
                reported = true;
            } else if (idle < 5) reported = false;
        }
    }
}
