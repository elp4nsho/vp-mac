package vpmac;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import viewpoint.mac41.Pages41;
import viewpoint.mac41.Textures41;
import viewpoint.mac41.Units41;

/**
 * glTextureView (OpenGL 4.3) en 4.1. Viewpoint 0.1.6.1 guarda las cascadas del sol en un arreglo de profundidad
 * (sampler2DArrayShadow) y dibuja cada capa a traves de una "vista" 2D. Aqui cada vista es una textura 2D real:
 * Viewpoint dibuja y lee en ella igual que antes, y justo antes de que alguien use el arreglo se copian a sus
 * capas las vistas que cambiaron (blit de profundidad). Se sabe que una vista cambio cuando se enlaza para
 * dibujar un framebuffer que la tiene puesta, o cuando glCopyImageSubData escribe en ella.
 *
 * Todo lo que aqui toca OpenGL directo guarda y restaura el estado real, para no descuadrar el registro que
 * llevan las imitaciones de xiveboy (Units41/Pages41).
 */
public final class Views41 {
    private static final int GL_TEXTURE_2D = 0x0DE1, GL_TEXTURE_2D_ARRAY = 0x8C1A;
    private static final int GL_TEXTURE_BINDING_2D = 0x8069, GL_TEXTURE_BINDING_2D_ARRAY = 0x8C1D;
    private static final int GL_FRAMEBUFFER = 0x8D40, GL_READ_FRAMEBUFFER = 0x8CA8, GL_DRAW_FRAMEBUFFER = 0x8CA9;
    private static final int GL_READ_FRAMEBUFFER_BINDING = 0x8CAA, GL_DRAW_FRAMEBUFFER_BINDING = 0x8CA6;
    private static final int GL_DEPTH_ATTACHMENT = 0x8D00, GL_DEPTH_STENCIL_ATTACHMENT = 0x821A;
    private static final int GL_COLOR_ATTACHMENT0 = 0x8CE0;
    private static final int GL_SCISSOR_TEST = 0x0C11;

    private static final int MAX = 32;
    // vistas registradas (pocas: 3 cascadas); arreglos planos para no crear objetos por llamada
    private static final int[] view = new int[MAX], origin = new int[MAX], layer = new int[MAX];
    private static final int[] width = new int[MAX], height = new int[MAX], format = new int[MAX];
    private static final boolean[] dirty = new boolean[MAX];
    private static int views;
    // framebuffers que tienen una vista puesta: fbo -> indice de vista
    private static final int[] fbo = new int[MAX], fboView = new int[MAX];
    private static int fbos;
    private static int boundDraw, boundRead;
    private static int copyRead, copyDraw;
    private static long copies;
    private static boolean announced;

    private Views41() {}

    // ------------------------------------------------------------------ glTextureView
    public static void glTextureView(int texture, int target, int origtexture, int internalformat, int minlevel,
                                     int numlevels, int minlayer, int numlayers) {
        if (target != GL_TEXTURE_2D || minlevel != 0 || numlevels != 1 || numlayers != 1) {
            throw new IllegalStateException("[VPMac] glTextureView outside what is emulated: target=" + target
                    + " levels=" + minlevel + "+" + numlevels + " layers=" + minlayer + "+" + numlayers);
        }
        int prevArray = GL11.glGetInteger(GL_TEXTURE_BINDING_2D_ARRAY);
        int prev2d = GL11.glGetInteger(GL_TEXTURE_BINDING_2D);
        GL11.glBindTexture(GL_TEXTURE_2D_ARRAY, origtexture);
        int w = GL11.glGetTexLevelParameteri(GL_TEXTURE_2D_ARRAY, 0, GL11.GL_TEXTURE_WIDTH);
        int h = GL11.glGetTexLevelParameteri(GL_TEXTURE_2D_ARRAY, 0, GL11.GL_TEXTURE_HEIGHT);
        GL11.glBindTexture(GL_TEXTURE_2D_ARRAY, prevArray);

        int[] ext = external(internalformat);
        GL11.glBindTexture(GL_TEXTURE_2D, texture);
        GL11.glTexImage2D(GL_TEXTURE_2D, 0, internalformat, w, h, 0, ext[0], ext[1], (ByteBuffer) null);
        GL11.glTexParameteri(GL_TEXTURE_2D, GL30.GL_TEXTURE_MAX_LEVEL, 0);
        GL11.glTexParameteri(GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, 0x812F);
        GL11.glTexParameteri(GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, 0x812F);
        GL11.glBindTexture(GL_TEXTURE_2D, prev2d);

        forgetView(texture);
        if (views == MAX) throw new IllegalStateException("[VPMac] too many texture views");
        int i = views++;
        view[i] = texture; origin[i] = origtexture; layer[i] = minlayer;
        width[i] = w; height[i] = h; format[i] = internalformat; dirty[i] = false;
        if (!announced) {
            announced = true;
            Log.info("texture views emulated (sun shadow cascades): " + w + "x" + h + " format 0x"
                    + Integer.toHexString(internalformat));
        }
    }

