package vpmac.zb;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;

/** Prueba offline del camino de los jugadores: VPMac.jar encuentra el item de xiveboy, extrae las imitaciones y
 *  puente + imitaciones + VPMac cargan y enlazan juntos (sin ningun jar de la carpeta del juego). */
public class PayloadTest {
    public static void main(String[] a) throws Exception {
        Path payload = PreMain.xiveboyPayload();
        System.out.println("jar de VPMac: " + PreMain.class.getProtectionDomain().getCodeSource().getLocation());
        System.out.println("payload de xiveboy: " + payload);
        if (payload == null) { System.out.println("FALLA: no encontro el item de xiveboy"); System.exit(1); }
        Path bridge = payload.resolve("pz-mac41-bridge.jar"), patched = payload.resolve("Viewpoint.jar");
        System.out.println("puente existe: " + Files.exists(bridge) + " | Viewpoint.jar de xiveboy existe: " + Files.exists(patched));
        Path shims = PreMain.shimsJar(patched);
        int n = 0, otros = 0;
        try (JarFile j = new JarFile(shims.toFile())) {
            for (var e = j.entries(); e.hasMoreElements(); ) {
                String name = e.nextElement().getName();
                if (name.startsWith("viewpoint/mac41/")) n++; else otros++;
            }
        }
        System.out.println("imitaciones extraidas: " + n + " clases, otros archivos: " + otros + " -> " + shims);
        Path again = PreMain.shimsJar(patched);
        System.out.println("segunda vez usa el cache: " + again.equals(shims));
        URL self = PreMain.class.getProtectionDomain().getCodeSource().getLocation();
        try (URLClassLoader cl = new URLClassLoader(new URL[] {bridge.toUri().toURL(), shims.toUri().toURL(), self, Path.of(System.getProperty("game.jar")).toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            for (String c : new String[] {"pzmac41.Agent", "viewpoint.mac41.Mac41", "viewpoint.mac41.Draws41", "viewpoint.mac41.Pages41",
                    "vpmac.Agent", "vpmac.Transformer", "vpmac.FastUnits41", "vpmac.Views41", "vpmac.Glsl", "org.objectweb.asm.tree.ClassNode"}) {
                Class<?> k = Class.forName(c, false, cl);
                k.getDeclaredMethods(); // fuerza la resolucion de las firmas
                System.out.println("  carga OK: " + c + "  (desde " + k.getProtectionDomain().getCodeSource().getLocation().getPath().replaceAll(".*/", "") + ")");
            }
            System.out.println("recurso pages-fast.glsl en VPMac.jar: " + (cl.getResource("vpmac/pages-fast.glsl") != null));
            System.out.println("recurso redirects.txt en VPMac.jar: " + (cl.getResource("vpmac/redirects.txt") != null));
        }
        System.out.println("OK");
    }
}
