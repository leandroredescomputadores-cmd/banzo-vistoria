package br.com.banzo.fiscalasfalto

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import java.io.File
import android.graphics.BitmapFactory
import android.location.Location
import android.location.Geocoder
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import br.com.banzo.fiscalasfalto.data.AppDatabase
import br.com.banzo.fiscalasfalto.data.Occurrence
import br.com.banzo.fiscalasfalto.data.OccurrenceStatus
import br.com.banzo.fiscalasfalto.data.Pathologies
import br.com.banzo.fiscalasfalto.sync.GraphSyncWorker
import br.com.banzo.fiscalasfalto.util.PdfReport
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    private val db by lazy { AppDatabase.get(this) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scheduleSync()
        setContent {
            val banzoPeach = androidx.compose.ui.graphics.Color(0xFFF9C493)
            val banzoBlack = androidx.compose.ui.graphics.Color(0xFF1D1917)
            MaterialTheme(
                colorScheme = androidx.compose.material3.lightColorScheme(
                    primary = banzoBlack,
                    onPrimary = androidx.compose.ui.graphics.Color.White,
                    secondary = banzoPeach,
                    onSecondary = banzoBlack,
                    tertiary = banzoPeach,
                    background = androidx.compose.ui.graphics.Color(0xFFFFFCFA),
                    surface = androidx.compose.ui.graphics.Color.White,
                    onSurface = banzoBlack
                )
            ) { InspectionApp() }
        }
    }

    private fun scheduleSync() {
        val request = PeriodicWorkRequestBuilder<GraphSyncWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("onedrive_sync", ExistingPeriodicWorkPolicy.KEEP, request)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun InspectionApp() {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var occurrences by remember { mutableStateOf(emptyList<Occurrence>()) }
        var inspector by remember { mutableStateOf("") }
        var address by remember { mutableStateOf("") }
        var reference by remember { mutableStateOf("") }
        var pathology by remember { mutableStateOf(Pathologies.options.first()) }
        var other by remember { mutableStateOf("") }
        var description by remember { mutableStateOf("") }
        var lat by remember { mutableStateOf("") }
        var lon by remember { mutableStateOf("") }
        var expanded by remember { mutableStateOf(false) }
        var status by remember { mutableStateOf(OccurrenceStatus.options.first()) }
        var workOrder by remember { mutableStateOf("") }
        var photos by remember { mutableStateOf("") }
        var pendingPhotoPath by remember { mutableStateOf("") }
        var locationPending by remember { mutableStateOf(false) }
        var selectedPhotoPath by remember { mutableStateOf<String?>(null) }
        var screen by remember { mutableStateOf("home") }
        var editingId by remember { mutableStateOf<String?>(null) }
        var searchQuery by remember { mutableStateOf("") }
        var reportFiles by remember { mutableStateOf(emptyList<File>()) }
        var reportToDelete by remember { mutableStateOf<File?>(null) }
        var occurrenceToDelete by remember { mutableStateOf<Occurrence?>(null) }
        fun refreshReports() {
            val dir = File(getExternalFilesDir(null) ?: filesDir, "relatorios")
            reportFiles = dir.listFiles { f -> f.isFile && f.extension.equals("pdf", true) }
                ?.sortedByDescending { it.lastModified() } ?: emptyList()
        }

        // Obtém uma posição atual (não depende apenas de uma localização antiga em cache).
        fun applyCapturedLocation(location: Location?) {
            if (location == null) {
                locationPending = false
                Toast.makeText(context, "Ainda não foi possível obter uma posição. Tente novamente em local aberto.", Toast.LENGTH_LONG).show()
                return
            }
            lat = String.format(Locale.US, "%.6f", location.latitude)
            lon = String.format(Locale.US, "%.6f", location.longitude)
            Toast.makeText(context, "Coordenadas capturadas. Buscando endereço…", Toast.LENGTH_SHORT).show()
            scope.launch {
                val resolvedAddress = withContext(Dispatchers.IO) {
                    try {
                        val geocoder = Geocoder(context, Locale("pt", "BR"))
                        @Suppress("DEPRECATION")
                        geocoder.getFromLocation(location.latitude, location.longitude, 1)
                            ?.firstOrNull()?.getAddressLine(0)
                    } catch (_: Exception) {
                        null
                    }
                }
                if (!resolvedAddress.isNullOrBlank()) {
                    // O campo permanece editável para correções feitas pelo fiscal.
                    address = resolvedAddress
                    Toast.makeText(context, "GPS e endereço preenchidos. Você pode corrigir o endereço.", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(context, "GPS capturado, mas o endereço automático não foi localizado. Você pode preenchê-lo manualmente.", Toast.LENGTH_LONG).show()
                }
                locationPending = false
            }
        }


        fun fetchCurrentLocation() {
            val fine = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val coarse = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (!fine && !coarse) {
                locationPending = false
                Toast.makeText(context, "Permita o acesso à localização para capturar o GPS.", Toast.LENGTH_LONG).show()
                return
            }

            locationPending = true
            val client = LocationServices.getFusedLocationProviderClient(this@MainActivity)
            val token = CancellationTokenSource()
            try {
                client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, token.token)
                    .addOnSuccessListener { current ->
                        if (current != null) {
                            applyCapturedLocation(current)
                        } else {
                            client.lastLocation
                                .addOnSuccessListener { last -> applyCapturedLocation(last) }
                                .addOnFailureListener {
                                    locationPending = false
                                    Toast.makeText(context, "Não foi possível obter a localização. Confira o GPS e tente novamente em local aberto.", Toast.LENGTH_LONG).show()
                                }
                        }
                    }
                    .addOnFailureListener {
                        client.lastLocation
                            .addOnSuccessListener { last -> applyCapturedLocation(last) }
                            .addOnFailureListener {
                                locationPending = false
                                Toast.makeText(context, "Falha ao obter a localização. Confira o GPS e tente novamente.", Toast.LENGTH_LONG).show()
                            }
                    }
            } catch (_: SecurityException) {
                locationPending = false
                Toast.makeText(context, "Sem permissão de localização. Confira as permissões do aplicativo.", Toast.LENGTH_LONG).show()
            }
        }

        val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true || result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            if (granted) fetchCurrentLocation()
            else {
                locationPending = false
                Toast.makeText(context, "Permissão de localização negada. Ative-a nas configurações do aplicativo.", Toast.LENGTH_LONG).show()
            }
        }

        val photoCapture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            val capturedPath = pendingPhotoPath
            pendingPhotoPath = ""
            if (success && capturedPath.isNotBlank() && File(capturedPath).exists() && File(capturedPath).length() > 0L) {
                photos = (photos.split("\n").filter { it.isNotBlank() } + capturedPath).joinToString("\n")
                Toast.makeText(context, "Foto adicionada à ocorrência", Toast.LENGTH_SHORT).show()
            } else if (!success) {
                capturedPath.takeIf { it.isNotBlank() }?.let { File(it).delete() }
                Toast.makeText(context, "A captura foi cancelada ou não foi concluída. Tente novamente.", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(context, "A câmera não salvou a fotografia. Tente novamente.", Toast.LENGTH_LONG).show()
            }
        }

        fun launchPhotoCapture() {
            try {
                val dir = File(getExternalFilesDir("photos") ?: filesDir, "occurrences")
                if (!dir.exists() && !dir.mkdirs()) {
                    Toast.makeText(context, "Não foi possível preparar a pasta das fotografias.", Toast.LENGTH_LONG).show()
                    return
                }
                val file = File(dir, "foto_${System.currentTimeMillis()}.jpg")
                val uri = androidx.core.content.FileProvider.getUriForFile(this@MainActivity, "${packageName}.files", file)
                pendingPhotoPath = file.absolutePath
                photoCapture.launch(uri)
            } catch (e: Exception) {
                pendingPhotoPath = ""
                Toast.makeText(context, "Não foi possível abrir a câmera: ${e.localizedMessage ?: "erro inesperado"}", Toast.LENGTH_LONG).show()
            }
        }

        val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) launchPhotoCapture()
            else Toast.makeText(context, "Permissão da câmera negada. Para fotografar, permita o acesso à câmera nas configurações do Banzo Vistoria.", Toast.LENGTH_LONG).show()
        }
        LaunchedEffect(Unit) { db.occurrenceDao().observeAll().collectLatest { occurrences = it } }
        Scaffold(topBar = { TopAppBar(title = {
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                Text("BANZO VISTORIA", style = MaterialTheme.typography.titleMedium)
                Text("Fiscalização de recomposição asfáltica", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }) }) { padding ->
            when (screen) {
                "home" -> LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { Text("Painel de fiscalização", style = MaterialTheme.typography.headlineSmall) }
                    item { Text("Acompanhe as vistorias e os relatórios registrados neste aparelho.", style = MaterialTheme.typography.bodyMedium) }
                    item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Card(Modifier.weight(1f)) { Column(Modifier.padding(16.dp)) { Text("OCORRÊNCIAS", style = MaterialTheme.typography.labelSmall); Text("${occurrences.size}", style = MaterialTheme.typography.headlineMedium) } }
                        Card(Modifier.weight(1f)) { Column(Modifier.padding(16.dp)) { Text("PENDENTES", style = MaterialTheme.typography.labelSmall); Text("${occurrences.count { it.status != "Corrigida e verificada" }}", style = MaterialTheme.typography.headlineMedium) } }
                    } }
                    item { Button(onClick = { editingId = null; inspector = ""; address = ""; reference = ""; pathology = Pathologies.options.first(); other = ""; description = ""; lat = ""; lon = ""; photos = ""; workOrder = ""; status = OccurrenceStatus.options.first(); screen = "form" }, modifier = Modifier.fillMaxWidth()) { Text("+ Nova vistoria") } }
                    item { Button(onClick = { screen = "occurrences" }, modifier = Modifier.fillMaxWidth()) { Text("Ver e gerenciar ocorrências") } }
                    item { Button(onClick = { refreshReports(); screen = "reports" }, modifier = Modifier.fillMaxWidth()) { Text("Central de relatórios PDF") } }
                    item { Text("Últimas ocorrências", style = MaterialTheme.typography.titleLarge) }
                    items(occurrences.take(3), key = { it.id }) { item ->
                        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(item.id, style = MaterialTheme.typography.titleMedium)
                            Text(item.address.ifBlank { "Endereço não informado" })
                            Text("${item.pathology} • ${item.status}", style = MaterialTheme.typography.bodySmall)
                        } }
                    }
                }
                "reports" -> LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item { Button(onClick = { screen = "home" }) { Text("← Painel inicial") } }
                    item { Text("Central de relatórios", style = MaterialTheme.typography.headlineSmall) }
                    item { Text("Os PDFs são armazenados no aparelho. Excluir um PDF não exclui a ocorrência.", style = MaterialTheme.typography.bodySmall) }
                    item { Button(onClick = { scope.launch {
                        val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY,0); set(Calendar.MINUTE,0); set(Calendar.SECOND,0); set(Calendar.MILLISECOND,0) }
                        val start = cal.timeInMillis; cal.add(Calendar.DAY_OF_MONTH,1)
                        val list = db.occurrenceDao().forPeriod(start, cal.timeInMillis)
                        val file = PdfReport.createDaily(context, list)
                        refreshReports()
                        sharePdf(file)
                    } }, modifier = Modifier.fillMaxWidth()) { Text("Gerar relatório diário de hoje") } }
                    item { Button(onClick = { scope.launch {
                        val file = PdfReport.createDaily(context, occurrences)
                        refreshReports(); sharePdf(file)
                    } }, modifier = Modifier.fillMaxWidth()) { Text("Gerar relatório de todas as ocorrências") } }
                    if (reportFiles.isEmpty()) item { Text("Nenhum PDF salvo ainda.", modifier = Modifier.padding(8.dp)) }
                    items(reportFiles, key = { it.absolutePath }) { file ->
                        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(file.name, style = MaterialTheme.typography.titleSmall)
                            Text("${file.length() / 1024} KB • ${SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR")).format(Date(file.lastModified()))}", style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { openPdf(file) }) { Text("Abrir") }
                                Button(onClick = { sharePdf(file) }) { Text("Compartilhar") }
                                Button(onClick = { reportToDelete = file }) { Text("Excluir PDF") }
                            }
                        } }
                    }
                }
                "occurrences" -> LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item { Button(onClick = { screen = "home" }) { Text("← Painel inicial") } }
                    item { Text("Ocorrências registradas", style = MaterialTheme.typography.headlineSmall) }
                    item { OutlinedTextField(searchQuery, { searchQuery = it }, label = { Text("Buscar por ID, endereço, OS ou patologia") }, modifier = Modifier.fillMaxWidth()) }
                    val filtered = occurrences.filter { listOf(it.id,it.address,it.workOrderNumber,it.pathology,it.inspector).any { value -> value.contains(searchQuery, ignoreCase = true) } }
                    if (filtered.isEmpty()) item { Text("Nenhuma ocorrência encontrada.") }
                    items(filtered, key = { it.id }) { item ->
                        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(item.id, style = MaterialTheme.typography.titleMedium)
                            Text(item.address.ifBlank { "Endereço não informado" })
                            Text("${item.pathology} • ${item.status}")
                            if (item.workOrderNumber.isNotBlank()) Text("OS: ${item.workOrderNumber}")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
                                    editingId = item.id; inspector = item.inspector; address = item.address; reference = item.reference
                                    lat = item.latitude?.toString() ?: ""; lon = item.longitude?.toString() ?: ""
                                    pathology = item.pathology; other = item.pathologyOther; description = item.description
                                    photos = item.photoPaths; workOrder = item.workOrderNumber; status = item.status; screen = "form"
                                }) { Text("Editar") }
                                Button(onClick = { scope.launch { sharePdf(PdfReport.createSingle(context, item)) } }) { Text("PDF") }
                                Button(onClick = { occurrenceToDelete = item }) { Text("Excluir") }
                            }
                        } }
                    }
                }
                else -> LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item { Button(onClick = { screen = "home" }) { Text("← Cancelar e voltar") } }
                    item { Text(if (editingId == null) "Nova ocorrência" else "Editar ocorrência ${editingId}", style = MaterialTheme.typography.headlineSmall) }
                    item { OutlinedTextField(inspector, { inspector = it }, label = { Text("Fiscal responsável") }, modifier = Modifier.fillMaxWidth()) }
                    item { OutlinedTextField(address, { address = it }, label = { Text("Endereço") }, modifier = Modifier.fillMaxWidth()) }
                    item { OutlinedTextField(reference, { reference = it }, label = { Text("Ponto de referência") }, modifier = Modifier.fillMaxWidth()) }
                    item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(lat, { lat = it }, label = { Text("Latitude") }, modifier = Modifier.weight(1f))
                        OutlinedTextField(lon, { lon = it }, label = { Text("Longitude") }, modifier = Modifier.weight(1f))
                    } }
                    item { Button(onClick = {
                        val fine = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                        val coarse = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                        if (fine || coarse) fetchCurrentLocation() else locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    }, enabled = !locationPending, modifier = Modifier.fillMaxWidth()) { Text(if (locationPending) "Obtendo localização…" else "Capturar coordenadas GPS e endereço") } }
                    item { ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                        OutlinedTextField(value = pathology, onValueChange = {}, readOnly = true, label = { Text("Patologia") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, modifier = Modifier.menuAnchor().fillMaxWidth())
                        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) { Pathologies.options.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { pathology = option; expanded = false }) } }
                    } }
                    if (pathology == "Outra — especificar") item { OutlinedTextField(other, { other = it }, label = { Text("Especifique a patologia") }, modifier = Modifier.fillMaxWidth()) }
                    item { OutlinedTextField(description, { description = it }, label = { Text("Descrição / providência solicitada") }, minLines = 3, modifier = Modifier.fillMaxWidth()) }
                    item {
                        val photoPaths = photos.split("\n").filter { it.isNotBlank() }
                        Text("Fotografias: ${photoPaths.size}")
                        if (photoPaths.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            photoPaths.forEachIndexed { index, path ->
                                val thumbnail = remember(path) { val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(path, bounds); var sample = 1; while (bounds.outWidth / sample > 320 || bounds.outHeight / sample > 320) sample *= 2; BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap() }
                                Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) { if (thumbnail != null) Image(bitmap = thumbnail, contentDescription = "Miniatura ${index + 1}", modifier = Modifier.size(96.dp).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)).padding(2.dp), contentScale = ContentScale.Crop); Text("Foto ${index + 1}") }
                            }
                        }
                        Button(onClick = { val granted = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED; if (granted) launchPhotoCapture() else cameraPermission.launch(Manifest.permission.CAMERA) }, modifier = Modifier.fillMaxWidth()) { Text("Fotografar local") }
                    }
                    item { OutlinedTextField(workOrder, { workOrder = it }, label = { Text("Número da ordem de serviço (se houver)") }, modifier = Modifier.fillMaxWidth()) }
                    item { var statusExpanded by remember { mutableStateOf(false) }; ExposedDropdownMenuBox(expanded = statusExpanded, onExpandedChange = { statusExpanded = !statusExpanded }) {
                        OutlinedTextField(status, {}, readOnly = true, label = { Text("Status") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(statusExpanded) }, modifier = Modifier.menuAnchor().fillMaxWidth())
                        ExposedDropdownMenu(expanded = statusExpanded, onDismissRequest = { statusExpanded = false }) { OccurrenceStatus.options.forEach { opt -> DropdownMenuItem(text = { Text(opt) }, onClick = { status = opt; statusExpanded = false }) } }
                    } }
                    item { Button(onClick = {
                        if (pathology == "Outra — especificar" && other.isBlank()) { Toast.makeText(context, "Descreva a patologia selecionada como Outra.", Toast.LENGTH_SHORT).show(); return@Button }
                        scope.launch {
                            val now = System.currentTimeMillis(); val existing = editingId?.let { id -> occurrences.firstOrNull { it.id == id } }
                            val id = existing?.id ?: "RA-${SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(now))}-${UUID.randomUUID().toString().take(4).uppercase(Locale.US)}"
                            val item = Occurrence(id, existing?.createdAt ?: now, inspector.trim(), address.trim(), reference.trim(), lat.toDoubleOrNull(), lon.toDoubleOrNull(), pathology, other.trim(), description.trim(), photos.trim().replace(";", "\n"), workOrder.trim(), status, existing?.synced ?: false)
                            db.occurrenceDao().upsert(item)
                            editingId = null; inspector = ""; address = ""; reference = ""; pathology = Pathologies.options.first(); other = ""; description = ""; lat = ""; lon = ""; photos = ""; workOrder = ""; status = OccurrenceStatus.options.first(); screen = "occurrences"
                            Toast.makeText(context, "Ocorrência ${if (existing == null) "salva" else "atualizada"}: $id", Toast.LENGTH_LONG).show()
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text(if (editingId == null) "Salvar ocorrência offline" else "Salvar alterações") }
                    }
                }
            }
        }
        if (reportToDelete != null) AlertDialog(onDismissRequest = { reportToDelete = null }, title = { Text("Excluir relatório?") }, text = { Text("O PDF será removido do aparelho. A ocorrência continuará salva.") }, confirmButton = { Button(onClick = { reportToDelete?.delete(); reportToDelete = null; refreshReports() }) { Text("Excluir PDF") } }, dismissButton = { Button(onClick = { reportToDelete = null }) { Text("Cancelar") } })
        if (occurrenceToDelete != null) AlertDialog(onDismissRequest = { occurrenceToDelete = null }, title = { Text("Excluir ocorrência?") }, text = { Text("A ocorrência ${occurrenceToDelete?.id} será removida do banco local. Essa ação não pode ser desfeita.") }, confirmButton = { Button(onClick = { val item = occurrenceToDelete; occurrenceToDelete = null; if (item != null) scope.launch { db.occurrenceDao().deleteById(item.id); Toast.makeText(context, "Ocorrência excluída", Toast.LENGTH_SHORT).show() } }) { Text("Excluir ocorrência") } }, dismissButton = { Button(onClick = { occurrenceToDelete = null }) { Text("Cancelar") } })
    }

    private fun openPdf(file: File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", file)
            val intent = Intent(Intent.ACTION_VIEW).apply { setDataAndType(uri, "application/pdf"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            startActivity(Intent.createChooser(intent, "Abrir relatório PDF"))
        } catch (_: Exception) { Toast.makeText(this, "Não foi encontrado um aplicativo para abrir PDF.", Toast.LENGTH_LONG).show() }
    }

    private fun sharePdf(file: File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"; putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Banzo Vistoria — ${file.nameWithoutExtension}")
                putExtra(Intent.EXTRA_TEXT, "Segue em anexo o relatório gerado pelo Banzo Vistoria.")
                clipData = android.content.ClipData.newUri(contentResolver, file.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Enviar relatório por e-mail ou compartilhar"))
        } catch (_: Exception) { Toast.makeText(this, "Não foi encontrado um aplicativo compatível para compartilhar o PDF.", Toast.LENGTH_LONG).show() }
    }

}
