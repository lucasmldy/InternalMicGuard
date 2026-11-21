# InternalMicGuard

Força o Android a usar o **microfone interno** do celular mesmo com um **fone P2** (ou USB) conectado — o áudio continua saindo pelo fone, mas a captação passa a ser feita pelo mic do aparelho. Sem root: o roteamento é aplicado pelo `IAudioService` do sistema através do **Shizuku**.

## Requisitos

- Android 13 ou superior (API 33+).
- [Shizuku](https://shizuku.rikka.app/) instalado e **ativo** (iniciado via Depuração sem fio).

## Instalação

- **Releases:** baixe o `InternalMicGuard-vX.Y.Z.apk` mais recente na aba [Releases](../../releases) e instale.
- **Obtainium:** adicione a URL deste repositório no [Obtainium](https://github.com/ImranR98/Obtainium) para receber atualizações automaticamente.

## Como usar

1. Abra o Shizuku e inicie o serviço.
2. Abra o InternalMicGuard e toque em **Permitir** para conceder acesso ao Shizuku.
3. Escolha o microfone interno desejado e, se quiser, ative **Bloquear mic do P2**.
4. Ligue a chave **Forçar mic interno**.

### Atalho na barra de notificações

Edite os blocos de Configurações rápidas e adicione o bloco **Mic interno**. Um toque liga/desliga o roteamento sem abrir o app (usa o mic e as opções salvas no app). O bloco fica indisponível enquanto o Shizuku não estiver pronto.

### Diagnóstico

A seção **Diagnóstico** (recolhida por padrão) tem um teste de captação ao vivo, que mostra o nível em dBFS e o dispositivo realmente usado, além do log com o botão **Copiar Logs**, útil para relatar problemas.

## Limitações conhecidas

- Depois de reiniciar o celular, o Shizuku precisa ser iniciado novamente e o roteamento reaplicado.
- Em chamadas telefônicas comuns, alguns fabricantes/operadoras usam um caminho de áudio próprio e podem ignorar a preferência de microfone. Apps de chamada por internet e gravação costumam respeitá-la.
- O bloqueio do mic do P2 só funciona se a versão do Android expuser um método compatível no `IAudioService`; caso contrário, apenas o mic interno preferido é aplicado.

## Licença

[MIT](LICENSE)
