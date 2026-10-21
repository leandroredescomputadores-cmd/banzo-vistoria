# Fiscal Asfalto — MVP Android

Projeto inicial Kotlin + Jetpack Compose para fiscalização de recomposição asfáltica.

## Implementado neste esqueleto
- Formulário de ocorrência com ID único, fiscal, endereço, referência, GPS manual/última posição conhecida.
- Lista de patologias predefinidas com opção “Outra — especificar”.
- Captura de fotos pela câmera do aparelho usando FileProvider e armazenamento privado do app.
- Controle inicial de status e número da OS.
- Banco local Room para registrar sem internet.
- PDF diário com os registros do dia e inclusão de imagens quando os caminhos locais são conhecidos.
- Agendamento WorkManager condicionado à existência de rede.

## Ainda precisa ser concluído antes de uso operacional
1. Testar o fluxo de câmera em aparelhos reais e otimizar/redimensionar fotos para reduzir espaço e tráfego.
2. Captura de localização atual com solicitação ativa de atualização, em vez de depender apenas da última posição conhecida.
3. Configuração do Microsoft Entra ID (tenant ID, client ID, redirect URI) e aprovação do administrador da organização.
4. Implementação real do Microsoft Graph para enviar fotos/PDF, resolver conflitos e marcar sincronização após confirmação do servidor. O `GraphSyncWorker` é apenas um ponto de partida e não envia arquivos ainda.
5. Configurar `FileProvider` para compartilhamento do PDF e validar build/instalação em Android Studio.
6. Testes de campo, de permissões, recuperação após reinicialização, integridade dos dados e acessibilidade.

## Abrir no Android Studio
Abra a pasta do projeto no Android Studio com JDK 17 e permita a sincronização do Gradle. O projeto usa compileSdk 35 e versões de dependências fixadas no `app/build.gradle.kts`. Se o Android Studio solicitar atualização de plugin/SDK, valide as mudanças antes de gerar APK.

## OneDrive institucional
Não inclua senha, segredo de cliente ou tokens no código-fonte. A configuração Microsoft precisa ser feita no portal Entra da organização e deve usar autenticação MSAL. O administrador deverá aprovar as permissões Microsoft Graph necessárias para a conta institucional.


## Gerar APK de teste sem instalar Android Studio no notebook

Este projeto inclui um fluxo de compilação em nuvem pelo GitHub Actions. Você precisará de uma conta GitHub e conexão com a internet; o notebook não precisa compilar o aplicativo localmente.

1. Crie um repositório no GitHub, por exemplo `FiscalAsfalto`.
2. Envie todo o conteúdo desta pasta `rasfalt` para a raiz do repositório (incluindo a pasta oculta `.github`).
3. No GitHub, abra a aba **Actions** e habilite os workflows, se solicitado.
4. Selecione **Build APK de teste** e clique em **Run workflow**. Também será executado a cada `push` na branch `main`.
5. Quando terminar, abra a execução concluída e baixe o artefato **FiscalAsfalto-debug-apk** na seção **Artifacts**. Dentro dele estará `app-debug.apk`.
6. Transfira o APK para o Android e autorize a instalação pelo navegador/gerenciador de arquivos, se o aparelho solicitar.

### Limitações importantes
- Este workflow tenta compilar o código atual; ainda não significa que o build já foi validado. Se houver erro de compilação, abra a execução em **Actions**, copie o primeiro erro e envie para correção.
- O APK de debug serve para teste, não é uma versão pronta para publicação na Play Store.
- A integração real com OneDrive/Microsoft Graph ainda exige configuração e desenvolvimento adicional. Não use esta versão para operação oficial até concluir os testes de GPS, fotos, PDF, armazenamento e sincronização.


## Rebranding: Banzo Vistoria
O nome exibido no launcher e na barra superior foi atualizado para **Banzo Vistoria**.
Foi aplicado um esquema inicial de cores inspirado na marca: preto carvão `#1D1917`, pêssego `#F9C493` e branco.
A logo e as referências visuais estão em `app/src/main/res/drawable-nodpi/`.
A imagem de conceito das telas está em `assets/banzo_vistoria_telas_conceito.png`.

**Nota:** a imagem é um mockup conceitual, não uma captura do aplicativo executando. O código continua precisando de compilação e teste; o fluxo de build em nuvem está em `.github/workflows/android.yml`. A integração real com Microsoft Graph/OneDrive e a validação funcional permanecem pendentes.
