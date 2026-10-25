package vpmac;

import static org.lwjgl.opengl.GL41C.*;

/**
 * glGetTexImage(GL_TEXTURE_2D, nivel, RGBA|DEPTH_COMPONENT, FLOAT, offset) hacia el PIXEL_PACK_BUFFER enlazado,
 * pero hecho EN LA GPU con transform feedback: un vertex shader lee cada texel (texelFetch) y lo escribe en el
 * buffer. Mismo resultado que la version del driver (floats normalizados; profundidad empaquetada de a 4 por
 * texel), sin que el CPU espere a la GPU. Lo usa Pages41.pack (Config.GPU_PACK) para las tablas de texels de los
 * shaders que pasan de 16 unidades (far shell). Guarda y restaura todo el estado que toca.
 */
public final class GpuPack {
    private static final String VS = "#version 410\n"
            + "uniform sampler2D uSrc; uniform int uWidth, uHeight, uLevel, uPacked;\n"
            + "out vec4 vOut;\n"
            + "vec4 at(int i) { ivec2 p = ivec2(i % uWidth, i / uWidth); return p.y < uHeight ? texelFetch(uSrc, p, uLevel) : vec4(0.0); }\n"
            + "void main() {\n"
            + "  if (uPacked == 0) vOut = at(gl_VertexID);\n"
            + "  else { int i = gl_VertexID * 4; vOut = vec4(at(i).r, at(i + 1).r, at(i + 2).r, at(i + 3).r); }\n"
            + "}\n";
    private static final String FS = "#version 410\nout vec4 o; void main() { o = vec4(0.0); }\n";

    private static int program, vao, sampler0, samplerMips, uSrc, uWidth, uHeight, uLevel, uPacked;
    private static boolean broken, announced;

    private GpuPack() {}

    /** misma firma que GL11.glGetTexImage(target, level, format, type, offset) */
    public static void glGetTexImage(int target, int level, int format, int type, long offset) {
        int buffer = glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING);
        if (broken || target != GL_TEXTURE_2D || type != GL_FLOAT || buffer == 0
                || (format != GL_RGBA && format != GL_DEPTH_COMPONENT) || (offset & 3) != 0 || !ready()) {
            org.lwjgl.opengl.GL11C.glGetTexImage(target, level, format, type, offset); // el del driver (no este)
            return;
        }
        int w = glGetTexLevelParameteri(GL_TEXTURE_2D, level, GL_TEXTURE_WIDTH);
        int h = glGetTexLevelParameteri(GL_TEXTURE_2D, level, GL_TEXTURE_HEIGHT);
        boolean packed = format == GL_DEPTH_COMPONENT;
        long texels = packed ? ((long) w * h + 3) / 4 : (long) w * h;
        if (w <= 0 || h <= 0 || texels <= 0) return;

        int unit = glGetInteger(GL_ACTIVE_TEXTURE) - GL_TEXTURE0; // Pages41 ya dejo la textura en esta unidad
        int prevProgram = glGetInteger(GL_CURRENT_PROGRAM);
        int prevVao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        int prevSampler = glGetInteger(GL_SAMPLER_BINDING);
        int prevTf = glGetInteger(GL_TRANSFORM_FEEDBACK_BUFFER_BINDING);
        boolean prevDiscard = glIsEnabled(GL_RASTERIZER_DISCARD);

        glUseProgram(program);
        glUniform1i(uSrc, unit);
        glUniform1i(uWidth, w);
        glUniform1i(uHeight, h);
        glUniform1i(uLevel, level);
        glUniform1i(uPacked, packed ? 1 : 0);
        // nivel 0 con filtro sin mipmaps (si no, una textura sin mipmaps queda "incompleta" y texelFetch da 0);
        // niveles mayores necesitan filtro con mipmaps para estar dentro del rango valido
        glBindSampler(unit, level == 0 ? sampler0 : samplerMips);
        glBindVertexArray(vao);
        glBindBufferRange(GL_TRANSFORM_FEEDBACK_BUFFER, 0, buffer, offset, texels * 16);
        glEnable(GL_RASTERIZER_DISCARD);
        glBeginTransformFeedback(GL_POINTS);
        glDrawArrays(GL_POINTS, 0, (int) texels);
        glEndTransformFeedback();

        if (!prevDiscard) glDisable(GL_RASTERIZER_DISCARD);
        glBindBufferBase(GL_TRANSFORM_FEEDBACK_BUFFER, 0, 0);
        glBindBuffer(GL_TRANSFORM_FEEDBACK_BUFFER, prevTf);
        glBindVertexArray(prevVao);
        glBindSampler(unit, prevSampler);
        glUseProgram(prevProgram);
    }

    private static boolean ready() {
        if (program != 0) return true;
        try {
            int v = shader(GL_VERTEX_SHADER, VS), f = shader(GL_FRAGMENT_SHADER, FS);
            int p = glCreateProgram();
            glAttachShader(p, v);
            glAttachShader(p, f);
            glTransformFeedbackVaryings(p, new CharSequence[] {"vOut"}, GL_INTERLEAVED_ATTRIBS);
            glLinkProgram(p);
            glDeleteShader(v);
            glDeleteShader(f);
            if (glGetProgrami(p, GL_LINK_STATUS) == 0) throw new IllegalStateException(glGetProgramInfoLog(p));
            uSrc = glGetUniformLocation(p, "uSrc");
            uWidth = glGetUniformLocation(p, "uWidth");
            uHeight = glGetUniformLocation(p, "uHeight");
            uLevel = glGetUniformLocation(p, "uLevel");
            uPacked = glGetUniformLocation(p, "uPacked");
            vao = glGenVertexArrays();
            sampler0 = sampler(GL_NEAREST);
            samplerMips = sampler(GL_NEAREST_MIPMAP_NEAREST);
            program = p;
            if (!announced) { announced = true; Log.info("pages: copying on the GPU (transform feedback) instead of glGetTexImage"); }
            return true;
        } catch (Throwable t) {
            broken = true;
            Log.warn("GpuPack unavailable, falling back to glGetTexImage: " + t);
            return false;
        }
    }

    private static int sampler(int minFilter) {
        int s = glGenSamplers();
        glSamplerParameteri(s, GL_TEXTURE_COMPARE_MODE, GL_NONE);
        glSamplerParameteri(s, GL_TEXTURE_MIN_FILTER, minFilter);
        glSamplerParameteri(s, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        return s;
    }

    private static int shader(int kind, String src) {
        int s = glCreateShader(kind);
        glShaderSource(s, src);
        glCompileShader(s);
        if (glGetShaderi(s, GL_COMPILE_STATUS) == 0) throw new IllegalStateException(glGetShaderInfoLog(s));
        return s;
    }
}