    /** formato/tipo externos para reservar una textura 2D con el mismo formato interno */
    private static int[] external(int internal) {
        switch (internal) {
            case 0x81A5: case 0x81A6: case 0x81A7: case 0x1902: // DEPTH_COMPONENT16/24/32, DEPTH_COMPONENT
                return new int[] {0x1902, 0x1405};               // DEPTH_COMPONENT, UNSIGNED_INT
            case 0x8CAC:                                         // DEPTH_COMPONENT32F
                return new int[] {0x1902, 0x1406};
            case 0x88F0:                                         // DEPTH24_STENCIL8
                return new int[] {0x84F9, 0x84FA};               // DEPTH_STENCIL, UNSIGNED_INT_24_8
            case 0x8CAD:                                         // DEPTH32F_STENCIL8
                return new int[] {0x84F9, 0x8DAD};
            default:
                return new int[] {0x1908, 0x1401};               // RGBA, UNSIGNED_BYTE
        }
    }

    private static boolean isDepth(int internal) {
        return external(internal)[0] != 0x1908;
    }

    private static int viewIndex(int texture) {
        if (texture == 0) return -1;
        for (int i = 0; i < views; i++) if (view[i] == texture) return i;
        return -1;
    }

    private static boolean isOrigin(int texture) {
        if (texture == 0) return false;
        for (int i = 0; i < views; i++) if (origin[i] == texture) return true;
        return false;
    }

    private static void forgetView(int texture) {
        for (int i = 0; i < views; i++) {
            if (view[i] == texture || origin[i] == texture) {
                for (int f = 0; f < fbos; f++) {
                    if (fboView[f] == i) { fbo[f] = fbo[fbos - 1]; fboView[f] = fboView[fbos - 1]; fbos--; f--; }
                    else if (fboView[f] == views - 1) fboView[f] = i;
                }
                int last = --views;
                view[i] = view[last]; origin[i] = origin[last]; layer[i] = layer[last];
                width[i] = width[last]; height[i] = height[last]; format[i] = format[last]; dirty[i] = dirty[last];
                i--;
            }
        }
    }

    private static void markFbo(int framebuffer) {
        if (framebuffer == 0) return;
        for (int f = 0; f < fbos; f++) if (fbo[f] == framebuffer) dirty[fboView[f]] = true;
    }

