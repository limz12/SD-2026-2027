package tpf;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Um chunk: o lote de registos que viaja do chunk aberto ate selados/.
 *
 * Formato binario do texto em claro de um chunk (documentado no README):
 *
 *   bytes 0..3     magic "TPFC"
 *   bytes 4..7     versao do formato = 1 (int big-endian)
 *   bytes 8..11    N = numero de registos (int big-endian)
 *   para cada registo:
 *       8 bytes    lsn      (long big-endian, >= 1)
 *       8 bytes    instante (long big-endian, epoch ms)
 *       2 bytes    comprimento do autor (UTF-8) + bytes
 *       2 bytes    comprimento do canal (UTF-8) + bytes
 *       4 bytes    comprimento do texto (UTF-8) + bytes
 *   padding: zeros ate o TOTAL CIFRADO (texto + tag GCM de 16 bytes)
 *            ser multiplo de Wire.PAD_BYTES
 *
 * Depois de selado, o chunk e imutavel: o registo seguinte abre um chunk novo.
 */
public final class Chunk {

    private static final byte[] MAGIC = {'T', 'P', 'F', 'C'};
    private static final int VERSAO_FORMATO = 1;
    private static final int CABECALHO = 12; // magic + versao + N

    private final List<Registo> registos = new ArrayList<>();
    private int tamanhoDados = 0; // bytes dos registos, sem cabecalho nem padding
    private boolean selado = false;

    /** Tamanho serializado de um registo, em bytes (sem padding). */
    public static int tamanhoDe(Registo r) {
        return 8                                  // lsn
                + 8                               // instante
                + 2 + utf8(r.autor()).length      // autor
                + 2 + utf8(r.canal()).length      // canal
                + 4 + utf8(r.texto()).length;     // texto
    }

    /** Bytes do registo se entrasse neste chunk, incl. cabecalho, tag GCM e padding. */
    private int tamanhoFicheiroSeEntrasse(Registo r) {
        int plaintext = CABECALHO + tamanhoDados + tamanhoDe(r);
        int total = plaintext + ChunkCrypto.TAG_BYTES;
        return ((total + Wire.PAD_BYTES - 1) / Wire.PAD_BYTES) * Wire.PAD_BYTES;
    }

    /**
     * True se o registo pode entrar neste chunk sem o ficheiro selado exceder
     * Wire.CHUNK_MAX_BYTES. O registo que NAO caber faz o chunk selar de imediato
     * (essa decisao e do Arquivo) e abre um chunk novo.
     */
    public synchronized boolean cabe(Registo r) {
        if (selado) {
            return false;
        }
        // Um chunk vazio aceita sempre (um registo maior que o limite e impossivel
        // com o maximo de 1000 caracteres do texto, mas evita ficar bloqueado).
        if (registos.isEmpty()) {
            return true;
        }
        return tamanhoFicheiroSeEntrasse(r) <= Wire.CHUNK_MAX_BYTES;
    }

    /** Acrescenta um registo ao chunk aberto. Falha se o chunk ja estiver selado. */
    public synchronized void adicionar(Registo r) {
        if (selado) {
            throw new IllegalStateException("O chunk ja esta selado; o registo seguinte abre um chunk novo.");
        }
        registos.add(r);
        tamanhoDados += tamanhoDe(r);
    }

    /** Primeiro LSN do chunk (ou 0 se vazio). */
    public synchronized long primeiroLsn() {
        return registos.isEmpty() ? 0 : registos.get(0).lsn();
    }

    /** Ultimo LSN do chunk (ou 0 se vazio). */
    public synchronized long ultimoLsn() {
        return registos.isEmpty() ? 0 : registos.get(registos.size() - 1).lsn();
    }

    public synchronized boolean vazio() {
        return registos.isEmpty();
    }

    public synchronized boolean isSelado() {
        return selado;
    }

    public synchronized int numeroDeRegistos() {
        return registos.size();
    }

    /** Marca o chunk como selado (imutavel). */
    public synchronized void selar() {
        this.selado = true;
    }

    /**
     * Serializa os registos com cabecalho e padding. O resultado, depois de
     * cifrado, tem tamanho multiplo de Wire.PAD_BYTES.
     */
    public synchronized byte[] serializar() {
        try {
            ByteArrayOutputStream saida = new ByteArrayOutputStream(CABECALHO + tamanhoDados + Wire.PAD_BYTES);
            saida.write(MAGIC);
            saida.write(intParaBytes(VERSAO_FORMATO));
            saida.write(intParaBytes(registos.size()));
            for (Registo r : registos) {
                saida.write(longParaBytes(r.lsn()));
                saida.write(longParaBytes(r.instante()));
                escreverComComprimento(saida, utf8(r.autor()), 2);
                escreverComComprimento(saida, utf8(r.canal()), 2);
                escreverComComprimento(saida, utf8(r.texto()), 4);
            }
            // Padding: o texto cifrado (plaintext + tag de 16 bytes) fica com
            // tamanho multiplo de PAD_BYTES.
            int totalCifrado = saida.size() + ChunkCrypto.TAG_BYTES;
            int padding = (Wire.PAD_BYTES - totalCifrado % Wire.PAD_BYTES) % Wire.PAD_BYTES;
            saida.write(new byte[padding]);
            return saida.toByteArray();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("ByteArrayOutputStream nao devia falhar", e);
        }
    }

