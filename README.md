# StudyDock — seu espaço de estudos (v1.2.0)

Aplicativo Android pessoal offline-first, pensado para celular e adaptado para tablet, reunindo produtividade, revisão espaçada, matemática e segundo cérebro em um único app.

## O que está implementado (v1.2.0)

- **Dashboard & Plano do Dia**: resumo do dia, meta visual, tarefas e compromissos, hábitos, atalhos e sessões recentes.
- **Timer de Estudo (`StudySessionManager`)**: cronômetro, Pomodoro configurável e temporizador regressivo baseados em `SystemClock.elapsedRealtime()` e `BOOT_COUNT`, com serviço em segundo plano e notificação interativa.
- **Segundo Cérebro (Obsidian-style)**: notas Markdown com frontmatter YAML, `[[wikilinks]]`, backlinks, aliases, snapshots, lixeira, busca avançada, **Grafo Interativo 60fps** (`KnowledgeGraph`) e **Quadro Canvas infinito** (`CanvasBoardWorkspace`).
- **Flashcards & FSRS-5**: baralhos hierárquicos, agendador **FSRS-5** (19 pesos padrão + SM-2 configurável), modos Basic, Reverse, Cloze, Type-Answer e Oclusão de Imagem, importação/exportação `.apkg` em streaming e `.studydock`.
- **Arena de Exatas & LaTeX**: renderizador LaTeX nativo (`LatexMathView`), gerador de questões em 11 frentes (`MathArenaEngine`), treino cronometrado e conversão em 1 toque de erros da sessão para flashcards LaTeX.
- **Leitor Imersivo (ReadEra-style)**: leitura de PDF (`PdfRenderer`), TXT e Markdown com paginação em background, marcadores, destaques e timer discreto.
- **Hábitos, Metas & Diário**: estúdio de hábitos com lembretes exatos, metas com contagem regressiva e diário com check-in de humor/energia/foco.
- **Áudio Ambiente Offline**: sintetizador lo-fi, ruído branco/marrom/rosa, chuva e frequências binaurais em serviço de mídia em segundo plano.
- **Tutor IA & Proxy Seguro**: modos Aluno (Feynman) e Professor, com chave/token protegido no Android Keystore (`SecretStore`) e proxy Cloudflare Worker (`backend/worker.js`).
- **Captura Rápida**: recebe texto compartilhado de outros apps via menu Compartilhar do Android (`ACTION_SEND`) para criar Nota, Tarefa ou Flashcard na hora.
- **Banco Room local (v32)**: 33 entidades com migrações incrementais 1→32 e backup/restauração atômica `.db` (incluindo verificação `integrity_check`, `foreign_key_check` e preferências).

## Como abrir e executar

1. Abra a pasta do projeto no **Android Studio** ou use o terminal com JDK 17 e Android SDK 35.
2. Para compilar e rodar testes unitários:
   `./gradlew testDebugUnitTest assembleDebug`
3. Para rodar os testes instrumentados no aparelho/emulador:
   `./gradlew connectedDebugAndroidTest`
4. Veja detalhes de atualização em [CHANGELOG.md](CHANGELOG.md) e do proxy do assistente em [backend/README.md](backend/README.md).
