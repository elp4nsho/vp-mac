package vpmac;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Estado por hilo de FastUnits41 (ver ahi). Publico: FastUnits41 se renombra a otro paquete al cargarse. */
public final class UnitsState {
    /** casilleros por tipo de textura: 0..5 fijos, 6..31 para otros tipos que aparezcan (se asignan al vuelo) */
    public static final int SLOTS = 32;
    public static final int RECORD_MASK = 4095;
    private static final int[] TARGETS = new int[SLOTS];
    private static int targets;

    static {
        for (int t : new int[] {3553, 32879, 34067, 35866, 35882, 3552}) TARGETS[targets++] = t;
    }

    public int active;
    public final int[][] bindTex = new int[256][SLOTS];
    public final int[] bindMask = new int[256];
    public final int[] bindSampler = new int[256];
    public final int[][] liveTex = new int[80][SLOTS];
    public final int[] liveMask = new int[80];
    public final int[] liveSampler = new int[80];
    public final Map<Integer, UnitsInput[]> programs = new HashMap<>();
    public final Map<Integer, Map<String, Integer>> names = new HashMap<>();
    public final Map<Integer, Set<String>> virtualNames = new HashMap<>();
    public final int[] dummy = new int[SLOTS];
    public final List<Integer> dummyTextures = new ArrayList<>(), dummyBuffers = new ArrayList<>();
    /** texturas ya informadas a Textures41.recordTarget (cache directo por id) */
    public final int[] recorded = new int[RECORD_MASK + 1];
    private int lastProgram = Integer.MIN_VALUE;
    private UnitsInput[] lastInputs;

    /** casillero del tipo de textura (los nuevos se asignan una vez, para todos los hilos) */
    public static int slot(int target) {
        for (int i = 0; i < targets; i++) if (TARGETS[i] == target) return i;
        return newSlot(target);
    }

    private static synchronized int newSlot(int target) {
        for (int i = 0; i < targets; i++) if (TARGETS[i] == target) return i;
        if (targets == SLOTS) throw new IllegalStateException("[VPMac] too many texture types");
        TARGETS[targets] = target;
        return targets++;
    }

    public static int target(int slot) {
        return TARGETS[slot];
    }

    public void bind(int unit, int slot, int tex) {
        bindTex[unit][slot] = tex;
        bindMask[unit] |= 1 << slot;
    }

    public void live(int physical, int slot, int tex) {
        liveTex[physical][slot] = tex;
        liveMask[physical] |= 1 << slot;
    }

    /** programs.get con cache del ultimo programa (beforeDraw lo pide en cada lote) */
    public UnitsInput[] inputs(int program) {
        if (program == lastProgram) return lastInputs;
        UnitsInput[] in = programs.get(program);
        lastProgram = program;
        lastInputs = in;
        return in;
    }

    public void forget(int program) {
        if (program == lastProgram) { lastProgram = Integer.MIN_VALUE; lastInputs = null; }
    }
}
