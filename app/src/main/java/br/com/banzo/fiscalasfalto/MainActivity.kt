package br.com.banzo.fiscalasfalto

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import java.io.File
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
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
        val photoCapture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            if (success && pendingPhotoPath.isNotBlank()) {
                photos = (photos.split("\n").filter { it.isNotBlank() } + pendingPhotoPath).joinToString("\n")
                Toast.makeText(context, "Foto adicionada à ocorrência", Toast.LENGTH_SHORT).show()
            }
            pendingPhotoPath = ""
        }
        val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.any { it }) {
                val lm = getSystemService(LOCATION_SERVICE) as LocationManager
                val loc: Location? = try {
                    if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
                        lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                    else lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                } catch (_: SecurityException) { null }
                if (loc != null) { lat = loc.latitude.toString(); lon = loc.longitude.toString() }
                else Toast.makeText(context, "Localização indisponível. Verifique o GPS e tente novamente.", Toast.LENGTH_LONG).show()
            }
            locationPending = false
        }
        LaunchedEffect(Unit) { db.occurrenceDao().observeAll().collectLatest { occurrences = it } }
        Scaffold(topBar = { TopAppBar(title = { Text("BANZO VISTORIA") }) }) { padding ->
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
                    locationPending = true
                    val fine = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    val coarse = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    if (fine || coarse) {
                        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
                        val loc = try { lm.getLastKnownLocation(if (fine) LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER) } catch (_: Exception) { null }
                        if (loc != null) { lat = loc.latitude.toString(); lon = loc.longitude.toString() } else Toast.makeText(context, "Sem posição recente. Ative o GPS e tente novamente.", Toast.LENGTH_LONG).show()
                        locationPending = false
                    } else locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }, modifier = Modifier.fillMaxWidth()) { Text("Capturar coordenadas GPS") } }
                item { ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                    OutlinedTextField(value = pathology, onValueChange = {}, readOnly = true, label = { Text("Patologia") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, modifier = Modifier.menuAnchor().fillMaxWidth())
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        Pathologies.options.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { pathology = option; expanded = false }) }
                    }
                } }
                if (pathology == "Outra — especificar") item { OutlinedTextField(other, { other = it }, label = { Text("Especifique a patologia") }, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(description, { description = it }, label = { Text("Descrição / providência solicitada") }, minLines = 3, modifier = Modifier.fillMaxWidth()) }
                item {
                    Text("Fotografias: ${photos.split("\n").count { it.isNotBlank() }}")
                    Button(onClick = {
                        val dir = File(getExternalFilesDir("photos") ?: filesDir, "occurrences")
                        dir.mkdirs()
                        val file = File(dir, "foto_${System.currentTimeMillis()}.jpg")
                        val uri: Uri = androidx.core.content.FileProvider.getUriForFile(this@MainActivity, "${packageName}.files", file)
                        pendingPhotoPath = file.absolutePath
                        photoCapture.launch(uri)
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
                        val intent = Intent(Intent.ACTION_SEND).apply { type = "application/pdf"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                        startActivity(Intent.createChooser(intent, "Compartilhar relatório diário"))
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("Gerar e compartilhar PDF diário") } }
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
