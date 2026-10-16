package ru.lumastack.lumawork.ui.employees

import ru.lumastack.lumawork.ui.theme.AppRefreshContainer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import ru.lumastack.lumawork.ui.theme.AppButton as Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import ru.lumastack.lumawork.ui.theme.AppOutlinedButton as OutlinedButton
import ru.lumastack.lumawork.ui.theme.AppTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.lumastack.lumawork.data.employees.EmployeeRepository
import ru.lumastack.lumawork.domain.employees.Employee
import ru.lumastack.lumawork.domain.employees.EmployeeAddress
import ru.lumastack.lumawork.domain.employees.employeeCountWord
import ru.lumastack.lumawork.ui.adaptive.AppWidthClass
import ru.lumastack.lumawork.ui.simpleone.SimpleOneUiState
import ru.lumastack.lumawork.ui.theme.LumaBackgroundBottomDark
import ru.lumastack.lumawork.ui.theme.LumaBackgroundBottomLight
import ru.lumastack.lumawork.ui.theme.LumaBackgroundTopDark
import ru.lumastack.lumawork.ui.theme.LumaBackgroundTopLight

@Composable
fun EmployeeRoute(
    ownerUserId: String,
    startOffline: Boolean,
    simpleOneState: SimpleOneUiState,
    repository: EmployeeRepository,
    widthClass: AppWidthClass,
    modifier: Modifier = Modifier,
) {
    val employeeViewModel: EmployeeViewModel = viewModel(
        key = "employees-$ownerUserId-$startOffline",
        factory = EmployeeViewModel.factory(repository, ownerUserId, startOffline),
    )
    val state by employeeViewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(simpleOneState.snapshot.currentUser?.sysId, simpleOneState.isAuthorized, simpleOneState.snapshot.updatedAtEpochMillis) {
        employeeViewModel.activate(
            simpleOneState.snapshot.currentUser?.sysId.orEmpty(),
            simpleOneState.isAuthorized,
        )
    }
    EmployeeScreen(
        state = state,
        widthClass = widthClass,
        onQueryChange = employeeViewModel::updateQuery,
        onRefresh = employeeViewModel::refresh,
        onOpenAddressPicker = employeeViewModel::openAddressPicker,
        onCloseAddressPicker = employeeViewModel::closeAddressPicker,
        onAddressQueryChange = employeeViewModel::updateAddressQuery,
        onSelectAddress = employeeViewModel::selectAddress,
        onRemoveAddress = employeeViewModel::removeAddress,
        onOpenEmployee = employeeViewModel::openEmployee,
        onCloseEmployee = employeeViewModel::closeEmployee,
        onRefreshEmployee = employeeViewModel::refreshEmployee,
        onActionError = employeeViewModel::showActionError,
        canRefresh = !startOffline,
        modifier = modifier,
    )
}

