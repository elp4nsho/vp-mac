package vpmac;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL41C.*;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

/** Prueba offline: GpuPack (transform feedback) debe dar los mismos floats que glGetTexImage del driver. */
public final class GpuPackTest {
    public static void main(String[] a) {
        glfwInit();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 1);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        long w = glfwCreateWindow(32, 32, "t", 0, 0);
        glfwMakeContextCurrent(w);
        GL.createCapabilities();
        int fails = 0;
        fails += check("RGBA8 61x37 con mipmaps", rgba(61, 37, true), GL_RGBA, true);
        fails += check("RGBA8 64x64 sin mipmaps", rgba(64, 64, false), GL_RGBA, false);
        fails += check("DEPTH24 50x30", depth(50, 30), GL_DEPTH_COMPONENT, false);
        System.out.println(fails == 0 ? "OK: GpuPack == glGetTexImage" : "FALLAS: " + fails);
        glfwTerminate();
        System.exit(fails == 0 ? 0 : 1);
    }

    static int rgba(int w, int h, boolean mips) {
        int t = glGenTextures();
        glActiveTexture(GL_TEXTURE0 + 31);
        glBindTexture(GL_TEXTURE_2D, t);
        ByteBuffer px = BufferUtils.createByteBuffer(w * h * 4);
        for (int i = 0; i < w * h * 4; i++) px.put((byte) ((i * 37 + i / 7) & 255));
        px.flip();
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, px);
        if (mips) glGenerateMipmap(GL_TEXTURE_2D);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, mips ? GL_LINEAR_MIPMAP_LINEAR : GL_LINEAR);
        return t;
    }

    static int depth(int w, int h) {
        int t = glGenTextures();
        glActiveTexture(GL_TEXTURE0 + 31);
        glBindTexture(GL_TEXTURE_2D, t);
        FloatBuffer px = BufferUtils.createFloatBuffer(w * h);
        for (int i = 0; i < w * h; i++) px.put((i % 997) / 997f);
        px.flip();
        glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT24, w, h, 0, GL_DEPTH_COMPONENT, GL_FLOAT, px);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_MODE, GL_COMPARE_REF_TO_TEXTURE); // como un shadow map
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        return t;
    }

    static int check(String what, int tex, int format, boolean mips) {
        glActiveTexture(GL_TEXTURE0 + 31);
        glBindTexture(GL_TEXTURE_2D, tex);
        int levels = 1;
        if (mips) while (glGetTexLevelParameteri(GL_TEXTURE_2D, levels, GL_TEXTURE_WIDTH) > 0) levels++;
        long total = 0;
        long[] offsets = new long[levels];
        for (int l = 0; l < levels; l++) {
            offsets[l] = total;
            int lw = glGetTexLevelParameteri(GL_TEXTURE_2D, l, GL_TEXTURE_WIDTH), lh = glGetTexLevelParameteri(GL_TEXTURE_2D, l, GL_TEXTURE_HEIGHT);
            total += format == GL_DEPTH_COMPONENT ? ((long) lw * lh + 3) / 4 : (long) lw * lh;
        }
        FloatBuffer ref = copy(total, offsets, levels, format, false), got = copy(total, offsets, levels, format, true);
        int bad = 0;
        double worst = 0;
        for (int i = 0; i < ref.capacity(); i++) {
            double d = Math.abs(ref.get(i) - got.get(i));
            worst = Math.max(worst, d);
            if (d > 1e-6) bad++;
        }
        System.out.printf("%-28s niveles=%d floats=%d distintos=%d (max dif %.2e)%n", what, levels, ref.capacity(), bad, worst);
        return bad == 0 ? 0 : 1;
    }

    static FloatBuffer copy(long texels, long[] offsets, int levels, int format, boolean gpu) {
        int buf = glGenBuffers();
        glBindBuffer(GL_PIXEL_PACK_BUFFER, buf);
        glBufferData(GL_PIXEL_PACK_BUFFER, texels * 16, GL_STREAM_COPY);
        glPixelStorei(GL_PACK_ALIGNMENT, 1);
        for (int l = 0; l < levels; l++) {
            if (gpu) GpuPack.glGetTexImage(GL_TEXTURE_2D, l, format, GL_FLOAT, offsets[l] * 16);
            else glGetTexImage(GL_TEXTURE_2D, l, format, GL_FLOAT, offsets[l] * 16);
        }
        glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
        glBindBuffer(GL_COPY_READ_BUFFER, buf);
        // solo los floats realmente escritos (la profundidad deja relleno al final del ultimo texel)
        FloatBuffer out = BufferUtils.createFloatBuffer((int) (texels * 4));
        glGetBufferSubData(GL_COPY_READ_BUFFER, 0, out);
        glDeleteBuffers(buf);
        return out;
    }
}
