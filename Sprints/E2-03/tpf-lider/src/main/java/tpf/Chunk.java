package tpf;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Conteúdo binário: tamanho útil, número de registos e linhas UTF-8 com comprimento. */
public final class Chunk {
    private final List<Registo> registos = new ArrayList<>();
    private long bytes = 8;
    private boolean selado;

    public void adicionar(Registo registo) {
        if (selado) throw new IllegalStateException("Chunk já selado");
        if (!registos.isEmpty() && registo.getLsn() != ultimoLsn() + 1)
            throw new IllegalArgumentException("LSN fora de sequência no chunk");
        byte[] linha = registo.toLine().getBytes(StandardCharsets.UTF_8);
        bytes = Math.addExact(bytes, 4L + linha.length);
        registos.add(registo);
    }
    public boolean vazio() { return registos.isEmpty(); }
    public long tamanho() { return bytes; }
    public long primeiroLsn() { return registos.get(0).getLsn(); }
    public long ultimoLsn() { return registos.get(registos.size() - 1).getLsn(); }
    public byte[] selar(int padding) throws IOException {
        if (selado || vazio() || padding <= 0) throw new IllegalStateException("Chunk ou padding inválido");
        if (bytes > Integer.MAX_VALUE) throw new IOException("Chunk demasiado grande");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(out)) {
            data.writeInt((int) bytes);
            data.writeInt(registos.size());
            for (Registo registo : registos) {
                byte[] linha = registo.toLine().getBytes(StandardCharsets.UTF_8);
                data.writeInt(linha.length);
                data.write(linha);
            }
        }
        long tamanho = ((bytes + padding - 1L) / padding) * padding;
        if (tamanho > Integer.MAX_VALUE) throw new IOException("Chunk com padding demasiado grande");
        selado = true;
        return java.util.Arrays.copyOf(out.toByteArray(), (int) tamanho);
    }
    public static List<Registo> abrir(byte[] padded) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(padded))) {
            int tamanho = in.readInt(), quantidade = in.readInt();
            if (tamanho < 8 || tamanho > padded.length || quantidade < 1 || quantidade > (tamanho - 8) / 4)
                throw new IOException("Cabeçalho de chunk inválido");
            List<Registo> resultado = new ArrayList<>();
            int consumidos = 8;
            for (int i = 0; i < quantidade; i++) {
                int n = in.readInt(); consumidos += 4;
                if (n < 0 || n > tamanho - consumidos) throw new IOException("Comprimento inválido");
                byte[] dados = in.readNBytes(n);
                if (dados.length != n) throw new IOException("Chunk incompleto");
                consumidos += n;
                String linha = StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(dados)).toString();
                try { resultado.add(Registo.fromLine(linha)); }
                catch (IllegalArgumentException e) { throw new IOException("Registo inválido", e); }
            }
            if (consumidos != tamanho) throw new IOException("Tamanho útil inválido");
            return List.copyOf(resultado);
        }
    }
}
