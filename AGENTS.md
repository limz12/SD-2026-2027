# AGENTS.md

## Projeto
- Fork pessoal (`origin`) de `cunhaestgv/SD-2026-2027` (`upstream`). Branch principal é `master`, não `main`.
- Sem `opencode.json`, build system, linter, CI ou testes. Sem `pom.xml`/`gradle`. Java puro no IntelliJ (`openjdk-26`, output `out/`, ver `.idea/misc.xml`).
- Neste ambiente Windows `java`/`javac` estão fora do `PATH` — compilar/correr via IntelliJ; só usar `javac`/`java` como fallback se o JDK estiver disponível.

## Estrutura
- `Readme.md` é a fonte da verdade para o fluxo git (fork → branch por tarefa → PR). Não duplicar passos aqui.
- Especificação real vive nas issues GitHub, não no repo: cada `Sprints/E1-*/e1-*.url` aponta para a issue (`#1`, `#2`, `#3`). `Sprints/regras.md` são só imagens.
- Código: `E1-UDP01/servidorUDP/` + `E1-UDP01/clienteUDP/` (UDP ordenado: `L` = última aceite, resposta `waitingfor,<L+1>`, porto `6789`); `E1-UDP02/*.java` na raiz do sprint. Ambos têm `ilustração.html` standalone. `E1-TCP01/` tem só o `.url`.
- `.agents/skills/tarefa/SKILL.md` e `.claude/skills/tarefa/SKILL.md` são idênticas e redirecionam para aqui (`/tarefa @ficha.md`); este ficheiro é a única fonte das regras.
- `CLAUDE.md` contém só `@AGENTS.md`.
- `.idea/` está commitado; `sourceFolder` atual é só `Sprints/E1-UDP02` (ver `.idea/Repository.iml`) — pode estar desatualizado ao mudar de tarefa. `.gitignore` ignora só `.clawdea/REPO_STATE.md`: `out/`, `*.class`, `.idea/` NÃO são ignorados.

## Fluxo de trabalho — detalhes no `Readme.md`
```bash
git checkout master && git fetch upstream && git merge upstream/master && git push origin master
git checkout -b tarefa-<n>   # nunca trabalhar direto em master; há branches reais fora do padrão (ex.: UDP02-VascoLima)
# editar, depois:
git status && git add <ficheiros>   # evitar `git add .`: arrastaria `out/`, `*.class`, `.idea/`
git commit -m "mensagem descritiva" && git push origin tarefa-<n>
# PR no GitHub: base `cunhaestgv/SD-2026-2027:master` <- head `<fork>:tarefa-<n>`; regra de ouro do Readme: nunca `push --force` para `master`, nem binários/IDE no commit
```

## Build / Run / Verificação
- Sem testes nem CI. Verificação = compilar e correr manualmente (IntelliJ, ou fallback):
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
6. **Arranque:** diagnostica pré-requisitos → relembra `fetch upstream`/`merge` (`Readme.md`) → verifica/ajuda a criar `branch tarefa-*` → acompanha etapas na ordem sem avançar sem evidência de compreensão.
7. **Fim:** relembra `commit` + `push` + PR e percorre cada Critério de Aceitação pedindo justificação (não aceites "funciona").
