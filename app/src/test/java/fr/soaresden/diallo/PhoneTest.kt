package fr.soaresden.diallo

import org.junit.Assert.assertEquals
import org.junit.Test

/** Lecture des numéros selon le pays où se trouve le téléphone. */
class PhoneTest {
    @Test fun belgique() {
        Home.force("BE")
        assertEquals("BE", countryOf("0470 12 34 56")?.iso)
        assertEquals("+32 470 12 34 56", intlNumber("0470 12 34 56"))
        assertEquals("+32 470 12 34 56", intlNumber("+32470123456"))
        assertEquals("+32 2 123 45 67", intlNumber("02 123 45 67"))
        assertEquals("+32 71 12 34 56", intlNumber("071 12 34 56"))
        assertEquals("Mobile", lineType("0470123456"))
        assertEquals("Mobile", lineType("+32 470 12 34 56"))
        assertEquals("Fixe", lineType("02 123 45 67"))
        assertEquals("Étranger", lineType("+33 6 12 34 56 78"))
        assertEquals(keyOf("+32 2 123 45 67"), keyOf("02 123 45 67"))
        assertEquals(keyOf("+32 470 12 34 56"), keyOf("0470123456"))
        assertEquals("0470123456", nationalDigits("+32 470 12 34 56"))
    }

    @Test fun france() {
        Home.force("FR")
        assertEquals("FR", countryOf("06 12 34 56 78")?.iso)
        assertEquals("+33 6 12 34 56 78", intlNumber("0612345678"))
        assertEquals("Mobile", lineType("0612345678"))
        assertEquals("Fixe", lineType("+33 1 23 45 67 89"))
        assertEquals("RE", countryOf("0692 12 34 56")?.iso)
        assertEquals("Mobile", lineType("0692123456"))
        assertEquals("Mobile", lineType("+262 692 12 34 56"))
        assertEquals("Étranger", lineType("+32 470 12 34 56"))
        assertEquals("612345678", keyOf("+33 6 12 34 56 78"))
        assertEquals("612345678", keyOf("0612345678"))
        assertEquals("3631", keyOf("3631"))
    }

    @Test fun italie() {
        Home.force("IT")
        assertEquals("IT", countryOf("347 123 4567")?.iso)
        assertEquals("Mobile", lineType("3471234567"))
        assertEquals("Fixe", lineType("06 1234 5678"))
    }
}
