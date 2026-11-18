# VEIL Launcher 1.1 — projeto Android

Launcher nativo em Kotlin + Jetpack Compose, com interface escura preto/azul-prateado.

## Recursos incluídos
- Launcher padrão (HOME) com lista de apps, ícones reais e busca.
- **Proteção em segundo plano:** um serviço de acessibilidade detecta quando um app protegido é aberto (por qualquer caminho: gaveta de apps, notificação, link, recentes) e mostra a tela de bloqueio do VEIL por cima.
- **Camada ao desbloquear o celular (opcional):** logo depois da tela de bloqueio do Android, o VEIL pede a combinação antes de liberar o uso.
- Autenticação combinada: 1 a 6 gestos desenhados (8 direções), sequência de 3 a 6 símbolos e PIN de 6 a 12 dígitos.
- Tempo de tolerância configurável (ao sair, 30 s, 1 min, 5 min) para não pedir a senha a cada troca de app.
- Tela de configurações protegida pela própria combinação.
- Hash PBKDF2-HMAC-SHA256 com salt aleatório; cofre cifrado com AES-GCM (Android Keystore).
- Bloqueio temporário de 30 s após cinco falhas consecutivas.
- A tela de bloqueio bloqueia capturas de tela (FLAG_SECURE) e não aparece nos recentes.

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
1. Escolha quantos gestos quer (1 a 6) e desenhe cada um em sequência. Use **Desfazer** ou **Limpar** para corrigir.
2. Selecione de 3 a 6 símbolos na grade.
3. Crie e confirme um PIN de 6 a 12 dígitos.
4. Abra **Configurações** (pede a combinação) e, em **Proteção em segundo plano**:
   - toque em **Serviço de acessibilidade**, abra **VEIL Proteção de apps** e ative;
   - toque em **Exibir sobre outros apps** e permita;
   - ative a proteção nos apps desejados e, se quiser, **Bloquear ao desbloquear o celular**.
5. Se o Android impedir de ativar o serviço (apps instalados fora da Play Store), vá em Configurações > Apps > VEIL Launcher > menu ⋮ > **Permitir configurações restritas** e tente de novo.

## Se esquecer a combinação
Em Configurações do Android > Apps > VEIL Launcher > Armazenamento, toque em **Limpar dados** (ou desinstale o app). Isso apaga o cofre e permite configurar de novo. As Configurações do Android nunca são bloqueadas pela camada "ao desbloquear", justamente para isso.

## Limitações e segurança (leia)
- O Android **não permite que um app substitua a tela de bloqueio do sistema**. A camada do VEIL aparece *depois* que o aparelho é desbloqueado e complementa a tela de bloqueio nativa, sem substituí-la. Mantenha PIN/biometria do Android ativados.
- A proteção depende do serviço de acessibilidade. Quem tem acesso às Configurações do Android com o aparelho desbloqueado pode desativá-lo, desinstalar o app ou limpar os dados. O VEIL não consegue impedir isso. Não proteja o app Configurações dentro do VEIL a menos que lembre bem da combinação.
- Há um instante muito curto, entre a abertura do app e a exibição do bloqueio, em que o app pode aparecer. O VEIL não é uma proteção contra alguém com conhecimento técnico e acesso físico prolongado.
- O serviço usa só o evento de mudança de janela; não lê textos, senhas ou o conteúdo de outros apps, e não há permissão de internet.
- Alguns fabricantes (Xiaomi, Samsung, Huawei, Oppo etc.) encerram serviços em segundo plano para economizar bateria. Se o bloqueio parar de funcionar, desative a otimização de bateria do VEIL e fixe-o nos apps recentes.
- Cada gesto registra a direção geral do traço (8 direções), não a trajetória inteira. Usar mais gestos aumenta a segurança.
- O bloqueio temporário é local ao VEIL; não é um bloqueio global do Android.
- Se a chave do Android Keystore for removida ou invalidada, o cofre pode ficar inacessível; reinstalar ou limpar os dados resolve.
- Na Google Play, apps que usam serviço de acessibilidade para esse fim têm regras e declarações próprias. Este projeto é pensado para uso pessoal.

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
├── build.gradle.kts / settings.gradle.kts / gradle.properties
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/veil/launcher/
        │   ├── MainActivity.kt      (launcher, configurações, cadastro)
        │   ├── SecureStore.kt       (cofre cifrado)
        │   ├── VeilGuardService.kt  (serviço em segundo plano)
        │   ├── LockActivity.kt      (tela de bloqueio)
        │   └── LockSession.kt       (estado compartilhado)
        └── res/
            ├── xml/accessibility_service_config.xml
            ├── drawable/ic_launcher_foreground.xml
            ├── mipmap-anydpi-v26/ic_launcher*.xml
            └── values/{styles,strings,ic_launcher_background}.xml
```
