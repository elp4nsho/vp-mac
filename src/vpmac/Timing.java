package vpmac;

import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;
import viewpoint.mac41.Timing41;

/**
 * Consultas de tiempo de GPU seguras para el Mac: su contador de timestamps tiene 0 bits, asi que las consultas
 * no sirven y esperar su resultado puede trabar. Sin timestamps no se emite nada y los resultados salen
 * "listos, 0 ns". Reemplaza a lo que xiveboy hacia saltando bloques dentro de Profile.mark/endFrame.
 */
public final class Timing {
    private static final int GL_QUERY_RESULT_AVAILABLE = 0x8867;

    private Timing() {}

    public static void glQueryCounter(int id, int target) {
        if (Timing41.timestampsSupported()) GL33.glQueryCounter(id, target);
    }

    public static int glGetQueryObjecti(int id, int pname) {
        if (!Timing41.timestampsSupported()) return pname == GL_QUERY_RESULT_AVAILABLE ? 1 : 0;
        return GL15.glGetQueryObjecti(id, pname);
    }

    public static long glGetQueryObjectui64(int id, int pname) {
        if (!Timing41.timestampsSupported()) return 0L;
        return GL33.glGetQueryObjectui64(id, pname);
    }
}
