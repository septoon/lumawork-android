package ru.lumastack.lumawork.data.tools

import org.junit.Assert.*
import org.junit.Test

class MtkStoragePolicyTest {
    @Test fun formats_follow_verified_geometry_and_skip_all_factory_regions() {
        val gpt=mtkP8LayoutFixture()
        assertTrue(gpt.matchesP8Profile)
        val policy=mtkUpgradeLayout(gpt)
        assertEquals(listOf(0x10d00000L to 0x7347f8000L,0xc000000L to 0xa00000L,
            0x3688000L to 0x769a000L,0x8000L to 0x1680000L),policy.formats.map { it.startBytes to it.sizeBytes })
        assertEquals(setOf("nvcfg","protect1","protect2","proinfo","nvram","otp","flashinfo"),policy.preserved.map { it.name }.toSet())
        for (part in policy.preserved) assertTrue(policy.formats.none { it.startBytes < part.startBytes+part.sizeBytes && part.startBytes < it.startBytes+it.sizeBytes })
        assertTrue(policy.preserved.sumOf { it.sizeBytes } < 256L*1024*1024)
    }
    @Test fun changed_geometry_even_with_copied_layout_hash_is_rejected() {
        val gpt=mtkP8LayoutFixture()
        assertThrows(IllegalArgumentException::class.java) { mtkUpgradeLayout(gpt.copy(partitions=gpt.partitions.map { if (it.name=="nvram") it.copy(startBytes=0x10d00000) else it })) }
        assertThrows(IllegalArgumentException::class.java) { mtkUpgradeLayout(gpt.copy(userSize=gpt.userSize+512)) }
    }
}
