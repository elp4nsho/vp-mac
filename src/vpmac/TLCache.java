package vpmac;

/**
 * Cache de ThreadLocal para el hilo de render (Config.TL_CACHE). Las imitaciones de xiveboy buscan su estado en
 * un ThreadLocal en CADA llamada (varias por dibujo); con ~10 ThreadLocal distintos el JDK cae seguido en el camino
 * lento (getEntryAfterMiss). Aqui el hilo que tiene el contexto OpenGL guarda una tablita (ThreadLocal -> valor)
 * que solo el toca; cualquier otro hilo va directo al ThreadLocal de siempre. set/remove la mantienen al dia.
 * Transformer cambia ThreadLocal.get/set/remove por estos metodos dentro de viewpoint/mac41/*.
 */
public final class TLCache {
    private static final int MAX = 32;
    private static final ThreadLocal<?>[] keys = new ThreadLocal<?>[MAX];
    private static final Object[] values = new Object[MAX];
    private static int n;
    private static volatile Thread owner;

    private TLCache() {}

    public static Object get(ThreadLocal<?> tl) {
        Thread t = Thread.currentThread();
        if (t != owner && !claim(t)) return tl.get();
        for (int i = 0; i < n; i++) if (keys[i] == tl) return values[i];
        Object v = tl.get();
        if (n < MAX) { keys[n] = tl; values[n++] = v; }
        return v;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void set(ThreadLocal tl, Object v) {
        tl.set(v);
        if (Thread.currentThread() != owner) return;
        for (int i = 0; i < n; i++) if (keys[i] == tl) { values[i] = v; return; }
        if (n < MAX) { keys[n] = tl; values[n++] = v; }
    }

    public static void remove(ThreadLocal<?> tl) {
        tl.remove();
        if (Thread.currentThread() != owner) return;
        for (int i = 0; i < n; i++) {
            if (keys[i] == tl) {
                n--;
                keys[i] = keys[n]; values[i] = values[n];
                keys[n] = null; values[n] = null;
                return;
            }
        }
    }

    /** el primer hilo con contexto OpenGL actual se queda con el cache (el de render) */
    private static synchronized boolean claim(Thread t) {
        if (owner != null) return owner == t;
        try {
            org.lwjgl.opengl.GL.getCapabilities(); // lanza si este hilo no tiene contexto
        } catch (Throwable e) {
            return false;
        }
        owner = t;
        return true;
    }
}
