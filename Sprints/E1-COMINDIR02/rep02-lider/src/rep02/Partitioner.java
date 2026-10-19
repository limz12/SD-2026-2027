package rep02;

/** Função determinística que atribui cada sensor a uma partição estática. */
public final class Partitioner {
    private final int n;

    public Partitioner(int n) {
        if (n < 1) {
            throw new IllegalArgumentException("Número de partições inválido: " + n);
        }
        this.n = n;
    }

    /** A mesma chave produz a mesma partição em qualquer JVM. */
    public int of(String sensorId) {
        if (sensorId == null || sensorId.isBlank()) {
            throw new IllegalArgumentException("Identificador de sensor vazio");
        }
        return Math.floorMod(sensorId.hashCode(), n);
    }

    public int count() { return n; }
}