    /** copia a las capas del arreglo las vistas que cambiaron */
    private static void flush(int arrayTexture) {
        boolean any = false;
        for (int i = 0; i < views; i++) if (origin[i] == arrayTexture && dirty[i]) { any = true; break; }
        if (!any) return;
        int prevRead = GL11.glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        int prevDraw = GL11.glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        boolean scissor = GL11.glIsEnabled(GL_SCISSOR_TEST);
        if (scissor) GL11.glDisable(GL_SCISSOR_TEST);
        if (copyRead == 0) { copyRead = GL30.glGenFramebuffers(); copyDraw = GL30.glGenFramebuffers(); }
        for (int i = 0; i < views; i++) {
            if (origin[i] != arrayTexture || !dirty[i]) continue;
            boolean depth = isDepth(format[i]);
            int attachment = !depth ? GL_COLOR_ATTACHMENT0
                    : (format[i] == 0x88F0 || format[i] == 0x8CAD) ? GL_DEPTH_STENCIL_ATTACHMENT : GL_DEPTH_ATTACHMENT;
            int mask = !depth ? GL11.GL_COLOR_BUFFER_BIT
                    : attachment == GL_DEPTH_STENCIL_ATTACHMENT ? (GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_STENCIL_BUFFER_BIT)
                    : GL11.GL_DEPTH_BUFFER_BIT;
            GL30.glBindFramebuffer(GL_READ_FRAMEBUFFER, copyRead);
            GL30.glFramebufferTexture2D(GL_READ_FRAMEBUFFER, attachment, GL_TEXTURE_2D, view[i], 0);
            GL11.glReadBuffer(depth ? GL11.GL_NONE : GL_COLOR_ATTACHMENT0);
            GL30.glBindFramebuffer(GL_DRAW_FRAMEBUFFER, copyDraw);
            GL30.glFramebufferTextureLayer(GL_DRAW_FRAMEBUFFER, attachment, arrayTexture, 0, layer[i]);
            GL11.glDrawBuffer(depth ? GL11.GL_NONE : GL_COLOR_ATTACHMENT0);
            GL30.glBlitFramebuffer(0, 0, width[i], height[i], 0, 0, width[i], height[i], mask, GL11.GL_NEAREST);
            dirty[i] = false;
            copies++;
        }
        // soltar las texturas para no dejar referencias que impidan borrarlas
        GL30.glBindFramebuffer(GL_READ_FRAMEBUFFER, copyRead);
        GL30.glFramebufferTexture2D(GL_READ_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, 0, 0);
        GL30.glFramebufferTexture2D(GL_READ_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_TEXTURE_2D, 0, 0);
        GL30.glFramebufferTexture2D(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, 0, 0);
        GL30.glBindFramebuffer(GL_DRAW_FRAMEBUFFER, copyDraw);
        GL30.glFramebufferTextureLayer(GL_DRAW_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, 0, 0, 0);
        GL30.glFramebufferTextureLayer(GL_DRAW_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, 0, 0, 0);
        GL30.glFramebufferTextureLayer(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, 0, 0, 0);
        GL30.glBindFramebuffer(GL_READ_FRAMEBUFFER, prevRead);
        GL30.glBindFramebuffer(GL_DRAW_FRAMEBUFFER, prevDraw);
        if (scissor) GL11.glEnable(GL_SCISSOR_TEST);
    }

    public static long copies() {
        return copies;
    }

    // ------------------------------------------------------------------ envoltorios (delegan en xiveboy)
    public static void glBindFramebuffer(int target, int framebuffer) {
        Pages41.glBindFramebuffer(target, framebuffer);
        if (target == GL_FRAMEBUFFER || target == GL_DRAW_FRAMEBUFFER) { boundDraw = framebuffer; markFbo(framebuffer); }
        if (target == GL_FRAMEBUFFER || target == GL_READ_FRAMEBUFFER) boundRead = framebuffer;
    }

    public static void glFramebufferTexture2D(int target, int attachment, int textarget, int texture, int level) {
        Pages41.glFramebufferTexture2D(target, attachment, textarget, texture, level);
        int framebuffer = target == GL_READ_FRAMEBUFFER ? boundRead : boundDraw;
        if (framebuffer == 0) return;
        int v = viewIndex(texture);
        for (int f = 0; f < fbos; f++) {
            if (fbo[f] == framebuffer) { fbo[f] = fbo[fbos - 1]; fboView[f] = fboView[fbos - 1]; fbos--; f--; }
        }
        if (v >= 0 && fbos < MAX) { fbo[fbos] = framebuffer; fboView[fbos] = v; fbos++; }
    }

    public static void glBindTexture(int target, int texture) {
        if (views > 0 && target == GL_TEXTURE_2D_ARRAY && isOrigin(texture)) flush(texture);
        Units41.glBindTexture(target, texture);
    }

    public static void glCopyImageSubData(int srcName, int srcTarget, int srcLevel, int srcX, int srcY, int srcZ,
                                          int dstName, int dstTarget, int dstLevel, int dstX, int dstY, int dstZ,
                                          int srcWidth, int srcHeight, int srcDepth) {
        if (views > 0 && isOrigin(srcName)) flush(srcName);
        Textures41.glCopyImageSubData(srcName, srcTarget, srcLevel, srcX, srcY, srcZ, dstName, dstTarget, dstLevel,
                dstX, dstY, dstZ, srcWidth, srcHeight, srcDepth);
        int v = viewIndex(dstName);
        if (v >= 0) dirty[v] = true;
    }

    public static void glDeleteTextures(int texture) {
        if (views > 0) forgetView(texture);
        Textures41.glDeleteTextures(texture);
    }

    public static void glDeleteTextures(int[] textures) {
        if (views > 0) for (int t : textures) forgetView(t);
        Textures41.glDeleteTextures(textures);
    }

    public static void glDeleteTextures(IntBuffer textures) {
        if (views > 0) for (int i = textures.position(); i < textures.limit(); i++) forgetView(textures.get(i));
        Textures41.glDeleteTextures(textures);
    }
}
