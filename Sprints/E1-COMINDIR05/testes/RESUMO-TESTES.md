# RESUMO DOS TESTES AUTOMÁTICOS — COMINDIR05

Data: 2026-11-10 10:28:21  ·  duração: 496 s

| Teste | Resultado | Detalhe |
| --- | --- | --- |
| sanidade: p0 elegeu líder | PASS |  |
| sanidade: p1 elegeu líder | PASS |  |
| sanidade: p2 elegeu líder | PASS |  |
| sanidade: sem termos com dois líderes | PASS |  |
| sanidade: sem leituras perdidas | PASS |  |
| sanidade: coincidência final OK | PASS |  |
| sanidade: CausalCheck OK | PASS |  |
| sanidade: primeiro líder p0 | INFO | n2, termo 1 |
| sanidade: primeiro líder p1 | INFO | n1, termo 1 |
| sanidade: primeiro líder p2 | INFO | n2, termo 1 |
| 9a: líder inicial de p1 | INFO | n3 (termo 2) |
| 9a: novo líder eleito após a falha | PASS |  |
| 9a: antigo líder registou TRUNCADO | PASS |  |
| 9a: sem termos com dois líderes | PASS |  |
| 9a: TRUNCADO com de=3 e descartados=1 | PASS |  |
| 9a: leitura durante a eleição perdida (21.1 ou 21.4) | PASS |  |
| 9a: coincidência final OK | PASS |  |
| 9a: CausalCheck OK (V[k]==seq através das mudanças de líder) | PASS |  |
| 9a: indisponibilidade medida | INFO | 7831 ms (previsto ]4000, 12200[ ms) |
| 9a: indisponibilidade no intervalo previsto | PASS |  |
| 9b-aleatorio-1: todas as partições elegeram líder | PASS |  |
| 9b-aleatorio-1: tempo até haver líder em p0/p1/p2 | INFO | 9466 ms |
| 9b-aleatorio-1: nº de termos observados (total) | INFO | 3 |
| 9b-aleatorio-1: votos divididos | INFO | 0 |
| 9b-aleatorio-1: nº de termos observados (total) | INFO | 3 |
| 9b-aleatorio-1: votos divididos | INFO | 0 |
| 9b-aleatorio-1: nº de termos observados (total) | INFO | 3 |
| 9b-aleatorio-1: votos divididos | INFO | 0 |
| 9b-aleatorio-1: sem termos com dois líderes | PASS |  |
| 9b-fixo-1: todas as partições elegeram líder | PASS |  |
| 9b-fixo-1: tempo até haver líder em p0/p1/p2 | INFO | 19197 ms |
| 9b-fixo-1: nº de termos observados (total) | INFO | 7 |
| 9b-fixo-1: votos divididos | INFO | 4 |
| 9b-fixo-1: nº de termos observados (total) | INFO | 7 |
| 9b-fixo-1: votos divididos | INFO | 4 |
| 9b-fixo-1: nº de termos observados (total) | INFO | 7 |
| 9b-fixo-1: votos divididos | INFO | 4 |
| 9b-fixo-1: sem termos com dois líderes | PASS |  |
| 9c-1: novo líder após a falha | PASS |  |
| 9c-1: sem termos com dois líderes | PASS |  |
| 9c-1: antigo líder truncou registos | PASS |  |
| 9c-1: houve leituras perdidas | PASS |  |
| 9c-1: coincidência final OK | PASS |  |
| 9c-1: CausalCheck OK | PASS |  |
| 9c-1: indisponibilidade | INFO | 7254 ms (previsto ]4000, 12200[ ms) |
| 9c-1: indisponibilidade no intervalo previsto | PASS |  |
| 9c-1: leituras p1 | INFO | enviadas=88, perdidas=77 |
| 9c-2: novo líder após a falha | PASS |  |
| 9c-2: sem termos com dois líderes | PASS |  |
| 9c-2: antigo líder truncou registos | PASS |  |
| 9c-2: houve leituras perdidas | PASS |  |
| 9c-2: coincidência final OK | PASS |  |
| 9c-2: CausalCheck OK | PASS |  |
| 9c-2: indisponibilidade | INFO | 6949 ms (previsto ]4000, 12200[ ms) |
| 9c-2: indisponibilidade no intervalo previsto | PASS |  |
| 9c-2: leituras p1 | INFO | enviadas=88, perdidas=27 |
| 9d: líder isolado | INFO | n2 (termo 1) |
| 9d-1: maioria elegeu novo líder durante o isolamento | PASS |  |
| 9d: dois líderes simultâneos | INFO | isolado n2 (termo 1) + maioria n3 (termo 2) |
| 9d-1: antigo líder demitiu-se após a reparação | PASS |  |
| 9d-1: antigo líder truncou os registos do isolamento | PASS |  |
| 9d-1: sem termos com dois líderes | PASS |  |
| 9d-1: coincidência final OK (registos reconciliados) | PASS |  |
| 9d-1: CausalCheck OK | PASS |  |
| 9d-1: houve truncagem no antigo líder | PASS |  |
| 9d-2: seguidor isolado | INFO | n1 (líder n2 no termo 1) |
| 9d-2: candidaturas do seguidor isolado | INFO | 6 (termos crescentes) |
| 9d-2: termo atingido pelo seguidor isolado | INFO | 7 |
| 9d-2: o regresso do seguidor isolado demitiu o líder saudável | PASS |  |
| 9d-2: nova eleição após o regresso | PASS |  |
| 9d-2: sem termos com dois líderes | PASS |  |
| 9d-2: termo nunca diminuiu | PASS |  |
| 9e: falha de dois nós | INFO | sobrevivente n1; terminados n2 (líder) e n |
| 9e: candidaturas do sobrevivente sem maioria | INFO | 3 |
| 9e: o sobrevivente nunca se elegeu sem maioria | PASS |  |
| 9e: líder eleito após o regresso de um nó | PASS |  |
| 9e: tempo até haver líder (desde o reinício) | INFO | 3608 ms |
| 9e: leituras da janela sem maioria perdidas | PASS |  |
| 9e: sem termos com dois líderes | PASS |  |
| f1: detetada — termos com MAIS de um líder | PASS |  |
| f1: eventos ERRO dois líderes | PASS |  |
| f1: registos divergem entre nós | PASS |  |
| f1: CausalCheck deteta erros | PASS |  |
| f3: pré-condição — líder inicial é n1 | PASS |  |
| f3: líder do termo 2 | INFO | n3 |
| f3: o nó ATRASADO (n3) foi eleito sem a restrição | PASS |  |
| f3: houve truncagem no nó atualizado (de=seq 4) | PASS |  |
| f3: registos do líder anterior descartados dos nós vivos | PASS |  |
| f3: a ELEIÇÃO continuou segura (sem dois líderes no mesmo termo) | PASS |  |
| f5: novo líder eleito | PASS |  |
| f5: CausalCheck deteta V[k] != seq (registos inconsistentes) | PASS |  |
| f5: resultado geral com erros | PASS |  |
| f2: o estado persistente NÃO foi gravado (mecanismo da falha ativo) | PASS |  |
| f4: sistema continua a funcionar com a falha (re-eleção) | PASS |  |
| f4: eleição mantém-se segura | PASS |  |

Total: 95 verificações, 0 FAIL.
