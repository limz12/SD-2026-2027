package tpf;

import java.util.Base64;

/**
 * Um registo do arquivo: a unidade duravel do sistema.
 *
 * A linha no registos.log tem o formato:
 *
 *   lsn;instante;autor;canal;texto em Base64
 *
 * - lsn: numero de sequencia, comecando em 1, sem repeticoes nem saltos
 * - instante: epoch em milissegundos
 * - autor: nome do autor (sem ';' nem quebras de linha)
 * - canal: nome do canal, sempre "arquivo" neste sprint
 * - texto: o texto do registo, codificado em Base64 (UTF-8) para poder ter ';' e acentos
 */
public record Registo(long lsn, long instante, String autor, String canal, String texto) {

    /** Codifica este registo como uma linha do diário (sem o '\n' final). */
    public String linhaParaDiario() {
        return lsn + ";" + instante + ";" + autor + ";" + canal + ";"
                + Base64.getEncoder().encodeToString(texto.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Le uma linha do diario. Devolve null se a linha estiver mal formada
     * (pode acontecer na ultima linha se o processo morreu a meio da escrita).
     */
    public static Registo daLinhaDiario(String linha) {
        if (linha == null || linha.isEmpty()) {
            return null;
        }
        String[] partes = linha.split(";", 5);
        if (partes.length != 5) {
            return null;
        }
        try {
            long lsn = Long.parseLong(partes[0]);
            long instante = Long.parseLong(partes[1]);
            byte[] texto = Base64.getDecoder().decode(partes[4].trim());
            return new Registo(lsn, instante, partes[2], partes[3],
                    new String(texto, java.nio.charset.StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            return null; // lsn/instante nao numericos, ou Base64 invalido
        }
    }

    /** Remove caracteres que partiriam o formato de linha do diario. */
    public static String limpar(String campo) {
        return campo.replace(';', ' ').replace('\n', ' ').replace('\r', ' ');
    }
}
