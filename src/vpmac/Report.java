package vpmac;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Informes de error para que los jugadores los compartan (nada se envia solo):
 *  - de la sesion anterior que termino mal (Session): tipo (crash nativo, excepcion, congelamiento, cierre forzado),
 *    pilas resumidas de los crash reports de macOS, versiones, la Mac, configuracion y avisos de VPMac;
 *  - de un congelamiento en vivo (Session.watch): pila del hilo de render en ese momento.
 * Version corta (cabe en un comentario de Steam, 1000 caracteres) y completa. Texto en ingles: se pega en el
 * Workshop. Se anonimiza: rutas con el usuario -> ~, Steam IDs tapados. Ventana de macOS con botones para copiar.
 */
final class Report {
    static final String WHERE = "VPMac Workshop page > Discussions > Bug reports";
    private static final Path REPORTS = Log.DIR.resolve("reports");
    private static final Path DIAG = Path.of(System.getProperty("user.home"), "Library", "Logs", "DiagnosticReports");
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
    private static final Pattern STEAM_ID = Pattern.compile("7656119\\d{10}");
    private static final Pattern USER_PATH = Pattern.compile("/Users/[^/\\s]+");

    private Report() {}

    // ------------------------------------------------------------------ sesion anterior
    static void previousSession(Properties prev, Path keep) throws Exception {
        long start = Long.parseLong(prev.getProperty("start", "0"));
        String id = prev.getProperty("id", "?"), phase = prev.getProperty("phase", "?");
        List<String> console = lines(keep.resolve("console.txt"));
        List<String> vpLog = lines(keep.resolve("vpmac.log"));
        Path freeze = Log.DIR.resolve("freeze-" + id + ".txt");

        List<Crash> crashes = crashes(start);
        Crash game = null;
        int compiler = 0;
        for (Crash c : crashes) {
            if (c.process.startsWith("MTLCompilerService")) compiler++;
            else if (game == null) game = c;
        }
        String exception = javaException(console);

        String kind;
        if (game != null) kind = "native crash (" + game.process + ", " + game.exception + ")";
        else if (Files.exists(freeze)) kind = "freeze (render stopped; the game was closed afterwards)";
        else if (exception != null) kind = "Java exception, then the game closed";
        else kind = "closed unexpectedly (force quit, killed, or power loss)";

        String hint = hint(game, compiler, exception, Files.exists(freeze), crashes);

        StringBuilder full = new StringBuilder();
        full.append("=== VPMac report #").append(id).append(" ===\n");
        full.append("What: ").append(kind).append('\n');
        if (hint != null) full.append("Known issue: ").append(hint).append('\n');
        full.append("When: ").append(WHEN.format(Instant.ofEpochMilli(start))).append(", last phase: ").append(phase).append('\n');
        full.append(versions(console, vpLog)).append('\n');
        full.append("Mac: ").append(machine()).append('\n');
        full.append("VPMac config: ").append(first(vpLog, "config: ", " | ", null)).append('\n');
        if (compiler > 0) full.append("Metal shader compiler crashes in that session: ").append(compiler).append('\n');
        if (game != null) full.append("\n--- crash (").append(game.file).append(")\n").append(game.frames);
        for (Crash c : crashes) {
            if (c.process.startsWith("MTLCompilerService")) {
                full.append("\n--- Metal compiler crash (").append(c.file).append(")\n").append(c.frames);
                break;
            }
        }
        if (Files.exists(freeze)) {
            full.append("\n--- freeze\n").append(Files.readString(freeze));
            Files.deleteIfExists(freeze);
        }
        if (exception != null) full.append("\n--- Java exception\n").append(exception);
        List<String> warns = new ArrayList<>();
        for (String l : vpLog) if (l.contains("WARNING") || l.contains("AVISO")) warns.add(l);
        if (!warns.isEmpty()) {
            full.append("\n--- VPMac warnings\n");
            for (String w : warns.subList(Math.max(0, warns.size() - 8), warns.size())) full.append(w).append('\n');
        }
        String stats = null;
        for (String l : console) if (l.contains("[Viewpoint] ") && l.contains(" fps | gpu ms")) stats = l;
        if (stats != null) {
            full.append("\n--- last Viewpoint stats\n").append(stats.length() > 2500 ? stats.substring(0, 2500) + " [...]" : stats).append('\n');
        }
        full.append("\n--- last console lines\n");
        List<String> tail = new ArrayList<>();
        for (String l : console) if (!noise(l)) tail.add(l);
        for (String l : tail.subList(Math.max(0, tail.size() - 25), tail.size())) {
            full.append(l.length() > 300 ? l.substring(0, 300) + " [...]" : l).append('\n'); // Viewpoint escribe lineas de KB
        }

        StringBuilder small = new StringBuilder();
        small.append("[VPMac #").append(id).append("] ").append(kind).append('\n');
        if (hint != null) small.append("Known: ").append(hint.length() > 160 ? hint.substring(0, 160) + "..." : hint).append('\n');
        small.append(versions(console, vpLog)).append('\n');
        small.append("Mac: ").append(machine()).append(" | phase: ").append(phase).append('\n');
        if (compiler > 0) small.append("Metal compiler crashes: ").append(compiler).append('\n');
        if (game != null) small.append("Top: ").append(game.top).append('\n');
        else if (exception != null) small.append("Ex: ").append(exception.lines().findFirst().orElse("")).append('\n');
        if (!warns.isEmpty()) small.append("Last warning: ").append(warns.get(warns.size() - 1).replaceFirst(".*(WARNING|AVISO): ", "")).append('\n');

        save(id, full.toString(), small.toString(), kind);
    }

