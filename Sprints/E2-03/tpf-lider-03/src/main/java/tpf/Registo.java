
package tpf;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

/**
 * Representa um registo individual do arquivo.
 *
 * Cada registo contém cinco campos:
 *
 * - lsn: número sequencial atribuído pelo líder;
 * - instante: milissegundos desde a época Unix;
 * - autor: ID numérico do utilizador Discord;
 * - canal: ID numérico do canal Discord;
 * - texto: conteúdo original da mensagem.
 *
 * A classe é imutável.
 *
 * Formato de registos.log:
 *
 * lsn;instante;autor;canal;textoBase64
 *
 * O texto é codificado em UTF-8 e representado
 * em Base64 na última coluna.
 *
 * Decisões de validação do grupo:
 *
 * - LSN estritamente positivo;
 * - instante não negativo;
 * - autor e canal obrigatórios;
 * - autor e canal são IDs Discord numéricos positivos;
 * - texto não pode ser nulo;
 * - texto vazio é permitido;
 * - sequências Unicode inválidas são rejeitadas;
 * - Base64 inválido ou UTF-8 inválido é rejeitado.
 *
 * Esta classe não atribui LSNs, não escreve ficheiros,
 * não sincroniza pedidos, não gere chunks e não cifra.
 */
public final class Registo {

    private final long lsn;
    private final long instante;

    private final String autor;
    private final String canal;
    private final String texto;

    /**
     * Constrói um registo com os dados fornecidos.
     *
     * O LSN e o instante são determinados
     * pela componente responsável pelo registo.
     */
    public Registo(
            long lsn,
            long instante,
            String autor,
            String canal,
            String texto) {

        if (lsn <= 0) {
            throw new IllegalArgumentException(
                "O LSN deve ser um inteiro positivo."
            );
        }

        if (instante < 0) {
            throw new IllegalArgumentException(
                "O instante não pode ser negativo."
            );
        }

        validarIdDiscord(autor, "autor");
        validarIdDiscord(canal, "canal");

        Objects.requireNonNull(
            texto,
            "O texto não pode ser nulo."
        );

        /*
         * Validar que a String contém uma
         * sequência Unicode bem formada.
         *
         * O codificador UTF-8 estrito rejeita
         * surrogates isolados.
         *
         * Isto garante que o texto poderá ser
         * serializado e recuperado sem alterações.
         */
        validarTextoUnicode(texto);

        this.lsn = lsn;
        this.instante = instante;

        this.autor = autor;
        this.canal = canal;
        this.texto = texto;
    }

    /**
     * Valida um identificador Discord.
     *
     * Decisão do grupo:
     *
     * Autor e canal são representados pelos
     * respetivos IDs numéricos Discord,
     * e não por nomes apresentados ao utilizador.
     *
     * Aceita apenas dígitos ASCII (0 a 9),
     * sem sinal, espaços ou separadores.
     *
     * O identificador deve representar
     * um número estritamente positivo.
     *
     * Não impõe um tamanho fixo para o ID.
     */
    private static void validarIdDiscord(
            String valor,
            String campo) {

        Objects.requireNonNull(
            valor,
            "O campo " + campo + " não pode ser nulo."
        );

        if (valor.isEmpty()) {
            throw new IllegalArgumentException(
                "O campo " + campo + " não pode estar vazio."
            );
        }

        boolean existeDigitoNaoZero = false;

        for (int i = 0; i < valor.length(); i++) {

            char c = valor.charAt(i);

            if (c < '0' || c > '9') {
                throw new IllegalArgumentException(
                    "O campo " + campo
                    + " deve conter apenas dígitos ASCII."
                );
            }

            if (c != '0') {
                existeDigitoNaoZero = true;
            }
        }

        if (!existeDigitoNaoZero) {
            throw new IllegalArgumentException(
                "O campo " + campo
                + " deve representar um ID positivo."
            );
        }
    }

    /**
     * Verifica se a String pode ser codificada
     * integralmente em UTF-8.
     *
     * Um surrogate isolado é rejeitado,
     * em vez de ser substituído por '?'.
     */
    private static void validarTextoUnicode(
            String texto) {

        try {

            StandardCharsets.UTF_8
                .newEncoder()
                .onMalformedInput(
                    CodingErrorAction.REPORT
                )
                .onUnmappableCharacter(
                    CodingErrorAction.REPORT
                )
                .encode(
                    CharBuffer.wrap(texto)
                );

        } catch (CharacterCodingException e) {

            throw new IllegalArgumentException(
                "O texto contém uma sequência "
                + "Unicode inválida.",
                e
            );
        }
    }