    /**
     * Leitura inversa: reconstrui os registos e ignora o padding.
     * Falha com IllegalStateException se o formato nao corresponder (magic,
     * versao, comprimentos ou bytes de padding diferentes de zero).
     */
    public static List<Registo> parse(byte[] plaintext) {
        if (plaintext.length < CABECALHO) {
            throw new IllegalStateException("Chunk demasiado pequeno: " + plaintext.length + " bytes.");
        }
        for (int i = 0; i < 4; i++) {
            if (plaintext[i] != MAGIC[i]) {
                throw new IllegalStateException("Magic errado: nao e um chunk TPF.");
            }
        }
        int versao = intDeBytes(plaintext, 4);
        if (versao != VERSAO_FORMATO) {
            throw new IllegalStateException("Versao de formato desconhecida: " + versao + ".");
        }
        int n = intDeBytes(plaintext, 8);
        List<Registo> lidos = new ArrayList<>(n);
        int pos = CABECALHO;
        for (int i = 0; i < n; i++) {
            pos = verificarDisponivel(plaintext, pos, 20, "registo " + i);
            long lsn = longDeBytes(plaintext, pos);
            long instante = longDeBytes(plaintext, pos + 8);
            pos += 16;
            String autor = lerComComprimento(plaintext, pos, 2, "autor");
            pos += 2 + utf8(autor).length;
            String canal = lerComComprimento(plaintext, pos, 2, "canal");
            pos += 2 + utf8(canal).length;
            String texto = lerComComprimento(plaintext, pos, 4, "texto");
            pos += 4 + utf8(texto).length;
            lidos.add(new Registo(lsn, instante, autor, canal, texto));
        }
        // O resto e padding: tem de ser tudo zeros.
        while (pos < plaintext.length) {
            if (plaintext[pos] != 0) {
                throw new IllegalStateException("Padding nao e zero na posicao " + pos + ".");
            }
            pos++;
        }
        return Collections.unmodifiableList(lidos);
    }

    // ---------------------------------------------------------------- auxiliares

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] intParaBytes(int v) {
        return new byte[]{(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
    }

    private static byte[] longParaBytes(long v) {
        byte[] b = new byte[8];
        for (int i = 0; i < 8; i++) {
            b[i] = (byte) (v >>> (8 * (7 - i)));
        }
        return b;
    }

    private static int intDeBytes(byte[] b, int pos) {
        return ((b[pos] & 0xFF) << 24) | ((b[pos + 1] & 0xFF) << 16) | ((b[pos + 2] & 0xFF) << 8) | (b[pos + 3] & 0xFF);
    }

    private static long longDeBytes(byte[] b, int pos) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (b[pos + i] & 0xFF);
        }
        return v;
    }

    private static void escreverComComprimento(ByteArrayOutputStream saida, byte[] valor, int bytesDeComprimento)
            throws java.io.IOException {
        if (valor.length >= (1L << (8 * bytesDeComprimento))) {
            throw new IllegalStateException("Campo maior do que o formato permite (" + valor.length + " bytes).");
        }
        byte[] comp = new byte[bytesDeComprimento];
        for (int i = 0; i < bytesDeComprimento; i++) {
            comp[i] = (byte) (valor.length >>> (8 * (bytesDeComprimento - 1 - i)));
        }
        saida.write(comp);
        saida.write(valor);
    }

    private static String lerComComprimento(byte[] dados, int pos, int bytesDeComprimento, String campo) {
        int comp = 0;
        for (int i = 0; i < bytesDeComprimento; i++) {
            comp = (comp << 8) | (dados[pos + i] & 0xFF);
        }
        verificarDisponivel(dados, pos + bytesDeComprimento, comp, campo);
        return new String(dados, pos + bytesDeComprimento, comp, StandardCharsets.UTF_8);
    }

    private static int verificarDisponivel(byte[] dados, int pos, int quantos, String campo) {
        if (pos + quantos > dados.length) {
            throw new IllegalStateException("Chunk truncado ao ler " + campo + ".");
        }
        return pos;
    }
}
