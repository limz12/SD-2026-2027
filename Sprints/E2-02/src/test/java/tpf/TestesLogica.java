package tpf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Testes SEM JUnit: corre-se como uma aplicacao normal (java tpf.TestesLogica).
 * Termina com codigo 1 se algum teste falhar.
 *
 * Cobre os criterios de aceitacao que nao precisam do Discord:
 * argumentos invalidos, permissoes e filtro do canal #arquivo.
 *
 * Os testes de cifra e de registo duravel estao em TestesCifra e TestesArquivo.
 */
public class TestesLogica {

    private static int total = 0;
    private static int falhas = 0;

    public static void main(String[] args) {
        argumentosValidos();
        argumentosInvalidos();
        permissoes();
        canalArquivo();

        System.out.println();
        System.out.println(total + " verificacoes, " + falhas + " falhas.");
        if (falhas > 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- criterios

    static void argumentosValidos() {
        List<String> admin = List.of("membro", "admin-arquivo");

        // /registar e /rajada devolvem OK com mensagem vazia (o LeaderBot responde com o LSN).
        Resultado registar = Decisor.decidir("registar", List.of(), mapa("texto", "ola"));
        verificar("/registar valido", registar.isOk() && registar.getMensagem().isEmpty(), registar.toString());
        Resultado rajada = Decisor.decidir("rajada", admin, mapa("n", "500", "intervalo_ms", "0"));
        verificar("/rajada 500 0 valido", rajada.isOk() && rajada.getMensagem().isEmpty(), rajada.toString());
        Resultado rajadaLenta = Decisor.decidir("rajada", admin, mapa("n", "10", "intervalo_ms", "50"));
        verificar("/rajada 10 50 valido", rajadaLenta.isOk() && rajadaLenta.getMensagem().isEmpty(), rajadaLenta.toString());

        verificar("estado ok (provisorio)", ok(Decisor.decidir("estado", List.of(), mapa())), "ainda não implementado");
        verificar("config r 3 ok (admin, provisorio)",
                ok(Decisor.decidir("config", admin, mapa("subcomando", "r", "n", "3"))), "n=3");
        verificar("recuperar 10 20 provisorio",
                ok(Decisor.decidir("recuperar", List.of(), mapa("de", "10", "ate", "20"))), "de=10, ate=20");
        verificar("recuperar 5 5 ok (de == ate)",
                ok(Decisor.decidir("recuperar", List.of(), mapa("de", "5", "ate", "5"))), "de=5, ate=5");
        verificar("replica-token ok (admin)", ok(Decisor.decidir("replica-token", admin, mapa())), "/replica-token");
        verificar("replica-remover r1 ok (admin)",
                ok(Decisor.decidir("replica-remover", admin, mapa("id", "r1"))), "id=r1");
    }

    static void argumentosInvalidos() {
        List<String> admin = List.of("admin-arquivo");
        verificar("registar vazio recusado",
                erro(Decisor.decidir("registar", List.of(), mapa("texto", "   "))), "não vazio");
        verificar("registar texto enorme recusado",
                erro(Decisor.decidir("registar", List.of(), mapa("texto", "x".repeat(1001)))), "máximo");
        verificar("config r abc recusado",
                erro(Decisor.decidir("config", admin, mapa("subcomando", "r", "n", "abc"))), "número inteiro");
        verificar("config r 0 recusado (abaixo de R_MIN)",
                erro(Decisor.decidir("config", admin, mapa("subcomando", "r", "n", "0"))), "entre 1 e 10");
        verificar("config r 11 recusado (acima de R_MAX)",
                erro(Decisor.decidir("config", admin, mapa("subcomando", "r", "n", "11"))), "entre 1 e 10");
        verificar("config sem subcomando r recusado",
                erro(Decisor.decidir("config", admin, mapa("subcomando", "x", "n", "2"))), "subcomando `r`");
        verificar("recuperar 20 10 recusado",
                erro(Decisor.decidir("recuperar", List.of(), mapa("de", "20", "ate", "10"))), "não pode ser maior");
        verificar("recuperar de=abc recusado",
                erro(Decisor.decidir("recuperar", List.of(), mapa("de", "abc", "ate", "10"))), "números inteiros");
        verificar("recuperar de=0 recusado",
                erro(Decisor.decidir("recuperar", List.of(), mapa("de", "0", "ate", "10"))), "pelo menos 1");
        verificar("replica-remover id com espacos recusado",
                erro(Decisor.decidir("replica-remover", admin, mapa("id", "a b"))), "inválido");
        verificar("rajada n=0 recusado",
                erro(Decisor.decidir("rajada", admin, mapa("n", "0", "intervalo_ms", "10"))), "entre 1 e");
        verificar("rajada intervalo negativo recusado",
                erro(Decisor.decidir("rajada", admin, mapa("n", "5", "intervalo_ms", "-1"))), "intervalo_ms");
        verificar("numero gigante nao rebenta (overflow)",
                erro(Decisor.decidir("rajada", admin, mapa("n", "99999999999999999999", "intervalo_ms", "1"))), "inteiros");
        verificar("comando desconhecido recusado",
                erro(Decisor.decidir("apagar", admin, mapa())), "desconhecido");
    }

    static void permissoes() {
        List<String> semPapel = List.of("membro");
        for (String cmd : List.of("config", "replica-token", "replica-remover", "rajada")) {
            Resultado r = Decisor.decidir(cmd, semPapel,
                    mapa("subcomando", "r", "n", "2", "id", "r1", "intervalo_ms", "10"));
            verificar("sem admin-arquivo: /" + cmd + " recusado", erro(r), "Sem permissão");
        }
        verificar("sem admin-arquivo: /registar continua permitido",
                ok(Decisor.decidir("registar", semPapel, mapa("texto", "x"))), "");
        verificar("sem admin-arquivo: /estado continua permitido",
                ok(Decisor.decidir("estado", semPapel, mapa())), "estado");
        verificar("sem admin-arquivo: /recuperar continua permitido",
                ok(Decisor.decidir("recuperar", semPapel, mapa("de", "1", "ate", "2"))), "recuperar");
        verificar("permissao e verificada antes dos argumentos (nao revela validacao)",
                erro(Decisor.decidir("rajada", semPapel, mapa("n", "abc", "intervalo_ms", "x"))), "Sem permissão");
        verificar("lista de papeis null nao rebenta",
                erro(Decisor.decidir("rajada", null, mapa("n", "1", "intervalo_ms", "1"))), "Sem permissão");
        verificar("papel 'admin-arquivo' em maiusculas tambem conta",
                ok(Decisor.decidir("replica-token", List.of("Admin-Arquivo"), mapa())), "replica-token");
        verificar("papel parecido (admin-arquivos) NAO conta",
                erro(Decisor.decidir("replica-token", List.of("admin-arquivos"), mapa())), "Sem permissão");
        verificar("so 4 comandos sao admin", Comandos.ADMIN.size() == 4, "ADMIN=" + Comandos.ADMIN);
        verificar("existem exatamente 7 comandos", Comandos.TODOS.size() == 7, "TODOS=" + Comandos.TODOS);
    }

    static void canalArquivo() {
        verificar("pessoa em #arquivo e detetada", CanalArquivo.deveProcessar(false, true, "arquivo"), "");
        verificar("pessoa em #geral e ignorada", !CanalArquivo.deveProcessar(false, true, "geral"), "");
        verificar("bot em #arquivo e ignorado", !CanalArquivo.deveProcessar(true, true, "arquivo"), "");
        verificar("mensagem privada (DM) e ignorada", !CanalArquivo.deveProcessar(false, false, "arquivo"), "");
        verificar("canal null nao rebenta", !CanalArquivo.deveProcessar(false, true, null), "");
        verificar("#arquivo-antigo nao conta", !CanalArquivo.deveProcessar(false, true, "arquivo-antigo"), "");
        verificar("ID do canal arquivo e aceite", CanalArquivo.eCanalArquivo(123, 123), "");
        verificar("outro canal com nome igual e recusado por ID", !CanalArquivo.eCanalArquivo(123, 456), "");
        verificar("ID de canal inválido é recusado", !CanalArquivo.eCanalArquivo(0, 0), "");
    }

    // ---------------------------------------------------------------- auxiliares

    static Map<String, String> mapa(String... paresChaveValor) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < paresChaveValor.length; i += 2) {
            m.put(paresChaveValor[i], paresChaveValor[i + 1]);
        }
        return m;
    }

    /** Devolve o resultado como "OK:..." ou "ERRO:..." para a verificacao, conforme o esperado. */
    static Object ok(Resultado r) {
        return r.isOk() ? r.getMensagem() : null;
    }

    static Object erro(Resultado r) {
        return r.isOk() ? null : r.getMensagem();
    }

    /** Se 'obtido' for null o teste falha; se for texto, tem de conter o pedaco esperado. */
    static void verificar(String nome, Object obtido, String deveConter) {
        boolean passou;
        String detalhe;
        if (obtido instanceof Boolean b) {
            passou = b;
            detalhe = deveConter;
        } else if (obtido == null) {
            passou = false;
            detalhe = "resultado inesperado (era para ser o contrario)";
        } else {
            passou = obtido.toString().contains(deveConter);
            detalhe = obtido.toString();
        }
        total++;
        if (!passou) {
            falhas++;
        }
        System.out.println((passou ? "  ok   " : "  FALHOU ") + nome + (passou ? "" : "  -> " + detalhe));
    }
}
