package tpf;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.stream.Stream;

// ##################### EXECUTAR PARA TESTES : SERVE APENAS PARA TESTAR O DECIFRAR DAS MENSAGENS ############################################

public class LerChunk {

    public static void main(String[] args) throws Exception {

        Path pastaSelados = Path.of(
            "./Sprints/E2-02/tpf-lider/src/main/java/tpf/selados"
        );
        Path caminhoEventos = Path.of(
            "./Sprints/E2-02/tpf-lider/src/main/java/tpf/registos/eventos-lider.txt"
        );

        // Inicializa o ChunkManager (carrega automaticamente a master.key)
        ChunkManager manager = new ChunkManager(pastaSelados, caminhoEventos);

        // Listar todos os ficheiros .bin existentes em selados/
        try (Stream<Path> ficheiros = Files.list(pastaSelados)) {
            List<Path> bins = ficheiros
                .filter(f -> f.toString().endsWith(".bin"))
                .toList();

            if (bins.isEmpty()) {
                System.out.println("Nenhum chunk selado encontrado.");
                return;
            }

            for (Path bin : bins) {
                String nome = bin.getFileName().toString();
                String chunkId = nome.substring(0, nome.length() - 4); // remove ".bin"

                System.out.println("=== CHUNK: " + chunkId + " ===");

                // 1. Decifrar o .bin usando o .hdr e a master.key
                List<String> registos = manager.abrirChunk(chunkId);

                for (String linha : registos) {
                    // Formato: lsn;instante;autor;canal;textoBase64
                    String[] campos = linha.split(";", 5);

                    String lsn = campos[0];
                    String instante = campos[1];
                    String autor = campos[2];
                    String canal = campos[3];

                    // 2. Descodificar o texto de Base64 para UTF-8
                    String textoOriginal = new String(
                        Base64.getDecoder().decode(campos[4]),
                        StandardCharsets.UTF_8
                    );

                    System.out.printf("[LSN %s] (%s) %s em %s: %s%n",
                        lsn, instante, autor, canal, textoOriginal);
                }
            }
        } finally {
            manager.fechar();
        }
    }
}