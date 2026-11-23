package tpf;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class Chunk {
    public static final byte[] MAGIC = "TPF2".getBytes(StandardCharsets.US_ASCII);

    private final ScheduledExecutorService scheduler;
    private final Consumer<List<Registo>> onSeal;
    private final List<Registo> records = new ArrayList<>();
    private ScheduledFuture<?> timer;
    private boolean sealed;

    public Chunk(ScheduledExecutorService scheduler, Consumer<List<Registo>> onSeal) {
        this.scheduler = scheduler;
        this.onSeal = onSeal;
    }

    public synchronized boolean add(Registo registo) throws IOException {
        if (sealed) throw new IllegalStateException("chunk já está selado");
        records.add(registo);
        if (records.size() == 1) {
            timer = scheduler.schedule(this::sealByTime, Wire.SELAGEM_MS, TimeUnit.MILLISECONDS);
        }
        if (serializedLength() >= Wire.CHUNK_MAX_BYTES) {
            sealNow();
            return true;
        }
        return false;
    }

    public synchronized int serializedLength() throws IOException {
        return serialize(records).length;
    }

    public synchronized List<Registo> getRecords() {
        return new ArrayList<>(records);
    }

    public synchronized boolean isSealed() {
        return sealed;
    }

    private void sealByTime() {
        sealNow();
    }

    private void sealNow() {
        List<Registo> snapshot;
        synchronized (this) {
            if (sealed) return;
            sealed = true;
            if (timer != null) timer.cancel(false);
            snapshot = new ArrayList<>(records);
        }
        onSeal.accept(snapshot);
    }

    public static byte[] serialize(List<Registo> records) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.write(MAGIC);
            out.writeInt(records.size());
            for (Registo r : records) {
                out.writeLong(r.getLsn());
                out.writeLong(r.getInstante());
                writeUtf(out, r.getAutor());
                writeUtf(out, r.getCanal());
                writeUtf(out, r.getTexto());
            }
        }
        byte[] result = bytes.toByteArray();
        int paddedLength = ((result.length + Wire.PAD_BYTES - 1) / Wire.PAD_BYTES) * Wire.PAD_BYTES;
        return java.util.Arrays.copyOf(result, paddedLength);
    }

    public static List<Registo> deserialize(byte[] data) throws IOException {
        if (data == null || data.length < 8) throw new IOException("chunk demasiado curto");
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            byte[] magic = new byte[4];
            in.readFully(magic);
            if (!java.util.Arrays.equals(magic, MAGIC)) throw new IOException("magic inválido");
            int count = in.readInt();
            if (count < 0 || count > 1_000_000) throw new IOException("número de registos inválido");
            List<Registo> records = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                long lsn = in.readLong();
                long instante = in.readLong();
                String autor = readUtf(in);
                String canal = readUtf(in);
                String texto = readUtf(in);
                records.add(new Registo(lsn, instante, autor, canal, texto));
            }
            return records;
        } catch (EOFException e) {
            throw new IOException("chunk truncado", e);
        }
    }

    private static void writeUtf(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readUtf(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > Wire.CHUNK_MAX_BYTES) throw new IOException("texto demasiado longo");
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
