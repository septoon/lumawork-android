package ru.lumastack.lumawork.data.tools

import java.io.OutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal const val MTK_WRITE_SUCCESS="Прошивка записана. Можно отсоединить USB. Дождитесь загрузки терминала"
internal enum class MtkWritePhase { VerifyingFiles, PreparingStorage, CheckingLayout, WritingPartition, Finalizing, WriteComplete }
internal data class MtkWriteEvent(val phase: MtkWritePhase, val pendingAction: String?=null, val acknowledgedAction: String?=null,
    val fileName: String?=null, val bytes: Long=0, val totalBytes: Long=0)
internal data class MtkWriteResult(val fingerprint: String, val packageHash: String, val detail: String=MTK_WRITE_SUCCESS)

/** One fresh run, with durable intent callbacks before every destructive command. Never resumes an offset. */
internal class MtkFirmwareWorkflow(private val agent: MtkUpgradeAgent,
    private val profile: MtkPackageProfile=MTK_P8_PROFILE,
    private val checkpoint: ()->Unit={}, private val event: (MtkWriteEvent)->Unit={},
) {
    private var used=false
    fun execute(pkg: MtkFirmwarePackage,target: MtkTargetInfo): MtkWriteResult {
        check(!used) { "Операция уже запускалась. Автоматическое продолжение FORMAT/DOWNLOAD запрещено." };used=true
        require(target.compatible && target.emmc.userSize==target.gpt?.userSize) { "Модель или разметка P8KC не подтверждены. Запись запрещена." }
        val layout=mtkUpgradeLayout(requireNotNull(target.gpt))
        var state=MtkWriteEvent(MtkWritePhase.VerifyingFiles)
        fun publish(next: MtkWriteEvent) { checkpoint();event(next);state=next }
        publish(state)
        var progressAt=0L
        val reader=MtkFirmwarePackageReader(profile,checkpoint=checkpoint,progress={name,bytes,total ->
            val now=System.nanoTime()
            if(name!=state.fileName || bytes==total || now-progressAt>200_000_000) {
                progressAt=now;publish(state.copy(fileName=name,bytes=bytes,totalBytes=total))
            }
        })
        val verified=reader.inspect(pkg.directory)
        require(verified.sha256==pkg.sha256 && verified.images==pkg.images && verified.agentProfile==pkg.agentProfile) { "Комплект изменился после подготовки." }
        verified.images.forEach { image ->
            require(image.partitionName !in layout.preserved.map { it.name })
            val capacity=when(image.partitionName) {
                "preloader","preloader_backup" -> minOf(target.emmc.boot1Size,target.emmc.boot2Size)
                else -> target.gpt.partitions.single { it.name==image.partitionName }.sizeBytes
            }
            require(mtkImageLogicalSize(image.file)<=capacity) { "Образ ${image.file.name} превышает проверенный раздел." }
        }
        publish(state.copy(phase=MtkWritePhase.PreparingStorage,fileName=null,bytes=0,totalBytes=0))
        publish(state.copy(pendingAction="start-download-info",acknowledgedAction=null))
        agent.beginUpgrade()
        publish(state.copy(pendingAction=null,acknowledgedAction="start-download-info"))
        layout.formats.forEachIndexed { index,range ->
            val action="format:$index"
            publish(state.copy(pendingAction=action,acknowledgedAction=null))
            agent.format(range)
            publish(state.copy(pendingAction=null,acknowledgedAction=action))
        }
        publish(state.copy(phase=MtkWritePhase.CheckingLayout,pendingAction=null))
        require(agent.readLayout()==target.gpt) { "GPT после FORMAT изменилась. DOWNLOAD запрещён; требуется восстановление." }
        verified.images.forEach { image ->
            val action="download:${image.partitionName}"
            publish(state.copy(phase=MtkWritePhase.WritingPartition,pendingAction=action,acknowledgedAction=null,
                fileName=image.partitionName,bytes=0,totalBytes=image.size))
            agent.download(image) { bytes ->
                require(bytes in state.bytes..image.size)
                publish(state.copy(bytes=bytes))
            }
            publish(state.copy(pendingAction=null,acknowledgedAction=action))
        }
        publish(state.copy(phase=MtkWritePhase.Finalizing,pendingAction="finish",acknowledgedAction=null,fileName=null,bytes=0,totalBytes=0))
        agent.finish()
        // Final ACK is already a fact. A subsequent detach/cancel cannot invalidate it.
        event(state.copy(phase=MtkWritePhase.WriteComplete,pendingAction=null,acknowledgedAction="finish"))
        return MtkWriteResult(target.fingerprint,verified.sha256)
    }
}

/** Read only the sparse header. Original sparse bytes are sent unchanged; never expand super.img. */
internal fun mtkImageLogicalSize(file: File): Long {
    if(file.length()<28) return file.length()
    val bytes=ByteArray(28)
    file.inputStream().use { java.io.DataInputStream(it).readFully(bytes) }
    val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    if(b.int!=0xed26ff3a.toInt()) return file.length()
    require((b.short.toInt() and 65535)==1)
    b.short
    val header=b.short.toInt() and 65535;val chunk=b.short.toInt() and 65535
    val block=b.int.toLong() and 0xffffffffL;val count=b.int.toLong() and 0xffffffffL
    require(header>=28 && chunk>=12 && block in 512..1048576 && block%512==0L && count>0)
    return Math.multiplyExact(block,count)
}

internal interface MtkUpgradeAgent {
    fun read(partition: MtkPartition, output: OutputStream)
    fun readLayout(): MtkGptInfo
    fun beginUpgrade()
    fun format(range: MtkFormatRange)
    fun download(image: MtkImage, confirmed: (Long)->Unit)
    fun finish()
}

internal data class MtkFormatRange(val startBytes: Long, val sizeBytes: Long)
internal data class MtkUpgradeLayout(val formats: List<MtkFormatRange>, val preserved: List<MtkPartition>)

/** Firmware Upgrade for the observed healthy GPT only. Never repairs or replaces a different layout. */
internal fun mtkUpgradeLayout(gpt: MtkGptInfo): MtkUpgradeLayout {
    val canonical = "${gpt.userSize}\n" + gpt.partitions.sortedBy { it.startBytes }.joinToString("\n") { "${it.name}:${it.startBytes}:${it.sizeBytes}" }
    require(gpt.matchesP8Profile && mtkSha256(canonical.toByteArray(Charsets.UTF_8)) == gpt.layoutSha256) {
        "Разметка eMMC отличается от проверенного P8KC. FORMAT запрещён."
    }
    fun partition(name: String) = gpt.partitions.single { it.name == name }
    fun span(first: String,last: String): MtkFormatRange {
        val a=partition(first); val b=partition(last)
        val end=Math.addExact(b.startBytes,b.sizeBytes)
        require(a.startBytes >= 32768 && end <= gpt.userSize-16896 && end>a.startBytes)
        return MtkFormatRange(a.startBytes,end-a.startBytes)
    }
    val formats=listOf(span("logo","userdata"),span("seccfg","sec1"),span("nvdata","metadata"),span("boot_para","frp"))
    val preserved=listOf("nvcfg","protect1","protect2","proinfo","nvram","otp","flashinfo").map(::partition)
    require(preserved.all { part -> formats.none { it.startBytes < part.startBytes+part.sizeBytes && part.startBytes < it.startBytes+it.sizeBytes } })
    return MtkUpgradeLayout(formats,preserved)
}
