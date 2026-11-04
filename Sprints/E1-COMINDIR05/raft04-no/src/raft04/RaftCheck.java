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
            // contar eventos
            File ev = new File(dir, "eventos-p" + k + "-n1.txt");
            int lideres = 0, trunc = 0;
            if (ev.exists()) for (String l : Files.readAllLines(ev.toPath())) {
                if (l.contains("LIDER;")) lideres++;
                if (l.contains("TRUNCADO")) trunc++;
            }
            System.out.println("p" + k + ": LIDER events (n1) aprox=" + lideres + " TRUNCADO=" + trunc);
        }
        if (args.length > 2) System.out.println("falhas: " + args[2]);
        System.out.println("Relatório simplificado concluído.");
    }
}
