package ru.lumastack.lumawork.ui.feedback

import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.lumastack.lumawork.data.feedback.FeedbackAttachment
import ru.lumastack.lumawork.data.feedback.FeedbackDraft
import ru.lumastack.lumawork.data.feedback.FeedbackFailure
import ru.lumastack.lumawork.data.feedback.FeedbackImage
import ru.lumastack.lumawork.data.feedback.FeedbackImpact
import ru.lumastack.lumawork.data.feedback.FeedbackFrequency
import ru.lumastack.lumawork.data.feedback.FeedbackKind
import ru.lumastack.lumawork.data.feedback.FeedbackMessage
import ru.lumastack.lumawork.data.feedback.FeedbackRepository
import ru.lumastack.lumawork.data.feedback.FeedbackSnapshot
import ru.lumastack.lumawork.domain.model.AppSession
import ru.lumastack.lumawork.ui.adaptive.AppWidthClass
import ru.lumastack.lumawork.ui.theme.LumaBackgroundBottomDark
import ru.lumastack.lumawork.ui.theme.LumaBackgroundBottomLight
import ru.lumastack.lumawork.ui.theme.LumaBackgroundTopDark
import ru.lumastack.lumawork.ui.theme.LumaBackgroundTopLight

data class FeedbackUiState(val snapshot:FeedbackSnapshot=FeedbackSnapshot(),val selected:FeedbackMessage?=null,val draft:FeedbackDraft?=null,val images:List<FeedbackImage> = emptyList(),val addition:String="",val loading:Boolean=false,val submitting:Boolean=false,val error:String?=null,val notice:String?=null)
class FeedbackViewModel(private val repository:FeedbackRepository,private val session:AppSession,private val offline:Boolean):ViewModel(){private val mutable=MutableStateFlow(FeedbackUiState());val state=mutable.asStateFlow();init{viewModelScope.launch{repository.read(session.user.id)?.let{v->mutable.update{it.copy(snapshot=v)}};if(!offline)refresh(false)}};fun refresh()=viewModelScope.launch{refresh(true)};fun open(v:FeedbackMessage)=mutable.update{it.copy(selected=v,addition="")};fun close()=mutable.update{it.copy(selected=null)};fun compose(v:FeedbackDraft=FeedbackDraft())=mutable.update{it.copy(draft=v,images=emptyList())};fun draft(v:FeedbackDraft)=mutable.update{it.copy(draft=v,error=null)};fun closeDraft()=mutable.update{it.copy(draft=null,images=emptyList())};fun addition(v:String)=mutable.update{it.copy(addition=v)};fun images(v:List<FeedbackImage>)=mutable.update{it.copy(images=v.take(4))};fun submit(){val d=state.value.draft?:return;if(!d.valid){mutable.update{it.copy(error="Заголовок — от 5 символов, описание — от 20; выберите раздел.")};return};viewModelScope.launch{mutable.update{it.copy(submitting=true,error=null)};try{val s=repository.submit(session.user.id,session.token,d,state.value.images);mutable.update{it.copy(snapshot=s,submitting=false,draft=null,images=emptyList(),notice="Сообщение отправлено.")}}catch(e:CancellationException){throw e}catch(e:Exception){mutable.update{it.copy(submitting=false,error=e.msg())}}}};fun add(){val selected=state.value.selected?:return;val text=state.value.addition.trim();if(text.length<3)return;viewModelScope.launch{mutable.update{it.copy(submitting=true)};try{val s=repository.add(session.user.id,session.token,selected.id,text);val updated=s.messages.firstOrNull{it.id==selected.id};mutable.update{it.copy(snapshot=s,selected=updated,addition="",submitting=false)}}catch(e:Exception){mutable.update{it.copy(submitting=false,error=e.msg())}}}};fun resend(v:FeedbackMessage)=compose(FeedbackDraft(kind=v.kind,title=v.title,message=v.message,reproductionSteps=v.reproductionSteps.orEmpty(),expectedResult=v.expectedResult.orEmpty(),areaCodes=v.areaCodes.toSet(),otherArea=v.otherArea.orEmpty(),impact=v.impact,frequency=v.frequency,resubmittedFromId=v.id));fun attachment(v:FeedbackMessage,a:FeedbackAttachment,context:Context)=viewModelScope.launch{try{val bytes=repository.attachment(session.token,v.id,a.id);val file=withContext(Dispatchers.IO){File(context.cacheDir,"feedback-${a.id}.jpg").apply{writeBytes(bytes)}};share(context,file,a.mimeType)}catch(e:Exception){mutable.update{it.copy(error=e.msg())}}};private suspend fun refresh(show:Boolean){mutable.update{it.copy(loading=true)};try{val s=repository.refresh(session.user.id,session.token);mutable.update{it.copy(snapshot=s,loading=false)}}catch(e:Exception){mutable.update{it.copy(loading=false,error=if(show)e.msg()else null)}}};private fun Throwable.msg()=(this as?FeedbackFailure)?.message?:"Не удалось выполнить действие.";companion object{fun factory(r:FeedbackRepository,s:AppSession,o:Boolean)=object:ViewModelProvider.Factory{@Suppress("UNCHECKED_CAST")override fun<T:ViewModel>create(modelClass:Class<T>):T=FeedbackViewModel(r,s,o)as T}}}

