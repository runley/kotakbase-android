package app.kotakbase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomCodeTest {
    @Test fun codes() {
        repeat(50) { val c = newCode(); assertEquals(c, parseCode(formatCode(c))) } // new codes read back like desktop's
        assertEquals("ABCD-EFGH-JKLM", formatCode("ABCDEFGHJKLM"))
    }

    @Test fun pins() {
        assertTrue(validPin("1234")); assertTrue(validPin("12345678"))
        assertFalse(validPin("123")); assertFalse(validPin("123456789")); assertFalse(validPin("12a4")); assertFalse(validPin(""))
    }
}
