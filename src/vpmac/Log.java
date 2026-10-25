package vpmac;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;

/**
 * Mensajes de VPMac: van a System.out (el juego los copia a ~/Zomboid/console.txt como "[VPMac] ...") y ademas a
 * ~/Zomboid/vpmac/vpmac.log con hora. console.txt se borra en cada arranque; vpmac.log guarda la sesion anterior
 * como vpmac.prev.log, asi un informe de crash todavia la tiene. Los ultimos avisos quedan en memoria para Report.
 */
final class Log {
    static final Path DIR = Path.of(System.getProperty("user.home"), "Zomboid", "vpmac");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final ArrayDeque<String> warnings = new ArrayDeque<>();
    private static BufferedWriter file;
    private static boolean fileFailed;

    private Log() {}

    static void info(String s) {
        emit("[VPMac] " + s);
    }

    static void warn(String s) {
        synchronized (warnings) {
            warnings.addLast(s);
            if (warnings.size() > 12) warnings.removeFirst();
        }
        emit("[VPMac] WARNING: " + s);
    }

    static void error(String s, Throwable t) {
        warn("ERROR: " + s + (t != null ? " (" + t + ")" : ""));
        if (t != null) t.printStackTrace(System.out);
    }

    /** ultimos avisos y errores de esta sesion (para el informe) */
    static String[] recentWarnings() {
        synchronized (warnings) {
            return warnings.toArray(new String[0]);
        }
    }

    private static synchronized void emit(String line) {
        System.out.println(line);
        if (fileFailed) return;
        try {
            if (file == null) open();
            file.write(LocalTime.now().format(TIME));
            file.write(' ');
            file.write(line);
            file.newLine();
            file.flush(); // pocos mensajes por sesion: vale la pena que sobrevivan a un crash
        } catch (Exception e) {
            fileFailed = true;
        }
    }

    private static void open() throws Exception {
        Files.createDirectories(DIR);
        Path log = DIR.resolve("vpmac.log");
        if (Files.exists(log)) Files.move(log, DIR.resolve("vpmac.prev.log"), StandardCopyOption.REPLACE_EXISTING);
        file = Files.newBufferedWriter(log, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }
}
