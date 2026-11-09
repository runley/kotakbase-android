package app.kotakbase

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatesTest {
    @Test fun versions() {
        assertTrue(Updates.newer("1.10.0", "1.9.2"))
        assertTrue(Updates.newer("v0.2.0", "0.1.0"))
        assertTrue(Updates.newer("0.1.1", "0.1"))
        assertFalse(Updates.newer("0.1.0", "0.1.0"))
        assertFalse(Updates.newer("0.1", "0.1.0"))
        assertFalse(Updates.newer("0.0.9", "0.1.0"))
    }
}
