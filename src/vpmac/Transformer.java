package vpmac;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Reescribe las clases de Viewpoint al cargarlas:
 *  1. reemplazo general: llamadas a OpenGL 4.2+ (y las que el Mac maneja distinto) -> imitaciones 4.1;
 *  2. los ajustes puntuales que xiveboy hizo a mano en su 0.1.6, buscados por clase y metodo.
 * Si un metodo esperado no aparece (Viewpoint cambio), se avisa en console.txt en vez de fallar callado.
 */
final class Transformer implements ClassFileTransformer, Opcodes {
    private static final String U41 = Rules.M41 + "Units41", E41 = Rules.M41 + "External41";
    private static final String T41 = Rules.M41 + "Timing41", S41 = Rules.M41 + "State41";
    private static final String MAC41 = Rules.M41 + "Mac41", D41 = Rules.M41 + "Draws41", R41 = Rules.M41 + "Ranges41";
    private static final String TIMING = "vpmac/Timing", PROBE = "vpmac/Probe";

    private int classes, calls;

    @Override
    public byte[] transform(ClassLoader loader, String name, Class<?> redefined, ProtectionDomain pd, byte[] bytes) {
        if (name == null || !name.startsWith("viewpoint/")) return null;
        if (Config.FAST_UNITS && name.equals(Rules.M41 + "Units41")) return replacement("vpmac/FastUnits41", name);
        try {
            ClassReader cr = new ClassReader(bytes);
            ClassNode cn = new ClassNode();
            cr.accept(cn, ClassReader.EXPAND_FRAMES);
            int n = rewrite(cn);
            if (n == 0) return null;
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cn.accept(cw);
            synchronized (this) {
                classes++;
                calls += n;
                if (name.equals("viewpoint/platform/Gl")) {
                    Log.info("Viewpoint version: " + viewpointVersion(loader));
                    Log.info("=== VPMac " + Agent.VERSION + " active: Viewpoint " + viewpointVersion(loader) + " is being patched for "
                            + "Mac OpenGL 4.1 | optimizations " + (Config.BATCH_DRAWS && Config.FAST_UNITS ? "on" : "partly off")
                            + " | crash reports " + (Config.REPORT ? "on (~/Zomboid/vpmac/reports/)" : "off")
                            + (Config.DIAG ? " | DEBUG mode" : "") + " ===");
                    Log.info("Viewpoint hooked (" + classes + " classes and " + calls + " changes so far; the rest as they load)");
                }
            }
            return cw.toByteArray();
        } catch (Throwable t) {
            Log.error("could not rewrite " + name + " (left as is: it may crash on Mac)", t);
            return null;
        }
    }

    /**
     * Clase de VPMac entregada en lugar de una de xiveboy: se renombra `from` -> `to` (solo cambian el nombre y los
     * duenos de sus propias llamadas/campos; por eso la reemplazante no tiene clases anidadas, lambdas ni instancias).
     */
    private static byte[] replacement(String from, String to) {
        try (java.io.InputStream in = Transformer.class.getResourceAsStream("/" + from + ".class")) {
            ClassNode cn = new ClassNode();
            new ClassReader(in.readAllBytes()).accept(cn, 0);
            cn.name = to;
            cn.sourceFile = null;
            for (MethodNode m : cn.methods) {
                for (AbstractInsnNode in2 = m.instructions.getFirst(); in2 != null; in2 = in2.getNext()) {
                    if (in2 instanceof MethodInsnNode mi && mi.owner.equals(from)) mi.owner = to;
                    else if (in2 instanceof FieldInsnNode fi && fi.owner.equals(from)) fi.owner = to;
                    else if (in2 instanceof org.objectweb.asm.tree.TypeInsnNode ti && ti.desc.equals(from)) ti.desc = to;
                    else if (in2 instanceof LdcInsnNode li && li.cst instanceof Type t && t.getInternalName().equals(from)) li.cst = Type.getObjectType(to);
                }
            }
            ClassWriter cw = new ClassWriter(0);
            cn.accept(cw);
            Log.info(to.substring(to.lastIndexOf('/') + 1) + " replaced by " + from.substring(from.lastIndexOf('/') + 1));
            return cw.toByteArray();
        } catch (Throwable t) {
            Log.error("could not replace " + to + " (keeping xiveboy's)", t);
            return null;
        }
    }

