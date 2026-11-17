# v1.2.0 — FSRS-5, Arena de Exatas, ReadEra, Canvas e Super-Otimização

## Principais novidades
- **Flashcards Pro & FSRS-5**: agendador FSRS-5 (19 pesos) e SM-2 configuráveis por baralho, oclusão de imagem com subamostragem segura, estudo personalizado, desfazimento transacional e importação `.apkg` com streaming em disco.
- **Arena de Exatas & LaTeX**: renderizador LaTeX nativo com proteção de profundidade, treino cronometrado com `elapsedRealtime()`, resumo por subtópico e geração de flashcards LaTeX direto dos erros da rodada.
- **Segundo Cérebro & Canvas**: grafo interativo, quadro Canvas infinito com persistência segura ao sair e extração modular de `CanvasBoardWorkspace.kt` e `NoteRichMarkdown.kt`.
- **Leitor ReadEra & Estante**: suporte a PDF/TXT/MD com paginação em background (`Dispatchers.Default`), `LruCache` de capas e timer isolado.
- **Captura Rápida & Backup Completo**: suporte a Compartilhar texto para o StudyDock (`ACTION_SEND`) virando Nota, Tarefa ou Flashcard, além de backup `.db` incluindo preferências de tema, leitor, áudio, editor e revisão.
- **Desempenho & Concorrência**: Room v32 com projeções leves (`noteHeadersFlow`, `flashcardsForSubject`, `flashcardsForNote`), migração completa para `java.time` e supertestes unitários e instrumentados.

# v1.0.1 — interface e fluxo de estudo

## Polimento
- Navegação adaptável: barra inferior no celular e barra lateral em telas largas, com conteúdo centralizado no tablet.
- Ícone próprio do StudyDock, no estilo do app e com suporte a ícones adaptativos do Android.
- Barras do sistema acompanham o modo claro ou escuro.
- Indicador fixo de sessão ativa ou pausada, para voltar ao timer e pausar/retomar de qualquer tela.
- Dashboard com data e saudação, atalho para estudar a matéria mais recente e acesso direto a tarefas, notas e revisões.
- Meta diária com anel de progresso e tempo restante.
- Pomodoro com anel visual de foco e pausa.
- Lista de matérias com tempo estudado hoje, total acumulado e comparação visual entre matérias.
- Timer: tocar no cartão de uma matéria começa o estudo; enquanto há uma sessão em andamento, os controles impedem trocar de matéria sem querer.
- Matérias: cartões mais compactos, edição e exclusão reunidas no menu e botão de iniciar sempre visível.
- Dashboard: o atalho principal muda para “Voltar para o timer” quando já existe uma sessão ativa ou pausa Pomodoro.
- Backup agora fecha o banco antes de guardar a cópia de segurança da restauração.

## Atualização
- `versionCode` passou para 4 e `versionName` para 1.0.1, para permitir instalar esta versão por cima da 1.0.0 sem desinstalar o app.

# v1.0.0

Revisão feita sem compilar neste ambiente: ao abrir no Android Studio, rode **Build → Rebuild Project** e confira os avisos. O banco de dados passou para a versão 11 e migra sozinho a partir da versão anterior, sem perder dados.

## Tarefas (estilo Todoist)
- **Adicionar rápido com linguagem natural**: digite `Revisar triângulos amanhã p1 #Geometria` e o app entende data (hoje, amanhã, sexta, 25/11, em 3 dias, semana que vem), prioridade (p1 a p4), matéria (`#nome`) e repetição (todo dia, toda semana, todo mês). Uma prévia mostra o que foi entendido antes de salvar.
- **Prioridades P1 a P4** com cores, **repetição** (a próxima ocorrência é criada ao concluir), **detalhes** e **nota ligada** em cada tarefa.
- **Filtros** Hoje, Próximas, Todas e Concluídas, com contagem, mais filtro por matéria e agrupamento por dia (atrasadas em vermelho).
- **Progresso de hoje** em porcentagem.
- **Estudar agora**: o botão de play numa tarefa com matéria abre o timer já iniciado nessa matéria.

## Notas (estilo Obsidian)
- **Tarefas dentro da nota**: cada nota mostra as tarefas ligadas a ela (com porcentagem) e permite criar novas com a mesma linguagem natural.
- **Nota de hoje** (daily note): um toque abre ou cria a nota do dia, no formato `AAAA-MM-DD`, com modelo pronto.
- **Fixar notas**, **busca** no título e no texto, **filtro por #tags** e contagem de links e tarefas em cada cartão.
- **Editor com barra de formatação** (negrito, itálico, título, lista, checklist, citação, código), **seletor de links** `[[ ]]` e botão de tag.
- **Prévia melhor**: títulos, listas, citações, código, links clicáveis e **checklists que dá para marcar direto na prévia**.
- **Salvamento automático** das notas existentes e aviso ao sair de uma nota nova sem salvar.
- **Renomear uma nota atualiza os `[[links]]`** nas outras notas.
- **Compartilhar nota** como texto, **contador de palavras** e tempo de leitura.
- **Corrigido**: editar uma nota ligada a um livro apagava o vínculo com o livro.

