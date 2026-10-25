package vpmac;

import java.lang.instrument.Instrumentation;

/**
 * VPMac: lleva Project Viewpoint a OpenGL 4.1 (Mac) reescribiendo sus clases EN MEMORIA al cargarlas.
 * El Viewpoint.jar del Workshop no se toca: Steam puede actualizarlo y las reglas se vuelven a aplicar solas.
 * Necesita el puente pz-mac41-bridge.jar (contexto 4.1 Core sobre Metal, y la ASM que usamos) cargado antes.
 * Solo para uso personal: la licencia de Viewpoint no permite redistribuirlo modificado.
 */
public final class Agent {
    public static final String VERSION = "0.3.1";

    private Agent() {}

    public static void premain(String args, Instrumentation inst) {
        if (Boolean.getBoolean("vpmac.active")) return; // ya arranco (por -javaagent o por ZombieBuddy)
        System.setProperty("vpmac.active", "true");
        Session.start(); // primero: registra si la sesion anterior termino mal (antes de que se borren sus logs)
        Rules.load();
        inst.addTransformer(new Transformer(), false);
        Log.info("agent " + VERSION + " ready: " + Rules.redirectCount() + " OpenGL replacements; "
                + "Viewpoint is rewritten in memory as it loads (the Workshop jar is not modified)");
        Log.info("config: " + Config.describe() + " | " + Report.machine());
    }
}
