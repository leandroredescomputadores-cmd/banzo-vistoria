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
            LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { Text("Nova ocorrência", style = MaterialTheme.typography.titleLarge) }
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
                    if (fine || coarse) fetchCurrentLocation()
                    else locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }, enabled = !locationPending, modifier = Modifier.fillMaxWidth()) {
                    Text(if (locationPending) "Obtendo localização…" else "Capturar coordenadas GPS e endereço")
                } }
                item { ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                    OutlinedTextField(value = pathology, onValueChange = {}, readOnly = true, label = { Text("Patologia") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, modifier = Modifier.menuAnchor().fillMaxWidth())
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        Pathologies.options.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { pathology = option; expanded = false }) }
                    }
                } }
                if (pathology == "Outra — especificar") item { OutlinedTextField(other, { other = it }, label = { Text("Especifique a patologia") }, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(description, { description = it }, label = { Text("Descrição / providência solicitada") }, minLines = 3, modifier = Modifier.fillMaxWidth()) }
                item {
                    val photoPaths = photos.split("\n").filter { it.isNotBlank() }
                    Text("Fotografias: ${photoPaths.size}")
                    if (photoPaths.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            photoPaths.forEachIndexed { index, path ->
                                val thumbnail = remember(path) {
                                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                    BitmapFactory.decodeFile(path, bounds)
                                    var sample = 1
                                    while (bounds.outWidth / sample > 320 || bounds.outHeight / sample > 320) sample *= 2
                                    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
                                }
                                Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                                    if (thumbnail != null) {
                                        Image(
                                            bitmap = thumbnail,
                                            contentDescription = "Miniatura da fotografia ${index + 1}",
                                            modifier = Modifier.size(96.dp)
                                                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                                                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                                                .padding(2.dp),
                                            contentScale = ContentScale.Crop
                                        )
                                    } else {
                                        Text("Foto ${index + 1}\nindisponível", modifier = Modifier.size(96.dp).padding(8.dp))
                                    }
                                    Text("Foto ${index + 1}", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                    Button(onClick = {
                        val granted = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                        if (granted) launchPhotoCapture() else cameraPermission.launch(Manifest.permission.CAMERA)
                    }, modifier = Modifier.fillMaxWidth()) { Text("Fotografar local") }
                }
                item { OutlinedTextField(workOrder, { workOrder = it }, label = { Text("Número da ordem de serviço (se houver)") }, modifier = Modifier.fillMaxWidth()) }
                item {
                    var statusExpanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(expanded = statusExpanded, onExpandedChange = { statusExpanded = !statusExpanded }) {
                        OutlinedTextField(status, {}, readOnly = true, label = { Text("Status") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(statusExpanded) }, modifier = Modifier.menuAnchor().fillMaxWidth())
                        ExposedDropdownMenu(expanded = statusExpanded, onDismissRequest = { statusExpanded = false }) {
                            OccurrenceStatus.options.forEach { opt -> DropdownMenuItem(text = { Text(opt) }, onClick = { status = opt; statusExpanded = false }) }
                        }
                    }
                }
                item { Button(onClick = {
                    if (pathology == "Outra — especificar" && other.isBlank()) { Toast.makeText(context, "Descreva a patologia selecionada como Outra.", Toast.LENGTH_SHORT).show(); return@Button }
                    scope.launch {
                        val now = System.currentTimeMillis()
                        val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(now))
                        val id = "RA-$day-${UUID.randomUUID().toString().take(4).uppercase(Locale.US)}"
                        val item = Occurrence(id, now, inspector.trim(), address.trim(), reference.trim(), lat.toDoubleOrNull(), lon.toDoubleOrNull(), pathology, other.trim(), description.trim(), photos.trim().replace(";", "\n"), workOrder.trim(), status, false)
                        db.occurrenceDao().upsert(item)
                        inspector = ""; address = ""; reference = ""; pathology = Pathologies.options.first(); other = ""; description = ""; lat = ""; lon = ""; photos = ""; workOrder = ""; status = OccurrenceStatus.options.first()
                        Toast.makeText(context, "Ocorrência salva no aparelho: $id", Toast.LENGTH_LONG).show()
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("Salvar ocorrência offline") } }
                item { Button(onClick = {
                    scope.launch {
                        val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
                        val start = cal.timeInMillis
                        cal.add(Calendar.DAY_OF_MONTH, 1)
                        val items = db.occurrenceDao().forPeriod(start, cal.timeInMillis)
                        val file = PdfReport.createDaily(context, items)
                        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${packageName}.files", file)
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "application/pdf"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            putExtra(Intent.EXTRA_SUBJECT, "Banzo Vistoria — Relatório diário de fiscalização")
                            putExtra(Intent.EXTRA_TEXT, "Segue em anexo o relatório diário de fiscalização gerado pelo Banzo Vistoria.")
                            clipData = android.content.ClipData.newUri(contentResolver, file.name, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        try {
                            startActivity(Intent.createChooser(intent, "Enviar relatório por e-mail ou compartilhar"))
                        } catch (_: Exception) {
                            Toast.makeText(context, "Não foi encontrado um aplicativo compatível para compartilhar o PDF.", Toast.LENGTH_LONG).show()
                        }
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("Gerar e enviar relatório por e-mail") } }
                item { Text("Ocorrências registradas (${occurrences.size})", style = MaterialTheme.typography.titleLarge) }
                items(occurrences, key = { it.id }) { item ->
                    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(item.id, style = MaterialTheme.typography.titleMedium)
                        Text(item.pathology + if (item.pathology == "Outra — especificar") ": ${item.pathologyOther}" else "")
                        Text(item.address.ifBlank { "Endereço não informado" })
                        Text("Status: ${item.status}" + if (item.workOrderNumber.isNotBlank()) " • OS ${item.workOrderNumber}" else "")
                        Text("GPS: ${item.latitude ?: "—"}, ${item.longitude ?: "—"}")
                    } }
                }
            }
        }
    }
}
