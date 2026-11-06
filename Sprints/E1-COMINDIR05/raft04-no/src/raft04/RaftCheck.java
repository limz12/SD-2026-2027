package raft04;

import java.io.File;
import java.nio.file.Files;
import java.util.*;

public class RaftCheck {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) { System.out.println("Uso: RaftCheck <N> <pasta> [falhas.txt]"); return; }
        int N = Integer.parseInt(args[0]);
        File dir = new File(args[1]);
        System.out.println("RaftCheck: N=" + N + " pasta=" + dir);
        for (int k=0;k<N;k++) {
            // eventos
            for (int i=1;i<=3;i++) {
                File ev = new File(dir, "eventos-p" + k + "-n" + i + ".txt");
                if (!ev.exists()) continue;
                int lideres=0, trunc=0, candidatos=0, termos=0;
                Set<Long> termosVistos = new HashSet<>();
                for (String l : Files.readAllLines(ev.toPath())) {
                    if (l.contains("LIDER;")) lideres++;
                    if (l.contains("TRUNCADO")) trunc++;
                    if (l.contains("CANDIDATO;")) candidatos++;
                    if (l.contains("TERMO;")) termos++;
                }
                System.out.println("p" + k + " n" + i + ": LIDER=" + lideres + " CANDIDATO=" + candidatos + " TRUNCADO=" + trunc + " TERMO=" + termos);
            }
            // hb
            for (int i=1;i<=3;i++) {
                File hb = new File(dir, "hb-p" + k + "-n" + i + ".txt");
                if (!hb.exists()) continue;
                int lotes = Files.readAllLines(hb.toPath()).size();
                System.out.println("p" + k + " n" + i + ": lotes=" + lotes);
            }
            // coincidencia final (seq ate ao menor ultimoSeq)
            // simplificado: contar linhas dos no-*
            for (int i=1;i<=3;i++) {
                File no = new File(dir, "no-p" + k + "-n" + i + ".txt");
                if (!no.exists()) continue;
                System.out.println("p" + k + " n" + i + ": registos=" + Files.readAllLines(no.toPath()).size());
            }
        }
        File prod = new File(dir, "produtor.txt");
        if (prod.exists()) System.out.println("produtor.txt linhas=" + Files.readAllLines(prod.toPath()).size());
        if (args.length > 2) System.out.println("falhas: " + args[2]);
        System.out.println("Relatório concluído.");
    }
}
