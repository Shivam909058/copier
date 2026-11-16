package dev.copier

import org.junit.Assert.assertEquals
import org.junit.Test

class CopyLabelTest {
    @Test
    fun recognisesCopyControlsOnly() {
        val cases = mapOf(
            "Copy" to true,
            " copy " to true,
            "Copy link" to true,
            "Copy image" to true,
            "कॉपी करें" to true, // the system's localized "Copy" on a Hindi phone
            "Copyright" to false,
            "Copy this whole paragraph to everyone" to false,
            "Paste" to false,
            "" to false,
        )
        for ((label, expected) in cases) {
            assertEquals("label '$label'", expected, isCopyLabel(label, localizedCopy = if (label == "कॉपी करें") label else "Copy"))
        }
    }
}
