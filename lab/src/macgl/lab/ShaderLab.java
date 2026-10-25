package macgl.lab;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL41C.*;

import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;

/**
 * Laboratorio de shaders para OpenGL 4.1 en Mac (Metal por debajo).
 *
 * Compila y enlaza un par vertex/fragment en un contexto 4.1 Core oculto y hace UN dibujo, que es cuando el
 * driver de Apple arma el "pipeline" de Metal y compila de verdad (ahi es donde Metal se puede colgar).
 * Imprime una linea por etapa: "STAGE nombre ms ok|FAIL detalle". El que corre esto (lab.py) pone el tiempo
 * limite desde afuera, porque un cuelgue de Metal no se puede interrumpir desde adentro del proceso.
 *
 * Uso: ShaderLab <vertex.glsl> <fragment.glsl> [targets=3] [format=rgba16f|rgba8|rgba32f] [foto-estado.json]
 * La foto (de _vpmac StateSnap) reproduce render targets, profundidad, mezcla y atributos de vertice del juego.
 */
public final class ShaderLab {
    private ShaderLab() {}

    public static void main(String[] a) throws Exception {
        if (a.length < 2) {
            System.err.println("uso: ShaderLab <vertex.glsl> <fragment.glsl> [targets] [format]");
            System.exit(2);
        }
        String vs = Files.readString(Path.of(a[0])), fs = Files.readString(Path.of(a[1]));
        int targets = a.length > 2 ? Integer.parseInt(a[2]) : 3;
        int format = switch (a.length > 3 ? a[3] : "rgba16f") {
            case "rgba8" -> GL_RGBA8;
            case "rgba32f" -> GL_RGBA32F;
            default -> GL_RGBA16F;
        };

        if (!glfwInit()) fail("glfw", "glfwInit fallo");
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 1);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        long win = glfwCreateWindow(64, 64, "macgl lab", 0, 0);
        if (win == 0) fail("context", "no se pudo crear el contexto 4.1");
        glfwMakeContextCurrent(win);
        GL.createCapabilities();
        System.out.println("INFO renderer " + glGetString(GL_RENDERER) + " | " + glGetString(GL_VERSION));

        int v = compile("vertex", GL_VERTEX_SHADER, vs);
        int f = compile("fragment", GL_FRAGMENT_SHADER, fs);
        long t = System.nanoTime();
        int p = glCreateProgram();
        glAttachShader(p, v);
        glAttachShader(p, f);
        glLinkProgram(p);
        if (glGetProgrami(p, GL_LINK_STATUS) == 0) fail("link", glGetProgramInfoLog(p));
        stage("link", t, "ok");

        org.json.JSONObject state = a.length > 4 ? new org.json.JSONObject(Files.readString(Path.of(a[4]))) : null;
        java.util.Map<String, org.json.JSONObject> snap = new java.util.HashMap<>();
        if (state != null) {
            org.json.JSONArray ss = state.getJSONArray("samplers");
            for (int i = 0; i < ss.length(); i++) snap.put(ss.getJSONObject(i).getString("name"), ss.getJSONObject(i));
        }

