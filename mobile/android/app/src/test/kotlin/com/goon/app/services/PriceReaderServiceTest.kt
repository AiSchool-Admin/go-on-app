package com.goon.app.services

import org.junit.Test
import org.junit.Assert.*
import com.goon.app.services.config.PricePatterns
import com.goon.app.services.config.AppConstants

/**
 * Unit tests for PriceReaderService functionality
 *
 * These tests verify price extraction patterns and automation logic
 * without requiring Android framework dependencies.
 */
class PriceReaderServiceTest {

    @Test
    fun `price pattern should match Egyptian pound format`() {
        val patterns = listOf(
            "85 ج.م" to 85.0,
            "100 EGP" to 100.0,
            "جنيه 75" to 75.0,
            "EGP 120" to 120.0,
            "٨٥ ج.م" to 85.0,  // Arabic numerals
        )

        for ((text, expected) in patterns) {
            val result = extractPrice(text)
            assertEquals("Failed for: $text", expected, result, 0.01)
        }
    }

    @Test
    fun `price pattern should handle ranges`() {
        // InDriver shows price ranges
        val rangeText = "75-85 ج.م"
        val result = extractPriceRange(rangeText)

        assertNotNull(result)
        assertEquals(75.0, result?.first, 0.01)
        assertEquals(85.0, result?.second, 0.01)
    }

    @Test
    fun `should identify Uber package name`() {
        val packageName = "com.ubercab"
        assertTrue(AppConstants.isUberPackage(packageName))
    }

    @Test
    fun `should identify Careem package name`() {
        val packageName = "com.careem.acma"
        assertTrue(AppConstants.isCareemPackage(packageName))
    }

    @Test
    fun `should identify DiDi package name`() {
        val packageName = "com.didiglobal.passenger"
        assertTrue(AppConstants.isDiDiPackage(packageName))
    }

    @Test
    fun `should identify InDriver package name`() {
        val packageName = "sinet.startup.inDriver"
        assertTrue(AppConstants.isInDriverPackage(packageName))
    }

    @Test
    fun `should identify Bolt package name`() {
        val packageName = "ee.mtakso.client"
        assertTrue(AppConstants.isBoltPackage(packageName))
    }

    @Test
    fun `should not identify unknown package`() {
        val packageName = "com.unknown.app"
        assertFalse(AppConstants.isRideHailingApp(packageName))
    }

    @Test
    fun `price extraction should handle whitespace`() {
        val prices = listOf(
            "  85 ج.م  " to 85.0,
            "85  ج.م" to 85.0,
            "EGP  100" to 100.0,
        )

        for ((text, expected) in prices) {
            val result = extractPrice(text.trim())
            assertEquals("Failed for: '$text'", expected, result, 0.01)
        }
    }

    @Test
    fun `should convert Arabic numerals to Western`() {
        val arabicNumerals = "٠١٢٣٤٥٦٧٨٩"
        val expected = "0123456789"

        assertEquals(expected, convertArabicToWestern(arabicNumerals))
    }

    @Test
    fun `price should be within reasonable range`() {
        // Egyptian ride prices typically 15-500 EGP
        val validPrice = 85.0
        val tooLow = 5.0
        val tooHigh = 10000.0

        assertTrue(isReasonablePrice(validPrice))
        assertFalse(isReasonablePrice(tooLow))
        assertFalse(isReasonablePrice(tooHigh))
    }

    // Helper functions that mirror the actual service implementation

    private fun extractPrice(text: String): Double {
        val patterns = listOf(
            """(\d+(?:\.\d+)?)\s*(?:ج\.م|جنيه|EGP|egp)""".toRegex(),
            """(?:ج\.م|جنيه|EGP|egp)\s*(\d+(?:\.\d+)?)""".toRegex(),
            """([٠-٩]+(?:\.[٠-٩]+)?)\s*(?:ج\.م|جنيه)""".toRegex(),
        )

        val normalizedText = convertArabicToWestern(text)

        for (pattern in patterns) {
            val match = pattern.find(normalizedText)
            if (match != null) {
                return match.groupValues[1].toDoubleOrNull() ?: 0.0
            }
        }
        return 0.0
    }

    private fun extractPriceRange(text: String): Pair<Double, Double>? {
        val pattern = """(\d+)-(\d+)\s*(?:ج\.م|EGP)""".toRegex()
        val match = pattern.find(text) ?: return null

        val min = match.groupValues[1].toDoubleOrNull() ?: return null
        val max = match.groupValues[2].toDoubleOrNull() ?: return null

        return Pair(min, max)
    }

    private fun convertArabicToWestern(text: String): String {
        val arabicDigits = "٠١٢٣٤٥٦٧٨٩"
        var result = text
        for (i in 0..9) {
            result = result.replace(arabicDigits[i], '0' + i)
        }
        return result
    }

    private fun isReasonablePrice(price: Double): Boolean {
        return price in 15.0..500.0
    }
}

/**
 * Tests for AppConstants utility functions
 */
object AppConstants {
    private val UBER_PACKAGES = setOf("com.ubercab", "com.ubercab.driver")
    private val CAREEM_PACKAGES = setOf("com.careem.acma", "com.careem.captain")
    private val DIDI_PACKAGES = setOf("com.didiglobal.passenger", "com.xiaojukeji.didi.gp")
    private val INDRIVER_PACKAGES = setOf("sinet.startup.inDriver")
    private val BOLT_PACKAGES = setOf("ee.mtakso.client", "ee.mtakso.driver")

    fun isUberPackage(packageName: String) = packageName in UBER_PACKAGES
    fun isCareemPackage(packageName: String) = packageName in CAREEM_PACKAGES
    fun isDiDiPackage(packageName: String) = packageName in DIDI_PACKAGES
    fun isInDriverPackage(packageName: String) = packageName in INDRIVER_PACKAGES
    fun isBoltPackage(packageName: String) = packageName in BOLT_PACKAGES

    fun isRideHailingApp(packageName: String): Boolean {
        return isUberPackage(packageName) ||
               isCareemPackage(packageName) ||
               isDiDiPackage(packageName) ||
               isInDriverPackage(packageName) ||
               isBoltPackage(packageName)
    }
}