    /** version del Viewpoint.jar cargado (viewpoint/build.properties), para el log y los informes */
    private static String viewpointVersion(ClassLoader loader) {
        try (java.io.InputStream in = (loader != null ? loader : ClassLoader.getSystemClassLoader())
                .getResourceAsStream("viewpoint/build.properties")) {
            java.util.Properties p = new java.util.Properties();
            p.load(in);
            return p.getProperty("version", "?") + " (" + p.getProperty("date", "?") + ")";
        } catch (Throwable t) {
            return "?";
        }
    }

    // ------------------------------------------------------------------ reglas
    private int rewrite(ClassNode cn) {
        String cls = cn.name;
        int n = 0;
        // imitaciones de xiveboy: no se reescriben, solo el aviso de diagnostico antes de sus dibujos reales
        if (cls.startsWith(Rules.M41) && !cls.equals(Rules.M41 + "Shaders41")) {
            if (cls.equals(D41) && Config.BATCH_DRAWS) n += batchDraws(cn);
            if (cls.equals(Rules.M41 + "Pages41") && Config.SKIP_MARKS) n += skipMarks(cn);
            if (cls.equals(Rules.M41 + "Pages41") && Config.GPU_PACK) {
                MethodNode pack = need(cn, "pack", "(Lviewpoint/mac41/Pages41$State;Lviewpoint/mac41/Pages41$Program;Z)V");
                if (pack != null) n += retarget(pack, "org/lwjgl/opengl/GL11", "glGetTexImage", "(IIIIJ)V", "vpmac/GpuPack", "glGetTexImage");
            }
            if (Config.TL_CACHE) n += threadLocals(cn); // al final: cubre tambien los get que insertan las reglas
            return n + (Config.DIAG ? drawHooks(cn) : 0);
        }
        if (!Rules.skipGeneral(cls)) n += general(cn);
        switch (cls) {
            case "viewpoint/platform/Gl": n += gl(cn); break;
            case "viewpoint/platform/GlProgram": n += glProgram(cn); break;
            case "viewpoint/platform/GpuParts": n += gpuParts(cn); break;
            case "viewpoint/platform/Profile":
                n += profile(cn);
                if (Config.VP_STATS_SECONDS > 0) n += statsInterval(cn);
                break;
            case "viewpoint/SceneDrawer": n += sceneDrawer(cn); break;
            case "viewpoint/render/FrameStream": n += frameStream(cn); break;
            case "viewpoint/render/ModelPass": n += modelPass(cn); n += hostTextures(cn); break;
            case "viewpoint/render/VehiclePass": n += hostTextures(cn); break;
            case "viewpoint/render/IrisPipeline": n += irisPipeline(cn); break;
            case "viewpoint/platform/StageSamplers": n += stageSamplers(cn); break;
            case "viewpoint/mac41/Shaders41": {
                MethodNode src = need(cn, "glShaderSource", "(ILjava/lang/CharSequence;)V");
                n += retargetGl(src, "glShaderSource", "(ILjava/lang/CharSequence;)V", "vpmac/Glsl", "glShaderSource");
                if (Config.SMART_PAGES && src != null) {
                    n += retarget(src, Rules.M41 + "Pages41", "lower", "(ILjava/lang/String;)Ljava/lang/String;", "vpmac/Accel", "pagesLower");
                }
                break;
            }
            // Shaders41 baja en el GLSL ATLAS_SLOTS 8->4 y MODEL_TEXTURES 16->6 (16 unidades de textura en el Mac);
            // javac copio esas constantes como numeros sueltos: hay que bajarlas igual en el bytecode
            case "viewpoint/FP":
                if (Config.BENCH > 0) {
                    MethodNode rw = need(cn, "renderWorld", "(Lzombie/iso/IsoCell;)Z");
                    if (rw != null) { rw.instructions.insert(call("vpmac/Bench", "tick", "()V")); n++; }
                }
                break;
            case "viewpoint/platform/FrameTimes": if (!Config.GPU_TIMING) n += noGpuTiming(cn); break;
            case "viewpoint/render/MousePick": if (Config.PICK_EVERY > 1) n += throttlePick(cn); break;
            case "viewpoint/render/AtlasGroups":
                n += constant(cn, "samplers", "(Lviewpoint/platform/GlProgram;)V", 8, 4, 1);
                n += constant(cn, "size", "(Lviewpoint/platform/GlProgram;)I", 8, 4, 1);
                break;
            case "viewpoint/render/ModelBatches": n += constant(cn, "slot", "(I)I", 16, 6, 1); break;
            default: break;
        }
        return n;
    }

