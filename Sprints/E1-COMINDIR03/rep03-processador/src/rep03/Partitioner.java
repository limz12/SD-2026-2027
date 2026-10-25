package rep03;

public class Partitioner {
    private final int n;

    public Partitioner(int n) {
        if (n <= 0) throw new IllegalArgumentException("N tem de ser > 0");
        this.n = n;
    }

    public int of(String sensorId) {
        return Math.floorMod(sensorId.hashCode(), n);
    }

    public int count() { return n; }
}
