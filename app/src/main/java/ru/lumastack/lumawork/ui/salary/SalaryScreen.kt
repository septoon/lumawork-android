package ru.lumastack.lumawork.ui.salary

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import ru.lumastack.lumawork.ui.theme.AppButton as Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import ru.lumastack.lumawork.ui.theme.AppOutlinedButton as OutlinedButton
import ru.lumastack.lumawork.ui.theme.AppTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.NumberFormat
import java.util.Locale
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.lumastack.lumawork.data.salary.SalaryDraft
import ru.lumastack.lumawork.data.salary.SalaryEntry
import ru.lumastack.lumawork.data.salary.SalaryFailure
import ru.lumastack.lumawork.data.salary.SalaryKind
import ru.lumastack.lumawork.data.salary.SalaryMonth
import ru.lumastack.lumawork.data.salary.SalaryPasscodeStore
import ru.lumastack.lumawork.data.salary.SalaryRepository
import ru.lumastack.lumawork.data.salary.SalarySlipStore
import ru.lumastack.lumawork.data.salary.SalarySnapshot
import ru.lumastack.lumawork.data.salary.salaryMonths
import ru.lumastack.lumawork.data.salary.validateSalaryDraft
import ru.lumastack.lumawork.domain.model.AppSession
import ru.lumastack.lumawork.ui.adaptive.AppWidthClass
import ru.lumastack.lumawork.ui.theme.LumaBackgroundBottomDark
import ru.lumastack.lumawork.ui.theme.LumaBackgroundBottomLight
import ru.lumastack.lumawork.ui.theme.LumaBackgroundTopDark
import ru.lumastack.lumawork.ui.theme.LumaBackgroundTopLight

data class SalaryUiState(
    val snapshot: SalarySnapshot = SalarySnapshot(), val selectedYear: String? = null, val selectedMonth: String? = null,
    val hidden: Boolean = true, val draft: SalaryDraft? = null, val editingId: String? = null, val pendingDelete: SalaryEntry? = null,
    val loading: Boolean = false, val saving: Boolean = false, val error: String? = null, val editorError: String? = null,
) { val months:List<SalaryMonth> get()=salaryMonths(snapshot.records);val years get()=months.map{it.key.take(4)}.distinct();val visibleMonths get()=months.filter{selectedYear==null||it.key.startsWith(selectedYear)};val total get()=months.sumOf(SalaryMonth::total);val average get()=months.takeIf{it.isNotEmpty()}?.map(SalaryMonth::total)?.average()?:0.0 }

class SalaryViewModel(private val repository:SalaryRepository,private val session:AppSession,private val offline:Boolean):ViewModel(){private val mutable=MutableStateFlow(SalaryUiState());val state=mutable.asStateFlow();init{viewModelScope.launch{val cached=repository.read(session.user.id);mutable.update{it.copy(snapshot=cached?:SalarySnapshot())};if(!offline)refresh(false)}};fun refresh()=viewModelScope.launch{refresh(true)};fun year(v:String?)=mutable.update{it.copy(selectedYear=v)};fun month(v:String?)=mutable.update{it.copy(selectedMonth=v)};fun hidden()=mutable.update{it.copy(hidden=!it.hidden)};fun add()=mutable.update{it.copy(draft=SalaryDraft(),editingId=null,editorError=null)};fun edit(v:SalaryEntry)=mutable.update{it.copy(draft=SalaryDraft(v),editingId=v.id,editorError=null)};fun draft(v:SalaryDraft)=mutable.update{it.copy(draft=v,editorError=null)};fun close()=mutable.update{it.copy(draft=null,editingId=null)};fun deleteAsk(v:SalaryEntry)=mutable.update{it.copy(pendingDelete=v)};fun deleteCancel()=mutable.update{it.copy(pendingDelete=null)};fun save(){val d=state.value.draft?:return;validateSalaryDraft(d)?.let{m->mutable.update{it.copy(editorError=m)};return};viewModelScope.launch{mutable.update{it.copy(saving=true)};try{val s=repository.save(session.user.id,session.token,state.value.editingId,d);mutable.update{it.copy(snapshot=s,saving=false,draft=null,editingId=null)}}catch(e:CancellationException){throw e}catch(e:Exception){mutable.update{it.copy(saving=false,editorError=e.msg())}}}};fun deleteConfirm(){val e=state.value.pendingDelete?:return;viewModelScope.launch{mutable.update{it.copy(saving=true)};try{val s=repository.delete(session.user.id,session.token,e);mutable.update{it.copy(snapshot=s,saving=false,pendingDelete=null)}}catch(x:CancellationException){throw x}catch(x:Exception){mutable.update{it.copy(saving=false,pendingDelete=null,error=x.msg())}}}};private suspend fun refresh(show:Boolean){mutable.update{it.copy(loading=true,error=null)};try{val s=repository.refresh(session.user.id,session.token);mutable.update{it.copy(snapshot=s,loading=false,selectedYear=it.selectedYear?:salaryMonths(s.records).firstOrNull()?.key?.take(4))}}catch(e:CancellationException){throw e}catch(e:Exception){mutable.update{it.copy(loading=false,error=if(show)e.msg()else null)}}};private fun Throwable.msg()=(this as?SalaryFailure)?.message?:"Не удалось загрузить зарплату.";companion object{fun factory(r:SalaryRepository,s:AppSession,o:Boolean)=object:ViewModelProvider.Factory{@Suppress("UNCHECKED_CAST")override fun<T:ViewModel>create(modelClass:Class<T>):T=SalaryViewModel(r,s,o)as T}}}

