package ru.lumastack.lumawork.domain.backpack

import com.google.zxing.BinaryBitmap
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.RGBLuminanceSource
import org.junit.Assert.*
import org.junit.Test

class SerialBarcodeTest {
    @Test fun `Code128 round trip preserves leading zeros case and punctuation`() {
        val serial = "000AbC-123/45"
        val matrix = SerialBarcode.encode(serial)!!
        val width = maxOf(1000, matrix.width * 3)
        val pixels = IntArray(width * 180) { index ->
            val x = (index % width) * matrix.width / width
            if (matrix[x, 0]) 0xff000000.toInt() else 0xffffffff.toInt()
        }
        val source = RGBLuminanceSource(width, 180, pixels)
        assertEquals(serial, SerialBarcode.decode(BinaryBitmap(HybridBinarizer(source))))
        assertTrue(source.isRotateSupported)
        assertEquals(serial, SerialBarcode.decode(BinaryBitmap(HybridBinarizer(source.rotateCounterClockwise()))))
        assertNull(SerialBarcode.serial("(не задано)"))
        assertNull(SerialBarcode.encode("Кириллица"))
        assertNull(SerialBarcode.serial("—"))
    }
}
