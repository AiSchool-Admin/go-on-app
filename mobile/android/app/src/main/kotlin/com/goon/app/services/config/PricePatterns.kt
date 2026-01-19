package com.goon.app.services.config

import android.util.Log
import java.util.regex.Pattern

/**
 * Price Detection Patterns
 *
 * Comprehensive regex patterns for detecting prices in Egyptian Pounds (EGP)
 * from various ride-hailing app UIs.
 *
 * Supports:
 * - EGP (English)
 * - ج.م (Arabic - Gineih Masri)
 * - جنيه (Arabic - Guinee)
 * - LE (Livre Egyptienne)
 * - E£ (Egyptian Pound symbol)
 * - Various RTL/LTR formatting
 */
object PricePatterns {

    private const val TAG = "GO-ON-PricePatterns"

    // ============================================================
    // MAIN PRICE PATTERNS
    // Ordered by specificity (more specific patterns first)
    // ============================================================

    val PATTERNS: List<Pattern> = listOf(
        // === EGP formats ===
        // "EGP 65" or "EGP65"
        Pattern.compile("EGP\\s*(\\d+[.,]?\\d*)", Pattern.CASE_INSENSITIVE),
        // "65 EGP" or "65EGP"
        Pattern.compile("(\\d+[.,]?\\d*)\\s*EGP", Pattern.CASE_INSENSITIVE),

        // === ج.م formats (Egyptian Arabic) ===
        // Including RTL mark \u200F and LTR mark \u200E
        // "ج.م 65" or "ج.م. 65"
        Pattern.compile("ج\\.?م\\.?[\\s\\u200F\\u200E]*(\\d+[.,]?\\d*)"),
        // "65 ج.م" or "65 ج.م."
        Pattern.compile("(\\d+[.,]?\\d*)[\\s\\u200F\\u200E]*ج\\.?م\\.?"),

        // === Careem-specific format ===
        // "ج.م.‏ 61.40" (with RTL mark before number)
        Pattern.compile("ج\\.م\\.?\\u200F?\\s*(\\d+[.,]\\d{2})"),

        // === جنيه (Guinee - full word) ===
        // "جنيه 65"
        Pattern.compile("جنيه\\s*(\\d+[.,]?\\d*)"),
        // "65 جنيه"
        Pattern.compile("(\\d+[.,]?\\d*)\\s*جنيه"),

        // === LE formats (Livre Egyptienne) ===
        // "LE 65" or "LE65"
        Pattern.compile("LE\\s*(\\d+[.,]?\\d*)", Pattern.CASE_INSENSITIVE),
        // "65 LE" or "65LE"
        Pattern.compile("(\\d+[.,]?\\d*)\\s*LE", Pattern.CASE_INSENSITIVE),

        // === E£ format (Egyptian Pound symbol) ===
        // "E£ 65"
        Pattern.compile("E£\\s*(\\d+[.,]?\\d*)"),
        // "65 E£"
        Pattern.compile("(\\d+[.,]?\\d*)\\s*E£"),

        // === Price ranges ===
        // "65-75" or "65–75" (takes the lower price)
        Pattern.compile("(\\d+)\\s*[-–]\\s*\\d+\\s*(?:EGP|ج\\.?م|جنيه|LE)?", Pattern.CASE_INSENSITIVE),

        // === Labeled prices ===
        // "Fare: 65" or "price: 65"
        Pattern.compile("(?:fare|price|السعر|الأجرة)[:\\s]*(\\d+)", Pattern.CASE_INSENSITIVE),

        // === Standalone numbers (fallback) ===
        // Decimal: "61.40" or "67,50" (common in DiDi)
        Pattern.compile("^\\s*(\\d{2,3}[.,]\\d{1,2})\\s*$"),
        // Integer: "65" or "120"
        Pattern.compile("^\\s*(\\d{2,3})\\s*$")
    )

    // ============================================================
    // APP-SPECIFIC PATTERNS
    // Some apps have unique price formats
    // ============================================================

    /**
     * Uber-specific patterns
     */
    val UBER_PATTERNS: List<Pattern> = listOf(
        // Uber often shows "EGP 65" format
        Pattern.compile("EGP\\s*(\\d+[.,]?\\d*)", Pattern.CASE_INSENSITIVE),
        // Or just the number with nearby EGP
        Pattern.compile("(\\d{2,3})(?:\\s*EGP)?", Pattern.CASE_INSENSITIVE)
    )

    /**
     * Careem-specific patterns
     */
    val CAREEM_PATTERNS: List<Pattern> = listOf(
        // Careem uses "ج.م.‏ XX.XX" with RTL mark
        Pattern.compile("ج\\.م\\.?\\u200F?\\s*(\\d+[.,]\\d{2})"),
        // Also shows decimal prices
        Pattern.compile("(\\d{2,3}[.,]\\d{2})")
    )

    /**
     * InDriver-specific patterns
     */
    val INDRIVER_PATTERNS: List<Pattern> = listOf(
        // InDriver shows "XX ج.م" format
        Pattern.compile("(\\d+)\\s*ج\\.?م"),
        // Also shows ranges like "65-85"
        Pattern.compile("(\\d+)\\s*[-–]\\s*\\d+")
    )

    /**
     * DiDi-specific patterns
     */
    val DIDI_PATTERNS: List<Pattern> = listOf(
        // DiDi often shows decimal like "61.40"
        Pattern.compile("(\\d{2,3}[.,]\\d{1,2})"),
        // Or "EGP 65"
        Pattern.compile("EGP\\s*(\\d+[.,]?\\d*)", Pattern.CASE_INSENSITIVE)
    )

