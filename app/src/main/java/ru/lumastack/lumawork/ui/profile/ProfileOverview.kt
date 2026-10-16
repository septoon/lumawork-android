package ru.lumastack.lumawork.ui.profile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.lumastack.lumawork.data.auto.VehicleImageRepository
import ru.lumastack.lumawork.domain.model.AppUser

@Composable
internal fun ProfileOverview(user: AppUser, state: ProfileUiState, images: VehicleImageRepository, showVehicle: Boolean) {
    val profile = user.profile
    val fullName = listOf(profile?.lastName, profile?.firstName, profile?.middleName)
        .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }.joinToString(" ")
    val contacts = listOf("Телефон" to profile?.personalPhone, "Почта" to profile?.workEmail).populated()
    val car = state.vehicle
    val vehicle = if (state.vehicleId != null) listOf(
        "Модель" to listOf(car.make, car.model).filter(String::isNotBlank).joinToString(" ").ifBlank { car.customName },
        "Поколение" to car.generation, "Год" to car.year, "Кузов" to car.bodyType,
        "Госномер" to car.licensePlate, "VIN" to car.vin, "СТС" to car.sts, "ПТС" to car.pts,
        "Цвет" to car.colorName, "Объем двигателя" to car.engineVolumeCm3.withUnit("см³"),
        "Мощность" to car.enginePowerHp.withUnit("л.с."), "Пробег" to car.currentMileageKm.withUnit("км"),
    ).populated() else listOf(
        "Модель" to profile?.vehicleModel, "Госномер" to profile?.vehiclePlate,
        "VIN" to profile?.vehicleVin, "СТС" to profile?.vehicleSts, "ПТС" to profile?.vehiclePts,
        "Цвет" to profile?.vehicleColor, "Объем двигателя" to profile?.engineVolumeCm3.withUnit("см³"),
        "Мощность" to profile?.enginePowerHp.withUnit("л.с."), "Начальный пробег" to profile?.initialMileageKm.withUnit("км"),
    ).populated()
    LazyColumn(Modifier.widthIn(max = 760.dp).fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item(key = "identity") {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    ProfileAvatar(user, images, 68.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(fullName.ifBlank { "Ваш профиль" }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        profile?.jobTitle?.trim()?.takeIf(String::isNotEmpty)?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        if (fullName.isBlank()) Text("Добавьте имя и контакты в настройках", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item(key = "contacts") {
            ProfileInfoSection("Контакты", contacts, emptyText = "Телефон и почта пока не добавлены")
        }
        profile?.city?.trim()?.takeIf(String::isNotEmpty)?.let { city ->
            item(key = "work") { ProfileInfoSection("Работа", listOf("Город" to city)) }
        }
        if (showVehicle && vehicle.isNotEmpty()) item(key = "vehicle") {
            ProfileInfoSection("Автомобиль", vehicle, copyValues = true)
        }
    }
}

@Composable
private fun ProfileInfoSection(title: String, values: List<Pair<String, String>>, emptyText: String = "", copyValues: Boolean = false) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (values.isEmpty()) Text(emptyText, Modifier.padding(6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                values.forEach { (label, value) ->
                    val row: @Composable () -> Unit = {
                        Row(Modifier.fillMaxWidth().padding(6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(label, modifier = Modifier.weight(.42f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(value, modifier = Modifier.weight(.58f))
                        }
                    }
                    if (copyValues) Surface(onClick = {
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, value))
                    }, color = MaterialTheme.colorScheme.surface, content = row)
                    else SelectionContainer { row() }
                }
            }
        }
        if (copyValues) Text("Нажмите строку, чтобы скопировать значение.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun List<Pair<String, String?>>.populated(): List<Pair<String, String>> = mapNotNull { (label, value) ->
    value?.trim()?.takeIf(String::isNotEmpty)?.let { label to it }
}
private fun String?.withUnit(unit: String): String? = this?.trim()?.takeIf(String::isNotEmpty)?.let { "$it $unit" }
