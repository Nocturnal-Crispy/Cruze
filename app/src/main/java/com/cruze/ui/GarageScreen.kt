package com.cruze.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cruze.garage.DueState
import com.cruze.garage.FuelEntry
import com.cruze.garage.GarageViewModel
import com.cruze.garage.ServiceStatus
import com.cruze.garage.averageMpg
import com.cruze.garage.costPerMile
import com.cruze.garage.totalFuelSpend
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun GarageScreen(vm: GarageViewModel, onExportCsv: () -> Unit) {
    var addBike by remember { mutableStateOf(false) }
    var addService by remember { mutableStateOf(false) }
    var addFuel by remember { mutableStateOf(false) }
    var editOdo by remember { mutableStateOf(false) }

    val bike = vm.bike

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Spacer(Modifier.height(8.dp)) }

        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(vm.garage.bikes) { b ->
                    FilterChip(
                        selected = b.id == bike?.id,
                        onClick = { vm.selectBike(b.id) },
                        label = { Text(b.title, maxLines = 1) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    )
                }
                item {
                    FilterChip(
                        selected = false,
                        onClick = { addBike = true },
                        label = { Text("Add bike") },
                        leadingIcon = { Icon(Icons.Default.Add, null, Modifier.size(18.dp)) },
                    )
                }
            }
        }

        if (bike == null) {
            item {
                Text(
                    "Add your bike to track servicing, fuel and mileage. Recorded rides roll " +
                        "onto the odometer automatically.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 24.dp),
                )
            }
            return@LazyColumn
        }

        item {
            Card(
                Modifier.fillMaxWidth().clickable { editOdo = true },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                Row(
                    Modifier.padding(16.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(bike.title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Odometer — tap to correct",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "${bike.odometerMi.toInt()} mi",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        item { SectionHeader("Service", action = "Add") { addService = true } }

        val statuses = vm.statuses()
        if (statuses.isEmpty()) {
            item { Muted("No service items yet.") }
        }
        items(statuses) { s -> ServiceRow(s, onDone = { vm.markDone(s.item) }, onDelete = { vm.deleteService(s.item.id) }) }

        item { SectionHeader("Fuel", action = "Add") { addFuel = true } }

        val entries = vm.fuelEntries()
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MiniStat(averageMpg(entries)?.let { "%.1f".format(it) } ?: "—", "avg mpg")
                MiniStat(costPerMile(entries)?.let { "%.2f".format(it) } ?: "—", "$/mile")
                MiniStat("%.0f".format(totalFuelSpend(entries)), "total $")
            }
        }
        if (entries.isEmpty()) {
            item { Muted("Log a fill-up to see economy and running costs.") }
        } else {
            item {
                TextButton(onClick = onExportCsv) { Text("Export fuel log (CSV)") }
            }
        }
        items(entries) { e -> FuelRow(e) { vm.deleteFuel(e.id) } }

        item { Spacer(Modifier.height(24.dp)) }
    }

    if (addBike) {
        AddBikeDialog(onDismiss = { addBike = false }) { name, make, model, year, odo ->
            vm.addBike(name, make, model, year, odo)
            addBike = false
        }
    }
    if (addService) {
        AddServiceDialog(onDismiss = { addService = false }) { name, mi, days ->
            vm.addService(name, mi, days)
            addService = false
        }
    }
    if (addFuel) {
        AddFuelDialog(
            defaultOdo = bike?.odometerMi ?: 0.0,
            onDismiss = { addFuel = false },
        ) { odo, gal, cost, full ->
            vm.addFuel(odo, gal, cost, full)
            addFuel = false
        }
    }
    if (editOdo) {
        NumberDialog(
            title = "Odometer (miles)",
            initial = "%.0f".format(bike?.odometerMi ?: 0.0),
            onDismiss = { editOdo = false },
        ) { v -> vm.setOdometer(v); editOdo = false }
    }
}

@Composable
private fun ServiceRow(s: ServiceStatus, onDone: () -> Unit, onDelete: () -> Unit) {
    val color = when (s.state) {
        DueState.OVERDUE -> MaterialTheme.colorScheme.error
        DueState.DUE, DueState.SOON -> Color(0xFFFFB300)
        DueState.OK -> Color(0xFF35C759)
    }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(color, CircleShape))
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(s.item.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(s.summary, fontSize = 13.sp, color = color)
            }
            TextButton(onClick = onDone) { Text("Done") }
            TextButton(onClick = onDelete) { Text("✕") }
        }
    }
}