    /**
     * Bolt-specific patterns
     */
    val BOLT_PATTERNS: List<Pattern> = listOf(
        // Bolt shows "XX EGP" or "EGP XX"
        Pattern.compile("(\\d+)\\s*EGP", Pattern.CASE_INSENSITIVE),
        Pattern.compile("EGP\\s*(\\d+)", Pattern.CASE_INSENSITIVE)
    )

    // ============================================================
    // EXCLUSION PATTERNS
    // Patterns that look like prices but aren't
    // ============================================================

    /**
     * Patterns to exclude (not prices)
     */
    val EXCLUSION_PATTERNS: List<Pattern> = listOf(
        // Time patterns like "5:30" or "12:45"
        Pattern.compile("\\d{1,2}:\\d{2}"),
        // Date patterns like "12/05" or "2024"
        Pattern.compile("\\d{1,2}/\\d{1,2}"),
        Pattern.compile("20\\d{2}"),
        // Phone numbers (Egyptian format)
        Pattern.compile("01[0-9]{9}"),
        // Rating patterns like "4.5" (too small to be a price)
        Pattern.compile("^[1-5][.,][0-9]$"),
        // Percentage patterns
        Pattern.compile("\\d+%"),
        // Distance patterns like "2.5 km"
        Pattern.compile("\\d+[.,]?\\d*\\s*(?:km|كم|meter|متر)", Pattern.CASE_INSENSITIVE)
    )

    // ============================================================
    // PRICE EXTRACTION METHODS
    // ============================================================

    /**
     * Extract price from text using all patterns
     * Returns null if no valid price found
     */
    fun extractPrice(text: String): Double? {
        val cleanText = cleanText(text)

        // Skip if text matches exclusion patterns
        if (isExcluded(cleanText)) {
            return null
        }

        // Try each pattern
        for (pattern in PATTERNS) {
            val matcher = pattern.matcher(cleanText)
            if (matcher.find()) {
                val priceStr = matcher.group(1) ?: continue
                val price = parsePrice(priceStr)
                if (isValidPrice(price)) {
                    Log.d(TAG, "Extracted price $price from '$text' using pattern: ${pattern.pattern()}")
                    return price
                }
            }
        }

        return null
    }

    /**
     * Extract price using app-specific patterns
     */
    fun extractPriceForApp(text: String, packageName: String): Double? {
        val patterns = when (packageName) {
            AppConstants.Packages.UBER -> UBER_PATTERNS
            AppConstants.Packages.CAREEM -> CAREEM_PATTERNS
            AppConstants.Packages.INDRIVER -> INDRIVER_PATTERNS
            AppConstants.Packages.DIDI -> DIDI_PATTERNS
            AppConstants.Packages.BOLT -> BOLT_PATTERNS
            else -> PATTERNS
        }

        val cleanText = cleanText(text)

        if (isExcluded(cleanText)) {
            return null
        }

        for (pattern in patterns) {
            val matcher = pattern.matcher(cleanText)
            if (matcher.find()) {
                val priceStr = matcher.group(1) ?: continue
                val price = parsePrice(priceStr)
                if (isValidPrice(price)) {
                    return price
                }
            }
        }

        // Fallback to general patterns
        return extractPrice(text)
    }

    /**
     * Extract all prices from text (for screens showing multiple options)
     */
    fun extractAllPrices(text: String): List<Double> {
        val prices = mutableListOf<Double>()
        val cleanText = cleanText(text)

        if (isExcluded(cleanText)) {
            return prices
        }

        for (pattern in PATTERNS) {
            val matcher = pattern.matcher(cleanText)
            while (matcher.find()) {
                val priceStr = matcher.group(1) ?: continue
                val price = parsePrice(priceStr)
                if (isValidPrice(price) && price !in prices) {
                    prices.add(price)
                }
            }
        }

        return prices.sorted()
    }

    // ============================================================
    // HELPER METHODS
    // ============================================================

    /**
     * Clean text for price extraction
     */
    private fun cleanText(text: String): String {
        return text
            // Remove RTL/LTR marks
            .replace("\u200F", "")
            .replace("\u200E", "")
            // Remove zero-width characters
            .replace("\u200B", "")
            .replace("\u200C", "")
            .replace("\u200D", "")
            // Normalize whitespace
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Parse price string to Double
     */
    private fun parsePrice(priceStr: String): Double {
        return try {
            priceStr
                .replace(",", ".")  // Normalize decimal separator
                .replace(Regex("[^0-9.]"), "")  // Remove non-numeric
                .toDoubleOrNull() ?: 0.0
        } catch (e: Exception) {
            0.0
        }
    }

    /**
     * Check if text matches any exclusion pattern
     */
    private fun isExcluded(text: String): Boolean {
        return EXCLUSION_PATTERNS.any { pattern ->
            pattern.matcher(text).find()
        }
    }

    /**
     * Validate if price is within reasonable range
     */
    fun isValidPrice(price: Double): Boolean {
        return price >= TimingConfig.PriceValidation.GENERAL_MIN_PRICE &&
               price <= TimingConfig.PriceValidation.GENERAL_MAX_PRICE
    }

    /**
     * Validate if price is valid for a specific app
     */
    fun isValidPriceForApp(price: Double, packageName: String): Boolean {
        val minPrice = when (packageName) {
            AppConstants.Packages.UBER -> TimingConfig.PriceValidation.UBER_MIN_PRICE
            else -> TimingConfig.PriceValidation.GENERAL_MIN_PRICE
        }
        return price >= minPrice && price <= TimingConfig.PriceValidation.GENERAL_MAX_PRICE
    }
}
