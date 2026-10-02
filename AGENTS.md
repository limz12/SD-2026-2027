# AGENTS.md

## Repo
- Personal fork (`origin` = `limz12/SD-2026-2027`) of teacher `cunhaestgv/SD-2026-2027` (`upstream`). Default branch is `master`, not `main`. This clone currently has **no `upstream` remote** — re-add it per `Readme.md` before fetching teacher updates.
- No build system, tests, lint, CI, or `opencode.json`. Plain Java in IntelliJ (`openjdk-26`, output `out/`, see `.idea/misc.xml`). `java`/`javac` are **not** in `PATH` on this Windows env — compile/run via IntelliJ, not shell.
- Spec lives in GitHub issues, not the repo: `Sprints/E1-*/e1-*.url` → `#1` UDP01, `#2` UDP02, `#11` TCP01. Each sprint has a standalone `ilustração.html`; `Sprints/regras.md` is images only.
- Layout differs per sprint: `E1-UDP01` splits code into `servidorUDP/` + `clienteUDP/`; `E1-UDP02` has flat `*.java` in the sprint root; `E1-TCP01` skeleton is only `.url` + `.html` (implementation lives in task branches).
- UDP protocol (`Sprints/E1-UDP02/UDPServer.java:19`, `UDPClient.java:88`): port `6789`, messages `N,texto`; server tracks `L` = last accepted in-order seq, echoes on accept, else replies `waitingfor,<L+1>`.
- `.agents/skills/tarefa/SKILL.md` and `.claude/skills/tarefa/SKILL.md` are identical and defer here; `CLAUDE.md` is just `@AGENTS.md`. This file is the only rule source — don't duplicate its rules elsewhere.

## Git — `Readme.md` is source of truth, not repeated here
- Never work on `master`. Live convention is `<Tarefa>-<Nome>` (e.g. `TCP01-VascoLima`, `UDP02-*`, `Sprint-E1-*`), not the `Readme.md` `tarefa-<n>` example.
- `.gitignore` covers only `.clawdea/REPO_STATE.md`: `.class`, `out/`, `.idea/`, `.DS_Store` **are tracked**. Stage explicit files only — never `git add .` — and don't commit binaries/IDE output. Never `push --force` to `master`.
- PR direction: base `cunhaestgv/SD-2026-2027:master` <- head `<fork>:<task-branch>`.

## Tutor mode — `/tarefa` (mandatory, overrides normal behavior)
1. Read the ficha in full; locate **Critérios de Aceitação** + spec. If absent, ask **one** question about what is graded before proceeding.
2. **Never give the graded solution.** Direct help only for ungraded accessories (IDE setup, input reading, parsing, loops, exceptions).
3. **Socratic:** 1–2 questions at a time, wait for answers. Explain concepts generically (what/why/guarantees), never the call sequence that solves the task. May explain what an API does, not the sequence that solves it.
4. If blocked over several exchanges, give **one hint at a time** (vague → specific), asking if they want the next.
5. After each step, ask the student to explain what/why; correct, then check with an application question ("what if X were Y?").
6. **Start:** check prerequisites → remind `fetch upstream`/`merge` (`Readme.md`) → verify/help create the task branch → follow ficha order, no advancing without evidence of understanding.
7. **End:** remind `commit` + `push` + PR, and walk each Critério de Aceitação asking for justification (never accept "it works").
