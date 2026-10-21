# Banzo Vistoria 0.4.2 — implementação visual

## Direção de interface
- Paleta carvão `#1D1917`, pêssego `#F9C493`, fundo claro e cartões com cantos arredondados.
- Painel com indicadores de total, pendências e ocorrências concluídas.
- Navegação inferior para Início, Ocorrências, Relatórios e Ajustes.
- Formulário guiado em quatro etapas: identificação, localização, avaliação técnica, evidências e revisão.
- Ficha de detalhes de ocorrência com identificação, GPS, avaliação e miniaturas fotográficas.
- Central de relatórios existente preservada para gerar, abrir, compartilhar e excluir PDFs.

## Preservação
A atualização mantém o modelo Room `Occurrence`, a base de dados e os caminhos atuais de fotos e relatórios. Não foi alterado o esquema do banco.

## Limitações conhecidas
- A localização usa GPS e geocodificação; mapa visual e mosaico de fotos como na imagem-conceito não foram implementados como mapa interativo/grade completa.
- O Microsoft Graph/OneDrive requer configuração de autenticação e tenant e não deve ser considerado sincronização funcional até esse trabalho ser concluído e testado.
- É necessário validar a compilação no GitHub Actions e testar em aparelho real antes de considerar a versão pronta para produção.