## Pomodoro ajustável
- Foco, pausa curta, pausa longa e blocos até a pausa longa são configuráveis (botão "Ajustar tempos" no timer), com atalhos Clássico, Foco profundo e Curto.

## Hábitos
- **Seletor de emoji** com 42 opções e campo para usar qualquer emoji do teclado.
- **Editar nome e emoji** de hábitos existentes (o lembrete é reagendado com o novo nome).
- Cartão **Hábitos de hoje** na tela inicial, com emojis, progresso e porcentagem.

## Porcentagens
- Meta diária, meta semanal, tarefas de hoje, hábitos de hoje, livros (porcentagem lida) e tarefas de cada nota.

## Outros
- Tarefas de hoje e atrasadas na tela inicial agora respeitam prioridade e repetição ao concluir.

## Arquivos novos
`ui/TodoPage.kt`, `ui/NotesPlus.kt`, `ui/Widgets.kt`.

## Ideias para depois da 1.0
- Lembretes de tarefas com horário, subtarefas, widget de tela inicial e sincronização entre aparelhos.

---

# v0.2.0 — novidades

Revisão feita sem compilar neste ambiente: ao abrir no Android Studio, rode **Build → Rebuild Project** e confira os avisos.

## Novo
- **Meta diária + sequência** (tela Hoje): barra de progresso até a meta de minutos do dia, ajustável, e contador de dias seguidos estudando.
- **Para hoje** (tela Hoje): tarefas atrasadas e do dia (dá pra concluir ali mesmo) e os compromissos de hoje. Some quando não há nada.
- **Resumo da semana** (tela Hoje): total da semana, comparação com a semana passada, dia mais forte e período preferido (manhã, tarde, noite ou madrugada).
- **Avisos do Pomodoro**: vibração e notificação ao terminar o foco e ao terminar a pausa. Funcionam enquanto o app ainda está em execução; se o Android encerrar o app em segundo plano, o aviso não dispara.
- **Tela ligada durante o timer** (opção em Mais, ligada por padrão).
- **Backup e restauração** (Mais): salva todo o banco em um arquivo e restaura depois. Ao restaurar, uma cópia dos dados atuais fica guardada no aparelho e o app fecha para recarregar. Capas de livros não entram no backup.

---

## Revisão e polimento (v0.1.1)

Revisão feita sem compilar neste ambiente: ao abrir no Android Studio, rode **Build → Rebuild Project** e confira os avisos.

## Corrigido
- **Botão Voltar do Android**: antes fechava o app em qualquer tela. Agora volta para a tela anterior (ex.: Editar nota → Notas → Organizar → Hoje) e só fecha o app na tela Hoje.
- **Navegação preservada**: a tela aberta (e a matéria/livro selecionados) sobrevive a girar o celular e a o Android recriar o app em segundo plano.
- **Lembretes de hábitos depois de reiniciar o celular**: o Android apaga os alarmes no reboot. Um novo `BootReceiver` reagenda todos os lembretes ao ligar o aparelho (nova permissão `RECEIVE_BOOT_COMPLETED`).
- **Modo escuro**: eixo do gráfico, números do mapa de atividade do mês e dia selecionado do calendário usavam branco/cinza fixos e ficavam ilegíveis; agora seguem o tema.
- **Backend (worker.js)**: não quebra mais se a OpenAI responder algo que não seja JSON.

## Novo
- **Excluir notas** (ícone de lixeira no editor, com confirmação).
- **Excluir contagens/metas** (lixeira no cartão, com confirmação).
- **Excluir compromissos** da agenda (lixeira ao lado do compromisso, com confirmação).

## Arquivos alterados
`MainActivity.kt`, `data/StudyDatabase.kt`, `ui/OrganizingPages.kt`, `habits/HabitReminderScheduler.kt`, `AndroidManifest.xml`, `backend/worker.js`, `README.md`.

## Ideias para a próxima rodada
- Aviso (vibração/notificação) ao fim do foco e da pausa do Pomodoro; tempos configuráveis (hoje fixos em 25/5/15 min).
- Exportar/importar backup dos dados.
- Dividir `OrganizingPages.kt` e `MainActivity.kt` em arquivos menores por tela.