@Composable
private fun FuelRow(e: FuelEntry, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "${"%.2f".format(e.gallons)} gal · $${"%.2f".format(e.cost)}" +
                    if (e.full) "" else " (partial)",
                fontSize = 14.sp,
            )
            Text(
                SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(e.atMs)) +
                    " · ${e.odometerMi.toInt()} mi",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onDelete) { Text("✕") }
    }
}

@Composable
private fun SectionHeader(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (action != null) TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun Muted(text: String) =
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)

@Composable
private fun MiniStat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = MaterialTheme.colorScheme.primary)
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// --- dialogs -------------------------------------------------------------------------------

@Composable
private fun AddBikeDialog(
    onDismiss: () -> Unit,
    onAdd: (String, String, String, Int, Double) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var make by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var year by remember { mutableStateOf("") }
    var odo by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a bike") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("Name (optional)", name) { name = it }
                Field("Make", make) { make = it }
                Field("Model", model) { model = it }
                Field("Year", year, KeyboardType.Number) { year = it }
                Field("Odometer (miles)", odo, KeyboardType.Decimal) { odo = it }
            }
        },
        confirmButton = {
            Button(onClick = {
                onAdd(name, make, model, year.toIntOrNull() ?: 0, odo.toDoubleOrNull() ?: 0.0)
            }, enabled = name.isNotBlank() || make.isNotBlank() || model.isNotBlank()) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AddServiceDialog(onDismiss: () -> Unit, onAdd: (String, Double, Int) -> Unit) {
    var name by remember { mutableStateOf("") }
    var miles by remember { mutableStateOf("") }
    var days by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add service item") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("What", name) { name = it }
                Field("Every … miles (0 = ignore)", miles, KeyboardType.Decimal) { miles = it }
                Field("Every … days (0 = ignore)", days, KeyboardType.Number) { days = it }
            }
        },
        confirmButton = {
            Button(
                onClick = { onAdd(name, miles.toDoubleOrNull() ?: 0.0, days.toIntOrNull() ?: 0) },
                enabled = name.isNotBlank(),
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AddFuelDialog(
    defaultOdo: Double,
    onDismiss: () -> Unit,
    onAdd: (Double, Double, Double, Boolean) -> Unit,
) {
    var odo by remember { mutableStateOf("%.0f".format(defaultOdo)) }
    var gal by remember { mutableStateOf("") }
    var cost by remember { mutableStateOf("") }
    var full by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log a fill-up") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("Odometer (miles)", odo, KeyboardType.Decimal) { odo = it }
                Field("Gallons", gal, KeyboardType.Decimal) { gal = it }
                Field("Cost ($)", cost, KeyboardType.Decimal) { cost = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = full, onCheckedChange = { full = it })
                    Column {
                        Text("Filled the tank")
                        Text(
                            "Economy is only calculated between full fills.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onAdd(
                        odo.toDoubleOrNull() ?: 0.0,
                        gal.toDoubleOrNull() ?: 0.0,
                        cost.toDoubleOrNull() ?: 0.0,
                        full,
                    )
                },
                enabled = (gal.toDoubleOrNull() ?: 0.0) > 0,
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun NumberDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (Double) -> Unit,
) {
    var v by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Field(title, v, KeyboardType.Decimal) { v = it } },
        confirmButton = {
            Button(onClick = { v.toDoubleOrNull()?.let(onConfirm) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Field(
    label: String,
    value: String,
    keyboard: KeyboardType = KeyboardType.Text,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, maxLines = 1) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth(),
    )
}