        // cada sampler en su propia unidad: dos tipos distintos en la misma unidad invalidan el dibujo y Metal
        // nunca llegaria a compilar (falso "anda bien")
        glUseProgram(p);
        int unit = 0, samplers = 0, fromGame = 0;
        int n = glGetProgrami(p, GL_ACTIVE_UNIFORMS);
        try (MemoryStack s = MemoryStack.stackPush()) {
            IntBuffer size = s.mallocInt(1), type = s.mallocInt(1);
            for (int i = 0; i < n; i++) {
                String name = glGetActiveUniform(p, i, size, type);
                if (!isSampler(type.get(0))) continue;
                int loc = glGetUniformLocation(p, name);
                for (int k = 0; k < size.get(0); k++) {
                    int u = Math.min(unit++, 15);
                    glUniform1i(loc + k, u);
                    // textura real del tipo justo: sin ella el driver pone una "textura cero" y se salta la
                    // variante pesada del shader (en el juego siempre hay texturas)
                    if (realTextures) {
                        org.json.JSONObject sn = snap.get(size.get(0) > 1 ? name.replace("[0]", "") + "[" + k + "]" : name);
                        if (sn == null && k == 0) sn = snap.get(name);
                        int fmt = sn != null ? sn.optInt("format", 0) : 0;
                        boolean cmp = sn != null && (sn.optInt("compare", 0) != 0
                                || sn.has("sampler") && sn.getJSONObject("sampler").optInt("compare", 0) != 0);
                        bindDummy(u, type.get(0), fmt, cmp);
                        if (fmt != 0) fromGame++;
                    }
                }
                samplers += size.get(0);
            }
        }
        glActiveTexture(GL_TEXTURE0);
        System.out.println("INFO samplers " + samplers + (samplers > 16 ? " (MAS DE 16: el Mac solo tiene 16 unidades)" : "")
                + (realTextures ? ", con texturas reales (" + fromGame + " con el formato del juego)" : ", sin texturas"));