@Composable
private fun EmployeeScreen(
    state: EmployeeUiState,
    widthClass: AppWidthClass,
    onQueryChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onOpenAddressPicker: () -> Unit,
    onCloseAddressPicker: () -> Unit,
    onAddressQueryChange: (String) -> Unit,
    onSelectAddress: (EmployeeAddress) -> Unit,
    onRemoveAddress: (EmployeeAddress) -> Unit,
    onOpenEmployee: (Employee) -> Unit,
    onCloseEmployee: () -> Unit,
    onRefreshEmployee: () -> Unit,
    onActionError: (String) -> Unit,
    canRefresh: Boolean,
    modifier: Modifier = Modifier,
) {
    val isDark = MaterialTheme.colorScheme.background == LumaBackgroundTopDark
    val background = remember(isDark) {
        Brush.verticalGradient(
            if (isDark) listOf(LumaBackgroundTopDark, LumaBackgroundBottomDark)
            else listOf(LumaBackgroundTopLight, LumaBackgroundBottomLight),
        )
    }
    val directoryState = rememberLazyListState()
    val detailState = rememberLazyListState()
    var displayedEmployeeId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(state.selectedEmployee?.sysId) {
        if (displayedEmployeeId != state.selectedEmployee?.sysId) {
            detailState.scrollToItem(0)
            displayedEmployeeId = state.selectedEmployee?.sysId
        }
    }
    BackHandler(
        enabled = state.selectedEmployee != null && !state.isAddressPickerOpen,
        onBack = onCloseEmployee,
    )
    if (!state.isAuthorized) {
        Box(modifier.fillMaxSize().background(background).padding(20.dp), contentAlignment = Alignment.TopCenter) {
            MessagePanel("SimpleOne не подключён", "Войдите в SimpleOne на экране «Заявки», затем вернитесь к сотрудникам.")
        }
        return
    }
    Box(modifier.fillMaxSize().background(background), contentAlignment = Alignment.TopCenter) {
        if (widthClass != AppWidthClass.Expanded) {
            if (state.selectedEmployee != null) {
                EmployeeDetail(
                    employee = state.selectedEmployee,
                    isLoading = state.isDetailLoading,
                    errorMessage = state.detailErrorMessage,
                    onBack = onCloseEmployee,
                    onRefresh = onRefreshEmployee, canRefresh = canRefresh && state.isAuthorized, onActionError = onActionError,
                    modifier = Modifier.fillMaxSize(),
                    listState = detailState,
                )
            } else {
                EmployeeDirectoryContent(
                    state = state,
                    onQueryChange = onQueryChange,
                    onRefresh = onRefresh,
                    onOpenAddressPicker = onOpenAddressPicker,
                    onOpenEmployee = onOpenEmployee,
                    modifier = Modifier.fillMaxSize(),
                    listState = directoryState,
                )
            }
        } else {
            Row(
                modifier = Modifier.widthIn(max = 1180.dp).fillMaxSize().padding(20.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Surface(
                    modifier = Modifier.weight(0.44f).fillMaxHeight().widthIn(max = 500.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                    shape = RoundedCornerShape(26.dp),
                    tonalElevation = 2.dp,
                ) {
                    EmployeeDirectoryContent(
                        state = state,
                        onQueryChange = onQueryChange,
                        onRefresh = onRefresh,
                        onOpenAddressPicker = onOpenAddressPicker,
                            onOpenEmployee = onOpenEmployee,
                        contentPadding = PaddingValues(16.dp),
                        modifier = Modifier.fillMaxSize(),
                        listState = directoryState,
                    )
                }
                Surface(
                    modifier = Modifier.weight(0.56f).fillMaxHeight(),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                    shape = RoundedCornerShape(26.dp),
                    tonalElevation = 2.dp,
                ) {
                    state.selectedEmployee?.let { employee ->
                        EmployeeDetail(
                            employee = employee,
                            isLoading = state.isDetailLoading,
                            errorMessage = state.detailErrorMessage,
                            onBack = null,
                            onRefresh = onRefreshEmployee, canRefresh = canRefresh && state.isAuthorized, onActionError = onActionError,
                            modifier = Modifier.fillMaxSize(),
                            listState = detailState,
                        )
                    } ?: EmployeeSelectionHint(state, Modifier.fillMaxSize())
                }
            }
        }
    }

    if (state.isAddressPickerOpen) {
        EmployeeAddressDialog(
            state = state,
            onDismiss = onCloseAddressPicker,
            onQueryChange = onAddressQueryChange,
            onSelect = onSelectAddress,
            onRemove = onRemoveAddress,
        )
    }
}

@Composable
private fun EmployeeDirectoryContent(
    state: EmployeeUiState,
    onQueryChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onOpenAddressPicker: () -> Unit,
    onOpenEmployee: (Employee) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(20.dp),
    listState: LazyListState = rememberLazyListState(),
) {
    val groups = remember(state.employees) {
        state.employees.groupBy { employee ->
            employee.sortName.firstOrNull()?.uppercaseChar()?.toString() ?: "#"
        }.toSortedMap(compareBy(String.CASE_INSENSITIVE_ORDER) { it })
    }
    AppRefreshContainer(modifier, isRefreshing = state.isLoading, onRefresh = onRefresh, enabled = state.isAuthorized && state.snapshot.selectedAddress != null) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "hero") {
                EmployeeHero(
                    state = state,
                    onOpenAddressPicker = onOpenAddressPicker,
                )
            }
            item(key = "search") {
                AppTextField(
                    value = state.query,
                    trailingIcon = { if (state.query.isNotEmpty()) TextButton(onClick = { onQueryChange("") }) { Text("×") } },
                    onValueChange = onQueryChange,
                    label = "Поиск сотрудников", search = true,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                )
            }
            if (!state.isAuthorized && state.simpleOneUserId.isBlank()) {
                item(key = "auth") {
                    MessagePanel("SimpleOne не подключён", "Войдите в SimpleOne в разделе «Заявки».")
                }
            }
            state.errorMessage?.let { error ->
                item(key = "error") { MessagePanel("Не удалось обновить каталог", error, isError = true) }
            }
            if (state.isLoading && state.employees.isEmpty()) {
                item(key = "loading") {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 28.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Загружаю сотрудников")
                    }
                }
            } else if (state.snapshot.selectedAddress == null) {
                item(key = "choose-address") { MessagePanel("Выберите населённый пункт", "Нажмите «Сменить населённый пункт» и сохраните адрес.") }
            } else if (state.employees.isEmpty()) {
                item(key = "empty") {
                    MessagePanel(
                        if (state.query.isBlank()) "Сотрудников нет" else "Ничего не найдено",
                        if (state.query.isBlank()) "В выбранном населённом пункте сотрудники не найдены."
                        else "Измените запрос или населённый пункт.",
                    )
                }
            }
            groups.forEach { (letter, employees) ->
                item(key = "letter-$letter") {
                    EmployeeLetterHeader(letter, employees.size)
                }
                items(employees, key = Employee::sysId) { employee ->
                    EmployeeRow(
                        employee = employee,
                        isCurrentUser = employee.sysId == state.simpleOneUserId,
                        isSelected = employee.sysId == state.selectedEmployee?.sysId,
                        onClick = { onOpenEmployee(employee) },
                    )
                }
            }
            item(key = "bottom-space") { Spacer(Modifier.height(4.dp)) }
        }
    }
}

