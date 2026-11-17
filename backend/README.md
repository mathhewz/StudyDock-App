# Conectar o assistente sem colocar a chave no APK

O Assistente do StudyDock suporta três modos em `AssistantProxyClient`: **Google Gemini (API Key direta)**, **OpenAI / Groq / OpenRouter compatível** ou **Proxy Privado via Cloudflare Worker**. No modo Proxy Privado, o Worker chama `/v1/chat/completions`, guarda `OPENAI_API_KEY` como segredo no servidor e valida `STUDYDOCK_ACCESS_TOKEN` antes de responder. Todas as credenciais configuradas no app são cifradas pelo Android Keystore (`SecretStore`).

## Publicar o serviço

1. Crie uma conta na Cloudflare e abra **Workers & Pages**.
2. Crie um Worker novo e cole o conteúdo de `worker.js` no editor online. Salve e publique.
3. Nas configurações do Worker, abra **Variables and Secrets** e adicione estes itens como **Secret**:
   - `OPENAI_API_KEY`: uma chave criada na plataforma da API da OpenAI. A API pode ter cobrança separada da assinatura do ChatGPT.
   - `STUDYDOCK_ACCESS_TOKEN`: um token aleatório longo criado por você. Use o mesmo no app. Não publique nem envie esse token a outras pessoas.
4. Publique o Worker outra vez se a tela solicitar. A URL ficará parecida com `https://nome-do-worker.seu-usuario.workers.dev`.
5. No StudyDock, abra **Mais → Assistente → Configurar**. Cole a URL e o `STUDYDOCK_ACCESS_TOKEN`, depois salve.

`OPENAI_MODEL` é opcional. Se não for definido, o Worker usa `gpt-4o-mini`; você pode definir outro modelo disponível para sua conta como variável no Worker.

## Privacidade

- A chave OpenAI existe somente como segredo no Worker, nunca no código Android ou APK.
- O app envia a pergunta e, se a opção **Usar meus dados de estudo nesta pergunta** estiver ativada, um resumo com matérias, tempos de estudo, metas, compromissos, títulos e pequenos trechos das notas recentes.
- A opção de incluir o resumo pode ser desligada antes de enviar uma pergunta.
- O Worker envia a solicitação ao endpoint oficial `/v1/chat/completions` com `store: false` e limita o tamanho da pergunta, do contexto e da resposta.
- O token de acesso do Worker é cifrado no dispositivo com uma chave do Android Keystore. Se publicar a URL do Worker, deixe o token privado.

Sem publicar e configurar o Worker, as telas locais do app continuam funcionando, mas o chat não consegue responder.
