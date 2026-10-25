package vpmac;

import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.lwjgl.opengl.GL20;

/**
 * Ultimo paso antes de que el GLSL llegue a OpenGL (despues de las imitaciones de xiveboy):
 *  1. reglas de reescritura para el compilador de Metal del Mac (cada una con nombre, avisa una vez al aplicarse);
 *  2. con dump=true (vpmac.properties) guarda el GLSL final, un archivo por fuente distinta (para _macgl/lab).
 */
public final class Glsl {
    private static final Set<String> told = new HashSet<>();

    // Lanczos 4x4 (lib/lanczos.glsl y lanczosFloor): recalcula lanczos2() -> sin() 20 veces por filtro y con los dos
    // filtros en el mismo shader (gbuffer) el compilador de Metal no termina nunca. Se precalculan los 8 pesos una
    // vez: mismo resultado, y compila (~5 s la primera vez).
    private static final Pattern LANCZOS_LOOP = Pattern.compile(
            "  float total = 0\\.0;\\n  for \\(int j = 0; j < 4; j\\+\\+\\) \\{\\n    float wy = lanczos2\\(float\\(j - 1\\) - f\\.y\\);");
    private static final String LANCZOS_HOISTED = "  float total = 0.0;\n"
            + "  vec4 wxs = vec4(lanczos2(-1.0 - f.x), lanczos2(-f.x), lanczos2(1.0 - f.x), lanczos2(2.0 - f.x));\n"
            + "  vec4 wys = vec4(lanczos2(-1.0 - f.y), lanczos2(-f.y), lanczos2(1.0 - f.y), lanczos2(2.0 - f.y));\n"
            + "  for (int j = 0; j < 4; j++) {\n    float wy = wys[j];";
    private static final String LANCZOS_INNER = "float w = lanczos2(float(i - 1) - f.x) * wy;";

    // Lanczos del piso: con los dos Lanczos (sprites + piso) en el mismo shader, el compilador de Metal de Apple
    // (macOS 26, M5) se cae ~50% de las veces (use-after-free en LLVM que MTE corta) y el driver muere al caer a su
    // modo de respaldo. Con uno solo: 0 caidas en 12. En el Mac el piso usa bilineal si se elige Lanczos.
    private static final String FLOOR_LANCZOS = "uFloorFilter == FILTER_LANCZOS && magnified(size, dx, dy) ? lanczosFloor(uv, layer)\n      : ";

    // Emulacion de texturas empaquetadas de xiveboy (Pages41.lower): cada texture(int,...) metia hasta ~20 lecturas
    // copiadas (todos los caminos de filtrado x 4 taps, cada una con su bucle de mipmaps y su swizzle). En modo Modern
    // el shader "light" tiene 19 texturas asi y el compilador de Metal no termina nunca. glsl/pages-fast.glsl hace lo
    // mismo con bucles (1 lectura escrita por llamada): 12 ms, y tools/PagesEquivTest da 0 pixeles distintos.
    private static final String PAGES_START = "vec4 vpPagePixel(int s,ivec2 at,int level,bool wrap) {";
    private static final String PAGES_END = "float vpPageLambda(int s";
    private static final String PAGES_FAST = resource("pages-fast.glsl");

    private Glsl() {}

    private static String resource(String name) {
        try (java.io.InputStream in = Glsl.class.getResourceAsStream(name)) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    public static void glShaderSource(int shader, CharSequence source) {
        String s = rewrite(source.toString());
        if (Config.DIAG || Config.DUMP) {
            String hash = String.format("%08x", s.hashCode());
            if (Config.DIAG) Diag.source(shader, hash);
            if (Config.DUMP) dump(hash, s);
        }
        GL20.glShaderSource(shader, s);
    }

    private static void dump(String hash, String s) {
        try {
            Files.writeString(Config.DUMP_DIR.resolve(hash + ".glsl"), s);
        } catch (Exception e) {
            Log.warn("could not dump a shader: " + e);
        }
    }

    static String rewrite(String s) {
        if (s.contains("lanczos2(")) {
            Matcher m = LANCZOS_LOOP.matcher(s);
            if (m.find()) {
                s = m.replaceAll(Matcher.quoteReplacement(LANCZOS_HOISTED)).replace(LANCZOS_INNER, "float w = wxs[i] * wy;");
                once("lanczos", "GLSL rule 'lanczos': precomputed weights (same result, easier for Metal's compiler)");
            }
            if (s.contains("float wy = lanczos2(float(j - 1)")) {
                once("lanczos-miss", "WARNING: a Lanczos loop was left without the 'lanczos' rule: Viewpoint changed the shader");
            }
        }
        if (s.contains(PAGES_START)) {
            int a = s.indexOf(PAGES_START), b = s.indexOf(PAGES_END, a);
            if (PAGES_FAST != null && b > a && s.contains("int vpWrap(") && s.contains("vec4 vpSwizzled(")) {
                s = s.substring(0, a) + PAGES_FAST + s.substring(b);
                once("pages-loops", "GLSL rule 'pages-loops': packed-texture sampling with loops (same result, avoids Metal compiler hangs)");
            } else {
                once("pages-miss", "WARNING: packed-texture code changed: rule 'pages-loops' not applied (Modern mode may freeze)");
            }
        }
        if (s.contains(FLOOR_LANCZOS) && s.contains("lanczos(tex, uv, lo, hi, false)")) {
            s = s.replace(FLOOR_LANCZOS, "");
            once("lanczos-floor", "GLSL rule 'lanczos-floor': no Lanczos on floors in shaders that also use it for sprites "
                    + "(avoids Apple's Metal compiler crash; on Mac floors stay bilinear if you pick Lanczos)");
        }
        return s;
    }

    private static void once(String key, String msg) {
        synchronized (told) {
            if (told.add(key)) Log.info(msg);
        }
    }
}
