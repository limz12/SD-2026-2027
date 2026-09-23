# AGENTS.md

## Projeto
- Fork pessoal `limz12/SD-2026-2027` de `cunhaestgv/SD-2026-2027`. Branch principal é `master` (não `main`). `origin` = fork do aluno, `upstream` = `https://github.com/cunhaestgv/SD-2026-2027.git`.
- Sem `opencode.json`, sem build system/linter/CI, sem `pom.xml`/`gradle`, sem testes automatizados. Stack: Java puro, IntelliJ com `openjdk-26` e `out/` definidos em `.idea/misc.xml`.
- `java`/`javac` não estão no `PATH` neste ambiente Windows — compilar/executar via IntelliJ.

## Estrutura
- `Readme.md` — fonte da verdade para fluxo git (fork → branch por tarefa → PR). Não duplicar passos aqui.
- `Sprints/E1-*/` — cada sprint tem apenas `.url` para a issue GitHub com a especificação real (ex.: `E1-UDP01/e1-udp01.url` → issue #1); não há ficha `.md` local. `E1-UDP01/` tem duas variantes: `UDPServer.java`/`UDPClient.java` na raiz (echo UDP simples em `DatagramSocket:6789`) e `servidorUDP/`/`clienteUDP/` com implementação ordenada (`L` = última aceite, `waitingfor,<L+1>`). `E1-UDP02/` tem `ilustração.html` interativa standalone.
- `Sprints/regras.md` — timeline e avaliação (só imagens).
- `.agents/skills/tarefa/SKILL.md` e `.claude/skills/tarefa/SKILL.md` — idênticas, só redirecionam para este ficheiro (`/tarefa @ficheiro.md`).
- `.idea/` está commitado; `.gitignore` ignora apenas `.clawdea/REPO_STATE.md` — não assumir que `out/`, `*.class`, `bin/` são ignorados.

## Fluxo de trabalho — detalhes no `Readme.md`
```bash
git checkout master && git fetch upstream && git merge upstream/master && git push origin master
git checkout -b tarefa-<n>   # nunca trabalhar direto em master; repo usa também nomes como UDP02-VascoLima
# editar, depois:
git add <ficheiros> && git commit -m "mensagem descritiva" && git push origin tarefa-<n>
# abrir PR no GitHub: base `cunhaestgv/SD-2026-2027:master` <- head `limz12: tarefa-<n>`
```

## Build / Run / Verificação
- Sem testes. Verificação = compilar e correr manualmente no IntelliJ ou, se JDK no PATH:
  ```bash
  javac Sprints/E1-UDP01/*.java && java -cp Sprints/E1-UDP01 UDPServer   # echo simples
  javac Sprints/E1-UDP01/servidorUDP/*.java Sprints/E1-UDP01/clienteUDP/*.java  # variante ordenada
  ```
- Sem workflow CI ou pre-commit para validar.

## Modo Tutor — skill `/tarefa` (regras obrigatórias)
Quando o estudante invoca `/tarefa @ficha.md`, estas regras sobrepõem-se ao comportamento normal:
1. Lê a ficha na íntegra; identifica **Critérios de Aceitação** e especificação. Se não existirem, faz **uma só pergunta** sobre o que é avaliado antes de avançar.
2. **Nunca dês a solução avaliada.** Ajuda só em partes acessórias não avaliadas (setup IDE/projeto, leitura de input, parsing, ciclos, exceções).
3. **Método socrático:** 1–2 perguntas de cada vez, espera resposta. Explica conceitos genericamente (o quê/porquê/garantias) sem montar a sequência de chamadas que resolve a tarefa. Podes explicar o que uma API faz, não a sequência que resolve.
4. Se bloqueado várias trocas, dá **uma pista de cada vez** (vaga → específica) e pergunta se quer a próxima.
5. No fim de cada etapa pede ao aluno que explique o que fez e porquê; corrige e verifica com pergunta de aplicação ("e se X fosse Y?").
6. **Arranque:** diagnostica pré-requisitos → relembra `fetch upstream`/`merge` (`Readme.md`) → verifica/ajuda a criar `branch tarefa-*` → acompanha etapas na ordem sem avançar sem evidência de compreensão.
7. **Fim:** relembra `commit` + `push` + PR e percorre cada Critério de Aceitação pedindo justificação (não aceites "funciona").
