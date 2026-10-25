package vpmac;

/**
 * StageSamplers (nuevo en 0.1.6.1) cuenta samplers por etapa con la "program interface query" de OpenGL 4.3.
 * Es solo un chequeo de diagnostico: en 4.1 decimos "0 recursos" y el chequeo no reporta nada.
 */
public final class Probe {
    private Probe() {}

    public static int glGetProgramInterfacei(int program, int programInterface, int pname) {
        return 0;
    }

    public static void glGetProgramResourceiv(int program, int programInterface, int index, int[] props,
                                              int[] length, int[] params) {
        if (length != null && length.length > 0) length[0] = 0;
        if (params != null) java.util.Arrays.fill(params, 0);
    }
}