    /**
     * Codifica o texto em UTF-8 estrito.
     *
     * Não substitui caracteres inválidos.
     */
    private static byte[] codificarUtf8(
            String texto) {

        try {

            ByteBuffer buffer = StandardCharsets.UTF_8
                .newEncoder()
                .onMalformedInput(
                    CodingErrorAction.REPORT
                )
                .onUnmappableCharacter(
                    CodingErrorAction.REPORT
                )
                .encode(
                    CharBuffer.wrap(texto)
                );

            byte[] bytes =
                new byte[buffer.remaining()];

            buffer.get(bytes);

            return bytes;

        } catch (CharacterCodingException e) {

            throw new IllegalArgumentException(
                "Não foi possível codificar "
                + "o texto em UTF-8.",
                e
            );
        }
    }

    /**
     * Descodifica bytes UTF-8 estritamente.
     *
     * Sequências incompletas ou malformadas
     * provocam uma exceção.
     *
     * Não são introduzidos caracteres
     * de substituição.
     */
    private static String descodificarUtf8(
            byte[] bytes) {

        try {

            CharBuffer caracteres =
                StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(
                        CodingErrorAction.REPORT
                    )
                    .onUnmappableCharacter(
                        CodingErrorAction.REPORT
                    )
                    .decode(
                        ByteBuffer.wrap(bytes)
                    );

            return caracteres.toString();

        } catch (CharacterCodingException e) {

            throw new IllegalArgumentException(
                "O texto Base64 contém bytes "
                + "UTF-8 inválidos.",
                e
            );
        }
    }

    /**
     * Número sequencial do registo.
     */
    public long getLsn() {
        return lsn;
    }

    /**
     * Instante em milissegundos Unix.
     */
    public long getInstante() {
        return instante;
    }

    /**
     * ID do utilizador Discord.
     */
    public String getAutor() {
        return autor;
    }

    /**
     * ID do canal Discord.
     */
    public String getCanal() {
        return canal;
    }

    /**
     * Texto original da mensagem.
     */
    public String getTexto() {
        return texto;
    }

    /**
     * Serializa o registo no formato exigido:
     *
     * lsn;instante;autor;canal;textoBase64
     *
     * A representação Base64 utiliza o
     * codificador padrão RFC 4648.
     *
     * A codificação é UTF-8 estrita.
     */
    public String toLine() {

        byte[] bytes = codificarUtf8(texto);

        String textoBase64 = Base64.getEncoder()
            .encodeToString(bytes);

        return lsn
            + ";" + instante
            + ";" + autor
            + ";" + canal
            + ";" + textoBase64;
    }

    /**
     * Reconstrói um registo a partir
     * de uma linha de registos.log.
     *
     * São exigidos exatamente cinco campos.
     *
     * A descodificação Base64 e UTF-8 é estrita.
     *
     * @param linha linha completa do log
     * @return registo reconstruído
     * @throws IllegalArgumentException
     *         se o formato for inválido
     */
    public static Registo fromLine(String linha) {

        if (linha == null) {
            throw new IllegalArgumentException(
                "A linha do registo não pode ser nula."
            );
        }

        /*
         * O argumento -1 preserva campos vazios,
         * incluindo o texto vazio no final.
         */
        String[] campos = linha.split(";", -1);

        if (campos.length != 5) {
            throw new IllegalArgumentException(
                "Formato inválido: esperados "
                + "exatamente cinco campos."
            );
        }

        long lsn;
        long instante;

        try {

            lsn = Long.parseLong(campos[0]);
            instante = Long.parseLong(campos[1]);

        } catch (NumberFormatException e) {

            throw new IllegalArgumentException(
                "LSN ou instante inválido.",
                e
            );
        }

        String autor = campos[2];
        String canal = campos[3];

        byte[] bytes;

        try {

            bytes = Base64.getDecoder()
                .decode(campos[4]);

        } catch (IllegalArgumentException e) {

            throw new IllegalArgumentException(
                "O campo texto não contém "
                + "Base64 válido.",
                e
            );
        }

        String texto = descodificarUtf8(bytes);

        /*
         * O construtor aplica novamente
         * todas as validações dos campos.
         */
        return new Registo(
            lsn,
            instante,
            autor,
            canal,
            texto
        );
    }

    /**
     * Representação para diagnóstico.
     *
     * Omite o conteúdo da mensagem, mas
     * inclui metadados como autor e canal.
     *
     * Não deve ser utilizada em logs públicos
     * quando esses metadados forem sensíveis.
     */
    @Override
    public String toString() {

        return "Registo{"
            + "lsn=" + lsn
            + ", instante=" + instante
            + ", autor='" + autor + '\''
            + ", canal='" + canal + '\''
            + '}';
    }
}
