package vpmac;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL41C.*;

import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

/**
 * Prueba offline: la emulacion de texturas empaquetadas reescrita (glsl/pages-fast.glsl) debe dar lo mismo que la
 * original de xiveboy (Pages41). Dibuja vpPageSample/texelFetch con ambas para 8 configuraciones (filtros, wrap,
 * mipmaps, 1 y 4 componentes, sRGB, swizzle, borde) y compara pixel a pixel.
 * Uso: PagesEquivTest <shader_con_emulacion_original.glsl> <pages-fast.glsl>
 */
public final class PagesEquivTest {
    static final int N = 8, W = 96, H = 96;

    public static void main(String[] a) throws Exception {
        String src = Files.readString(Path.of(a[0])), fast = Files.readString(Path.of(a[1]));
        String helpers = src.substring(src.indexOf("ivec2 textureSize(int s,int level)"), src.indexOf("vec4 vpPagePixel(int s,ivec2 at,int level,bool wrap) {"));
        String original = src.substring(src.indexOf("vec4 vpPagePixel(int s,ivec2 at,int level,bool wrap) {"), src.indexOf("float vpPageLambda(int s"));
        String head = "#version 410\nuniform samplerBuffer uVpPages;\nuniform ivec4 vpPageImage[" + N + "],vpPageFilter[" + N + "],vpPageSwizzle[" + N
                + "];\nuniform vec4 vpPageBorder[" + N + "],vpPageLod[" + N + "];\nuniform int vpPageComponents[" + N + "];\n";
        String body = "uniform int uS; uniform int uMode; out vec4 o;\nvoid main(){ vec2 c=gl_FragCoord.xy/vec2(" + W + "," + H + ");"
                + " vec2 uv=c*1.6-0.3; float lod=c.y*8.0-1.0;"
                + " if(uMode==0) o=vpPageSample(uS,uv,lod,true); else if(uMode==1) o=vpPageSample(uS,uv,lod,false);"
                + " else o=texelFetch(uS,ivec2(c*vec2(textureSize(uS,0))*1.2)-ivec2(1),int(c.y*4.0)); }\n";

        glfwInit();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 1);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        glfwMakeContextCurrent(glfwCreateWindow(32, 32, "t", 0, 0));
        GL.createCapabilities();

