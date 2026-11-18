# VEIL Launcher 1.1 — projeto Android

Launcher nativo em Kotlin + Jetpack Compose, com interface escura preto/azul-prateado.

## Recursos incluídos
- Atividade registrada como launcher padrão Android (HOME).
- Lista de aplicativos instalados que o Android permite consultar pela visibilidade de pacotes.
- Ícones reais dos aplicativos e busca por nome.
- Tela de configurações.
- Seleção individual de aplicativos para exigir autenticação ao abrir pelo VEIL.
- Autenticação combinada: 1 a 6 gestos desenhados, sequência de símbolos e PIN.
- Alteração de credenciais mediante confirmação da combinação atual.
- Redefinição local de credenciais.
- Hash PBKDF2-HMAC-SHA256 com salt aleatório para a combinação.
- Cofre de dados cifrado com AES-GCM usando chave do Android Keystore.
- Bloqueio temporário após cinco falhas consecutivas (30 segundos).

## Requisitos
- Android Studio recente
- JDK 17
- Android SDK 35
- Dispositivo Android 8.0 (API 26) ou superior

## Abrir o projeto
1. Extraia o ZIP.
2. Abra o Android Studio.
3. Selecione **Open** e escolha a pasta `VEILLauncher-Full`.
4. Aguarde a sincronização do Gradle.
5. Execute em um emulador ou celular Android.

## Gerar APK
No Android Studio:
**Build > Build Bundle(s) / APK(s) > Build APK(s)**

O APK de depuração costuma ser criado em:
`app/build/outputs/apk/debug/app-debug.apk`

Para APK de distribuição:
**Build > Generate Signed Bundle / APK > APK** e crie/seleciona uma chave de assinatura. Guarde a chave e as senhas com segurança.

## Tornar o VEIL o launcher padrão
No aparelho, abra Configurações > Aplicativos > Aplicativos padrão > App de início (o nome varia por fabricante) e escolha VEIL Launcher.

## Primeiro uso
1. Escolha quantos gestos quer (de 1 a 6) e desenhe cada um na área de desenho, em sequência. Cada traço é reconhecido em uma de 8 direções (↑ ↗ → ↘ ↓ ↙ ← ↖). Use **Desfazer** ou **Limpar** para corrigir.
2. Selecione de 3 a 6 símbolos na grade.
3. Crie e confirme um PIN de 6 a 12 dígitos.
4. Abra Configurações e ative a proteção nos aplicativos desejados.

## Limitações e segurança
- A autenticação é aplicada somente quando um aplicativo protegido é iniciado pela lista do VEIL. Não bloqueia abertura pelo launcher de fábrica, notificações, links externos, widgets, tela de recentes ou outros caminhos do sistema.
- Não substitui a tela de bloqueio nativa do Android nem protege o próprio dispositivo quando ele está desbloqueado.
- A proteção de aplicativos no Android de consumo não pode ser garantida por um launcher comum. Para cobertura de outros caminhos, seriam necessários recursos de sistema/gerenciamento corporativo, ou um serviço de acessibilidade com consentimento explícito; este projeto não instala nem oculta esse tipo de serviço.
- Cada gesto registra apenas a direção geral do traço (8 direções), não a trajetória completa. Usar mais gestos aumenta a segurança.
- O bloqueio temporário é local ao cofre do VEIL; não é um bloqueio global do Android.
- O projeto não foi compilado neste ambiente. Faça a sincronização e compilação no Android Studio; dependências podem exigir atualização conforme a versão instalada.
- O app não solicita `QUERY_ALL_PACKAGES`; usa uma consulta de intent LAUNCHER para listar apps visíveis, conforme as regras de visibilidade de pacotes do Android.
- Se a chave do Android Keystore for removida ou invalidada, o cofre cifrado pode ficar inacessível; reinstalação/limpeza de dados pode exigir reconfiguração.

## Gerar o APK sem Android Studio (GitHub Actions)
1. Envie esta pasta para um repositório no GitHub.
2. Abra **Actions > Build APK > Run workflow**.
3. Baixe o artefato `VEIL-Launcher-debug-apk` (contém `app-debug.apk`).

## Estrutura
```
VEILLauncher-Full/
├── .github/workflows/build-apk.yml
├── .gitignore
├── gradle/wrapper/gradle-wrapper.properties
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/veil/launcher/MainActivity.kt
        └── res/
            ├── drawable/ic_launcher_foreground.xml
            ├── mipmap-anydpi-v26/ic_launcher.xml
            ├── mipmap-anydpi-v26/ic_launcher_round.xml
            └── values/{styles,strings,ic_launcher_background}.xml
```
