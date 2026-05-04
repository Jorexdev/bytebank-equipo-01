package dev.bytebank.fraud.check;

/** Resultado de la validación, ordenado de menos a más grave. */
public enum Verdict {
    APROBADA,
    REVISAR,
    BLOQUEADA;

    public Verdict worst(Verdict other) {
        return other.ordinal() > ordinal() ? other : this;
    }
}
