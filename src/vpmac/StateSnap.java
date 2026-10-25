package vpmac;

import static org.lwjgl.opengl.GL41C.*;

import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import org.lwjgl.system.MemoryStack;

/**
 * Foto del estado de OpenGL que decide que variante compila Metal en el primer dibujo de un programa: render
 * targets (formatos), mezcla, profundidad, cada sampler con su textura y sampler object, y los atributos de
 * vertice. Se guarda como JSON en _vpmac/dump/state_<vs>+<fs>.json para que _macgl/lab la reproduzca.
 * Solo consulta (glGet*); lo poco que cambia (textura activa, bindings para preguntar formatos) lo deja como estaba.
 */
final class StateSnap {
    private StateSnap() {}

    static void save(Path dir, int program, String shaders) {
        try {
            Files.writeString(dir.resolve("state_" + shaders + ".json"), take(program));
        } catch (Throwable t) {
            Log.warn("could not save the state snapshot: " + t);
        }
    }

    static String take(int program) {
        StringBuilder j = new StringBuilder("{\n");
        int fbo = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        j.append("  \"framebuffer\": ").append(fbo).append(",\n  \"targets\": [");
        if (fbo != 0) {
            for (int i = 0; i < 8; i++) {
                int db = glGetInteger(GL_DRAW_BUFFER0 + i);
                if (db == GL_NONE) continue;
                int att = db;
                int type = glGetFramebufferAttachmentParameteri(GL_DRAW_FRAMEBUFFER, att, GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
                if (type == GL_NONE) continue;
                int name = glGetFramebufferAttachmentParameteri(GL_DRAW_FRAMEBUFFER, att, GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
                int fmt = type == GL_TEXTURE ? textureFormat(name) : renderbufferFormat(name);
                j.append(i == 0 ? "" : ",").append("\n    {\"slot\": ").append(i).append(", \"attachment\": ")
                        .append(att - GL_COLOR_ATTACHMENT0).append(", \"format\": ").append(fmt).append("}");
            }
            int dt = glGetFramebufferAttachmentParameteri(GL_DRAW_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
            if (dt != GL_NONE) {
                int dn = glGetFramebufferAttachmentParameteri(GL_DRAW_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
                j.append("\n  ],\n  \"depth\": ").append(dt == GL_TEXTURE ? textureFormat(dn) : renderbufferFormat(dn)).append(",\n");
            } else j.append("\n  ],\n  \"depth\": 0,\n");
        } else j.append("],\n  \"depth\": -1,\n");

        j.append("  \"blend\": ").append(glIsEnabled(GL_BLEND))
                .append(", \"blendSrc\": ").append(glGetInteger(GL_BLEND_SRC_RGB))
                .append(", \"blendDst\": ").append(glGetInteger(GL_BLEND_DST_RGB))
                .append(", \"blendSrcA\": ").append(glGetInteger(GL_BLEND_SRC_ALPHA))
                .append(", \"blendDstA\": ").append(glGetInteger(GL_BLEND_DST_ALPHA))
                .append(", \"depthTest\": ").append(glIsEnabled(GL_DEPTH_TEST))
                .append(", \"depthWrite\": ").append(glGetBoolean(GL_DEPTH_WRITEMASK))
                .append(", \"cull\": ").append(glIsEnabled(GL_CULL_FACE))
                .append(", \"alphaToCoverage\": ").append(glIsEnabled(GL_SAMPLE_ALPHA_TO_COVERAGE)).append(",\n");
        try (MemoryStack s = MemoryStack.stackPush()) {
            IntBuffer cm = s.mallocInt(4);
            glGetIntegerv(GL_COLOR_WRITEMASK, cm);
            j.append("  \"colorMask\": [").append(cm.get(0)).append(",").append(cm.get(1)).append(",")
                    .append(cm.get(2)).append(",").append(cm.get(3)).append("],\n");
        }

        // samplers del programa
        j.append("  \"samplers\": [");
        int active = glGetInteger(GL_ACTIVE_TEXTURE);
        int n = glGetProgrami(program, GL_ACTIVE_UNIFORMS);
        boolean first = true;
        try (MemoryStack s = MemoryStack.stackPush()) {
            IntBuffer size = s.mallocInt(1), type = s.mallocInt(1);
            for (int i = 0; i < n; i++) {
                String name = glGetActiveUniform(program, i, size, type);
                int target = targetOf(type.get(0));
                if (target == 0) continue;
                int loc = glGetUniformLocation(program, name);
                for (int k = 0; k < size.get(0); k++) {
                    int unit = glGetUniformi(program, loc + k);
                    glActiveTexture(GL_TEXTURE0 + unit);
                    int tex = glGetInteger(bindingOf(target));
                    int sampler = glGetInteger(GL_SAMPLER_BINDING);
                    j.append(first ? "" : ",").append("\n    {\"name\": \"").append(name).append(k > 0 ? "[" + k + "]" : "")
                            .append("\", \"type\": ").append(type.get(0)).append(", \"unit\": ").append(unit)
                            .append(", \"texture\": ").append(tex);
                    if (tex != 0 && target == GL_TEXTURE_BUFFER) {
                        j.append(", \"format\": ").append(glGetTexLevelParameteri(GL_TEXTURE_BUFFER, 0, GL_TEXTURE_INTERNAL_FORMAT));
                    }
                    if (tex != 0 && target != GL_TEXTURE_BUFFER) {
                        int lvlTarget = target == GL_TEXTURE_CUBE_MAP ? GL_TEXTURE_CUBE_MAP_POSITIVE_X : target;
                        j.append(", \"format\": ").append(glGetTexLevelParameteri(lvlTarget, 0, GL_TEXTURE_INTERNAL_FORMAT))
                                .append(", \"compare\": ").append(glGetTexParameteri(target, GL_TEXTURE_COMPARE_MODE))
                                .append(", \"minFilter\": ").append(glGetTexParameteri(target, GL_TEXTURE_MIN_FILTER))
                                .append(", \"magFilter\": ").append(glGetTexParameteri(target, GL_TEXTURE_MAG_FILTER))
                                .append(", \"swizzleR\": ").append(glGetTexParameteri(target, GL_TEXTURE_SWIZZLE_R));
                    }
                    if (sampler != 0) {
                        j.append(", \"sampler\": {\"compare\": ").append(glGetSamplerParameteri(sampler, GL_TEXTURE_COMPARE_MODE))
                                .append(", \"minFilter\": ").append(glGetSamplerParameteri(sampler, GL_TEXTURE_MIN_FILTER))
                                .append(", \"magFilter\": ").append(glGetSamplerParameteri(sampler, GL_TEXTURE_MAG_FILTER))
                                .append(", \"wrapS\": ").append(glGetSamplerParameteri(sampler, GL_TEXTURE_WRAP_S)).append("}");
                    }
                    j.append("}");
                    first = false;
                }
            }
        }
        glActiveTexture(active);

        // atributos de vertice del VAO actual
        j.append("\n  ],\n  \"vao\": ").append(glGetInteger(GL_VERTEX_ARRAY_BINDING)).append(",\n  \"attributes\": [");
        first = true;
        int maxAttr = Math.min(glGetInteger(GL_MAX_VERTEX_ATTRIBS), 16);
        for (int a = 0; a < maxAttr; a++) {
            if (glGetVertexAttribi(a, GL_VERTEX_ATTRIB_ARRAY_ENABLED) == 0) continue;
            j.append(first ? "" : ",").append("\n    {\"index\": ").append(a)
                    .append(", \"size\": ").append(glGetVertexAttribi(a, GL_VERTEX_ATTRIB_ARRAY_SIZE))
                    .append(", \"type\": ").append(glGetVertexAttribi(a, GL_VERTEX_ATTRIB_ARRAY_TYPE))
                    .append(", \"normalized\": ").append(glGetVertexAttribi(a, GL_VERTEX_ATTRIB_ARRAY_NORMALIZED))
                    .append(", \"integer\": ").append(glGetVertexAttribi(a, GL_VERTEX_ATTRIB_ARRAY_INTEGER))
                    .append(", \"stride\": ").append(glGetVertexAttribi(a, GL_VERTEX_ATTRIB_ARRAY_STRIDE))
                    .append(", \"divisor\": ").append(glGetVertexAttribi(a, GL_VERTEX_ATTRIB_ARRAY_DIVISOR)).append("}");
            first = false;
        }
        return j.append("\n  ]\n}\n").toString();
    }

    private static int textureFormat(int tex) {
        // el adjunto puede ser 2D o una capa de 2D_ARRAY: se prueba con el binding guardado y restaurado
        while (glGetError() != GL_NO_ERROR) { /* errores viejos del juego: no confundirlos con el de esta prueba */ }
        int saved = glGetInteger(GL_TEXTURE_BINDING_2D);
        glBindTexture(GL_TEXTURE_2D, tex);
        int f = glGetError() == GL_NO_ERROR ? glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_INTERNAL_FORMAT) : 0;
        glBindTexture(GL_TEXTURE_2D, saved);
        if (f != 0) return f;
        while (glGetError() != GL_NO_ERROR) { /* idem */ }
        int savedA = glGetInteger(GL_TEXTURE_BINDING_2D_ARRAY);
        glBindTexture(GL_TEXTURE_2D_ARRAY, tex);
        f = glGetError() == GL_NO_ERROR ? glGetTexLevelParameteri(GL_TEXTURE_2D_ARRAY, 0, GL_TEXTURE_INTERNAL_FORMAT) : 0;
        glBindTexture(GL_TEXTURE_2D_ARRAY, savedA);
        return f;
    }

    private static int renderbufferFormat(int rb) {
        int saved = glGetInteger(GL_RENDERBUFFER_BINDING);
        glBindRenderbuffer(GL_RENDERBUFFER, rb);
        int f = glGetRenderbufferParameteri(GL_RENDERBUFFER, GL_RENDERBUFFER_INTERNAL_FORMAT);
        glBindRenderbuffer(GL_RENDERBUFFER, saved);
        return f;
    }

    static int targetOf(int samplerType) {
        return switch (samplerType) {
            case GL_SAMPLER_2D, GL_SAMPLER_2D_SHADOW, GL_INT_SAMPLER_2D, GL_UNSIGNED_INT_SAMPLER_2D -> GL_TEXTURE_2D;
            case GL_SAMPLER_2D_ARRAY, GL_SAMPLER_2D_ARRAY_SHADOW, GL_INT_SAMPLER_2D_ARRAY, GL_UNSIGNED_INT_SAMPLER_2D_ARRAY -> GL_TEXTURE_2D_ARRAY;
            case GL_SAMPLER_3D, GL_INT_SAMPLER_3D, GL_UNSIGNED_INT_SAMPLER_3D -> GL_TEXTURE_3D;
            case GL_SAMPLER_CUBE, GL_SAMPLER_CUBE_SHADOW -> GL_TEXTURE_CUBE_MAP;
            case GL_SAMPLER_BUFFER, GL_INT_SAMPLER_BUFFER, GL_UNSIGNED_INT_SAMPLER_BUFFER -> GL_TEXTURE_BUFFER;
            case GL_SAMPLER_2D_RECT -> GL_TEXTURE_RECTANGLE;
            default -> 0;
        };
    }

    private static int bindingOf(int target) {
        return switch (target) {
            case GL_TEXTURE_2D -> GL_TEXTURE_BINDING_2D;
            case GL_TEXTURE_2D_ARRAY -> GL_TEXTURE_BINDING_2D_ARRAY;
            case GL_TEXTURE_3D -> GL_TEXTURE_BINDING_3D;
            case GL_TEXTURE_CUBE_MAP -> GL_TEXTURE_BINDING_CUBE_MAP;
            case GL_TEXTURE_BUFFER -> GL_TEXTURE_BINDING_BUFFER;
            default -> GL_TEXTURE_BINDING_RECTANGLE;
        };
    }
}
