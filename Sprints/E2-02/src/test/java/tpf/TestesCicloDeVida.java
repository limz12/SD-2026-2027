package tpf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Testes "de vida real", SEM JUnit: simulam exatamente o que o LeaderBot.main
 * faz (arrancar, registar, fechar, voltar a arrancar), mais concorrencia
 * (varias threads no mesmo metodo registar) e a validacao do chunkId.
 *
 * Corre com: java -cp "target/test-classes;target/classes" tpf.TestesCicloDeVida
 */
public class TestesCicloDeVida {

    private static int total = 0;
    private static int falhas = 0;

    public static void main(String[] args) throws Exception {
        Path raiz = Files.createTempDirectory("tpf-ciclo-teste");
        try {
            cicloDeVidaCompleto(raiz);
            concorrencia(raiz);
            chunkIdInvalidoRecusado(raiz);
        } finally {
            apagarRecursivo(raiz);
        }

        System.out.println();
        System.out.println(total + " verificacoes, " + falhas + " falhas.");
        if (falhas > 0) {
            System.exit(1);
        }
    }

    /**
     * Arranque 1: gera a master.key, regista 3, fecha (sela).
     * Arranque 2: recarrega a MESMA chave, continua no LSN 3, regista mais 2, fecha.
     * Depois: abre TODOS os chunks selados com a chave certa (e so com ela) e
     * confirma que o conteudo total e exatamente o dos 5 registos, por ordem.
     */
    static void cicloDeVidaCompleto(Path raiz) throws Exception {
        Path pasta = raiz.resolve("ciclo");
        Files.createDirectories(pasta);
        Path caminhoChave = pasta.resolve("master.key");

        // ----- arranque 1 (como no LeaderBot.main) -----
        byte[] chave1 = MasterKey.carregar(caminhoChave).copiarBytes();
        try (Arquivo arquivo = new Arquivo(pasta, chave1)) {
            Registo a = arquivo.registar("ana", "arquivo", "um");
            Registo b = arquivo.registar("bruno", "arquivo", "dois");
            Registo c = arquivo.registar("carla", "arquivo", "três");
            verificar("arranque 1: LSN 1,2,3 em sequencia",
                    a.lsn() == 1 && b.lsn() == 2 && c.lsn() == 3, a.lsn() + "," + b.lsn() + "," + c.lsn());
        } // close() sela o chunk aberto

        // ----- arranque 2 -----
        byte[] chave2 = MasterKey.carregar(caminhoChave).copiarBytes();
        verificar("arranque 2: a master.key recarregada e a mesma",
                java.util.Arrays.equals(chave1, chave2), "");
        List<Registo> finais = new ArrayList<>();
        try (Arquivo arquivo = new Arquivo(pasta, chave2)) {
            verificar("arranque 2: a numeracao continua no ultimo LSN (3)",
                    arquivo.getUltimoLsn() == 3, String.valueOf(arquivo.getUltimoLsn()));
            arquivo.registar("diana", "arquivo", "quatro");
            arquivo.registar("elias", "arquivo", "cinco");
        }

        // ----- abrir todos os chunks selados -----
        Path selados = pasta.resolve("selados");
        List<Path> bins;
        try (var s = Files.list(selados)) {
            bins = s.filter(p -> p.toString().endsWith(".bin")).toList();
        }
        verificar("os dois arranques produziram 2 chunks selados", bins.size() == 2, String.valueOf(bins.size()));

        for (Path bin : bins) {
            String nome = bin.getFileName().toString().replace(".bin", "");
            List<Registo> rs = Sealed.abrir(selados, nome, chave2).lerRegistos();
            finais.addAll(rs);

            // dentro de um chunk os LSN sao contiguos e crescentes
            for (int i = 1; i < rs.size(); i++) {
                verificar("chunk com LSN contiguos e crescentes",
                        rs.get(i).lsn() == rs.get(i - 1).lsn() + 1,
                        rs.get(i - 1).lsn() + "->" + rs.get(i).lsn());
            }
            // e com a chave errada o open falha
            byte[] chaveErrada = new byte[32];
            new java.util.Random(999).nextBytes(chaveErrada);
            try {
                Sealed.abrir(selados, nome, chaveErrada);
                verificar("chunk com master.key errada falha", false, "nao lancou excecao");
            } catch (GeneralSecurityException e) {
                verificar("chunk com master.key errada falha", true, "");
            }
        }
        finais.sort(java.util.Comparator.comparingLong(Registo::lsn));

        verificar("os 2 chunks juntos tem exatamente os 5 registos, por ordem de LSN",
                finais.size() == 5
                        && finais.get(0).texto().equals("um") && finais.get(0).autor().equals("ana")
                        && finais.get(1).texto().equals("dois") && finais.get(2).texto().equals("três")
                        && finais.get(3).texto().equals("quatro") && finais.get(4).texto().equals("cinco")
                        && finais.get(4).autor().equals("elias")
                        && finais.stream().allMatch(r -> r.canal().equals("arquivo")),
                String.valueOf(finais));

        String eventos = Files.readString(pasta.resolve("eventos-lider.txt"));
        verificar("2 eventos SELADO (um por arranque) e 5 REGISTO",
                eventos.split("SELADO;", -1).length - 1 == 2
                        && eventos.split("REGISTO;", -1).length - 1 == 5, eventos);
    }

