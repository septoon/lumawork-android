package ru.lumastack.lumawork.ui.employees

import ru.lumastack.lumawork.ui.theme.AppContentHorizontalPadding
import ru.lumastack.lumawork.ui.theme.AppRefreshContainer
import ru.lumastack.lumawork.ui.shell.AppHeaderBackHandler as BackHandler
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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocationOn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.lumastack.lumawork.data.employees.EmployeeRepository
import ru.lumastack.lumawork.domain.employees.Employee
import ru.lumastack.lumawork.domain.employees.EmployeeAddress
import ru.lumastack.lumawork.domain.employees.employeeCountWord
import ru.lumastack.lumawork.ui.adaptive.AppWidthClass
import ru.lumastack.lumawork.ui.simpleone.SimpleOneUiState
import ru.lumastack.lumawork.ui.theme.appScreenBackground
import ru.lumastack.lumawork.ui.theme.AppButton
import ru.lumastack.lumawork.ui.theme.AppCard
import ru.lumastack.lumawork.ui.theme.AppPanel
import ru.lumastack.lumawork.ui.theme.AppSurfaces
import ru.lumastack.lumawork.ui.theme.AppModalSheet
import ru.lumastack.lumawork.ui.theme.AppModalHeader

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
    val background = appScreenBackground()
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
        Box(modifier.fillMaxSize().background(background).padding(horizontal = AppContentHorizontalPadding, vertical = 20.dp), contentAlignment = Alignment.TopCenter) {
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
                modifier = Modifier.widthIn(max = 1180.dp).fillMaxSize().padding(horizontal = AppContentHorizontalPadding, vertical = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                AppPanel(
                    modifier = Modifier.weight(0.44f).fillMaxHeight().widthIn(max = 500.dp),
                ) {
                    EmployeeDirectoryContent(
                        state = state,
                        onQueryChange = onQueryChange,
                        onRefresh = onRefresh,
                        onOpenAddressPicker = onOpenAddressPicker,
                            onOpenEmployee = onOpenEmployee,
                        contentPadding = PaddingValues(horizontal = AppContentHorizontalPadding, vertical = 16.dp),
                        modifier = Modifier.fillMaxSize(),
                        listState = directoryState,
                    )
                }
                AppPanel(
                    modifier = Modifier.weight(0.56f).fillMaxHeight(),
                ) {
                    state.selectedEmployee?.let { employee ->
                        EmployeeDetail(
                            employee = employee,
                            isLoading = state.isDetailLoading,
                            errorMessage = state.detailErrorMessage,
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
    contentPadding: PaddingValues = PaddingValues(horizontal = AppContentHorizontalPadding, vertical = 20.dp),
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
    AppCard(
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    ) {
        Column(
            Modifier.background(
                AppSurfaces.hero,
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
                AppButton(
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
    AppPanel(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
            else AppSurfaces.card,
        border = if (isSelected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
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
    AppModalSheet(onDismiss = onDismiss, maxWidth = 720.dp) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.94f)) {
            AppModalHeader("Адрес сотрудников", onDismiss, Modifier.padding(horizontal = 12.dp))
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                item(key = "saved-title") {
                    AddressSectionTitle("Сохранённые адреса", "Переключайтесь между каталогами или удаляйте ненужные")
                }
                item(key = "saved-addresses") {
                    if (state.snapshot.savedAddresses.isEmpty()) MessagePanel("Адресов пока нет", "Найдите населённый пункт через строку поиска.")
                    else AddressList(state.snapshot.savedAddresses, state.snapshot.selectedAddress?.sysId, onSelect, onRemove)
                }
                item(key = "results-title") {
                    AddressSectionTitle("Населённый пункт", "Найдите и добавьте ещё один адрес")
                }
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
                if (state.addressResults.isNotEmpty()) item(key = "results") {
                    AddressList(state.addressResults, state.snapshot.selectedAddress?.sysId, onSelect)
                }
            }
            AppTextField(
                value = state.addressQuery, onValueChange = onQueryChange,
                label = "Например, Алушта", search = true, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 12.dp), shape = CircleShape,
            )
        }
    }
}

@Composable
private fun AddressList(addresses: List<EmployeeAddress>, selectedId: String?, onSelect: (EmployeeAddress) -> Unit, onRemove: ((EmployeeAddress) -> Unit)? = null) {
    AppCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        addresses.forEachIndexed { index, address ->
            AddressRow(address, address.sysId == selectedId, { onSelect(address) }, onRemove?.let { remove -> { remove(address) } })
            if (index < addresses.lastIndex) HorizontalDivider(Modifier.padding(start = 58.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .12f))
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
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).clickable(onClick = onSelect).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            Box(Modifier.size(38.dp).background(if (isSelected) colors.secondary else colors.secondary.copy(alpha = .12f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.LocationOn, null, Modifier.size(22.dp), tint = if (isSelected) Color.White else colors.secondary)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(address.title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                address.subtitle.takeIf(String::isNotBlank)?.let {
                    Text(it, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Icon(if (isSelected) Icons.Default.CheckCircle else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                if (isSelected) "Выбранный адрес" else null, Modifier.size(20.dp), tint = if (isSelected) colors.primary else colors.onSurfaceVariant)
        }
        if (onRemove != null) {
            VerticalDivider(Modifier.height(32.dp), color = colors.onSurface.copy(alpha = .12f))
            IconButton(onClick = onRemove, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.Delete, "Удалить ${address.title} из сохранённых", Modifier.size(20.dp), tint = colors.error)
            }
        }
    }
}

@Composable
private fun AddressSectionTitle(title: String, caption: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(caption, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
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
