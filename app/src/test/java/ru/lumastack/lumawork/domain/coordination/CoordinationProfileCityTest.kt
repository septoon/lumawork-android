package ru.lumastack.lumawork.domain.coordination

import org.junit.Assert.*
import org.junit.Test

class CoordinationProfileCityTest {
    @Test fun `profile cities match supported regions including case and common city prefixes`() {
        assertEquals(CoordinationRegion.Simferopol, coordinationRegionForProfileCity(" г. Симферополь "))
        assertEquals(CoordinationRegion.Alushta, coordinationRegionForProfileCity("АЛУШТА"))
        assertEquals(CoordinationRegion.Kerch, coordinationRegionForProfileCity("город Керчь"))
        assertEquals(CoordinationRegion.Yalta, coordinationRegionForProfileCity("г.Ялта"))
        for (region in CoordinationRegion.entries) assertEquals(region, coordinationRegionForProfileCity(region.title))
    }

    @Test fun `empty or unsupported city never falls back to Alushta`() {
        for (city in listOf(null, "", "  ", "Неизвестный город", "Алушта и Ялта")) assertNull(coordinationRegionForProfileCity(city))
    }
}
