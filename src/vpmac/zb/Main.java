package vpmac.zb;

/**
 * Fase normal de ZombieBuddy (cuando se cargan los mods). VPMac no puede arrancar aqui: el juego ya abrio su
 * ventana y cargo las clases que el puente necesita parchear. Solo avisa si VPMac todavia no esta activo
 * (primer arranque, antes de que ZombieBuddy lo cargue como preload).
 */
public final class Main {
    private Main() {}

    public static void main(String[] args) {
        if (Boolean.getBoolean("vpmac.active")) return;
        Notice.show("VPMac: restart the game", "VPMac is installed but not active yet.\n\n"
                + "Close the game and start it again: from the next start ZombieBuddy loads VPMac early, before the game.\n\n"
                + "Do not enter a world before restarting: Viewpoint would crash on Mac.");
    }
}