    /** 8 threads x 125 registos no MESMO metodo sincronizado: LSN 1..1000 sem repetir nem saltar. */
    static void concorrencia(Path raiz) throws Exception {
        Path pasta = raiz.resolve("concorrencia");
        Files.createDirectories(pasta);
        byte[] chave = chaveDeTeste(21);
        int nThreads = 8, porThread = 125, nTotal = nThreads * porThread;

        try (Arquivo arquivo = new Arquivo(pasta, chave)) {
            ExecutorService ex = Executors.newFixedThreadPool(nThreads);
            List<Future<?>> futuros = new ArrayList<>();
            for (int t = 0; t < nThreads; t++) {
                final int id = t;
                futuros.add(ex.submit(() -> {
                    for (int i = 1; i <= porThread; i++) {
                        try {
                            arquivo.registar("t" + id, "arquivo", "concorrente " + id + ":" + i);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                }));
            }
            for (Future<?> f : futuros) {
                f.get(120, TimeUnit.SECONDS);
            }
            ex.shutdown();
        }

        List<String> linhas = Files.readAllLines(pasta.resolve("registos.log"));
        verificar("o diario tem as " + nTotal + " linhas", linhas.size() == nTotal,
                String.valueOf(linhas.size()));

        boolean[] vistos = new boolean[nTotal + 1];
        boolean semRepetirNemSaltar = true;
        for (String linha : linhas) {
            Registo r = Registo.daLinhaDiario(linha);
            if (r == null || r.lsn() < 1 || r.lsn() > nTotal || vistos[(int) r.lsn()]) {
                semRepetirNemSaltar = false;
                break;
            }
            vistos[(int) r.lsn()] = true;
        }
        verificar("LSN 1.." + nTotal + " exatamente uma vez cada", semRepetirNemSaltar, "");

        String eventos = Files.readString(pasta.resolve("eventos-lider.txt"));
        verificar(nTotal + " eventos REGISTO", eventos.split("REGISTO;", -1).length - 1 == nTotal, "");

        // chunks: abrir todos e confirmar que juntos cobrem 1..nTotal
        Path selados = pasta.resolve("selados");
        List<Long> lsns = new ArrayList<>();
        int nChunks = 0;
        try (var s = Files.list(selados)) {
            for (Path bin : s.filter(p -> p.toString().endsWith(".bin")).toList()) {
                nChunks++;
                String nome = bin.getFileName().toString().replace(".bin", "");
                for (Registo r : Sealed.abrir(selados, nome, chave).lerRegistos()) {
                    lsns.add(r.lsn());
                }
            }
        }
        lsns.sort(Long::compare);
        boolean cobre = lsns.size() == nTotal;
        for (int i = 0; i < lsns.size() && cobre; i++) {
            cobre = lsns.get(i) == i + 1L;
        }
        verificar("os chunks cobrem todos os " + nTotal + " registos (" + nChunks + " chunks)",
                cobre, "total=" + lsns.size());
    }

    /** Um chunkId que nao e SHA-256 em hex e recusado antes de tocar no disco. */
    static void chunkIdInvalidoRecusado(Path raiz) throws Exception {
        Path pasta = raiz.resolve("ids-maus");
        Files.createDirectories(pasta);
        byte[] chave = chaveDeTeste(22);
        String[] maus = {"..\\..\\escapa", "../../escapa", "curto", null,
                "g".repeat(64), "a".repeat(63) + "g"};
        int antes = total;
        for (String id : maus) {
            try {
                Sealed.abrir(pasta, id, chave);
                verificar("chunkId invalido recusado: " + id, false, "nao lancou excecao");
            } catch (IllegalArgumentException e) {
                verificar("chunkId invalido recusado: " + (id == null ? "null" : id), true, "");
            }
        }
        if (total == antes) {
            verificar("nenhum chunkId invalido foi aceite", false, "sem testes corridos");
        }
    }

    // ---------------------------------------------------------------- auxiliares

    private static byte[] chaveDeTeste(int semente) {
        byte[] chave = new byte[32];
        new java.util.Random(semente).nextBytes(chave);
        return chave;
    }

    static void verificar(String nome, boolean passou, String detalhe) {
        total++;
        if (!passou) {
            falhas++;
        }
        System.out.println((passou ? "  ok   " : "  FALHOU ") + nome + (passou ? "" : "  -> " + detalhe));
    }

    static void apagarRecursivo(Path raiz) throws Exception {
        if (!Files.isDirectory(raiz)) {
            return;
        }
        try (var s = Files.list(raiz)) {
            for (Path p : s.toList()) {
                if (Files.isDirectory(p)) {
                    apagarRecursivo(p);
                } else {
                    Files.deleteIfExists(p);
                }
            }
        }
        Files.deleteIfExists(raiz);
    }
}
