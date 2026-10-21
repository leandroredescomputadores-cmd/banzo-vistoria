# Banzo Vistoria 0.4.4 — correções consolidadas

- Campo Endereço removido da etapa Identificação; permanece na etapa Localização para edição manual e preenchimento via GPS.
- Miniatura OpenStreetMap: habilitado JavaScript e armazenamento DOM no WebView, além de configurações de viewport e fundo. O mapa depende de conexão e de o serviço externo estar acessível.
- PDF: fotografias são redimensionadas para até 1800 px no lado maior e recomprimidas em JPEG a 82% para a cópia incorporada. Os arquivos originais não são alterados.
- Banco Room, campos de ocorrência e fluxo de edição mantidos.

A compilação ainda precisa ser validada pelo GitHub Actions/Android Studio e o mapa/PDF devem ser testados em aparelho real.
