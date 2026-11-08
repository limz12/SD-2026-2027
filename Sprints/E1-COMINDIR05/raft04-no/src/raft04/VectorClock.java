package raft04;

import java.util.Arrays;

public final class VectorClock {
    public enum Order { BEFORE, AFTER, EQUAL, CONCURRENT }
    private final long[] values;

    public VectorClock(int n) {
        if (n <= 0) throw new IllegalArgumentException("N tem de ser > 0");
        values = new long[n];
    }

    private VectorClock(long[] values) { this.values = values; }

    public void merge(VectorClock other) {
        if (values.length != other.values.length) throw new IllegalArgumentException("Dimensões incompatíveis");
        for (int i = 0; i < values.length; i++) values[i] = Math.max(values[i], other.values[i]);
    }

    public void set(VectorClock other) {
        if (values.length != other.values.length) throw new IllegalArgumentException("Dimensões incompatíveis");
        System.arraycopy(other.values, 0, values, 0, values.length);
    }

    public void tick(int partition) { values[partition]++; }
    public long get(int partition) { return values[partition]; }
    public VectorClock copy() { return new VectorClock(values.clone()); }

    public Order compare(VectorClock other) {
        if (values.length != other.values.length) throw new IllegalArgumentException("Dimensões incompatíveis");
        boolean less = false, greater = false;
        for (int i = 0; i < values.length; i++) {
            less |= values[i] < other.values[i];
            greater |= values[i] > other.values[i];
        }
        if (less && greater) return Order.CONCURRENT;
        if (less) return Order.BEFORE;
        if (greater) return Order.AFTER;
        return Order.EQUAL;
    }

    @Override public String toString() { return String.join(",", Arrays.stream(values).mapToObj(Long::toString).toArray(String[]::new)); }

    public static VectorClock parse(String text, int n) {
        String[] fields = text.trim().split(",", -1);
        if (fields.length != n) throw new IllegalArgumentException("Vetor com dimensão " + fields.length + ", esperado " + n);
        long[] values = new long[n];
        for (int i = 0; i < n; i++) values[i] = Long.parseLong(fields[i]);
        return new VectorClock(values);
    }

    @Override public boolean equals(Object other) {
        return other instanceof VectorClock && Arrays.equals(values, ((VectorClock) other).values);
    }
    @Override public int hashCode() { return Arrays.hashCode(values); }
}
