package com.doloh.marstekwidget.domain

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PowerFieldsTest {
    @Test
    fun foldsUint16WrappedNegatives() {
        assertEquals(-14.0, PowerFields.normalize(65522.0), 0.0)
        assertEquals(-1.0, PowerFields.normalize(65535.0), 0.0)
        assertEquals(-32768.0, PowerFields.normalize(32768.0), 0.0)
    }

    @Test
    fun leavesRealValuesUntouched() {
        assertEquals(-2490.0, PowerFields.normalize(-2490.0), 0.0)
        assertEquals(438.0, PowerFields.normalize(438.0), 0.0)
        assertEquals(32767.0, PowerFields.normalize(32767.0), 0.0)
        assertEquals(0.0, PowerFields.normalize(0.0), 0.0)
        assertEquals(65536.0, PowerFields.normalize(65536.0), 0.0)
        assertNull(PowerFields.normalize(null as Double?))
    }

    @Test
    fun readAndNormalizeJson() {
        val data = JSONObject("""{"offgrid_power":65522,"ongrid_power":-2490,"pv_power":null,"bat_power":438,"bat_soc":65522}""")
        assertEquals(-14.0, PowerFields.read(data, "offgrid_power")!!, 0.0)
        assertNull(PowerFields.read(data, "pv_power"))
        assertNull(PowerFields.read(data, "missing"))
        assertNull(PowerFields.read(null, "offgrid_power"))
        val folded = PowerFields.normalize(data)
        assertEquals(-14.0, folded.getDouble("offgrid_power"), 0.0)
        assertEquals(-2490, folded.getInt("ongrid_power"))
        assertEquals(438, folded.getInt("bat_power"))
        assertEquals(65522, folded.getInt("bat_soc"))
        assertEquals(65522, data.getInt("offgrid_power"))
    }
}
