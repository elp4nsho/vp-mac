package vpmac;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Interruptores de VPMac, leidos una vez al arrancar desde ~/Zomboid/vpmac.properties (si no existe: todo apagado,
 * que es lo normal para jugar). Lo apagado no cuesta nada: los ganchos ni siquiera se insertan en el bytecode.
 *
 *   debug=true  lo que hay que pedirle a un jugador cuando reporta algo: diag + fps (sin volcados grandes)
 *   fps=true    cada 10 s: FPS promedio, tiempo de cuadro medio y el 1% peor (para comparar cambios)
 *   diag=true   "primer dibujo con <programa> [vs+fs]" + vigilante del render (cuesta un poco por dibujo)
 *   dump=true   guarda el GLSL final y la foto de estado del primer dibujo en dumpDir (para _macgl/lab)
 *   dumpDir=... carpeta de volcados (por defecto ~/Zomboid/Workshop/_vpmac/dump)
 *   report=true       informe de errores: si una sesion termina mal, al arrancar la siguiente se arma un informe
 *                     para compartir (~/Zomboid/vpmac/reports/) y se vigila si el render se congela
 *   reportPopup=true  ventana de macOS con botones para copiar ese informe
 *   vpStatsSeconds=60 cada cuantos segundos Viewpoint escribe su linea de estadisticas (~4 KB) en la consola
 *                     (Viewpoint trae 10; 0 = no tocar). La ultima igual entra en los informes.
 *
 *   bench=60    benchmark: entra solo a primera persona y mide 60 s (Bench); fuerza fps=true
 *   benchDelay=15  segundos de mundo cargado antes de entrar (deja que se asiente la carga)
 *
 * Rendimiento (encendidos por defecto; ponerlos en false solo para comparar o si algo se ve raro):
 *   batchDraws=true   multi-draw emulado: revisa el estado una vez por lote y no por comando (Accel)
 *   gpuTiming=false   sin sellos de tiempo de GPU por cuadro (solo los usa el overlay de rendimiento de Viewpoint)
 *   pickEvery=3       lectura de profundidad bajo la mira (MousePick) cada N cuadros; 1 = como el original
 *   smartPages=true   no empaquetar (Pages41) shaders que caben en las 16 unidades del Mac
 *   tlCache=true      cache de ThreadLocal de las imitaciones para el hilo de render (TLCache)
 *   skipMarks=true    Pages41.markDraw no hace nada mientras no haya programas empaquetados
 *   fastUnits=true    Units41 (unidades de textura virtuales) reemplazada por FastUnits41: misma logica, sin HashMap
 *   gpuPack=false     copiar las tablas de texels en la GPU (transform feedback). APAGADO: medido mas lento en el
 *                     OpenGL-sobre-Metal de Apple (43 vs 50 fps, 2026-10-24); se deja por si un macOS futuro cambia
 */
final class Config {
    static final Path FILE = Path.of(System.getProperty("user.home"), "Zomboid", "vpmac.properties");
    static final boolean FPS, DIAG, DUMP, BATCH_DRAWS, GPU_TIMING, SMART_PAGES, GPU_PACK, TL_CACHE, SKIP_MARKS, FAST_UNITS, REPORT, REPORT_POPUP;
    static final int PICK_EVERY, BENCH, BENCH_DELAY, VP_STATS_SECONDS;
    private static String raw = "";
    static final Path DUMP_DIR;

    static {
        Properties p = new Properties();
        if (Files.isRegularFile(FILE)) {
            try (InputStream in = Files.newInputStream(FILE)) {
                p.load(in);
            } catch (Exception e) {
                Log.warn("could not read " + FILE + ": " + e);
            }
        }
        BENCH = num(p, "bench", 0);
        BENCH_DELAY = num(p, "benchDelay", 15);
        VP_STATS_SECONDS = num(p, "vpStatsSeconds", 60);
        boolean debug = on(p, "debug");
        FPS = on(p, "fps") || BENCH > 0 || debug;
        DIAG = on(p, "diag") || debug;
        DUMP = on(p, "dump");
        BATCH_DRAWS = Boolean.parseBoolean(p.getProperty("batchDraws", "true").trim());
        GPU_TIMING = on(p, "gpuTiming");
        SMART_PAGES = Boolean.parseBoolean(p.getProperty("smartPages", "true").trim());
        GPU_PACK = on(p, "gpuPack");
        TL_CACHE = Boolean.parseBoolean(p.getProperty("tlCache", "true").trim());
        SKIP_MARKS = Boolean.parseBoolean(p.getProperty("skipMarks", "true").trim());
        FAST_UNITS = Boolean.parseBoolean(p.getProperty("fastUnits", "true").trim());
        REPORT = Boolean.parseBoolean(p.getProperty("report", "true").trim());
        REPORT_POPUP = Boolean.parseBoolean(p.getProperty("reportPopup", "true").trim());
        PICK_EVERY = Math.max(1, Math.min(num(p, "pickEvery", 3), 30));
        StringBuilder r = new StringBuilder();
        p.stringPropertyNames().stream().sorted().forEach(k -> r.append(k).append('=').append(p.getProperty(k)).append(' '));
        raw = r.toString().trim();
        DUMP_DIR = Path.of(p.getProperty("dumpDir",
                Path.of(System.getProperty("user.home"), "Zomboid", "Workshop", "_vpmac", "dump").toString()));
        if (DUMP) {
            try { Files.createDirectories(DUMP_DIR); } catch (Exception e) { Log.warn("could not create " + DUMP_DIR); }
        }
    }

    private Config() {}

    private static int num(Properties p, String key, int def) {
        try { return Integer.parseInt(p.getProperty(key, String.valueOf(def)).trim()); } catch (NumberFormatException e) { return def; }
    }

    /** el archivo tal cual (para dejarlo junto a un resultado de benchmark) */
    static String raw() {
        return raw;
    }

    private static boolean on(Properties p, String key) {
        return Boolean.parseBoolean(p.getProperty(key, "false").trim());
    }

    static String describe() {
        return "fps=" + FPS + " diag=" + DIAG + " dump=" + DUMP + " batchDraws=" + BATCH_DRAWS + " gpuTiming=" + GPU_TIMING + " smartPages=" + SMART_PAGES + " gpuPack=" + GPU_PACK + " tlCache=" + TL_CACHE + " skipMarks=" + SKIP_MARKS + " fastUnits=" + FAST_UNITS
                + " pickEvery=" + PICK_EVERY + (BENCH > 0 ? " bench=" + BENCH : "");
    }
}
