package vpmac;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/**
 * Sesion de juego para el informe de errores (Config.REPORT):
 *  - al arrancar (premain, ANTES de que el juego borre console.txt) mira la marca de la sesion anterior; si no
 *    termino limpia (crash, cierre forzado, congelamiento) guarda su console.txt y su vpmac.log y arma un informe
 *    (Report) con los crash reports de macOS de ese rato;
 *  - marca la sesion nueva como "en curso" y un shutdown hook la marca "limpia" al salir bien (un crash nativo o
 *    un kill -9 no corren hooks: eso es justo lo que se detecta la vez siguiente);
 *  - latido: FrameStream.frameStarted llama beat() (un nanoTime por cuadro); un hilo vigilante guarda un informe de
 *    congelamiento con la pila del hilo de render si en primera persona no hay cuadros por 15 s.
 */
public final class Session {
    static final Path MARK = Log.DIR.resolve("session.properties");
    private static final long FREEZE_NANOS = 15_000_000_000L;
    private static volatile long lastBeat;
    private static volatile Thread renderThread;
    static volatile String phase = "starting"; // starting -> menu/world -> first person
    static final long START = System.currentTimeMillis();
    static final String ID = Long.toHexString(START & 0xFFFFFF).toUpperCase();

    private Session() {}

    /** primero en premain: antes de que el juego borre console.txt y antes de que Log rote vpmac.log */
    public static void start() {
        try {
            Files.createDirectories(Log.DIR);
            Path keep = Log.DIR.resolve("previous-session");
            Files.createDirectories(keep);
            Path console = Path.of(System.getProperty("user.home"), "Zomboid", "console.txt");
            if (Files.exists(console)) Files.copy(console, keep.resolve("console.txt"), StandardCopyOption.REPLACE_EXISTING);
            Path log = Log.DIR.resolve("vpmac.log");
            if (Files.exists(log)) Files.copy(log, keep.resolve("vpmac.log"), StandardCopyOption.REPLACE_EXISTING);
            Properties prev = new Properties();
            if (Files.exists(MARK)) try (var in = Files.newInputStream(MARK)) { prev.load(in); }
            if (!prev.isEmpty() && !"clean".equals(prev.getProperty("state"))) previousEndedBadly(prev, keep);
            write("running");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> write("clean"), "VPMac session end"));
            if (Config.REPORT) {
                Thread w = new Thread(Session::watch, "VPMac freeze watch");
                w.setDaemon(true);
                w.start();
            }
        } catch (Throwable t) {
            System.out.println("[VPMac] WARNING: could not start session tracking: " + t);
        }
    }

    private static void previousEndedBadly(Properties prev, Path keep) {
        try {
            if (Config.REPORT) Report.previousSession(prev, keep);
        } catch (Throwable t) {
            System.out.println("[VPMac] WARNING: could not build the previous session report: " + t);
        }
    }

    static synchronized void write(String state) {
        try {
            Properties p = new Properties();
            p.setProperty("id", ID);
            p.setProperty("start", String.valueOf(START));
            p.setProperty("state", state);
            p.setProperty("phase", phase);
            p.setProperty("version", Agent.VERSION);
            p.setProperty("updated", String.valueOf(System.currentTimeMillis()));
            Path tmp = MARK.resolveSibling("session.tmp");
            try (var out = Files.newOutputStream(tmp)) { p.store(out, "VPMac: estado de la sesion (para detectar crashes)"); }
            Files.move(tmp, MARK, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            // sin marca no hay deteccion, pero el juego sigue
        }
    }

    /** FrameStream.frameStarted: un latido por cuadro de Viewpoint */
    public static void beat() {
        lastBeat = System.nanoTime();
        if (renderThread == null) renderThread = Thread.currentThread();
    }

    private static void watch() {
        boolean reported = false;
        Field enabled = null;
        java.lang.reflect.Method player = null;
        while (true) {
            try { Thread.sleep(2000); } catch (InterruptedException e) { return; }
            if (enabled == null) {
                try { enabled = Class.forName("viewpoint.core.View").getField("enabled"); } catch (Throwable t) { continue; }
            }
            boolean firstPerson, inWorld = false;
            try { firstPerson = enabled.getBoolean(null); } catch (Throwable t) { continue; }
            try {
                if (player == null) player = Class.forName("zombie.characters.IsoPlayer").getMethod("getInstance");
                inWorld = player.invoke(null) != null; // hay jugador = partida cargada (aunque sea en isometrico)
            } catch (Throwable t) {
                // sin el dato: se queda en lo que diga el latido
            }
            String now = firstPerson ? "first person" : inWorld || lastBeat != 0 ? "world (isometric)" : "menu";
            if (!now.equals(phase) && !(phase.equals("frozen") && reported)) { phase = now; write("running"); }
            if (!firstPerson || lastBeat == 0) { reported = false; continue; }
            long idle = System.nanoTime() - lastBeat;
            if (idle >= FREEZE_NANOS && !reported) {
                reported = true;
                Thread rt = renderThread;
                StackTraceElement[] stack = rt != null ? rt.getStackTrace() : new StackTraceElement[0];
                Log.warn("render has not advanced for " + idle / 1_000_000_000L + " s in first person: freeze report saved");
                Report.freeze(idle, stack, Thread.getAllStackTraces());
            } else if (idle < FREEZE_NANOS) {
                reported = false;
            }
        }
    }

}
