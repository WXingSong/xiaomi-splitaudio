package app.splitaudio

import org.junit.Assert.*
import org.junit.Test

/** 特定蓝牙音箱白名单（BtFilter）的纯逻辑单元测试 */
class BtFilterTest {
    @Test fun normalizeMac_uppercasesAndDropsPlaceholders() {
        assertEquals("AA:BB:CC:DD:EE:FF", BtFilter.normalizeMac(" aa:bb:cc:dd:ee:ff "))
        assertEquals("", BtFilter.normalizeMac("02:00:00:00:00:00"))
        assertEquals("", BtFilter.normalizeMac("00:00:00:00:00:00"))
        assertEquals("", BtFilter.normalizeMac(null))
        assertEquals("", BtFilter.normalizeMac("  "))
    }

    @Test fun matches_whitelistOnlyAllowsSelectedSpeakers() {
        val speaker = BtFilter.Entry("AA:BB:CC:DD:EE:FF", "Marshall Acton")
        val list = listOf(speaker)
        assertTrue(BtFilter.matches(list, "aa:bb:cc:dd:ee:ff", "Renamed"))
        assertFalse(BtFilter.matches(list, "11:22:33:44:55:66", "Xiaomi Buds 5"))
        assertTrue(BtFilter.matches(list, "", "Marshall Acton"))
        assertTrue(BtFilter.matches(emptyList(), "11:22:33:44:55:66", "Any Device"))
    }

    @Test fun toggled_canAddAndClearAll() {
        val a = BtFilter.Entry("AA:BB:CC:DD:EE:FF", "Speaker A")
        val b = BtFilter.Entry("11:22:33:44:55:66", "Speaker B")
        val l1 = BtFilter.toggled(emptyList(), a)
        assertEquals(1, l1.size)
        val l2 = BtFilter.toggled(l1, b)
        assertEquals(2, l2.size)
        val l3 = BtFilter.toggled(l2, a)
        assertEquals(listOf(b), l3)
        assertTrue(BtFilter.toggled(l3, b).isEmpty())
    }

    @Test fun fileText_andParseLine_roundtrip() {
        val entries = listOf(
            BtFilter.Entry("aa:bb:cc:dd:ee:ff", "JBL|Flip 6\n"),
            BtFilter.Entry("", "Office Speaker")
        )
        val text = BtFilter.fileText(entries)
        assertEquals("AA:BB:CC:DD:EE:FF|JBL Flip 6\n|Office Speaker\n", text)
        val parsed = text.lines().mapNotNull { BtFilter.parseLine(it) }
        assertEquals(2, parsed.size)
        assertEquals("AA:BB:CC:DD:EE:FF", parsed[0].mac)
        assertEquals("JBL Flip 6", parsed[0].name)
        assertEquals("", parsed[1].mac)
        assertEquals("Office Speaker", parsed[1].name)
    }
}