    /**
     * Firmas ya entendidas (ver _vpmac/README.md seccion 3): ayudan al mantenedor a clasificar informes al tiro.
     * Agregar aqui cada causa nueva que se descubra.
     */
    private static String hint(Crash game, int compilerCrashes, String exception, boolean froze, List<Crash> all) {
        String frames = game != null ? game.frames : "";
        boolean mte = false;
        for (Crash c : all) mte |= c.process.startsWith("MTLCompilerService") && c.frames.contains("DominatorTreeBase");
        if (compilerCrashes > 0 && (frames.contains("gleFallbackBegin") || frames.contains("gleSwitchPlugin") || game == null))
            return "Apple Metal shader compiler crashed" + (mte ? " (MTE use-after-free in LLVM eraseNode, macOS 26 + M5)" : "")
                    + " and the OpenGL driver died falling back. VPMac's GLSL rules avoid the shaders we know; this is a new one:"
                    + " please include your Viewpoint graphics settings.";
        if (frames.contains("jni_FatalError") && frames.contains("liblwjgl"))
            return "an OpenGL function missing on Mac (GL 4.2+) was called: Viewpoint probably updated and VPMac needs a new rule.";
        if (froze) return "render froze in first person (stack above). If MTLCompilerService was at 100% CPU, it is a Metal shader compiler hang.";
        if (exception != null && exception.contains("vpmac")) return "exception inside VPMac itself (stack above).";
        return null;
    }

    // ------------------------------------------------------------------ congelamiento en vivo
    static void freeze(long idleNanos, StackTraceElement[] render, Map<Thread, StackTraceElement[]> all) {
        try {
            StringBuilder b = new StringBuilder();
            b.append("Render thread stuck for ").append(idleNanos / 1_000_000_000L).append(" s in first person. Render thread stack:\n");
            for (int i = 0; i < Math.min(render.length, 25); i++) b.append("  at ").append(render[i]).append('\n');
            for (Map.Entry<Thread, StackTraceElement[]> e : all.entrySet()) {
                if (!e.getKey().getName().equals("MainThread")) continue;
                b.append("Game thread (MainThread):\n");
                StackTraceElement[] s = e.getValue();
                for (int i = 0; i < Math.min(s.length, 12); i++) b.append("  at ").append(s[i]).append('\n');
            }
            Files.writeString(Log.DIR.resolve("freeze-" + Session.ID + ".txt"), clean(b.toString()));
            Session.phase = "frozen";
            Session.write("running");
        } catch (Exception e) {
            // nada: es un informe de apoyo
        }
    }

    // ------------------------------------------------------------------ piezas
    private record Crash(String file, String process, String exception, String top, String frames) {}

