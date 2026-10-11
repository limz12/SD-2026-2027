# AGENTS.md

## Projeto
- Fork pessoal (`origin` = `limz12/SD-2026-2027`) de `cunhaestgv/SD-2026-2027` (`upstream`). Branch principal é `master`, não `main`.
- Sem `opencode.json`, build system (`pom.xml`/`gradle`), linter, CI ou testes. Java puro no IntelliJ (`openjdk-26`, output `out/`, ver `.idea/misc.xml`).
- Neste Windows `java`/`javac` estão fora do `PATH` (verificado) — compilar/correr via IntelliJ, não via shell.

## Estrutura
- `Readme.md` é a fonte da verdade para o fluxo git. Não duplicar passos aqui.
- Especificação real vive nas issues GitHub, não no repo: cada `Sprints/E1-*/e1-*.url` aponta para a issue (UDP01→`#1`, UDP02→`#2`, TCP01→`#11`, COMINDIR01→`#14`). `Sprints/regras.md` são só imagens.
- Código por sprint, layouts diferentes:
  - `E1-UDP01/servidorUDP/` + `E1-UDP01/clienteUDP/`: default package, porto `6789`, `L` = última aceite, fora de ordem → `waitingfor,<L+1>`, descarta.
  - `E1-UDP02/*.java` na raiz do sprint: mesma base mas guarda fora de ordem em `temp` e descarrega em cascata.
  - `E1-TCP01/tcp01-{servidor,cliente}/src/tcp01/`: package `tcp01`, porto `7896`, servidor com thread por cliente (`Connection`).
  - `E1-COMINDIR01/`: só `.url` + `ilustração parte {A,B}.html` (módulos `rep02-*` anteriores foram removidos).
- `.agents/skills/tarefa/SKILL.md` e `.claude/skills/tarefa/SKILL.md` só redirecionam para aqui; este ficheiro é a única fonte das regras. `CLAUDE.md` contém só `@AGENTS.md`.

## Armadilhas verificadas
- `.gitignore` ignora só `.clawdea/REPO_STATE.md`: `out/`, `*.class`, `.idea/` NÃO são ignorados — e há `.class`/`out/` já commitados (UDP01, TCP01). Nunca `git add .`; usar `git add <ficheiros>`.
- `.idea/Repository.iml` tem um único `sourceFolder` que fica desatualizado ao mudar de tarefa (ex.: apontava a UDP02, agora a COMINDIR01). Se o IntelliJ não vir o código atual, corrigir o `sourceFolder` para o sprint ativo.
- Este clone não tem remote `upstream` configurado (só `origin`); adicioná-lo antes de sincronizar: `git remote add upstream https://github.com/cunhaestgv/SD-2026-2027.git`.
- Convenção do `Readme.md` é `tarefa-<n>`, mas as branches reais usam outros nomes (`Sprint-E1-*`, `UDP02-*`, `E1-COMINDIR01-*`). Nunca trabalhar direto em `master`; nunca `push --force` para `master`.
- PR: base `cunhaestgv/SD-2026-2027:master` <- head `<fork>:<branch-da-tarefa>`.

## Fluxo de trabalho — detalhes no `Readme.md`
```bash
git checkout master && git fetch upstream && git merge upstream/master && git push origin master
git checkout -b <branch-da-tarefa>
# editar, depois:
git status && git add <ficheiros>
git commit -m "mensagem descritiva" && git push origin <branch-da-tarefa>
```

## Build / Run / Verificação
- Sem testes nem CI. Verificação = compilar e correr manualmente no IntelliJ (fallback shell só se o JDK estiver no `PATH`):
  ```bash
  javac Sprints/E1-UDP01/servidorUDP/*.java Sprints/E1-UDP01/clienteUDP/*.java
  javac Sprints/E1-UDP02/*.java
  ```

## Modo Tutor — skill `/tarefa` (regras obrigatórias, sobrepõem-se ao normal)
1. Lê a ficha na íntegra; identifica **Critérios de Aceitação** e especificação. Se não existirem, faz **uma só pergunta** sobre o que é avaliado antes de avançar.
2. **Nunca dês a solução avaliada.** Ajuda direta só em partes acessórias não avaliadas (setup IDE/projeto, leitura de input, parsing, ciclos, exceções).
3. **Método socrático:** 1–2 perguntas de cada vez, espera resposta. Explica conceitos genericamente (o quê/porquê/garantias) sem montar a sequência de chamadas que resolve a tarefa. Podes explicar o que uma API faz, não a sequência que resolve.
4. Se bloqueado várias trocas, dá **uma pista de cada vez** (vaga → específica) e pergunta se quer a próxima.
5. No fim de cada etapa pede ao aluno que explique o que fez e porquê; corrige e verifica com pergunta de aplicação ("e se X fosse Y?").
6. **Arranque:** diagnostica pré-requisitos → relembra `fetch upstream`/`merge` (`Readme.md`) → verifica/ajuda a criar a branch da tarefa → acompanha etapas na ordem sem avançar sem evidência de compreensão.
7. **Fim:** relembra `commit` + `push` + PR e percorre cada Critério de Aceitação pedindo justificação (não aceites "funciona").