    /** reemplazo general por tabla (+ aviso de diagnostico antes de cada dibujo) */
    private static int general(ClassNode cn) {
        int n = 0;
        for (MethodNode m : cn.methods) {
            for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
                if (in.getOpcode() != INVOKESTATIC) continue;
                MethodInsnNode mi = (MethodInsnNode) in;
                // dibujo que va directo a OpenGL: aviso aqui (los que van a una imitacion se avisan adentro de ella)
                if (Config.DIAG && isDraw(mi) && Rules.redirect(mi.name, mi.desc) == null) {
                    m.instructions.insertBefore(mi, call("vpmac/Diag", "draw", "()V"));
                }
                if (!mi.owner.startsWith(Rules.GL_PREFIX)) continue;
                String to = Rules.redirect(mi.name, mi.desc);
                if (to == null) continue;
                mi.owner = to;
                mi.itf = false;
                n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ aceleradores (Accel)
    /** Draws41: estado revisado una vez por multi-draw (submit) y no en cada comando (arrays/elements) */
    private int batchDraws(ClassNode cn) {
        MethodNode submit = need(cn, "submit", "(IIJIII)V");
        MethodNode arrays = need(cn, "arrays", "(IIIIJIZ)V");
        MethodNode elements = need(cn, "elements", "(IIIJIIII)V");
        if (submit == null || arrays == null || elements == null) return 0;
        // las tres piezas van juntas: si falta alguna, no se toca nada (mejor lento que mal dibujado)
        AbstractInsnNode snap = null, draw = null, extra = null;
        for (AbstractInsnNode in : list(submit)) if (isCall(in, D41, "snapshotArray", "()I")) { snap = in; break; }
        for (AbstractInsnNode in : list(arrays)) if (isCall(in, D41, "glDrawArraysInstanced", "(IIII)V")) draw = in;
        boolean afterRebase = false;
        for (AbstractInsnNode in : list(elements)) {
            if (isCall(in, D41, "rebase", "(J)V")) afterRebase = true;
            else if (afterRebase && isCall(in, Rules.M41 + "Ranges41", "beforeDraw", "()V")) { extra = in; break; }
        }
        if (snap == null || draw == null || extra == null) {
            Log.warn("Draws41 changed: 'batchDraws' not applied (snapshot=" + (snap != null) + " draw=" + (draw != null)
                    + " elements=" + (extra != null) + ")");
            return 0;
        }
        submit.instructions.insertBefore(snap, call(Rules.M41 + "Ranges41", "beforeDraw", "()V"));
        arrays.instructions.insertBefore(draw, new VarInsnNode(ILOAD, 7)); // el boolean "viene de un lote"
        arrays.instructions.set(draw, call("vpmac/Accel", "drawArraysInstanced", "(IIIIZ)V"));
        elements.instructions.remove(extra);
        return 3;
    }

    /**
     * Pages41.markDraw (en cada dibujo): lleva versiones de las texturas del framebuffer para re-empaquetar paginas.
     * Si no hay ningun programa empaquetado (con smartPages es lo normal) no sirve: sale al tiro. Un programa que
     * se empaquete despues hace su primer empaquetado completo igual (layoutDirty), asi que no se pierde nada.
     */
    private int skipMarks(ClassNode cn) {
        MethodNode mark = need(cn, "markDraw", "()V");
        if (mark == null) return 0;
        String state = cn.name + "$State";
        LabelNode go = new LabelNode();
        InsnList g = new InsnList();
        g.add(new FieldInsnNode(GETSTATIC, cn.name, "LOCAL", "Ljava/lang/ThreadLocal;"));
        g.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/ThreadLocal", "get", "()Ljava/lang/Object;", false));
        g.add(new org.objectweb.asm.tree.TypeInsnNode(CHECKCAST, state));
        g.add(new FieldInsnNode(GETFIELD, state, "programs", "Ljava/util/Map;"));
        g.add(new MethodInsnNode(INVOKEINTERFACE, "java/util/Map", "isEmpty", "()Z", true));
        g.add(new JumpInsnNode(IFEQ, go));
        g.add(new InsnNode(RETURN));
        return guard(cn, mark, g, go);
    }

    /** imitaciones: ThreadLocal.get/set/remove -> TLCache (mismo orden en la pila: el ThreadLocal pasa a ser argumento) */
    private static int threadLocals(ClassNode cn) {
        int n = 0;
        for (MethodNode m : cn.methods) {
            for (AbstractInsnNode in : list(m)) {
                if (in.getOpcode() != INVOKEVIRTUAL) continue;
                MethodInsnNode mi = (MethodInsnNode) in;
                if (!mi.owner.equals("java/lang/ThreadLocal")) continue;
                String desc = switch (mi.name + mi.desc) {
                    case "get()Ljava/lang/Object;" -> "(Ljava/lang/ThreadLocal;)Ljava/lang/Object;";
                    case "set(Ljava/lang/Object;)V" -> "(Ljava/lang/ThreadLocal;Ljava/lang/Object;)V";
                    case "remove()V" -> "(Ljava/lang/ThreadLocal;)V";
                    default -> null;
                };
                if (desc == null) continue;
                m.instructions.set(mi, new MethodInsnNode(INVOKESTATIC, "vpmac/TLCache", mi.name, desc, false));
                n++;
            }
        }
        return n;
    }

    /** Profile: la linea de estadisticas (~4 KB, decenas de contadores) cada Config.VP_STATS_SECONDS en vez de 10 s */
    private int statsInterval(ClassNode cn) {
        int n = 0;
        for (MethodNode m : cn.methods) {
            boolean callsLog = false;
            for (AbstractInsnNode in : list(m)) callsLog |= isCall(in, cn.name, "log", "(J)V");
            if (!callsLog) continue;
            for (AbstractInsnNode in : list(m)) {
                if (in instanceof LdcInsnNode ldc && Long.valueOf(10_000_000_000L).equals(ldc.cst)) {
                    ldc.cst = Config.VP_STATS_SECONDS * 1_000_000_000L;
                    n++;
                }
            }
        }
        if (n != 1) Log.warn("Profile changed: stats interval patched " + n + " times (expected 1)");
        return n;
    }

    /** FrameTimes: sin sellos de tiempo de GPU (prepare deja gpuTimed=false y devuelve false) */
    private int noGpuTiming(ClassNode cn) {
        MethodNode prepare = need(cn, "prepare", "()Z");
        if (prepare == null) return 0;
        InsnList g = new InsnList();
        g.add(new FieldInsnNode(GETSTATIC, "java/lang/Boolean", "FALSE", "Ljava/lang/Boolean;"));
        g.add(new FieldInsnNode(PUTSTATIC, cn.name, "gpuTimed", "Ljava/lang/Boolean;"));
        g.add(new InsnNode(ICONST_0));
        g.add(new InsnNode(IRETURN));
        // cuerpo reemplazado entero: un return al comienzo dejaria codigo muerto sin stack map frames
        prepare.instructions.clear();
        prepare.tryCatchBlocks.clear();
        prepare.localVariables = null;
        prepare.instructions.add(g);
        return 1;
    }

    /** MousePick.read: solo cada Config.PICK_EVERY cuadros */
    private int throttlePick(ClassNode cn) {
        MethodNode read = need(cn, "read", "(Lviewpoint/render/FrameContext;ILorg/joml/Matrix4f;)V");
        if (read == null) return 0;
        LabelNode go = new LabelNode();
        InsnList g = new InsnList();
        g.add(call("vpmac/Accel", "skipPick", "()Z"));
        g.add(new JumpInsnNode(IFEQ, go));
        g.add(new InsnNode(RETURN));
        return guard(cn, read, g, go);
    }

    private static boolean isDraw(MethodInsnNode mi) {
        return mi.owner.startsWith(Rules.GL_PREFIX) && (mi.name.startsWith("glDraw") || mi.name.startsWith("glMultiDraw"));
    }

    /** aviso de diagnostico antes de cada dibujo OpenGL real (el estado ya quedo armado) */
    private static int drawHooks(ClassNode cn) {
        int n = 0;
        boolean pages = cn.name.equals(Rules.M41 + "Pages41");
        for (MethodNode m : cn.methods) {
            for (AbstractInsnNode in : list(m)) {
                if (in.getOpcode() == INVOKESTATIC && isDraw((MethodInsnNode) in)) {
                    m.instructions.insertBefore(in, call("vpmac/Diag", "draw", "()V"));
                    n++;
                } else if (pages && in.getOpcode() == INVOKESTATIC && ((MethodInsnNode) in).name.equals("glGetTexImage")) {
                    m.instructions.insertBefore(in, call("vpmac/Diag", "pageRead", "()V"));
                    n++;
                }
            }
        }
        return n;
    }

    // Gl: arranque, triangulo de pantalla completa, glDisable y samplers del juego al restaurar estado
    private int gl(ClassNode cn) {
        int n = 0;
        MethodNode init = need(cn, "init", "()V");
        if (init != null) {
            InsnList start = new InsnList();
            start.add(call(MAC41, "require", "()V"));
            start.add(call("vpmac/Compat", "check", "()V")); // informe de esta Mac (chip, RAM, macOS, limites)
            init.instructions.insert(start);
            n++;
        }
        MethodNode tri = need(cn, "screenTriangle", "()V");
        if (tri != null) n += retarget(tri, D41, "glDrawArrays", "(III)V", S41, "fullscreenDraw");
        MethodNode reset = need(cn, "resetState", "()V");
        if (reset != null) {
            n += retargetGl(reset, "glDisable", "(I)V", S41, "glDisable");
            for (AbstractInsnNode in : list(reset)) {
                if (isCall(in, U41, "glBindSampler", "(II)V")) { reset.instructions.insert(in, call(U41, "resetHostSamplers", "()V")); n++; }
            }
        }
        return n;
    }

    // GlProgram: samplers con nombre que Units41 reparte en las 16 unidades reales del Mac
    private int glProgram(ClassNode cn) {
        int n = 0;
        MethodNode has = need(cn, "has", "(Ljava/lang/String;)Z");
        if (has != null) {
            LabelNode go = new LabelNode();
            InsnList g = new InsnList();
            g.add(new VarInsnNode(ALOAD, 0));
            g.add(new FieldInsnNode(GETFIELD, cn.name, "id", "I"));
            g.add(new VarInsnNode(ALOAD, 1));
            g.add(call(U41, "hasVirtualSampler", "(ILjava/lang/String;)Z"));
            g.add(new JumpInsnNode(IFEQ, go));
            g.add(new InsnNode(ICONST_1));
            g.add(new InsnNode(IRETURN));
            n += guard(cn, has, g, go);
        }
        for (String mname : new String[] {"setInt", "sampler"}) {
            MethodNode m = need(cn, mname, "(Ljava/lang/String;I)V");
            if (m == null) continue;
            InsnList g = new InsnList();
            g.add(new VarInsnNode(ALOAD, 0));
            g.add(new FieldInsnNode(GETFIELD, cn.name, "id", "I"));
            g.add(new VarInsnNode(ALOAD, 1));
            g.add(new VarInsnNode(ILOAD, 2));
            g.add(call(U41, "namedSampler", "(ILjava/lang/String;I)V"));
            m.instructions.insert(g);
            n++;
        }
        MethodNode use = Config.DIAG ? need(cn, "use", "()V") : null;
        if (use != null) {
            InsnList g = new InsnList();
            g.add(new VarInsnNode(ALOAD, 0));
            g.add(new FieldInsnNode(GETFIELD, cn.name, "name", "Ljava/lang/String;"));
            g.add(new VarInsnNode(ALOAD, 0));
            g.add(new FieldInsnNode(GETFIELD, cn.name, "id", "I"));
            g.add(call("vpmac/Diag", "use", "(Ljava/lang/String;I)V"));
            use.instructions.insert(g);
            n++;
        }
        MethodNode adopt = need(cn, "adopt", "(Lviewpoint/platform/GlProgram$Linked;)V");
        if (adopt != null) {
            for (AbstractInsnNode in : list(adopt)) {
                if (in.getOpcode() != RETURN) continue;
                InsnList g = new InsnList();
                g.add(new VarInsnNode(ALOAD, 0));
                g.add(new FieldInsnNode(GETFIELD, cn.name, "id", "I"));
                g.add(new VarInsnNode(ALOAD, 0));
                g.add(new FieldInsnNode(GETFIELD, cn.name, "samplers", "Ljava/util/LinkedHashMap;"));
                g.add(call(U41, "namedSamplers", "(ILjava/util/Map;)V"));
                adopt.instructions.insertBefore(in, g);
                n++;
            }
        }
        return n;
    }

    // GpuParts: sin timestamps de GPU no se mide nada
    private int gpuParts(ClassNode cn) {
        int n = 0;
        for (String[] sig : new String[][] {{"begin", "(I)V"}, {"end", "(I)V"}, {"frameEnded", "()V"}}) {
            MethodNode m = need(cn, sig[0], sig[1]);
            if (m == null) continue;
            LabelNode go = new LabelNode();
            InsnList g = new InsnList();
            g.add(call(T41, "timestampsSupported", "()Z"));
            g.add(new JumpInsnNode(IFNE, go));
            g.add(new InsnNode(RETURN));
            n += guard(cn, m, g, go);
        }
        MethodNode report = need(cn, "report", "()Ljava/lang/String;");
        if (report != null) {
            LabelNode go = new LabelNode();
            InsnList g = new InsnList();
            g.add(call(T41, "timestampsSupported", "()Z"));
            g.add(new JumpInsnNode(IFNE, go));
            g.add(call(T41, "unavailable", "()Ljava/lang/String;"));
            g.add(new InsnNode(ARETURN));
            n += guard(cn, report, g, go);
        }
        return n + timingQueries(cn);
    }

    // Profile: consultas seguras + "sin dato" en vez de 0 ms para el tiempo de GPU
    private int profile(ClassNode cn) {
        int n = timingQueries(cn);
        MethodNode ms = need(cn, "recentGpuMs", "(I)D");
        if (ms != null) {
            LabelNode go = new LabelNode();
            InsnList g = new InsnList();
            g.add(call(T41, "available", "()Z"));
            g.add(new JumpInsnNode(IFNE, go));
            g.add(new LdcInsnNode(Double.NaN));
            g.add(new InsnNode(DRETURN));
            n += guard(cn, ms, g, go);
        }
        return n;
    }

    private static int timingQueries(ClassNode cn) {
        int n = 0;
        for (MethodNode m : cn.methods) {
            n += retargetGl(m, "glQueryCounter", "(II)V", TIMING, "glQueryCounter");
            n += retargetGl(m, "glGetQueryObjecti", "(II)I", TIMING, "glGetQueryObjecti");
            n += retargetGl(m, "glGetQueryObjectui64", "(II)J", TIMING, "glGetQueryObjectui64");
        }
        return n;
    }

    // SceneDrawer.guarded: el juego dibuja su parte con Push/PopAttrib, que no existen en el perfil Core
    private int sceneDrawer(ClassNode cn) {
        MethodNode m = need(cn, "guarded", "(Ljava/lang/String;Ljava/lang/Runnable;)V");
        if (m == null) return 0;
        int n = 0;
        n += retargetGl(m, "glPushClientAttrib", "(I)V", E41, "enter");
        n += retargetGl(m, "glPushAttrib", "(I)V", E41, "ignorePush");
        n += retargetGl(m, "glPopAttrib", "()V", E41, "restoreAttributes");
        n += retargetGl(m, "glPopClientAttrib", "()V", E41, "ignorePop");
        AbstractInsnNode enter = null;
        for (AbstractInsnNode in : list(m)) {
            if (isCall(in, E41, "enter", "(I)V")) enter = in;
            if (in.getOpcode() == RETURN) { m.instructions.insertBefore(in, call(E41, "finish", "()V")); n++; }
        }
        if (enter == null) { Log.warn("SceneDrawer.guarded has no glPushClientAttrib: cleanup not added"); return n; }
        // try { ...resto... } catch (Throwable t) { External41.cleanup(t, isActive, lastTexture); throw t; }
        // locales del original: 0 nombre, 1 runnable, 2 isActive (boolean), 3 programa, 4 lastTextureID
        LabelNode start = new LabelNode(), end = new LabelNode(), handler = new LabelNode();
        m.instructions.insert(enter, start);
        InsnList h = new InsnList();
        h.add(end);
        h.add(handler);
        h.add(new FrameNode(F_NEW, 5, new Object[] {"java/lang/String", "java/lang/Runnable", INTEGER, INTEGER, INTEGER},
                1, new Object[] {"java/lang/Throwable"}));
        h.add(new InsnNode(DUP));
        h.add(new VarInsnNode(ILOAD, 2));
        h.add(new VarInsnNode(ILOAD, 4));
        h.add(call(E41, "cleanup", "(Ljava/lang/Throwable;ZI)V"));
        h.add(new InsnNode(ATHROW));
        m.instructions.add(h);
        m.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, null));
        return n + 1;
    }