    /** crash reports de macOS (juego y compilador de Metal) escritos despues de `since` */
    private static List<Crash> crashes(long since) {
        List<Crash> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(DIAG)) {
            s.filter(p -> {
                String n = p.getFileName().toString();
                return (n.startsWith("JavaAppLauncher") || n.startsWith("java-") || n.startsWith("MTLCompilerService")) && n.endsWith(".ips");
            }).filter(p -> stampOf(p) >= since).sorted().forEach(p -> {
                Crash c = parse(p);
                if (c != null) out.add(c);
            });
        } catch (Exception e) {
            // sin acceso a los reportes: el informe sigue sin ellos
        }
        return out;
    }

    /**
     * hora del crash segun el NOMBRE (Proceso-AAAA-MM-DD-HHMMSS[.n].ips): macOS re-toca algunos .ips viejos y su
     * fecha de modificacion engana
     */
    private static long stampOf(Path p) {
        Matcher m = Pattern.compile("-(\\d{4})-(\\d{2})-(\\d{2})-(\\d{2})(\\d{2})(\\d{2})").matcher(p.getFileName().toString());
        if (!m.find()) return 0;
        try {
            return java.time.LocalDateTime.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)),
                    Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)), Integer.parseInt(m.group(6)))
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (Exception e) {
            return 0;
        }
    }

    /** lo justo de un .ips (2 lineas JSON): proceso, excepcion y las primeras funciones del hilo que cayo */
    private static Crash parse(Path p) {
        try {
            String raw = Files.readString(p);
            int nl = raw.indexOf('\n');
            String body = nl > 0 ? raw.substring(nl + 1) : raw;
            String process = p.getFileName().toString().replaceFirst("-\\d{4}-.*", "");
            String type = find(body, "\"type\"\\s*:\\s*\"([^\"]+)\""), sub = find(body, "\"subtype\"\\s*:\\s*\"([^\"]+)\""),
                    signal = find(body, "\"signal\"\\s*:\\s*\"([^\"]+)\"");
            String exception = (type != null ? type : "?") + (signal != null ? "/" + signal : "")
                    + (sub != null ? " " + sub.replaceAll(" at 0x[0-9a-f]+", "") : "");
            // hilo que cayo: el primero con "triggered":true; sus "symbol" en orden
            int trig = body.indexOf("\"triggered\":true");
            if (trig < 0) trig = body.indexOf("\"triggered\" : true");
            StringBuilder frames = new StringBuilder();
            String top = null;
            if (trig >= 0) {
                int framesAt = body.lastIndexOf("\"frames\"", trig);
                if (framesAt < 0) framesAt = body.indexOf("\"frames\"", trig);
                Matcher m = Pattern.compile("\"symbol\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
                if (framesAt >= 0) m.region(framesAt, Math.min(body.length(), framesAt + 20000));
                int n = 0;
                while (m.find() && n < 14) {
                    String sym = m.group(1);
                    if (top == null && !sym.startsWith("__pthread") && !sym.equals("pthread_kill") && !sym.equals("abort")
                            && !sym.startsWith("os::") && !sym.startsWith("VMError") && !sym.startsWith("JVM_handle")
                            && !sym.equals("_sigtramp")) top = sym;
                    frames.append("  ").append(sym.length() > 140 ? sym.substring(0, 140) : sym).append('\n');
                    n++;
                }
            }
            return new Crash(p.getFileName().toString(), process, exception, top != null ? top : "?", frames.toString());
        } catch (Exception e) {
            return null;
        }
    }

    /** primer bloque de excepcion de Java que toque a Viewpoint, VPMac o las imitaciones */
    private static String javaException(List<String> console) {
        for (int i = 0; i < console.size(); i++) {
            String l = console.get(i);
            if (!(l.contains("Exception") || l.contains("Error:")) || l.contains("CellLoader")) continue;
            StringBuilder b = new StringBuilder(l.trim()).append('\n');
            boolean ours = l.contains("viewpoint") || l.contains("vpmac");
            for (int j = i + 1; j < Math.min(console.size(), i + 30) && console.get(j).trim().startsWith("at "); j++) {
                String s = console.get(j).trim();
                ours |= s.contains("viewpoint") || s.contains("vpmac");
                if (j - i <= 15) b.append("  ").append(s).append('\n');
            }
            if (ours) return b.toString();
        }
        return null;
    }

    private static String versions(List<String> console, List<String> vpLog) {
        String vp = first(vpLog, "Viewpoint version: ", null, null);
        String pz = first(console, "game build: ", ",", null);
        String zb = first(console, "getVersion() = ", null, null);
        return "VPMac " + Agent.VERSION + " | Viewpoint " + (vp != null ? vp : "?") + " | PZ " + (pz != null ? pz.trim() : "?")
                + " | ZombieBuddy " + (zb != null ? zb.trim() : "?");
    }

    /** texto despues de `after` (hasta `until`) en la primera linea que lo tenga (y `must`, si se da) */
    private static String first(List<String> lines, String after, String until, String must) {
        for (String l : lines) {
            if (must != null && !l.contains(must)) continue;
            int i = l.indexOf(after);
            if (i < 0) continue;
            String rest = l.substring(i + after.length());
            if (until != null && rest.contains(until)) rest = rest.substring(0, rest.indexOf(until));
            return rest.trim();
        }
        return null;
    }

    static String machine() {
        return sysctl("machdep.cpu.brand_string") + " (" + sysctl("hw.model") + "), "
                + (parseLong(sysctl("hw.memsize")) >> 30) + " GB, macOS " + System.getProperty("os.version");
    }

    private static void save(String id, String full, String small, String kind) throws Exception {
        full = clean(full);
        small = clean(small);
        if (small.length() > 990) small = small.substring(0, 987) + "...";
        Files.createDirectories(REPORTS);
        Path file = REPORTS.resolve("report-" + id + ".txt");
        Files.writeString(file, full + "\n\n=== short version (for a Steam comment) ===\n" + small, StandardCharsets.UTF_8);
        Files.writeString(Log.DIR.resolve("last-report.txt"), Files.readString(file), StandardCharsets.UTF_8);
        System.out.println("[VPMac] the previous session ended badly (" + kind + "); report to share: " + file);
        if (Config.REPORT_POPUP) popup(kind, full, small, file);
    }

    /** ventana de macOS (osascript) en otro hilo, sin frenar el arranque: copiar corto / completo / cerrar */
    private static void popup(String kind, String full, String small, Path file) {
        Thread t = new Thread(() -> {
            try {
                String msg = "Project Viewpoint (VPMac) noticed that your last session ended badly:\n" + kind
                        + "\n\nA report is ready to share (nothing is sent automatically). Copy it and paste it in:\n" + WHERE
                        + "\n\nShort = fits a Steam comment. Full = for a discussion post.\nSaved in: ~/Zomboid/vpmac/reports/";
                String choice = osascript("display dialog " + quote(msg) + " with title \"VPMac crash report\" buttons "
                        + "{\"Close\", \"Copy full report\", \"Copy short report\"} default button \"Copy short report\" with icon caution");
                if (choice.contains("Copy short")) pbcopy(small);
                else if (choice.contains("Copy full")) pbcopy(full);
            } catch (Exception e) {
                // sin ventana: el informe ya quedo en disco y en console.txt
            }
        }, "VPMac report dialog");
        t.setDaemon(true);
        t.start();
    }

    private static String osascript(String script) throws Exception {
        Process p = new ProcessBuilder("/usr/bin/osascript", "-e", script).redirectErrorStream(true).start();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            StringBuilder b = new StringBuilder();
            for (String l; (l = r.readLine()) != null; ) b.append(l);
            p.waitFor();
            return b.toString();
        }
    }

    private static void pbcopy(String text) throws Exception {
        Process p = new ProcessBuilder("/usr/bin/pbcopy").start();
        p.getOutputStream().write(text.getBytes(StandardCharsets.UTF_8));
        p.getOutputStream().close();
        p.waitFor();
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** anonimiza: rutas con el usuario y Steam IDs */
    static String clean(String s) {
        s = s.replace(System.getProperty("user.home"), "~");
        s = USER_PATH.matcher(s).replaceAll("~");
        return STEAM_ID.matcher(s).replaceAll("7656119**********");
    }

    /** lineas conocidas que no dicen nada del problema (para que la cola del informe muestre lo util) */
    private static boolean noise(String l) {
        return l.contains("has no uniform uTextures[") // MODEL_TEXTURES 16->6 de xiveboy: inofensivo
                || l.contains("CellLoader> missing tile") || l.contains("Cargando pack de texturas") || l.contains("texturepack: loading")
                || l.contains("[VPMac] fps ") || l.contains("[Viewpoint] slow frame");
    }

    private static List<String> lines(Path p) {
        try {
            return Files.readAllLines(p, StandardCharsets.ISO_8859_1);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private static String find(String s, String regex) {
        Matcher m = Pattern.compile(regex).matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private static long parseLong(String s) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return 0; }
    }

    private static String sysctl(String key) {
        try {
            Process p = new ProcessBuilder("/usr/sbin/sysctl", "-n", key).redirectErrorStream(true).start();
            try (BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line = in.readLine();
                return line == null ? "?" : line.trim();
            }
        } catch (Exception e) {
            return "?";
        }
    }
}
