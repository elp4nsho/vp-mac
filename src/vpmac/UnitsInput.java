package vpmac;

/** Un sampler de un programa (FastUnits41): ubicacion, tipo, unidad fisica asignada y unidad logica pedida. */
public final class UnitsInput {
    public final int location, target, physical, slot;
    public int logical;

    public UnitsInput(int location, int target, int physical) {
        this.location = location;
        this.target = target;
        this.physical = physical;
        this.slot = UnitsState.slot(target);
        // logical queda en 0 como en el original (campo sin inicializar) hasta que glUniform1i lo fije
    }
}