@Composable fun SalaryRoute(session:AppSession,startOffline:Boolean,repository:SalaryRepository,widthClass:AppWidthClass,modifier:Modifier=Modifier){val context=LocalContext.current;val passcodeStore=remember{SalaryPasscodeStore(context)};var unlocked by remember(session.user.id){mutableStateOf(false)};if(!unlocked){SalaryPasscodeScreen(passcodeStore,modifier){unlocked=true};return};val vm:SalaryViewModel=viewModel(key="salary-${session.user.id}",factory=SalaryViewModel.factory(repository,session,startOffline));val s by vm.state.collectAsStateWithLifecycle();SalaryScreen(session.user.id,s,widthClass,vm::refresh,vm::year,vm::month,vm::hidden,vm::add,vm::edit,vm::draft,vm::close,vm::save,vm::deleteAsk,vm::deleteCancel,vm::deleteConfirm,modifier)}

@Composable private fun SalaryScreen(owner:String,s:SalaryUiState,w:AppWidthClass,refresh:()->Unit,year:(String?)->Unit,month:(String?)->Unit,hidden:()->Unit,add:()->Unit,edit:(SalaryEntry)->Unit,draft:(SalaryDraft)->Unit,close:()->Unit,save:()->Unit,del:(SalaryEntry)->Unit,delCancel:()->Unit,delConfirm:()->Unit,modifier:Modifier){val context=LocalContext.current;val slips=remember(owner){SalarySlipStore(context,owner)};var slipMonths by remember(owner){mutableStateOf(emptySet<String>())};LaunchedEffect(owner){slipMonths=slips.months()};val dark=MaterialTheme.colorScheme.background==LumaBackgroundTopDark;val bg=remember(dark){Brush.verticalGradient(if(dark)listOf(LumaBackgroundTopDark,LumaBackgroundBottomDark)else listOf(LumaBackgroundTopLight,LumaBackgroundBottomLight))};Box(modifier.fillMaxSize().background(bg),contentAlignment=Alignment.TopCenter){if(w==AppWidthClass.Compact)SalaryList(s,slips,slipMonths,{slipMonths=it},refresh,year,month,hidden,add,edit,del,Modifier.widthIn(max=680.dp).fillMaxSize())else Row(Modifier.widthIn(max=1180.dp).fillMaxSize().padding(20.dp),horizontalArrangement=Arrangement.spacedBy(18.dp)){SalarySummary(s,refresh,year,hidden,add,Modifier.weight(.4f).fillMaxHeight());SalaryMonths(s,slips,slipMonths,{slipMonths=it},month,edit,del,Modifier.weight(.6f).fillMaxHeight())}};s.draft?.let{SalaryEditor(it,s.saving,s.editorError,draft,close,save)};if(s.pendingDelete!=null)AlertDialog(onDismissRequest=delCancel,title={Text("Удалить выплату?")},confirmButton={TextButton(onClick=delConfirm){Text("Удалить")}},dismissButton={TextButton(onClick=delCancel){Text("Отмена")}})}
@Composable private fun SalaryList(s:SalaryUiState,slips:SalarySlipStore,slipMonths:Set<String>,updateSlips:(Set<String>)->Unit,refresh:()->Unit,year:(String?)->Unit,month:(String?)->Unit,hidden:()->Unit,add:()->Unit,edit:(SalaryEntry)->Unit,del:(SalaryEntry)->Unit,m:Modifier){LazyColumn(m,contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){item{SalarySummary(s,refresh,year,hidden,add)};items(s.visibleMonths,key=SalaryMonth::key){SalaryMonthCard(it,s.hidden,s.selectedMonth==it.key,it.key in slipMonths,{month(if(s.selectedMonth==it.key)null else it.key)},edit,del,slips,updateSlips)}}}
@Composable private fun SalarySummary(s:SalaryUiState,refresh:()->Unit,year:(String?)->Unit,hidden:()->Unit,add:()->Unit,m:Modifier=Modifier){Column(m,verticalArrangement=Arrangement.spacedBy(12.dp)){Surface(shape=RoundedCornerShape(24.dp),color=MaterialTheme.colorScheme.surface.copy(alpha=.97f)){Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("Выплаты",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold);TextButton(onClick=refresh){Text("Обновить")}};Text(if(s.hidden)"Сумма скрыта"else money(s.total),style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.Bold);Text("Средняя за месяц: ${if(s.hidden)"••••••"else money(s.average)}",color=MaterialTheme.colorScheme.onSurfaceVariant);Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedButton(onClick=hidden,modifier=Modifier.weight(1f)){Text(if(s.hidden)"Показать"else"Скрыть")};Button(onClick=add,modifier=Modifier.weight(1f)){Text("Добавить")}}}};SalaryAnalytics(s);if(s.years.isNotEmpty())Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedButton(onClick={year(null)}){Text("Все")};s.years.forEach{v->if(s.selectedYear==v)Button(onClick={year(v)}){Text(v)}else OutlinedButton(onClick={year(v)}){Text(v)}}};s.error?.let{SalaryNotice(it)};if(s.loading&&s.snapshot.records.isEmpty())CircularProgressIndicator(Modifier.size(24.dp))}}
@Composable private fun SalaryMonths(s:SalaryUiState,slips:SalarySlipStore,slipMonths:Set<String>,updateSlips:(Set<String>)->Unit,month:(String?)->Unit,edit:(SalaryEntry)->Unit,del:(SalaryEntry)->Unit,m:Modifier){LazyColumn(m,contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){item{Text("Периоды",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)};items(s.visibleMonths,key=SalaryMonth::key){SalaryMonthCard(it,s.hidden,s.selectedMonth==it.key,it.key in slipMonths,{month(if(s.selectedMonth==it.key)null else it.key)},edit,del,slips,updateSlips)}}}
@Composable private fun SalaryMonthCard(v:SalaryMonth,hidden:Boolean,expanded:Boolean,hasSlip:Boolean,toggle:()->Unit,edit:(SalaryEntry)->Unit,del:(SalaryEntry)->Unit,slips:SalarySlipStore,updateSlips:(Set<String>)->Unit){val context=LocalContext.current;val scope=rememberCoroutineScope();val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->uri?.let{readSalaryDocument(context,it)?.let{file->scope.launch{runCatching{slips.attach(v.key,file.first,file.second)};updateSlips(slips.months())}}}};Surface(Modifier.fillMaxWidth().clickable(onClick=toggle),shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.surface.copy(alpha=.97f)){Column(Modifier.padding(15.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(v.key,fontWeight=FontWeight.Bold);Text(if(hidden)"••••••"else money(v.total),fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.primary)};Text("${v.entries.size} выплат · ${if(hasSlip)"листок прикреплён"else"без листка"}",color=MaterialTheme.colorScheme.onSurfaceVariant);if(expanded){v.entries.forEach{e->Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(e.resolvedKind.title,fontWeight=FontWeight.SemiBold);Text("${e.date} · ${if(hidden)"••••••"else money(e.payout)}",color=MaterialTheme.colorScheme.onSurfaceVariant)};TextButton(onClick={edit(e)}){Text("Изменить")};TextButton(onClick={del(e)}){Text("Удалить")}}};Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){if(hasSlip){OutlinedButton(onClick={slips.file(v.key)?.let{shareSalaryDocument(context,it)}}){Text("Открыть")};OutlinedButton(onClick={scope.launch{slips.delete(v.key);updateSlips(slips.months())}}){Text("Удалить листок")}};Button(onClick={picker.launch(arrayOf("application/pdf","text/html"))}){Text(if(hasSlip)"Заменить листок"else"Прикрепить листок")}}}}}}
@Composable private fun SalaryEditor(d:SalaryDraft,saving:Boolean,error:String?,change:(SalaryDraft)->Unit,close:()->Unit,save:()->Unit){AlertDialog(onDismissRequest=close,title={Text("Запись зарплаты")},text={LazyColumn(verticalArrangement=Arrangement.spacedBy(10.dp)){item{SalaryField("Дата, ГГГГ-ММ-ДД",d.date){change(d.copy(date=it))};if(d.date>=SalaryEntry.NET_START){SalaryField("За месяц, ГГГГ-ММ",d.periodMonth){change(d.copy(periodMonth=it))};Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){SalaryKind.entries.forEach{k->KindButton(k.title,d.kind==k){change(d.copy(kind=k))}}};SalaryField("Выплата на руки",d.amount,true){change(d.copy(amount=it))};if(d.kind==SalaryKind.Other)SalaryField("Комментарий",d.comment){change(d.copy(comment=it))}}else{SalaryField("Оклад",d.baseSalary,true){change(d.copy(baseSalary=it))};SalaryField("Оплата в выходной",d.weekendPay,true){change(d.copy(weekendPay=it))}};error?.let{SalaryNotice(it)}}}},confirmButton={Button(onClick=save,enabled=!saving){Text("Сохранить")}},dismissButton={TextButton(onClick=close){Text("Отмена")}})}
@Composable private fun SalaryPasscodeScreen(store:SalaryPasscodeStore,modifier:Modifier=Modifier,unlocked:()->Unit){val saved=remember{store.read()};var input by remember{mutableStateOf("")};var first by remember{mutableStateOf<String?>(null)};var error by remember{mutableStateOf(false)};val title=if(saved!=null)"Сведения о зарплате"else if(first==null)"Придумайте код"else"Повторите код";val press:(Int)->Unit={digit->if(input.length<4){input+=digit;if(input.length==4){if(saved!=null){if(input==saved)unlocked()else{error=true;input=""}}else if(first==null){first=input;input=""}else if(first==input){store.save(input);unlocked()}else{error=true;first=null;input=""}}}};Box(modifier.fillMaxSize().background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.background,MaterialTheme.colorScheme.surfaceVariant))),contentAlignment=Alignment.Center){Column(Modifier.widthIn(max=420.dp).padding(28.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(24.dp)){Text(title,style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold);Row(horizontalArrangement=Arrangement.spacedBy(14.dp)){repeat(4){index->Surface(shape=RoundedCornerShape(50),color=if(error)MaterialTheme.colorScheme.error else if(index<input.length)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,modifier=Modifier.size(12.dp)) {}}};listOf(listOf(1,2,3),listOf(4,5,6),listOf(7,8,9)).forEach{digits->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly){digits.forEach{digit->TextButton(onClick={press(digit)},modifier=Modifier.size(72.dp)){Text(digit.toString(),style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold)}}}};Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly,verticalAlignment=Alignment.CenterVertically){TextButton(onClick={input="";first=null;error=false},modifier=Modifier.size(90.dp)){Text("Сбросить")};TextButton(onClick={press(0)},modifier=Modifier.size(72.dp)){Text("0",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold)};TextButton(onClick={if(input.isNotEmpty())input=input.dropLast(1);error=false},modifier=Modifier.size(90.dp)){Text("Удалить")}}}}}
@Composable private fun SalaryAnalytics(s:SalaryUiState){val months=s.months.take(6).reversed();val average=months.takeIf{it.isNotEmpty()}?.map(SalaryMonth::total)?.average()?:0.0;val trend=if(months.size>=2&&months[months.lastIndex-1].total!=0.0)(months.last().total-months[months.lastIndex-1].total)/months[months.lastIndex-1].total*100 else null;Surface(shape=RoundedCornerShape(20.dp),color=MaterialTheme.colorScheme.surface.copy(alpha=.97f)){Column(Modifier.fillMaxWidth().padding(15.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){Text("Аналитика",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold);Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Text("Среднее: ${if(s.hidden)"••••••"else money(average)}",modifier=Modifier.weight(1f));Text("Изменение: ${if(s.hidden)"••••••"else trend?.let{"%+.0f%%".format(it)}?:"—"}",modifier=Modifier.weight(1f))};if(months.isNotEmpty()){val max=months.maxOf{it.total}.coerceAtLeast(1.0);months.forEach{month->Column{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(month.key);Text(if(s.hidden)"••••••"else money(month.total))};Box(Modifier.fillMaxWidth().size(width=1.dp,height=6.dp).background(MaterialTheme.colorScheme.surfaceVariant,RoundedCornerShape(8.dp))){Box(Modifier.fillMaxWidth((month.total/max).toFloat()).size(width=1.dp,height=6.dp).background(MaterialTheme.colorScheme.primary,RoundedCornerShape(8.dp)))}}}}}}}
@Composable private fun SalaryField(label:String,value:String,number:Boolean=false,change:(String)->Unit){AppTextField(value,change,label = label,singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=if(number)KeyboardType.Decimal else KeyboardType.Text),modifier=Modifier.fillMaxWidth())}
@Composable private fun RowScope.KindButton(t:String,on:Boolean,click:()->Unit){if(on)Button(onClick=click,contentPadding=PaddingValues(horizontal=7.dp)){Text(t,style=MaterialTheme.typography.labelSmall)}else OutlinedButton(onClick=click,contentPadding=PaddingValues(horizontal=7.dp)){Text(t,style=MaterialTheme.typography.labelSmall)}}
@Composable private fun SalaryNotice(v:String){Surface(color=MaterialTheme.colorScheme.errorContainer,shape=RoundedCornerShape(16.dp)){Text(v,modifier=Modifier.padding(14.dp),color=MaterialTheme.colorScheme.onErrorContainer)}}
private val currency=NumberFormat.getCurrencyInstance(Locale.forLanguageTag("ru-RU")).apply{maximumFractionDigits=0};private fun money(v:Double)=currency.format(v)
private fun readSalaryDocument(context:Context,uri:Uri,maxBytes:Int=10*1024*1024):Pair<String,ByteArray>?{val name=context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use{if(it.moveToFirst())it.getString(0)else null}?:"document.pdf";context.contentResolver.openAssetFileDescriptor(uri,"r")?.use{if(it.length>maxBytes)return null};val data=context.contentResolver.openInputStream(uri)?.use{input->val output=ByteArrayOutputStream();val buffer=ByteArray(32*1024);var total=0;while(true){val count=input.read(buffer);if(count<0)break;total+=count;if(total>maxBytes)return null;output.write(buffer,0,count)};output.toByteArray()}?:return null;return name to data}
private fun shareSalaryDocument(context:Context,file:File){val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",file);val mime=if(file.extension.lowercase()=="pdf")"application/pdf"else"text/html";context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type=mime;putExtra(Intent.EXTRA_STREAM,uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)},"Расчётный листок"))}
