package vpmac;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.lwjgl.opengl.GL31;
import viewpoint.mac41.Draws41;
import viewpoint.mac41.Pages41;

/**
 * Aceleradores de rendimiento (Config.BATCH_DRAWS / PICK_EVERY). Cada uno es equivalente a lo original:
 *  - lotes de dibujo: en un multi-draw indirecto emulado (Draws41.submit) el programa, texturas y uniforms no
 *    cambian entre comandos, asi que el estado se revisa UNA vez por lote (Transformer lo inserta en submit) y
 *    cada comando dibuja directo;
 *  - lectura de la mira (MousePick.read): cada N cuadros en vez de todos (glReadPixels de profundidad hace que el
 *    driver de Apple espere a la GPU);
 *  - paginas solo si hacen falta (Config.SMART_PAGES): Pages41 empaqueta texturas de algunos shaders en una tabla
 *    de texels para no pasarse de las 16 unidades del Mac, y las re-copia con glGetTexImage (sincrono) cada vez
 *    que cambian: far shell, far shell band y light lo hacian en CADA cuadro. Si el shader cabe en 16 unidades
 *    sin empaquetar (conteo conservador de declaraciones), se deja tal cual.
 */
public final class Accel {
    private static int pickFrame;

    private Accel() {}

    /** reemplaza Draws41.glDrawArraysInstanced dentro de Draws41.arrays: `batch` = viene de un lote ya revisado */
    public static void drawArraysInstanced(int mode, int first, int count, int instances, boolean batch) {
        if (batch) GL31.glDrawArraysInstanced(mode, first, count, instances);
        else Draws41.glDrawArraysInstanced(mode, first, count, instances);
    }

    private static final Pattern SAMPLER = Pattern.compile(
            "\\buniform\\s+(?:(?:lowp|mediump|highp)\\s+)?[iu]?sampler\\w+\\s+(\\w+)\\s*(?:\\[\\s*(\\w+)\\s*\\])?\\s*;");
    private static final Pattern DEFINE = Pattern.compile("(?m)^\\s*#\\s*define\\s+(\\w+)\\s+(\\d+)\\s*$");
    private static int pagesSkipped, pagesKept;

    /** reemplaza Pages41.lower(shader, fuente) en Shaders41 */
    public static String pagesLower(int shader, String src) {
        boolean candidate = src.contains("uniform sampler2D uAlbedo;") || src.contains("uniform sampler2D uAtlases[");
        // rapido: contando declaraciones; si no alcanza, exacto: lo que el compilador deja activo
        int units = candidate ? units(src) : Integer.MAX_VALUE;
        int limit = Compat.fragmentUnits(); // el de ESTA Mac (16 en todos los Apple Silicon probados)
        if (candidate && units > limit) units = activeUnits(src);
        if (candidate && units <= limit) {
            Pages41.lower(shader, ""); // solo para que Pages41 olvide lo que tuviera de este shader
            if (pagesSkipped++ == 0) Log.info("pages: shaders that fit in " + limit + " texture units are left unpacked");
            return src;
        }
        String out = Pages41.lower(shader, src);
        if (!out.equals(src)) pagesKept++;
        return out;
    }

    /**
     * Unidades que el compilador deja ACTIVAS en este fragment shader (los samplers de codigo apagado por #if
     * desaparecen): se compila y enlaza como programa separable de prueba y se cuentan los uniforms sampler.
     * MAX_VALUE si algo falla (entonces se empaqueta como siempre).
     */
    static int activeUnits(String src) {
        int sh = 0, prog = 0;
        try {
            sh = org.lwjgl.opengl.GL20C.glCreateShader(org.lwjgl.opengl.GL20C.GL_FRAGMENT_SHADER);
            org.lwjgl.opengl.GL20C.glShaderSource(sh, src);
            org.lwjgl.opengl.GL20C.glCompileShader(sh);
            if (org.lwjgl.opengl.GL20C.glGetShaderi(sh, org.lwjgl.opengl.GL20C.GL_COMPILE_STATUS) == 0) return Integer.MAX_VALUE;
            prog = org.lwjgl.opengl.GL20C.glCreateProgram();
            org.lwjgl.opengl.GL41C.glProgramParameteri(prog, org.lwjgl.opengl.GL41C.GL_PROGRAM_SEPARABLE, 1);
            org.lwjgl.opengl.GL20C.glAttachShader(prog, sh);
            org.lwjgl.opengl.GL20C.glLinkProgram(prog);
            if (org.lwjgl.opengl.GL20C.glGetProgrami(prog, org.lwjgl.opengl.GL20C.GL_LINK_STATUS) == 0) return Integer.MAX_VALUE;
            int n = org.lwjgl.opengl.GL20C.glGetProgrami(prog, org.lwjgl.opengl.GL20C.GL_ACTIVE_UNIFORMS), total = 0;
            java.nio.IntBuffer size = org.lwjgl.BufferUtils.createIntBuffer(1), type = org.lwjgl.BufferUtils.createIntBuffer(1);
            for (int i = 0; i < n; i++) {
                org.lwjgl.opengl.GL20C.glGetActiveUniform(prog, i, size, type);
                if (StateSnap.targetOf(type.get(0)) != 0 || isShadowOrInt(type.get(0))) total += size.get(0);
            }
            return total;
        } catch (Throwable t) {
            return Integer.MAX_VALUE;
        } finally {
            if (prog != 0) org.lwjgl.opengl.GL20C.glDeleteProgram(prog);
            if (sh != 0) org.lwjgl.opengl.GL20C.glDeleteShader(sh);
        }
    }

    private static boolean isShadowOrInt(int type) {
        // tipos sampler que StateSnap.targetOf no lista (1D, multisample...): igual ocupan unidad
        return type == 0x8B5D || type == 0x8B61 || type == 0x9108 || type == 0x9109 || type == 0x910A || type == 0x910B
                || type == 0x910C || type == 0x910D || type == 0x8DC9 || type == 0x8DD1 || type == 0x8DD6 || type == 0x8DC0;
    }

    /** unidades de textura que pediria el shader sin empaquetar; MAX_VALUE si no se puede saber */
    static int units(String src) {
        Map<String, Integer> defs = new HashMap<>();
        Matcher d = DEFINE.matcher(src);
        while (d.find()) defs.put(d.group(1), Integer.parseInt(d.group(2)));
        int total = 0;
        Matcher m = SAMPLER.matcher(src);
        while (m.find()) {
            String size = m.group(2);
            if (size == null) { total++; continue; }
            Integer n = size.chars().allMatch(Character::isDigit) ? Integer.valueOf(size) : defs.get(size);
            if (n == null) return Integer.MAX_VALUE;
            total += n;
        }
        return total;
    }

    /** true = este cuadro se salta la lectura de la mira */
    public static boolean skipPick() {
        if (++pickFrame >= Config.PICK_EVERY) {
            pickFrame = 0;
            return false;
        }
        return true;
    }
}