    // FrameStream: arranque de cuadro de Draws41 y alineacion de buffers de textura del Mac
    private int frameStream(ClassNode cn) {
        int n = 0;
        MethodNode started = need(cn, "frameStarted", "()V");
        if (started != null) {
            started.instructions.insert(call(D41, "beginFrame", "()V"));
            started.instructions.insert(call("vpmac/Session", "beat", "()V")); // latido para el detector de congelamiento
            if (Config.FPS) started.instructions.insert(call("vpmac/Fps", "frame", "()V"));
            if (Config.BENCH > 0) started.instructions.insert(call("vpmac/Bench", "frame", "()V"));
            n++;
        }
        for (MethodNode m : cn.methods) {
            for (String d : new String[] {"(IJLjava/nio/ByteBuffer;)V", "(IJLjava/nio/FloatBuffer;)V", "(IJLjava/nio/IntBuffer;)V"}) {
                n += retargetGl(m, "glBufferSubData", d, Rules.M41 + "Buffers41", "glBufferSubData");
            }
        }
        MethodNode align = need(cn, "textureAlignment", "()I");
        if (align != null) {
            align.instructions.clear();
            align.tryCatchBlocks.clear();
            if (align.localVariables != null) align.localVariables.clear();
            align.instructions.add(call(R41, "textureAlignment", "()I"));
            align.instructions.add(new InsnNode(IRETURN));
            n++;
        }
        return n;
    }

