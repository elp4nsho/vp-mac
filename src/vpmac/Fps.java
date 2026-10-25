package vpmac;

import java.util.Arrays;

/**
 * Medidor de cuadros (Config.FPS): se llama al empezar cada cuadro de Viewpoint (FrameStream.frameStarted) y
 * cada 10 s escribe FPS, tiempo medio y el 1% peor. Sin objetos por cuadro: un arreglo circular de longs.
 */
public final class Fps {
    private static final long WINDOW = 10_000_000_000L;
    private static final long[] frames = new long[8192];
    private static int count;
    private static long last, windowStart;

    private Fps() {}

    public static void frame() {
        long now = System.nanoTime();
        if (last != 0 && count < frames.length) frames[count++] = now - last;
        last = now;
        if (windowStart == 0) windowStart = now;
        if (now - windowStart >= WINDOW && count > 0) {
            report(now - windowStart);
            count = 0;
            windowStart = now;
        }
    }

    private static void report(long span) {
        long[] f = Arrays.copyOf(frames, count);
        Arrays.sort(f);
        double avg = span / 1e6 / count;
        double p50 = f[count / 2] / 1e6;
        double worst1 = f[Math.max(0, count - 1 - count / 100)] / 1e6;
        Log.info(String.format(java.util.Locale.ROOT, "fps %.1f | mean frame %.1f ms, median %.1f ms, 1%% worst %.1f ms (%d frames)",
                count * 1e9 / span, avg, p50, worst1, count));
    }
}