        // estado: el de una foto del juego (_vpmac StateSnap) si se pasa, o uno generico
        int fbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        int[] formats;
        if (state != null && state.getJSONArray("targets").length() > 0) {
            org.json.JSONArray ts = state.getJSONArray("targets");
            formats = new int[ts.length()];
            for (int i = 0; i < formats.length; i++) formats[i] = ts.getJSONObject(i).getInt("format");
        } else {
            formats = new int[targets];
            java.util.Arrays.fill(formats, format);
        }
        int[] bufs = new int[formats.length];
        for (int i = 0; i < formats.length; i++) {
            int rb = glGenRenderbuffers();
            glBindRenderbuffer(GL_RENDERBUFFER, rb);
            glRenderbufferStorage(GL_RENDERBUFFER, formats[i], 64, 64);
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0 + i, GL_RENDERBUFFER, rb);
            bufs[i] = GL_COLOR_ATTACHMENT0 + i;
        }
        int depthFormat = state != null ? state.getInt("depth") : GL_DEPTH_COMPONENT24;
        if (depthFormat > 0) {
            int depth = glGenRenderbuffers();
            glBindRenderbuffer(GL_RENDERBUFFER, depth);
            glRenderbufferStorage(GL_RENDERBUFFER, depthFormat, 64, 64);
            boolean stencil = depthFormat == GL_DEPTH24_STENCIL8 || depthFormat == GL_DEPTH32F_STENCIL8;
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, stencil ? GL_DEPTH_STENCIL_ATTACHMENT : GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, depth);
        }
        glDrawBuffers(bufs);
        int st = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        if (st != GL_FRAMEBUFFER_COMPLETE) fail("fbo", "incompleto 0x" + Integer.toHexString(st));
        glViewport(0, 0, 64, 64);
        System.out.println("INFO targets " + java.util.Arrays.toString(formats) + " depth " + depthFormat
                + (state != null ? " (foto del juego)" : ""));

        glBindVertexArray(glGenVertexArrays());
        boolean instanced = false;
        if (state != null) {
            if (state.getBoolean("blend")) {
                glEnable(GL_BLEND);
                glBlendFuncSeparate(state.getInt("blendSrc"), state.getInt("blendDst"), state.getInt("blendSrcA"), state.getInt("blendDstA"));
            }
            if (state.getBoolean("depthTest")) glEnable(GL_DEPTH_TEST);
            glDepthMask(state.getBoolean("depthWrite"));
            if (state.getBoolean("cull")) glEnable(GL_CULL_FACE);
            if (state.getBoolean("alphaToCoverage")) glEnable(GL_SAMPLE_ALPHA_TO_COVERAGE);
            int vbo = glGenBuffers();
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            glBufferData(GL_ARRAY_BUFFER, 1 << 16, GL_STATIC_DRAW);
            org.json.JSONArray attrs = state.getJSONArray("attributes");
            for (int i = 0; i < attrs.length(); i++) {
                org.json.JSONObject at = attrs.getJSONObject(i);
                int idx = at.getInt("index"), size = at.getInt("size"), type = at.getInt("type"), stride = at.getInt("stride");
                glEnableVertexAttribArray(idx);
                if (at.getInt("integer") != 0) glVertexAttribIPointer(idx, size, type, stride, 0L);
                else glVertexAttribPointer(idx, size, type, at.getInt("normalized") != 0, stride, 0L);
                glVertexAttribDivisor(idx, at.getInt("divisor"));
                instanced |= at.getInt("divisor") != 0;
            }
        }

        glValidateProgram(p);
        if (glGetProgrami(p, GL_VALIDATE_STATUS) == 0) System.out.println("INFO validate " + glGetProgramInfoLog(p).trim());
        while (glGetError() != GL_NO_ERROR) { /* limpiar */ }

        System.out.println("BEGIN draw");
        System.out.flush();
        t = System.nanoTime();
        if (instanced) glDrawArraysInstanced(GL_TRIANGLES, 0, 3, 1);
        else glDrawArrays(GL_TRIANGLES, 0, 3);
        glFinish();
        int err = glGetError();
        stage("draw", t, err == GL_NO_ERROR ? "ok" : "FAIL glError 0x" + Integer.toHexString(err));
        glfwTerminate();
    }

    private static boolean realTextures = !"0".equals(System.getenv("MACGL_TEXTURES"));

    /** textura chica del tipo que pide el sampler, en la unidad `u` */
    private static void bindDummy(int u, int samplerType, int gameFormat, boolean compare) {
        glActiveTexture(GL_TEXTURE0 + u);
        int t = glGenTextures();
        java.nio.ByteBuffer none = null;
        if (gameFormat != 0) {
            int target = targetOf(samplerType);
            glBindTexture(target, t);
            if (target == GL_TEXTURE_BUFFER) {
                int b = glGenBuffers();
                glBindBuffer(GL_TEXTURE_BUFFER, b);
                glBufferData(GL_TEXTURE_BUFFER, 1 << 16, GL_STATIC_DRAW);
                glTexBuffer(GL_TEXTURE_BUFFER, gameFormat, b);
                return;
            }
            int[] ft = pixelFormat(gameFormat);
            if (target == GL_TEXTURE_2D_ARRAY || target == GL_TEXTURE_3D) glTexImage3D(target, 0, gameFormat, 16, 16, 4, 0, ft[0], ft[1], none);
            else if (target == GL_TEXTURE_CUBE_MAP) for (int face = 0; face < 6; face++)
                glTexImage2D(GL_TEXTURE_CUBE_MAP_POSITIVE_X + face, 0, gameFormat, 16, 16, 0, ft[0], ft[1], none);
            else glTexImage2D(target, 0, gameFormat, 16, 16, 0, ft[0], ft[1], none);
            params(target, compare);
            if (glGetError() == GL_NO_ERROR) return;
            System.out.println("INFO formato 0x" + Integer.toHexString(gameFormat) + " no se pudo crear; uso uno generico");
        }
        switch (samplerType) {
            case GL_SAMPLER_2D_ARRAY, GL_SAMPLER_2D_ARRAY_SHADOW, GL_INT_SAMPLER_2D_ARRAY, GL_UNSIGNED_INT_SAMPLER_2D_ARRAY -> {
                glBindTexture(GL_TEXTURE_2D_ARRAY, t);
                boolean depth = samplerType == GL_SAMPLER_2D_ARRAY_SHADOW;
                int[] f = texFormat(samplerType, depth);
                glTexImage3D(GL_TEXTURE_2D_ARRAY, 0, f[0], 16, 16, 4, 0, f[1], f[2], none);
                params(GL_TEXTURE_2D_ARRAY, depth);
            }
            case GL_SAMPLER_3D, GL_INT_SAMPLER_3D, GL_UNSIGNED_INT_SAMPLER_3D -> {
                glBindTexture(GL_TEXTURE_3D, t);
                int[] f = texFormat(samplerType, false);
                glTexImage3D(GL_TEXTURE_3D, 0, f[0], 8, 8, 8, 0, f[1], f[2], none);
                params(GL_TEXTURE_3D, false);
            }
            case GL_SAMPLER_BUFFER, GL_INT_SAMPLER_BUFFER, GL_UNSIGNED_INT_SAMPLER_BUFFER -> {
                int b = glGenBuffers();
                glBindBuffer(GL_TEXTURE_BUFFER, b);
                glBufferData(GL_TEXTURE_BUFFER, 4096, GL_STATIC_DRAW);
                glBindTexture(GL_TEXTURE_BUFFER, t);
                glTexBuffer(GL_TEXTURE_BUFFER, samplerType == GL_SAMPLER_BUFFER ? GL_RGBA32F
                        : samplerType == GL_INT_SAMPLER_BUFFER ? GL_RGBA32I : GL_RGBA32UI, b);
            }
            case GL_SAMPLER_CUBE, GL_SAMPLER_CUBE_SHADOW -> {
                glBindTexture(GL_TEXTURE_CUBE_MAP, t);
                boolean depth = samplerType == GL_SAMPLER_CUBE_SHADOW;
                int[] f = texFormat(samplerType, depth);
                for (int face = 0; face < 6; face++)
                    glTexImage2D(GL_TEXTURE_CUBE_MAP_POSITIVE_X + face, 0, f[0], 16, 16, 0, f[1], f[2], none);
                params(GL_TEXTURE_CUBE_MAP, depth);
            }
            default -> {
                glBindTexture(GL_TEXTURE_2D, t);
                boolean depth = samplerType == GL_SAMPLER_2D_SHADOW;
                int[] f = texFormat(samplerType, depth);
                glTexImage2D(GL_TEXTURE_2D, 0, f[0], 16, 16, 0, f[1], f[2], none);
                params(GL_TEXTURE_2D, depth);
            }
        }
    }

    /** {format, type} validos para crear (sin datos) una textura con ese formato interno */
    private static int[] pixelFormat(int internal) {
        return switch (internal) {
            case GL_DEPTH_COMPONENT16, GL_DEPTH_COMPONENT24, GL_DEPTH_COMPONENT32F -> new int[] {GL_DEPTH_COMPONENT, GL_FLOAT};
            case GL_DEPTH24_STENCIL8, GL_DEPTH32F_STENCIL8 -> new int[] {GL_DEPTH_STENCIL, GL_UNSIGNED_INT_24_8};
            case GL_R8UI, GL_R16UI, GL_R32UI -> new int[] {GL_RED_INTEGER, GL_UNSIGNED_INT};
            case GL_RG8UI, GL_RG16UI, GL_RG32UI -> new int[] {GL_RG_INTEGER, GL_UNSIGNED_INT};
            case GL_RGBA8UI, GL_RGBA16UI, GL_RGBA32UI -> new int[] {GL_RGBA_INTEGER, GL_UNSIGNED_INT};
            case GL_R8I, GL_R16I, GL_R32I -> new int[] {GL_RED_INTEGER, GL_INT};
            case GL_RG8I, GL_RG16I, GL_RG32I -> new int[] {GL_RG_INTEGER, GL_INT};
            case GL_RGBA8I, GL_RGBA16I, GL_RGBA32I -> new int[] {GL_RGBA_INTEGER, GL_INT};
            case GL_R8, GL_R16F, GL_R32F -> new int[] {GL_RED, GL_FLOAT};
            case GL_RG8, GL_RG16F, GL_RG32F -> new int[] {GL_RG, GL_FLOAT};
            case GL_RGB8, GL_RGB16F, GL_RGB32F, GL_R11F_G11F_B10F, GL_RGB9_E5, GL_SRGB8 -> new int[] {GL_RGB, GL_FLOAT};
            default -> new int[] {GL_RGBA, GL_FLOAT};
        };
    }

    private static int targetOf(int samplerType) {
        return switch (samplerType) {
            case GL_SAMPLER_2D_ARRAY, GL_SAMPLER_2D_ARRAY_SHADOW, GL_INT_SAMPLER_2D_ARRAY, GL_UNSIGNED_INT_SAMPLER_2D_ARRAY -> GL_TEXTURE_2D_ARRAY;
            case GL_SAMPLER_3D, GL_INT_SAMPLER_3D, GL_UNSIGNED_INT_SAMPLER_3D -> GL_TEXTURE_3D;
            case GL_SAMPLER_CUBE, GL_SAMPLER_CUBE_SHADOW -> GL_TEXTURE_CUBE_MAP;
            case GL_SAMPLER_BUFFER, GL_INT_SAMPLER_BUFFER, GL_UNSIGNED_INT_SAMPLER_BUFFER -> GL_TEXTURE_BUFFER;
            default -> GL_TEXTURE_2D;
        };
    }

    /** {internalFormat, format, type} */
    private static int[] texFormat(int samplerType, boolean depth) {
        if (depth) return new int[] {GL_DEPTH_COMPONENT24, GL_DEPTH_COMPONENT, GL_UNSIGNED_INT};
        return switch (samplerType) {
            case GL_INT_SAMPLER_2D, GL_INT_SAMPLER_3D, GL_INT_SAMPLER_2D_ARRAY -> new int[] {GL_RGBA32I, GL_RGBA_INTEGER, GL_INT};
            case GL_UNSIGNED_INT_SAMPLER_2D, GL_UNSIGNED_INT_SAMPLER_3D, GL_UNSIGNED_INT_SAMPLER_2D_ARRAY ->
                    new int[] {GL_RGBA32UI, GL_RGBA_INTEGER, GL_UNSIGNED_INT};
            default -> new int[] {GL_RGBA8, GL_RGBA, GL_UNSIGNED_BYTE};
        };
    }

    private static void params(int target, boolean depth) {
        glTexParameteri(target, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(target, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(target, GL_TEXTURE_MAX_LEVEL, 0);
        if (depth) {
            glTexParameteri(target, GL_TEXTURE_COMPARE_MODE, GL_COMPARE_REF_TO_TEXTURE);
            glTexParameteri(target, GL_TEXTURE_COMPARE_FUNC, GL_LEQUAL);
        }
    }

    private static int compile(String what, int kind, String src) {
        long t = System.nanoTime();
        int sh = glCreateShader(kind);
        glShaderSource(sh, src);
        glCompileShader(sh);
        if (glGetShaderi(sh, GL_COMPILE_STATUS) == 0) fail(what, glGetShaderInfoLog(sh));
        stage(what, t, "ok");
        return sh;
    }

    private static boolean isSampler(int type) {
        return switch (type) {
            case GL_SAMPLER_1D, GL_SAMPLER_2D, GL_SAMPLER_3D, GL_SAMPLER_CUBE, GL_SAMPLER_2D_SHADOW,
                 GL_SAMPLER_1D_ARRAY, GL_SAMPLER_2D_ARRAY, GL_SAMPLER_2D_ARRAY_SHADOW, GL_SAMPLER_CUBE_SHADOW,
                 GL_SAMPLER_BUFFER, GL_SAMPLER_2D_RECT, GL_SAMPLER_2D_MULTISAMPLE,
                 GL_INT_SAMPLER_2D, GL_INT_SAMPLER_3D, GL_INT_SAMPLER_2D_ARRAY, GL_INT_SAMPLER_BUFFER,
                 GL_UNSIGNED_INT_SAMPLER_2D, GL_UNSIGNED_INT_SAMPLER_3D, GL_UNSIGNED_INT_SAMPLER_2D_ARRAY,
                 GL_UNSIGNED_INT_SAMPLER_BUFFER -> true;
            default -> false;
        };
    }

    private static void stage(String name, long t0, String result) {
        System.out.printf(java.util.Locale.ROOT, "STAGE %s %.1f %s%n", name, (System.nanoTime() - t0) / 1e6, result);
        System.out.flush();
    }

    private static void fail(String stage, String detail) {
        System.out.println("STAGE " + stage + " 0 FAIL " + detail.trim().replace('\n', '|'));
        System.out.flush();
        System.exit(1);
    }
}
