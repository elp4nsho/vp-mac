package vpmac;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL33;
import viewpoint.mac41.Mac41;
import viewpoint.mac41.ModelTextureProbe;
import viewpoint.mac41.Ranges41;
import viewpoint.mac41.Textures41;
import zombie.core.textures.Texture;

/**
 * Units41 de xiveboy (unidades de textura virtuales: 256 logicas sobre las 16 reales del Mac), misma API y misma
 * logica, pero sin HashMap&lt;Integer,Integer&gt; en el camino caliente (beforeDraw / glBindTexture corren miles de
 * veces por cuadro): cada unidad guarda un arreglo de enteros por tipo de textura (2D, 3D, cubo, arreglo 2D,
 * buffer, 1D, y casilleros extra para otros) con un bit de "presente" para imitar las llaves del mapa.
 *
 * Config.FAST_UNITS: Transformer entrega esta clase renombrada a viewpoint/mac41/Units41 cuando la JVM pide esa
 * clase (todas las llamadas de las imitaciones y de VPMac llegan aqui sin cambiar ningun sitio de llamada). Por eso
 * no tiene clases anidadas, lambdas ni instancias de si misma: el estado vive en UnitsState / UnitsInput.
 */
public final class FastUnits41 {
    private static final boolean TRACE_MODELS = Boolean.getBoolean("viewpoint.mac41.traceModelTextures");
    private static final ThreadLocal<UnitsState> LOCAL = new ThreadLocal<>();
    private static volatile Thread fastThread;
    private static UnitsState fastState;

    private FastUnits41() {}

    // ------------------------------------------------------------------ estado por hilo (camino rapido: render)
    private static UnitsState state() {
        if (Thread.currentThread() == fastThread) return fastState;
        UnitsState s = LOCAL.get();
        if (s == null) { s = new UnitsState(); LOCAL.set(s); }
        if (fastThread == null) claim(s);
        return s;
    }

    private static synchronized void claim(UnitsState s) {
        if (fastThread != null) return;
        try {
            org.lwjgl.opengl.GL.getCapabilities(); // solo el hilo con contexto OpenGL (el de render)
        } catch (Throwable e) {
            return;
        }
        fastState = s;
        fastThread = Thread.currentThread();
    }

    // ------------------------------------------------------------------ API de Units41
    public static int physical(int n) {
        return Mac41.active() ? 79 : n;
    }

    public static void glActiveTexture(int n) {
        if (!Mac41.active()) {
            GL13.glActiveTexture(n);
            return;
        }
        int unit = n - 33984;
        if (unit < 0 || unit >= 256) throw new IllegalArgumentException("Logical texture unit " + unit);
        UnitsState s = state();
        s.active = unit;
        GL13.glActiveTexture(34063);
        int mask = s.bindMask[unit];
        for (int slot = 0; mask != 0; slot++, mask >>>= 1) {
            if ((mask & 1) == 0) continue;
            int tex = s.bindTex[unit][slot];
            GL11.glBindTexture(UnitsState.target(slot), tex);
            s.live(79, slot, tex);
        }
        GL33.glBindSampler(79, s.bindSampler[unit]);
        s.liveSampler[79] = s.bindSampler[unit];
    }

    public static void glBindSampler(int n, int sampler) {
        if (!Mac41.active()) {
            GL33.glBindSampler(n, sampler);
            return;
        }
        UnitsState s = state();
        s.bindSampler[n] = sampler;
        GL33.glBindSampler(79, sampler);
        s.liveSampler[79] = sampler;
    }

    public static void glBindTexture(int target, int tex) {
        GL11.glBindTexture(target, tex);
        if (!Mac41.active()) return;
        UnitsState s = state();
        record(s, tex, target);
        int slot = UnitsState.slot(target);
        s.bind(s.active, slot, tex);
        s.live(79, slot, tex);
        if (target == 35882) Ranges41.bound(s.active, tex);
    }

    public static void bindHostTexture(Texture texture) {
        texture.bind();
        if (Mac41.active()) {
            hostTextureBound(texture.getID());
            if (TRACE_MODELS) ModelTextureProbe.observe(active(), texture.getID());
        }
    }

    public static void hostTextureBound(int n) {
        if (!Mac41.active()) return;
        if (n <= 0) throw new IllegalStateException("Ready host texture has no native ID: " + n);
        UnitsState s = state();
        record(s, n, 3553);
        s.bind(s.active, 0, n);
        s.live(79, 0, n);
    }

    public static void resetHostSamplers() {
        if (!Mac41.active()) return;
        UnitsState s = state();
        for (int i = 0; i < 2; ++i) {
            GL33.glBindSampler(i, 0);
            s.liveSampler[i] = 0;
            s.liveMask[i] &= ~1; // live[i].remove(GL_TEXTURE_2D)
        }
    }

    public static int active() {
        return state().active;
    }

