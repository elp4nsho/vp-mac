package vpmac.zb;

/**
 * Avisos al jugador cuando VPMac NO puede arrancar (entonces no hay informe de crash: VPMac no esta activo).
 * Ventana de macOS (osascript) en otro hilo, para no frenar el arranque del juego.
 */
final class Notice {
    private Notice() {}

    static void show(String title, String message) {
        System.out.println("[VPMac] WARNING: " + message.replace('\n', ' '));
        Thread t = new Thread(() -> {
            try {
                String script = "display dialog " + quote(message) + " with title " + quote(title)
                        + " buttons {\"OK\"} default button \"OK\" with icon caution";
                new ProcessBuilder("/usr/bin/osascript", "-e", script).redirectErrorStream(true).start().waitFor();
            } catch (Exception e) {
                // sin ventana: el aviso quedo en console.txt
            }
        }, "VPMac notice");
        t.setDaemon(true);
        t.start();
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
