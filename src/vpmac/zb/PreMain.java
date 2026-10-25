package vpmac.zb;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

/**
 * VPMac como mod de ZombieBuddy con PRELOAD (Opcion A: el jugador solo se suscribe en el Workshop).
 * ZombieBuddy llama a este premain en SU premain (antes del juego) desde el segundo arranque, cuando el jugador
 * aprobo el mod como preload y la firma .zbs es valida. Aqui se arranca lo que antes hacian los -javaagent:
 *  1. el puente OpenGL 4.1 de xiveboy (pzmac41.Agent) y sus imitaciones viewpoint/mac41/*;
 *  2. VPMac (vpmac.Agent), que reescribe Viewpoint al cargarse.
 *
 * VPMac.jar NO trae el puente ni las imitaciones (el puente deriva de codigo sin licencia de redistribucion, ver
 * CREDITS): se toman de la copia LOCAL del jugador del item de xiveboy (Workshop 3812168749), que es requisito.
 * De su Viewpoint.jar parchado se extrae solo viewpoint/mac41/* a ~/Zomboid/vpmac/cache/ (agregar el jar entero
 * pondria sus clases viejas de Viewpoint 0.1.6 por delante del Viewpoint real).
 * La Instrumentation se toma de ZombieBuddy (Loader.g_instrumentation; no hay API publica todavia).
 * Si ya corren como -javaagent (instalacion vieja con install.sh), no se arranca nada dos veces.
 */
public final class PreMain {
    static final String XIVEBOY_ITEM = "3812168749";
    private static final String PAYLOAD = "mods/ViewpointMac41Patch/common/tools/payload";

    private PreMain() {}

    public static void premain(String[] args) {
        List<String> jvm = ManagementFactory.getRuntimeMXBean().getInputArguments();
        boolean bridgeAgent = jvm.stream().anyMatch(a -> a.startsWith("-javaagent:") && a.contains("pz-mac41-bridge"));
        boolean vpmacAgent = jvm.stream().anyMatch(a -> a.startsWith("-javaagent:") && a.contains("vpmac-agent"));
        if (vpmacAgent || Boolean.getBoolean("vpmac.active")) {
            System.out.println("[VPMac] already running as -javaagent (old install); ZombieBuddy preload not needed");
            return;
        }
        Instrumentation inst = zombieBuddyInstrumentation();
        if (inst == null) {
            System.out.println("[VPMac] WARNING: could not get ZombieBuddy's Instrumentation; VPMac is NOT active");
            return;
        }
        try {
            Path payload = xiveboyPayload();
            // 1) puente (si no viene en el classpath: -javaagent o build privada con todo adentro)
            if (!bridgeAgent && ClassLoader.getSystemResource("pzmac41/Agent.class") == null) {
                Path bridge = payload == null ? null : payload.resolve("pz-mac41-bridge.jar");
                if (bridge == null || !Files.exists(bridge)) {
                    missing("the Mac OpenGL bridge (pz-mac41-bridge.jar)");
                    return;
                }
                inst.appendToSystemClassLoaderSearch(new JarFile(bridge.toFile()));
            }
            // 2) imitaciones viewpoint/mac41 (extraidas una vez a un cache local)
            if (ClassLoader.getSystemResource("viewpoint/mac41/Mac41.class") == null) {
                Path patched = payload == null ? null : payload.resolve("Viewpoint.jar");
                if (patched == null || !Files.exists(patched)) {
                    missing("the OpenGL 4.1 shims (viewpoint/mac41 in xiveboy's Viewpoint.jar)");
                    return;
                }
                inst.appendToSystemClassLoaderSearch(new JarFile(shimsJar(patched).toFile()));
            }
            if (!bridgeAgent) {
                Class.forName("pzmac41.Agent").getMethod("premain", String.class, Instrumentation.class).invoke(null, "", inst);
                System.out.println("[VPMac] Mac OpenGL 4.1 bridge (xiveboy) started through ZombieBuddy preload");
            }
            Class.forName("vpmac.Agent").getMethod("premain", String.class, Instrumentation.class).invoke(null, "", inst);
        } catch (Throwable t) {
            System.out.println("[VPMac] WARNING: could not start through ZombieBuddy preload: " + t);
            t.printStackTrace(System.out);
        }
    }

    private static void missing(String what) {
        Notice.show("VPMac is not active", "VPMac could not find " + what + ".\n\n"
                + "Subscribe to \"Project Viewpoint - Patch for Mac\" by xiveboy on the Workshop (just subscribe: do NOT run "
                + "its installer), then restart the game.\n\n"
                + "Until then, Viewpoint will crash when you enter a world.");
    }

    /** carpeta payload del item de xiveboy, buscada al lado de este mod en workshop/content/108600/ */
    static Path xiveboyPayload() {
        try {
            Path self = Path.of(PreMain.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            for (Path p = self; p != null; p = p.getParent()) {
                if (p.getFileName() != null && p.getFileName().toString().equals("108600")) {
                    Path candidate = p.resolve(XIVEBOY_ITEM).resolve(PAYLOAD);
                    if (Files.isDirectory(candidate)) return candidate;
                }
            }
        } catch (Exception e) {
            // mod local (no del Workshop): se prueba la biblioteca de Steam por defecto
        }
        Path fallback = Path.of(System.getProperty("user.home"), "Library", "Application Support", "Steam", "steamapps",
                "workshop", "content", "108600", XIVEBOY_ITEM).resolve(PAYLOAD);
        return Files.isDirectory(fallback) ? fallback : null;
    }

    /** jar con SOLO viewpoint/mac41/* del Viewpoint.jar de xiveboy, en ~/Zomboid/vpmac/cache/ (uno por version) */
    static Path shimsJar(Path patched) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(patched));
        Path cache = Path.of(System.getProperty("user.home"), "Zomboid", "vpmac", "cache");
        Files.createDirectories(cache);
        Path out = cache.resolve("mac41-shims-" + HexFormat.of().formatHex(digest, 0, 6) + ".jar");
        if (Files.exists(out)) return out;
        Path tmp = cache.resolve(out.getFileName() + ".tmp");
        try (JarFile in = new JarFile(patched.toFile()); OutputStream fo = Files.newOutputStream(tmp);
             JarOutputStream jo = new JarOutputStream(fo)) {
            for (Enumeration<JarEntry> e = in.entries(); e.hasMoreElements(); ) {
                JarEntry entry = e.nextElement();
                if (entry.isDirectory() || !entry.getName().startsWith("viewpoint/mac41/")) continue;
                jo.putNextEntry(new JarEntry(entry.getName()));
                try (InputStream is = in.getInputStream(entry)) { is.transferTo(jo); }
                jo.closeEntry();
            }
        }
        Files.move(tmp, out);
        return out;
    }

    static Instrumentation zombieBuddyInstrumentation() {
        try {
            Field f = Class.forName("me.zed_0xff.zombie_buddy.Loader").getDeclaredField("g_instrumentation");
            f.setAccessible(true);
            return (Instrumentation) f.get(null);
        } catch (Throwable t) {
            return null;
        }
    }
}
