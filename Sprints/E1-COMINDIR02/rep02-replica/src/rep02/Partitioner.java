package rep02;

/**
 * Função de partição: chave = identificador do sensor.
 * Determinística e igual em qualquer JVM (String.hashCode() está fixado pela
 * especificação da
 * linguagem), por isso o produtor e o líder, em processos diferentes, chegam ao
 * mesmo resultado.
 * Math.floorMod devolve sempre um valor em [0, N): o operador % devolveria um
 * valor NEGATIVO para
 * uma chave com hashCode() negativo, e não existe partição -2.
 */
public final class Partitioner {
    private final int n;

    public Partitioner(int n) {
        if (n < 1) {
            throw new IllegalArgumentException("Número de partições inválido: " + n);
        }
        this.n = n;
    }

    // partição de um sensor: determinística e igual em qualquer JVM
    public int of(String sensorId) {
        return Math.floorMod(sensorId.hashCode(), n);
    }

    public int count() {
        return n;
    }
}