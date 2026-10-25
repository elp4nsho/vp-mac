package vpmac;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Tabla de reemplazos "funcion de OpenGL -> imitacion en 4.1".
 *
 * redirects.txt sale del Viewpoint parcheado de xiveboy (0.1.6): cada llamada que el redirigio, por nombre y
 * firma. Se aplica en TODAS las clases de Viewpoint menos las Iris* (shaderpacks externos, que el parche
 * bloquea). Encima van los reemplazos propios de VPMac (OVERRIDES), que ganan.
 */
final class Rules {
    static final String GL_PREFIX = "org/lwjgl/opengl/GL";
    static final String M41 = "viewpoint/mac41/";
    static final String VIEWS = "vpmac/Views41";

    /** "nombre desc" -> clase dueña de la imitacion */
    private static final Map<String, String> REDIRECTS = new HashMap<>();

    private Rules() {}

    static void load() {
        try (InputStream in = Rules.class.getResourceAsStream("redirects.txt")) {
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line; (line = r.readLine()) != null; ) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] p = line.split("\\s+");
                REDIRECTS.put(p[0] + " " + p[1], p[2]);
            }
        } catch (Exception e) {
            throw new IllegalStateException("[VPMac] could not read redirects.txt", e);
        }
        // glDrawArrays: en todo Viewpoint va a Draws41 (Gl.screenTriangle tiene su regla propia en Transformer)
        REDIRECTS.put("glDrawArrays (III)V", M41 + "Draws41");
        // Propios de VPMac: vistas de textura de las cascadas del sol (0.1.6.1). Delegan en las de xiveboy.
        REDIRECTS.put("glTextureView (IIIIIIII)V", VIEWS);
        REDIRECTS.put("glBindFramebuffer (II)V", VIEWS);
        REDIRECTS.put("glFramebufferTexture2D (IIIII)V", VIEWS);
        REDIRECTS.put("glBindTexture (II)V", VIEWS);
        REDIRECTS.put("glCopyImageSubData (IIIIIIIIIIIIIII)V", VIEWS);
        REDIRECTS.put("glDeleteTextures (I)V", VIEWS);
        REDIRECTS.put("glDeleteTextures ([I)V", VIEWS);
        REDIRECTS.put("glDeleteTextures (Ljava/nio/IntBuffer;)V", VIEWS);
        // Diagnostico (solo con diag=true): que programa (y que shaders volcados) se usa. Delegan en Pages41/Ranges41.
        if (Config.DIAG) {
            REDIRECTS.put("glAttachShader (II)V", "vpmac/Diag");
            REDIRECTS.put("glUseProgram (I)V", "vpmac/Diag");
        }
    }

    static int redirectCount() {
        return REDIRECTS.size();
    }

    /** dueño de la imitacion para una llamada a org/lwjgl/opengl/GL*, o null si se deja tal cual */
    static String redirect(String name, String desc) {
        return REDIRECTS.get(name + " " + desc);
    }

    /** Clases donde NO se aplica el reemplazo general. */
    static boolean skipGeneral(String cls) {
        return cls.startsWith(M41) || cls.startsWith("viewpoint/render/Iris");
    }
}