@Composable fun FeedbackRoute(session:AppSession,startOffline:Boolean,repository:FeedbackRepository,widthClass:AppWidthClass,modifier:Modifier=Modifier){val vm:FeedbackViewModel=viewModel(key="feedback-${session.user.id}",factory=FeedbackViewModel.factory(repository,session,startOffline));val s by vm.state.collectAsStateWithLifecycle();FeedbackScreen(s,widthClass,vm,modifier)}
@Composable private fun FeedbackScreen(s:FeedbackUiState,w:AppWidthClass,vm:FeedbackViewModel,modifier:Modifier){val dark=MaterialTheme.colorScheme.background==LumaBackgroundTopDark;val bg=remember(dark){Brush.verticalGradient(if(dark)listOf(LumaBackgroundTopDark,LumaBackgroundBottomDark)else listOf(LumaBackgroundTopLight,LumaBackgroundBottomLight))};Box(modifier.fillMaxSize().background(bg),contentAlignment=Alignment.TopCenter){LazyColumn(Modifier.widthIn(max=if(w==AppWidthClass.Compact)680.dp else 1080.dp).fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){item{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){Column{Text("Обратная связь",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold);Text("Сообщения, предложения и проблемы",color=MaterialTheme.colorScheme.onSurfaceVariant)};Button(onClick={vm.compose()}){Text("Создать")}}};s.error?.let{item{Notice(it,true)}};s.notice?.let{item{Notice(it)}};if(s.loading&&s.snapshot.messages.isEmpty())item{CircularProgressIndicator(Modifier.size(24.dp))};if(!s.loading&&s.snapshot.messages.isEmpty())item{Notice("Отправленных сообщений пока нет.")};items(s.snapshot.messages,key=FeedbackMessage::id){v->FeedbackCard(v,{vm.open(v)},{vm.resend(v)})}}};s.draft?.let{FeedbackEditor(s,vm)};s.selected?.let{FeedbackDetail(it,s,vm)} }
@Composable private fun FeedbackCard(v:FeedbackMessage,open:()->Unit,resend:()->Unit){Surface(Modifier.fillMaxWidth().clickable(onClick=open),shape=RoundedCornerShape(19.dp),color=MaterialTheme.colorScheme.surface.copy(alpha=.97f)){Column(Modifier.padding(15.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(v.number.ifBlank{"Обратная связь"},fontWeight=FontWeight.Bold);Text(v.status.title,color=MaterialTheme.colorScheme.primary)};Text(v.title,fontWeight=FontWeight.SemiBold);Text(v.message,maxLines=3,color=MaterialTheme.colorScheme.onSurfaceVariant);TextButton(onClick=resend){Text("Отправить заново")}}}}
@Composable private fun FeedbackEditor(s:FeedbackUiState,vm:FeedbackViewModel){val d=s.draft?:return;val context=LocalContext.current;val launcher=rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()){uris->vm.images(uris.take(4).mapNotNull{uri->runCatching{FeedbackImage("screenshot-${System.nanoTime()}.jpg",readLimited(context,uri)?:return@runCatching null)}.getOrNull()})};AlertDialog(onDismissRequest=vm::closeDraft,title={Text("Новое сообщение")},text={LazyColumn(verticalArrangement=Arrangement.spacedBy(10.dp)){item{ChoiceRow(FeedbackKind.entries.map{it.title},FeedbackKind.entries.indexOf(d.kind)){vm.draft(d.copy(kind=FeedbackKind.entries[it]))};Field("Заголовок",d.title){vm.draft(d.copy(title=it))};Field("Описание",d.message,false){vm.draft(d.copy(message=it))};if(d.kind.needsReproductionSteps)Field("Условие, при котором возникает проблема",d.reproductionSteps,false){vm.draft(d.copy(reproductionSteps=it))};Field("Как должно работать",d.expectedResult,false){vm.draft(d.copy(expectedResult=it))};Text("Разделы",fontWeight=FontWeight.Bold);Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){AREAS.forEach{(code,title)->val selected=code in d.areaCodes;if(selected)Button(onClick={vm.draft(d.copy(areaCodes=d.areaCodes-code))}){Text(title)}else OutlinedButton(onClick={vm.draft(d.copy(areaCodes=d.areaCodes+code))}){Text(title)}}};if("other" in d.areaCodes)Field("Укажите раздел",d.otherArea){vm.draft(d.copy(otherArea=it))};Text("Насколько мешает",fontWeight=FontWeight.Bold);ChoiceRow(FeedbackImpact.entries.map{it.title},FeedbackImpact.entries.indexOf(d.impact)){vm.draft(d.copy(impact=FeedbackImpact.entries[it]))};Text("Как часто возникает",fontWeight=FontWeight.Bold);ChoiceRow(FeedbackFrequency.entries.map{it.title},FeedbackFrequency.entries.indexOf(d.frequency)){vm.draft(d.copy(frequency=FeedbackFrequency.entries[it]))};OutlinedButton(onClick={launcher.launch("image/*")}){Text("Снимки: ${s.images.size}/4")};s.error?.let{Notice(it,true)}}}},confirmButton={Button(onClick=vm::submit,enabled=!s.submitting){Text("Отправить")}},dismissButton={TextButton(onClick=vm::closeDraft){Text("Отмена")}})}
@Composable private fun FeedbackDetail(v:FeedbackMessage,s:FeedbackUiState,vm:FeedbackViewModel){val context=LocalContext.current;AlertDialog(onDismissRequest=vm::close,title={Text("${v.number} · ${v.status.title}")},text={LazyColumn(verticalArrangement=Arrangement.spacedBy(10.dp)){item{Text(v.title,fontWeight=FontWeight.Bold);Text(v.message);v.reproductionSteps?.let{Text("Шаги: $it")};v.additions.forEach{Text("Дополнение: ${it.text}")};v.attachments.forEach{a->OutlinedButton(onClick={vm.attachment(v,a,context)},modifier=Modifier.fillMaxWidth()){Text("Открыть ${a.fileName}")}};Field("Дополнение",s.addition,false,vm::addition)}}},confirmButton={Button(onClick=vm::add,enabled=!s.submitting){Text("Дополнить")}},dismissButton={TextButton(onClick=vm::close){Text("Закрыть")}})}
@Composable private fun Field(label:String,value:String,single:Boolean=true,change:(String)->Unit){AppTextField(value,change,label = label,singleLine=single,minLines=if(single)1 else 3,modifier=Modifier.fillMaxWidth())}
@Composable private fun ChoiceRow(values:List<String>,selected:Int,change:(Int)->Unit){Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){values.forEachIndexed{i,v->if(i==selected)Button(onClick={change(i)}){Text(v)}else OutlinedButton(onClick={change(i)}){Text(v)}}}}
@Composable private fun Notice(v:String,error:Boolean=false){Surface(color=if(error)MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,shape=RoundedCornerShape(16.dp)){Text(v,modifier=Modifier.padding(14.dp),color=if(error)MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant)}}
private val AREAS=listOf("home" to "Главная","backpack" to "Рюкзак","maintenance" to "Авто","fuel" to "Топливо","wiki" to "Вики","ftp" to "FTP","salary" to "Зарплата","requests" to "Заявки","analytics" to "Аналитика","other" to "Другое")
private val FeedbackKind.needsReproductionSteps get()=this in setOf(FeedbackKind.Error,FeedbackKind.Freeze,FeedbackKind.Slowdown,FeedbackKind.LongLoading)
private fun readLimited(context:Context,uri:android.net.Uri,maxBytes:Int=10*1024*1024):ByteArray?{context.contentResolver.openAssetFileDescriptor(uri,"r")?.use{if(it.length>maxBytes)return null};return context.contentResolver.openInputStream(uri)?.use{input->val output=ByteArrayOutputStream();val buffer=ByteArray(32*1024);var total=0;while(true){val count=input.read(buffer);if(count<0)break;total+=count;if(total>maxBytes)return null;output.write(buffer,0,count)};output.toByteArray()}}
private fun share(context:Context,file:File,mime:String){val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",file);context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type=mime;putExtra(Intent.EXTRA_STREAM,uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)},"Открыть снимок"))}
