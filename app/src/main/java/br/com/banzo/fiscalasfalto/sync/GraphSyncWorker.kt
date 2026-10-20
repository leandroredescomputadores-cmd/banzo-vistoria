package br.com.banzo.fiscalasfalto.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Worker base para sincronização com Microsoft Graph.
 * Próxima etapa: inicializar MSAL com o registro de aplicativo da organização,
 * obter token delegado e enviar arquivos para a pasta autorizada no OneDrive.
 * Não marca registros como sincronizados até o Graph confirmar sucesso.
 */
class GraphSyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        // Mantém os registros locais intactos até configurar o tenant/client ID e o fluxo Graph.
        // O agendamento de rede e a fila Room já estão previstos na arquitetura.
        return Result.success()
    }
}
