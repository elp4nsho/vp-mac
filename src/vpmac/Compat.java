package vpmac;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL20C;

/**
 * Informe de compatibilidad al arrancar el renderer de Viewpoint (Gl.init, hilo de render, contexto ya creado):
 * chip, RAM, macOS, GPU y limites de OpenGL, comparados con lo PROBADO (Air M5 16 GB, macOS 26.5.1). No bloquea
 * nada: avisa en console.txt y deja ~/Zomboid/vpmac-compat.txt. Los limites que usa VPMac salen de aqui, no fijos.
 */
public final class Compat {
    static final String TESTED = "Apple M5, 16 GB, macOS 26.5.1, OpenGL 4.1 Metal";
    private static int fragmentUnits = 16;
    private static boolean done;

    private Compat() {}

    /** unidades de textura del fragment shader (GL_MAX_TEXTURE_IMAGE_UNITS) de ESTA Mac */
    static int fragmentUnits() {
        return fragmentUnits;
    }

    public static void check() {
        if (done) return;
        done = true;
        try {
            String chip = sysctl("machdep.cpu.brand_string"), model = sysctl("hw.model");
            long ramGb = Long.parseLong(sysctl("hw.memsize").trim()) >> 30;
            String macos = System.getProperty("os.version");
            String renderer = GL11C.glGetString(GL11C.GL_RENDERER), version = GL11C.glGetString(GL11C.GL_VERSION);
            fragmentUnits = GL11C.glGetInteger(GL20C.GL_MAX_TEXTURE_IMAGE_UNITS);
            int combined = GL11C.glGetInteger(GL20C.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS);
            int vertexUnits = GL11C.glGetInteger(GL20C.GL_MAX_VERTEX_TEXTURE_IMAGE_UNITS);
            int tbo = GL11C.glGetInteger(0x8C2B); // GL_MAX_TEXTURE_BUFFER_SIZE
            int attribs = GL11C.glGetInteger(GL20C.GL_MAX_VERTEX_ATTRIBS);

            StringBuilder r = new StringBuilder();
            r.append("chip=").append(chip).append(" (").append(model).append(") ram=").append(ramGb).append("GB macOS=")
                    .append(macos).append(" gpu=").append(renderer).append(" | ").append(version)
                    .append(" | texture units frag/vert/total=").append(fragmentUnits).append('/').append(vertexUnits).append('/')
                    .append(combined).append(" tbo=").append(tbo).append(" attribs=").append(attribs);
            Log.info("compatibility: " + r);

            StringBuilder w = new StringBuilder();
            if (!chip.startsWith("Apple M")) w.append("\n - not an Apple Silicon M chip: untested");
            else if (!chip.startsWith("Apple M5")) w.append("\n - different chip than tested: its Metal shader compiler is ")
                    .append("different (each M family has its own), and the bug we found showed up through the M5's MTE. ")
                    .append("Please run _vpmac/compat-test.sh once on this Mac.");
            if (renderer == null || !renderer.startsWith("Apple")) w.append("\n - not an Apple GPU: untested");
            if (version == null || !version.contains("Metal")) w.append("\n - OpenGL is not running over Metal: untested");
            if (fragmentUnits < 16 || combined < 80 || vertexUnits < 16) w.append("\n - texture limits below what ")
                    .append("xiveboy's shims need (16/16/80): Viewpoint may fail");
            if (ramGb < 16) w.append("\n - ").append(ramGb).append(" GB of RAM: Viewpoint reserves a lot (mesh arena ")
                    .append("~2 GB, memory.* in viewpoint-live.properties). If it stutters or closes, lower memory.nearMeshMiB, ")
                    .append("memory.reliefMiB and memory.shellMiB (e.g. by half) and keep -Xmx in Info.plist at 3 GB or less.");
            if (!macos.startsWith("26.")) w.append("\n - macOS ").append(macos).append(": Apple's OpenGL driver changes ")
                    .append("between versions; tested on 26.5.1");
            if (w.length() > 0) Log.warn("this Mac differs from the tested one (" + TESTED + "):" + w);

            Files.writeString(Path.of(System.getProperty("user.home"), "Zomboid", "vpmac-compat.txt"),
                    "VPMac " + Agent.VERSION + "\n" + r + "\ntested on: " + TESTED + (w.length() > 0 ? "\nwarnings:" + w : "\nno warnings") + "\n");
        } catch (Throwable t) {
            Log.warn("could not build the compatibility report: " + t);
        }
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
