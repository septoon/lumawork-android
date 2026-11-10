package ru.lumastack.lumawork.ui.tools

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun FirmwareResetButton(resetting: Boolean, onReset: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    TextButton(onClick={confirm=true},enabled=!resetting,modifier=Modifier.fillMaxWidth()) {
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            if(resetting) CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp)
            else Icon(Icons.Outlined.Refresh,null,Modifier.size(20.dp))
            Text(if(resetting) "Сброс процесса…" else "Сбросить процесс")
        }
    }
    if(confirm) AlertDialog(onDismissRequest={confirm=false},title={Text("Сбросить процесс прошивки?")},
        text={Text("Текущая операция будет остановлена. Комплект и история сохранятся. При прерывании записи может потребоваться восстановление терминала. Перед новой попыткой отключите USB и перезапустите терминал.")},
        confirmButton={TextButton(onClick={confirm=false;onReset()}) { Text("Сбросить",color=MaterialTheme.colorScheme.error) }},
        dismissButton={TextButton(onClick={confirm=false}) { Text("Отмена") }})
}
