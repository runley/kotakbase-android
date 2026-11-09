package app.kotakbase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NavTest {
    @Test fun backGoesThroughTabs() {
        val nav = Nav()
        nav.go(Page.Show(1)) // a show opened from Home
        nav.tab(Page.MyList)
        nav.tab(Page.Downloads)
        nav.tab(Page.Downloads) // tapping the tab you're on adds nothing
        nav.back(); assertEquals(listOf(Page.MyList), nav.stack.toList())
        nav.back(); assertEquals(listOf(Page.Home, Page.Show(1)), nav.stack.toList())
        nav.back(); assertEquals(listOf(Page.Home), nav.stack.toList())
        assertFalse(nav.canGoBack) // the next Back leaves the app
    }

    @Test fun modeSwitchForgets() {
        val nav = Nav()
        nav.tab(Page.MyList)
        nav.tab(Page.Home, fresh = true)
        assertFalse(nav.canGoBack)
    }
}