@Composable
private fun EmployeeHero(
    state: EmployeeUiState,
    onOpenAddressPicker: () -> Unit,
) {
    val address = state.snapshot.selectedAddress
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            Modifier.background(
                Brush.linearGradient(
                    listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary),
                ),
            ).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "КОМАНДА",
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        address?.title ?: "Выберите город",
                        color = Color.White,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                    )
                    address?.subtitle?.takeIf(String::isNotBlank)?.let {
                        Text(it, color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall)
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    if (state.isLoading && state.employees.isNotEmpty()) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = Color.White)
                    } else {
                        Text(
                            state.visibleDirectory.totalCount.toString(),
                            color = Color.White,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(employeeCountWord(state.visibleDirectory.totalCount, "сотрудник", "сотрудника", "сотрудников"), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                if (state.employees.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy((-11).dp)) {
                        state.employees.take(4).forEach { employee ->
                            Surface(color = employeeAccent(employee.sysId), shape = CircleShape, border = BorderStroke(2.dp, Color.White.copy(alpha = .7f))) {
                                Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) { Text(employee.initials, color = Color.White, style = MaterialTheme.typography.labelSmall) }
                            }
                        }
                        if (state.employees.size > 4) Surface(color = Color.Black.copy(alpha = .25f), shape = CircleShape, border = BorderStroke(2.dp, Color.White.copy(alpha = .5f))) {
                            Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) { Text("+${state.employees.size - 4}", color = Color.White, style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                }
                Button(
                    onClick = onOpenAddressPicker,
                    modifier = Modifier.weight(1f),
                ) { Text("Сменить населённый пункт", maxLines = 2) }
            }
        }
    }
}