        int pOld = program(head + helpers + original + body), pNew = program(head + helpers + fast + body);
        // datos: un buffer de texels al azar (valores 0..1) con espacio para todas las configuraciones
        Random rnd = new Random(42);
        int texels = 1 << 18;
        FloatBuffer data = BufferUtils.createFloatBuffer(texels * 4);
        for (int i = 0; i < texels * 4; i++) data.put(rnd.nextFloat());
        data.flip();
        int buf = glGenBuffers();
        glBindBuffer(GL_TEXTURE_BUFFER, buf);
        glBufferData(GL_TEXTURE_BUFFER, data, GL_STATIC_DRAW);
        int tbo = glGenTextures();
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_BUFFER, tbo);
        glTexBuffer(GL_TEXTURE_BUFFER, GL_RGBA32F, buf);

        // {ancho, alto, niveles, componentes(+256 sRGB), minFilter, magFilter, wrapS, wrapT}
        int[][] cfg = {
            {37, 23, 6, 4, 9987, 9729, 10497, 10497}, {64, 64, 7, 4, 9984, 9728, 33071, 33071},
            {50, 30, 1, 1, 9729, 9729, 33069, 33071}, {33, 17, 5, 4 | 256, 9985, 9729, 33648, 10497},
            {16, 40, 6, 1, 9986, 9728, 33071, 33648}, {29, 29, 5, 4, 9728, 9728, 33069, 33069},
            {7, 5, 3, 4 | 256, 9987, 9728, 10497, 33071}, {80, 12, 7, 1, 9987, 9729, 33648, 33648}};
        int[][] swz = {{0, 1, 2, 3}, {2, 1, 0, 3}, {0, 0, 0, 5}, {0, 1, 2, 3}, {0, 4, 4, 5}, {3, 2, 1, 0}, {1, 1, 1, 3}, {0, 0, 0, 0}};
        int bad = 0;
        double worst = 0;
        int fbo = glGenFramebuffers(), rt = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, rt);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA32F, W, H, 0, GL_RGBA, GL_FLOAT, (FloatBuffer) null);
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, rt, 0);
        glViewport(0, 0, W, H);
        glBindVertexArray(glGenVertexArrays());
        for (int mode = 0; mode < 3; mode++) {
            for (int s = 0; s < N; s++) {
                FloatBuffer ro = render(pOld, cfg, swz, s, mode), rn = render(pNew, cfg, swz, s, mode);
                double w = 0;
                int diff = 0;
                for (int i = 0; i < ro.capacity(); i++) {
                    double d = Math.abs(ro.get(i) - rn.get(i));
                    if (Double.isNaN(ro.get(i)) != Double.isNaN(rn.get(i))) d = 1;
                    w = Math.max(w, Double.isNaN(d) ? 0 : d);
                    if (d > 1e-4) diff++;
                }
                worst = Math.max(worst, w);
                if (diff > 0) bad++;
                System.out.printf("modo %d config %d: pixeles distintos %d de %d (max dif %.1e)%n", mode, s, diff, ro.capacity(), w);
            }
        }
        System.out.println(bad == 0 ? "OK: emulacion nueva == original (max dif " + worst + ")" : "FALLAS en " + bad + " casos");
        glfwTerminate();
        System.exit(bad == 0 ? 0 : 1);
    }

    static FloatBuffer render(int p, int[][] cfg, int[][] swz, int s, int mode) {
        glUseProgram(p);
        glUniform1i(glGetUniformLocation(p, "uVpPages"), 0);
        glUniform1i(glGetUniformLocation(p, "uS"), s);
        glUniform1i(glGetUniformLocation(p, "uMode"), mode);
        int base = 0;
        for (int i = 0; i < N; i++) {
            int[] c = cfg[i];
            glUniform4i(glGetUniformLocation(p, "vpPageImage[" + i + "]"), c[0], c[1], base, c[2]);
            glUniform4i(glGetUniformLocation(p, "vpPageFilter[" + i + "]"), c[4], c[5], c[6], c[7]);
            glUniform4i(glGetUniformLocation(p, "vpPageSwizzle[" + i + "]"), swz[i][0], swz[i][1], swz[i][2], swz[i][3]);
            glUniform4f(glGetUniformLocation(p, "vpPageBorder[" + i + "]"), 0.25f * i, 0.5f, 1f - 0.1f * i, 0.75f);
            glUniform4f(glGetUniformLocation(p, "vpPageLod[" + i + "]"), (i % 3) * 0.5f, 3f + i, (i % 2) * 0.7f - 0.3f, 0f);
            glUniform1i(glGetUniformLocation(p, "vpPageComponents[" + i + "]"), c[3]);
            int w = c[0], h = c[1], comps = c[3] & 255;
            for (int l = 0; l < c[2]; l++) { base += (w * h * comps + 3) / 4; w = Math.max(w / 2, 1); h = Math.max(h / 2, 1); }
        }
        glDrawArrays(GL_TRIANGLES, 0, 3); // triangulo de pantalla completa sin vertices (abajo, VS)
        FloatBuffer out = BufferUtils.createFloatBuffer(W * H * 4);
        glReadPixels(0, 0, W, H, GL_RGBA, GL_FLOAT, out);
        return out;
    }

    static int program(String fs) {
        String vs = "#version 410\nvoid main(){ vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2); gl_Position=vec4(p*2.0-1.0,0.0,1.0); }\n";
        int p = glCreateProgram();
        for (int[] st : new int[][] {{GL_VERTEX_SHADER}, {GL_FRAGMENT_SHADER}}) {
            int sh = glCreateShader(st[0]);
            glShaderSource(sh, st[0] == GL_VERTEX_SHADER ? vs : fs);
            glCompileShader(sh);
            if (glGetShaderi(sh, GL_COMPILE_STATUS) == 0) throw new IllegalStateException(glGetShaderInfoLog(sh));
            glAttachShader(p, sh);
        }
        glLinkProgram(p);
        if (glGetProgrami(p, GL_LINK_STATUS) == 0) throw new IllegalStateException(glGetProgramInfoLog(p));
        return p;
    }
}