    // ModelPass.prepare: soltar los samplers del juego antes de hornear texturas de personajes e items
    private int modelPass(ClassNode cn) {
        MethodNode m = need(cn, "prepare", "(Lviewpoint/render/SceneData;)V");
        if (m == null) return 0;
        int n = 0;
        for (AbstractInsnNode in : list(m)) {
            if (!(in instanceof MethodInsnNode)) continue;
            MethodInsnNode mi = (MethodInsnNode) in;
            boolean ready = mi.owner.equals("viewpoint/render/CharacterTextures") && mi.name.equals("ready");
            boolean make = mi.owner.equals("viewpoint/render/ItemTextures") && mi.name.equals("make");
            if (ready || make) { m.instructions.insertBefore(in, call(U41, "resetHostSamplers", "()V")); n++; }
        }
        if (n == 0) Log.warn("ModelPass.prepare: CharacterTextures.ready / ItemTextures.make not found");
        return n;
    }

    // Texturas del juego (Texture.bind) pasan por Units41 para respetar las unidades virtuales
    private static int hostTextures(ClassNode cn) {
        int n = 0;
        for (MethodNode m : cn.methods) {
            for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
                if (in.getOpcode() != INVOKEVIRTUAL) continue;
                MethodInsnNode mi = (MethodInsnNode) in;
                if (mi.owner.equals("zombie/core/textures/Texture") && mi.name.equals("bind") && mi.desc.equals("()V")) {
                    MethodInsnNode r = new MethodInsnNode(INVOKESTATIC, U41, "bindHostTexture",
                            "(Lzombie/core/textures/Texture;)V", false);
                    m.instructions.set(in, r);
                    in = r;
                    n++;
                }
            }
        }
        return n;
    }

    private int irisPipeline(ClassNode cn) {
        for (MethodNode m : cn.methods) {
            if (m.name.equals("create") && m.desc.endsWith(")Lviewpoint/render/IrisPipeline;")) {
                m.instructions.insert(call(MAC41, "rejectExternalIris", "()V"));
                return 1;
            }
        }
        Log.warn("IrisPipeline.create not found");
        return 0;
    }

    private static int stageSamplers(ClassNode cn) {
        int n = 0;
        for (MethodNode m : cn.methods) {
            n += retargetGl(m, "glGetProgramInterfacei", "(III)I", PROBE, "glGetProgramInterfacei");
            n += retargetGl(m, "glGetProgramResourceiv", "(III[I[I[I)V", PROBE, "glGetProgramResourceiv");
        }
        return n;
    }

    /** cambia la constante entera `from` por `to` dentro de un metodo; avisa si no aparece `expected` veces */
    private static int constant(ClassNode cn, String name, String desc, int from, int to, int expected) {
        MethodNode m = need(cn, name, desc);
        if (m == null) return 0;
        int n = 0;
        for (AbstractInsnNode in : list(m)) {
            if (in instanceof IntInsnNode && in.getOpcode() == BIPUSH && ((IntInsnNode) in).operand == from) {
                ((IntInsnNode) in).operand = to;
                n++;
            }
        }
        if (n != expected) Log.warn(cn.name.replace('/', '.') + "." + name + ": expected " + expected + " occurrence(s) of "
                + from + " but found " + n + " (did Viewpoint change?)");
        return n;
    }

    // ------------------------------------------------------------------ utilidades
    private static MethodNode need(ClassNode cn, String name, String desc) {
        for (MethodNode m : cn.methods) if (m.name.equals(name) && m.desc.equals(desc)) return m;
        Log.warn("Viewpoint changed: missing " + cn.name.replace('/', '.') + "." + name + desc
                + " (xiveboy's adjustment not applied)");
        return null;
    }

    private static MethodInsnNode call(String owner, String name, String desc) {
        return new MethodInsnNode(INVOKESTATIC, owner, name, desc, false);
    }

    private static boolean isCall(AbstractInsnNode in, String owner, String name, String desc) {
        if (!(in instanceof MethodInsnNode)) return false;
        MethodInsnNode mi = (MethodInsnNode) in;
        return mi.owner.equals(owner) && mi.name.equals(name) && mi.desc.equals(desc);
    }

    private static List<AbstractInsnNode> list(MethodNode m) {
        List<AbstractInsnNode> out = new ArrayList<>();
        for (ListIterator<AbstractInsnNode> it = m.instructions.iterator(); it.hasNext(); ) out.add(it.next());
        return out;
    }

    /** cambia el destino de llamadas (ya redirigidas o no) owner.name desc -> toOwner.toName */
    private static int retarget(MethodNode m, String owner, String name, String desc, String toOwner, String toName) {
        int n = 0;
        for (AbstractInsnNode in : list(m)) {
            if (in.getOpcode() != INVOKESTATIC) continue;
            MethodInsnNode mi = (MethodInsnNode) in;
            if ((mi.owner.equals(owner) || mi.owner.startsWith(Rules.GL_PREFIX)) && mi.name.equals(name) && mi.desc.equals(desc)) {
                mi.owner = toOwner;
                mi.name = toName;
                mi.itf = false;
                n++;
            }
        }
        return n;
    }

    private static int retargetGl(MethodNode m, String name, String desc, String toOwner, String toName) {
        return retarget(m, Rules.GL_PREFIX, name, desc, toOwner, toName);
    }

    /**
     * Pone `g` al comienzo del metodo; `g` salta a `go` para seguir con el codigo original. Como el salto crea
     * un destino nuevo, va un frame completo con las locales de entrada (o se reusa el que ya hubiera ahi).
     */
    private static int guard(ClassNode cn, MethodNode m, InsnList g, LabelNode go) {
        AbstractInsnNode first = m.instructions.getFirst();
        AbstractInsnNode scan = first;
        while (scan != null && scan.getOpcode() < 0 && !(scan instanceof FrameNode)) scan = scan.getNext();
        InsnList after = new InsnList();
        after.add(go);
        if (!(scan instanceof FrameNode)) {
            Object[] locals = entryLocals(cn, m);
            after.add(new FrameNode(F_NEW, locals.length, locals, 0, new Object[0]));
        }
        g.add(after);
        m.instructions.insert(g);
        return 1;
    }

    private static Object[] entryLocals(ClassNode cn, MethodNode m) {
        List<Object> l = new ArrayList<>();
        if ((m.access & ACC_STATIC) == 0) l.add(cn.name);
        for (Type t : Type.getArgumentTypes(m.desc)) {
            switch (t.getSort()) {
                case Type.BOOLEAN: case Type.BYTE: case Type.CHAR: case Type.SHORT: case Type.INT: l.add(INTEGER); break;
                case Type.FLOAT: l.add(FLOAT); break;
                case Type.LONG: l.add(LONG); break;
                case Type.DOUBLE: l.add(DOUBLE); break;
                default: l.add(t.getInternalName()); break;
            }
        }
        return l.toArray();
    }
}