@Composable
private fun EmployeeLetterHeader(letter: String, count: Int) {
    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(letter, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(10.dp))
            HorizontalDivider(Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            Text(count.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun EmployeeRow(employee: Employee, isCurrentUser: Boolean, isSelected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        border = if (isSelected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 1.dp,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(46.dp).background(employeeAccent(employee.sysId), RoundedCornerShape(15.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(employee.initials, color = Color.White, fontWeight = FontWeight.Bold)
                if (employee.isLocked == true) {
                    Box(
                        Modifier.align(Alignment.BottomEnd).size(13.dp).background(MaterialTheme.colorScheme.error, CircleShape),
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(
                        employee.fullName,
                        modifier = Modifier.weight(1f, fill = false),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (isCurrentUser) {
                        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = CircleShape) {
                            Text("ВЫ", Modifier.padding(horizontal = 7.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Text(employee.roleText, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                employee.login.takeIf(String::isNotBlank)?.let {
                    Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f), maxLines = 1, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
                }
            }
            Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun EmployeeSelectionHint(state: EmployeeUiState, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 360.dp).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Карточка сотрудника", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                if (state.employees.isEmpty()) "Выберите населённый пункт и загрузите каталог."
                else "Выберите сотрудника слева — полная информация откроется здесь.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
private fun EmployeeAddressDialog(
    state: EmployeeUiState,
    onDismiss: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSelect: (EmployeeAddress) -> Unit,
    onRemove: (EmployeeAddress) -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().fillMaxHeight(0.9f).padding(18.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Адрес сотрудников", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("ТГР Насел. пункт", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = onDismiss) { Text("Закрыть") }
                }
                AppTextField(
                    value = state.addressQuery,
                    onValueChange = onQueryChange,
                    label = "Например, Алушта", search = true,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                )
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (state.snapshot.savedAddresses.isNotEmpty()) {
                        item(key = "saved-title") { SectionTitle("Сохранённые") }
                        items(state.snapshot.savedAddresses, key = { "saved-${it.sysId}" }) { address ->
                            AddressRow(
                                address = address,
                                isSelected = address.sysId == state.snapshot.selectedAddress?.sysId,
                                onSelect = { onSelect(address) },
                                onRemove = { onRemove(address) },
                            )
                        }
                    }
                    item(key = "results-title") { SectionTitle("Результаты поиска") }
                    if (state.isSearchingAddresses) {
                        item(key = "address-loading") {
                            Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.Center) {
                                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            }
                        }
                    }
                    state.addressErrorMessage?.let { error ->
                        item(key = "address-error") { MessagePanel("Поиск не выполнен", error, isError = true) }
                    }
                    if (state.addressQuery.trim().length >= 2 && !state.isSearchingAddresses && state.addressResults.isEmpty() && state.addressErrorMessage == null) {
                        item(key = "address-empty") { MessagePanel("Ничего не найдено", "Уточните название населённого пункта.") }
                    }
                    items(state.addressResults, key = { "result-${it.sysId}" }) { address ->
                        AddressRow(address, address.sysId == state.snapshot.selectedAddress?.sysId, onSelect = { onSelect(address) }, onRemove = null)
                    }
                }
            }
        }
    }
}

@Composable
private fun AddressRow(
    address: EmployeeAddress,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onRemove: (() -> Unit)?,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(address.title, fontWeight = FontWeight.SemiBold)
                address.subtitle.takeIf(String::isNotBlank)?.let {
                    Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (isSelected) Text("Выбран", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
            if (onRemove != null) TextButton(onClick = onRemove) { Text("Удалить") }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

@Composable
private fun MessagePanel(title: String, text: String, isError: Boolean = false) {
    Surface(
        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(text, color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun employeeAccent(id: String): Color {
    val palette = listOf(
        Color(0xFF4169E1), Color(0xFF6C4AB6), Color(0xFF00897B), Color(0xFFC65D24), Color(0xFF9C3C71),
    )
    return palette[(id.hashCode() and Int.MAX_VALUE) % palette.size]
}
