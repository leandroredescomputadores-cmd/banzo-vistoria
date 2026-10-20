package br.com.banzo.fiscalasfalto.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "occurrences")
data class Occurrence(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val inspector: String,
    val address: String,
    val reference: String,
    val latitude: Double?,
    val longitude: Double?,
    val pathology: String,
    val pathologyOther: String,
    val description: String,
    val photoPaths: String,
    val workOrderNumber: String,
    val status: String,
    val synced: Boolean = false
)

object Pathologies {
    val options = listOf(
        "Afundamento da recomposição",
        "Desagregação do pavimento",
        "Trincas ou fissuras",
        "Bordas irregulares ou deterioradas",
        "Recalque da recomposição",
        "Desnível em relação ao pavimento",
        "Acabamento inadequado",
        "Material solto ou desprendimento",
        "Dimensões inadequadas da recomposição",
        "Outra — especificar"
    )
}

object OccurrenceStatus {
    val options = listOf(
        "Identificada",
        "Aguardando abertura da OS",
        "OS aberta",
        "Programada",
        "Em execução",
        "Aguardando verificação",
        "Corrigida e verificada",
        "Reprovada na verificação"
    )
}