    public static void synchronize() {
        if (!Mac41.active()) return;
        UnitsState s = state();
        s.active = GL11.glGetInteger(34016) - 33984;
        Arrays.fill(s.liveMask, 0);
        Arrays.fill(s.liveSampler, -1);
    }

    public static void linked(int program) {
        UnitsState s = state();
        s.virtualNames.remove(program);
        s.programs.remove(program);
        s.forget(program);
        if (GL20.glGetProgrami(program, 35714) == 0) return;
        ArrayList<UnitsInput> inputs = new ArrayList<>();
        IntBuffer size = BufferUtils.createIntBuffer(1), type = BufferUtils.createIntBuffer(1);
        int count = GL20.glGetProgrami(program, 35718);
        for (int u = 0; u < count; ++u) {
            size.clear();
            type.clear();
            String name = GL20.glGetActiveUniform(program, u, 512, size, type);
            int target = target(type.get(0));
            if (target == 0) continue;
            int n5 = size.get(0);
            for (int i = 0; i < n5; ++i) {
                String element = n5 > 1 ? name.replace("[0]", "[" + i + "]") : name;
                int location = GL20.glGetUniformLocation(program, element);
                if (location < 0) continue;
                if (inputs.size() >= 79) throw new IllegalStateException("Program needs too many texture units");
                UnitsInput input = new UnitsInput(location, target, inputs.size());
                if (element.equals("uVpCascade012")) input.logical = 200;
                if (element.equals("uVpPages")) input.logical = 203;
                if (element.equals("uVpRelief")) input.logical = 201;
                if (element.equals("uVpVehicleDamage")) input.logical = 202;
                inputs.add(input);
            }
        }
        UnitsInput[] array = inputs.toArray(new UnitsInput[0]);
        s.programs.put(program, array);
        int previous = GL11.glGetInteger(35725);
        GL20.glUseProgram(program);
        for (UnitsInput input : array) GL20.glUniform1i(input.location, input.physical);
        GL20.glUseProgram(previous);
    }

    private static int target(int type) {
        switch (type) {
            case 35678: case 35682: case 36293: case 36298: case 36306: return 3553;
            case 35679: case 36299: case 36307: return 32879;
            case 35680: case 35685: case 36300: case 36308: return 34067;
            case 36289: case 36292: case 36303: case 36311: return 35866;
            case 36290: case 36304: case 36312: return 35882;
            case 35677: case 35681: return 3552;
            default: return 0;
        }
    }

    public static void uniform(int program, int location, int value) {
        UnitsInput[] inputs = state().inputs(program);
        if (inputs != null) {
            for (UnitsInput input : inputs) {
                if (input.location != location) continue;
                input.logical = value;
                GL20.glUniform1i(location, input.physical);
                return;
            }
        }
        GL20.glUniform1i(location, value);
    }

    public static void beforeDraw(int program) {
        UnitsState s = state();
        UnitsInput[] inputs = s.inputs(program);
        if (inputs == null) return;
        int restore = physical(s.active);
        boolean switched = false;
        for (UnitsInput input : inputs) {
            int logical = input.logical;
            if (logical < 0 || logical >= 256) throw new IllegalStateException("Sampler unit " + logical);
            int slot = input.slot;
            int tex = (s.bindMask[logical] & (1 << slot)) != 0 ? s.bindTex[logical][slot] : 0;
            if (tex == 0) tex = dummy(s, input.target, slot);
            int p = input.physical;
            if ((s.liveMask[p] & (1 << slot)) == 0 || s.liveTex[p][slot] != tex) {
                GL13.glActiveTexture(33984 + p);
                switched = true;
                GL11.glBindTexture(input.target, tex);
                s.live(p, slot, tex);
            }
            int sampler = s.bindSampler[logical];
            if (s.liveSampler[p] == sampler) continue;
            GL33.glBindSampler(p, sampler);
            s.liveSampler[p] = sampler;
        }
        if (switched) GL13.glActiveTexture(33984 + restore);
    }

    private static int dummy(UnitsState s, int target, int slot) {
        int existing = s.dummy[slot];
        if (existing != 0) return existing;
        int previousUnit = GL11.glGetInteger(34016);
        GL13.glActiveTexture(34063);
        int tex = GL11.glGenTextures();
        GL11.glBindTexture(target, tex);
        if (target == 35882) {
            int previousArray = GL11.glGetInteger(34964);
            int buffer = GL15.glGenBuffers();
            GL15.glBindBuffer(34962, buffer);
            GL15.glBufferData(34962, 64L, 35044);
            GL31.glTexBuffer(target, 34836, buffer);
            GL15.glBindBuffer(34962, previousArray);
            s.dummyBuffers.add(buffer);
        } else {
            int previousUnpack = GL11.glGetInteger(35055);
            GL15.glBindBuffer(35052, 0);
            if (target == 35866 || target == 32879) {
                GL12.glTexImage3D(target, 0, 32856, 1, 1, 1, 0, 6408, 5121, (ByteBuffer) null);
            } else if (target == 3553) {
                GL11.glTexImage2D(target, 0, 32856, 1, 1, 0, 6408, 5121, (ByteBuffer) null);
            } else if (target == 34067) {
                for (int i = 0; i < 6; ++i) GL11.glTexImage2D(34069 + i, 0, 32856, 1, 1, 0, 6408, 5121, (ByteBuffer) null);
            } else {
                GL11.glTexImage1D(target, 0, 32856, 1, 0, 6408, 5121, (ByteBuffer) null);
            }
            GL11.glTexParameteri(target, 10241, 9728);
            GL11.glTexParameteri(target, 10240, 9728);
            GL15.glBindBuffer(35052, previousUnpack);
        }
        s.dummy[slot] = tex;
        s.dummyTextures.add(tex);
        s.live(79, slot, tex);
        GL13.glActiveTexture(previousUnit);
        return tex;
    }

