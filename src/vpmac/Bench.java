package vpmac;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;

/**
 * Benchmark automatico (Config.BENCH = segundos): sin tocar el teclado, entra a primera persona cuando el mundo ya
 * cargo (pide el "O" igual que la tecla, via FP.cycleAsked), mide BENCH segundos y deja el resultado en
 * console.txt ("[VPMac] BENCH ...") y en _vpmac/bench/<fecha>.txt junto con la configuracion usada.
 * Se llama desde FP.renderWorld (hilo del juego, una vez por cuadro). Usar con _cine (escena vp_mac_diag).
 */
public final class Bench {
    private static final int MAX = 1 << 16;
    private static final long[] frames = new long[MAX];
    private static int count;
    private static long firstTick, asked, start, last;
    private static boolean done;

    private Bench() {}

    /** FP.renderWorld: decide cuando entrar a primera persona y cuando cortar */
    public static void tick() {
        if (done) return;
        long now = System.nanoTime();
        if (firstTick == 0) firstTick = now;
        if (asked == 0 && now - firstTick >= Config.BENCH_DELAY * 1_000_000_000L) {
            asked = now;
            if (!viewEnabled()) askToggle();
            Log.info("BENCH: entering first person; measuring " + Config.BENCH + " s after 5 s to settle");
        }
        if (asked != 0 && start == 0 && now - asked >= 5_000_000_000L) {
            if (!viewEnabled()) { Log.warn("BENCH: Viewpoint did not enter first person; cancelled"); done = true; return; }
            start = now;
            last = now;
        }
    }

    /** FrameStream.frameStarted (hilo de render): junta tiempos de cuadro durante la medicion */
    public static void frame() {
        if (start == 0 || done) return;
        long now = System.nanoTime();
        if (count < MAX) frames[count++] = now - last;
        last = now;
        if (now - start >= Config.BENCH * 1_000_000_000L) finish(now - start);
    }

    private static void finish(long span) {
        done = true;
        long[] f = Arrays.copyOf(frames, count);
        Arrays.sort(f);
        String r = String.format(java.util.Locale.ROOT,
                "fps %.1f | mean frame %.1f ms, median %.1f ms, 1%% worst %.1f ms, 0.1%% worst %.1f ms (%d frames in %.0f s)",
                count * 1e9 / span, span / 1e6 / count, f[count / 2] / 1e6, f[Math.max(0, count - 1 - count / 100)] / 1e6,
                f[Math.max(0, count - 1 - count / 1000)] / 1e6, count, span / 1e9);
        Log.info("BENCH result: " + r + " | " + Config.describe());
        try {
            Path dir = Path.of(System.getProperty("user.home"), "Zomboid", "Workshop", "_vpmac", "bench");
            Files.createDirectories(dir);
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
            Files.writeString(dir.resolve(stamp + ".txt"), r + "\n" + Config.describe() + "\n" + Config.raw() + "\n");
        } catch (Exception e) {
            Log.warn("BENCH: could not save the result: " + e);
        }
    }

    private static boolean viewEnabled() {
        try {
            return Class.forName("viewpoint.core.View").getField("enabled").getBoolean(null);
        } catch (Throwable t) {
            return false;
        }
    }

    private static void askToggle() {
        try {
            Field f = Class.forName("viewpoint.FP").getDeclaredField("cycleAsked");
            f.setAccessible(true);
            f.setBoolean(null, true);
        } catch (Throwable t) {
            Log.warn("BENCH: could not request first person: " + t);
        }
    }
}
