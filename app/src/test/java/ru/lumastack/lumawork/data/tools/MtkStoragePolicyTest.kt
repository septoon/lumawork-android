package ru.lumastack.lumawork.data.tools

import org.junit.Assert.*
import org.junit.Test

class MtkStoragePolicyTest {
    @Test fun captured_64gb_profile_uses_its_own_exact_layout_and_preserves_factory_tail() {
        val gpt=mtkP8LayoutFixture(62528684032L)
        assertEquals("c178c9c1dc37b62384c6ea681e9e11fdc80124101acc0bd9826a2dfc457e1a12",gpt.layoutSha256)
        assertTrue(gpt.matchesP8Profile)
        val policy=mtkUpgradeLayout(gpt)
        assertEquals(listOf(0x10d00000L to 0xe7a7f8000L,0xc000000L to 0xa00000L,
            0x3688000L to 0x769a000L,0x8000L to 0x1680000L),policy.formats.map { it.startBytes to it.sizeBytes })
        assertEquals(0xe8b4f8000L,policy.preserved.single { it.name=="otp" }.startBytes)
        assertEquals(0xe8dff8000L,policy.preserved.single { it.name=="flashinfo" }.startBytes)
        for (part in policy.preserved) assertTrue(policy.formats.none { it.startBytes < part.startBytes+part.sizeBytes && part.startBytes < it.startBytes+it.sizeBytes })
        val target=mtkWriteTarget().copy(emmc=mtkWriteTarget().emmc.copy(userSize=gpt.userSize),gpt=gpt)
        assertTrue(target.compatible)
    }
    @Test fun mixed_capacity_hashes_and_uncaptured_geometries_are_rejected() {
        val small=mtkP8LayoutFixture();val large=mtkP8LayoutFixture(62528684032L)
        for (gpt in listOf(large.copy(layoutSha256=small.layoutSha256),small.copy(layoutSha256=large.layoutSha256),
            large.copy(userSize=large.userSize+512))) {
            assertFalse(gpt.matchesP8Profile)
            assertThrows(IllegalArgumentException::class.java) { mtkUpgradeLayout(gpt) }
        }
        assertThrows(IllegalArgumentException::class.java) { mtkUpgradeLayout(large.copy(partitions=small.partitions)) }
        assertFalse(mtkWriteTarget().copy(gpt=large).compatible)
    }
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