    public static void namedSampler(int program, String name, int unit) {
        if (Mac41.active() && program != 0) s(program).put(name, unit);
    }

    public static void namedSamplers(int program, Map<String, Integer> map) {
        if (Mac41.active()) s(program).putAll(map);
    }

    private static Map<String, Integer> s(int program) {
        Map<Integer, Map<String, Integer>> names = state().names;
        Map<String, Integer> m = names.get(program);
        if (m == null) { m = new HashMap<>(); names.put(program, m); }
        return m;
    }

    public static void virtualSamplers(int program, String... names) {
        Map<Integer, Set<String>> all = state().virtualNames;
        Set<String> set = all.get(program);
        if (set == null) { set = new HashSet<>(); all.put(program, set); }
        set.addAll(Arrays.asList(names));
    }

    public static boolean hasVirtualSampler(int program, String name) {
        if (!Mac41.active()) return false;
        UnitsState s = state();
        if (s.virtualNames.isEmpty()) return false;
        Set<String> set = s.virtualNames.get(program);
        return set != null && set.contains(name);
    }

    public static int namedUnit(int program, String name) {
        Map<String, Integer> m = state().names.get(program);
        if (m == null) return -1;
        Integer unit = m.get(name);
        return unit == null ? -1 : unit;
    }

    public static int sampler(int n) {
        return n < 0 ? 0 : state().bindSampler[n];
    }

    public static int namedTexture(int program, String name, int target) {
        return texture(namedUnit(program, name), target);
    }

    public static int texture(int n, int target) {
        if (n < 0) return 0;
        UnitsState s = state();
        int slot = UnitsState.slot(target);
        return (s.bindMask[n] & (1 << slot)) != 0 ? s.bindTex[n][slot] : 0;
    }

    public static void invalidateNative() {
        UnitsState s = state();
        Arrays.fill(s.liveMask, 0);
        Arrays.fill(s.liveSampler, -1);
    }

    public static void setTexture(int n, int target, int tex) {
        UnitsState s = state();
        s.bind(n, UnitsState.slot(target), tex);
    }

    public static void deletedProgram(int program) {
        UnitsState s = state();
        s.programs.remove(program);
        s.names.remove(program);
        s.virtualNames.remove(program);
        s.forget(program);
    }

    public static void deletedTexture(int tex) {
        UnitsState s = state();
        // bindings: el valor pasa a 0 pero la llave queda (como replaceAll del original)
        for (int u = 0; u < 256; u++) {
            int mask = s.bindMask[u];
            for (int slot = 0; mask != 0; slot++, mask >>>= 1) {
                if ((mask & 1) != 0 && s.bindTex[u][slot] == tex) s.bindTex[u][slot] = 0;
            }
        }
        // estado real: se borra la entrada (como values().removeIf del original)
        for (int p = 0; p < 80; p++) {
            int mask = s.liveMask[p];
            for (int slot = 0; mask != 0; slot++, mask >>>= 1) {
                if ((mask & 1) != 0 && s.liveTex[p][slot] == tex) s.liveMask[p] &= ~(1 << slot);
            }
        }
        if (tex > 0 && s.recorded[tex & UnitsState.RECORD_MASK] == tex) s.recorded[tex & UnitsState.RECORD_MASK] = 0;
    }

    public static void release() {
        UnitsState s = state();
        for (int buffer : s.dummyBuffers) GL15.glDeleteBuffers(buffer);
        for (int tex : s.dummyTextures) GL11.glDeleteTextures(tex);
        LOCAL.remove();
        if (Thread.currentThread() == fastThread) {
            synchronized (FastUnits41.class) { fastThread = null; fastState = null; }
        }
    }

    /** Textures41.recordTarget usa putIfAbsent: repetirlo para la misma textura no cambia nada; se evita la llamada */
    private static void record(UnitsState s, int tex, int target) {
        if (tex == 0) return;
        int i = tex & UnitsState.RECORD_MASK;
        if (s.recorded[i] == tex) return;
        Textures41.recordTarget(tex, target);
        s.recorded[i] = tex;
    }
}
