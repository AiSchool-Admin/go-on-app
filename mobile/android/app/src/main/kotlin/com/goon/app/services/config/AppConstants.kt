package com.goon.app.services.config

/**
 * GO-ON App Constants
 *
 * Centralized configuration for all constant values used across the app.
 * This makes it easier to maintain and update values in one place.
 */
object AppConstants {

    // ============================================================
    // PACKAGE NAMES - Supported ride-hailing apps
    // ============================================================
    object Packages {
        const val UBER = "com.ubercab"
        const val CAREEM = "com.careem.acma"
        const val INDRIVER = "sinet.startup.inDriver"
        const val DIDI = "com.didiglobal.passenger"
        const val BOLT = "ee.mtakso.client"

        val ALL = listOf(UBER, CAREEM, INDRIVER, DIDI, BOLT)

        fun getAppName(packageName: String): String = when (packageName) {
            UBER -> "Uber"
            CAREEM -> "Careem"
            INDRIVER -> "InDriver"
            DIDI -> "DiDi"
            BOLT -> "Bolt"
            else -> "Unknown"
        }
    }

    // ============================================================
    // BROADCAST ACTIONS
    // ============================================================
    object Broadcast {
        const val ACTION_PRICE_UPDATE = "com.goon.app.PRICE_UPDATE"
        const val EXTRA_PRICE_DATA = "price_data"
    }

    // ============================================================
    // TIMEOUTS (in milliseconds)
    // ============================================================
    object Timeouts {
        // App-specific timeouts
        const val UBER_MIN_WAIT_MS = 3000L
        const val CAREEM_TIMEOUT_MS = 25000L
        const val CAREEM_LOADER_EXTENSION_MS = 10000L
        const val DIDI_TIMEOUT_MS = 15000L  // Reduced - progressive detection is faster
        const val INDRIVER_MIN_WAIT_AFTER_DONE_MS = 1000L
        const val DEFAULT_TIMEOUT_MS = 12000L

        // Debounce intervals
        const val PROCESS_DEBOUNCE_MS = 100L
        const val TOAST_DEBOUNCE_MS = 3000L

        // Cooldowns
        const val DIDI_ADDRESS_CLICK_COOLDOWN_MS = 2000L
        const val INDRIVER_CLICK_COOLDOWN_MS = 1000L
    }

    // ============================================================
    // RETRY LIMITS
    // ============================================================
    object Retries {
        const val MAX_GENERAL = 10
        const val DIDI_MAX_DESTINATION_ATTEMPTS = 10
        const val DIDI_MAX_SUGGESTION_RETRIES = 3
        const val INDRIVER_MAX_DONE_CLICKS = 3
        const val CAREEM_MAX_CAR_BUTTON_CLICKS = 5
        const val CAREEM_MAX_SEARCH_BAR_CLICKS = 5
        const val CAREEM_MAX_PICKUP_CLICKS = 5
        const val CAREEM_MAX_DESTINATION_CLICKS = 5
        const val CAREEM_MAX_POSITION_CLICKS = 3
        const val CAREEM_MAX_GESTURE_CLICKS = 3
        const val CAREEM_MAX_MAP_CONFIRM_ATTEMPTS = 5
        const val CAREEM_MAX_PICKUP_TEXT_RETRIES = 3
        const val CAREEM_MAX_DESTINATION_MISMATCH_RETRIES = 3
    }

    // ============================================================
    // PRICE VALIDATION
    // ============================================================
    object Prices {
        const val UBER_MIN_PRICE = 50.0
        const val GENERAL_MIN_PRICE = 10.0
        const val GENERAL_MAX_PRICE = 5000.0
        const val DEFAULT_CURRENCY = "EGP"
    }

    // ============================================================
    // SERVICE RATINGS (for "best_service" preference)
    // ============================================================
    object ServiceRatings {
        val RATINGS = mapOf(
            Packages.UBER to 4.5,
            Packages.CAREEM to 4.3,
            Packages.DIDI to 4.0,
            Packages.BOLT to 4.2,
            Packages.INDRIVER to 3.8
        )

        fun getRating(packageName: String): Double = RATINGS[packageName] ?: 3.0
    }

    // ============================================================
    // USER PREFERENCES
    // ============================================================
    object Preferences {
        const val SORT_LOWEST_PRICE = "lowest_price"
        const val SORT_BEST_SERVICE = "best_service"
        const val SORT_FASTEST_ARRIVAL = "fastest_arrival"
        const val DEFAULT_SORT = SORT_LOWEST_PRICE
    }
}

/**
 * UI Element Text Constants
 *
 * All text strings used to identify UI elements in different apps.
 * Organized by language (Arabic/English) and by function.
 */
object UITexts {

    // ============================================================
    // COMMON BUTTONS
    // ============================================================
    object Buttons {
        // Done/Confirm buttons
        val DONE = listOf(
            // Arabic
            "تم", "موافق", "تأكيد", "تطبيق",
            // English
            "Done", "OK", "Confirm", "Apply", "Got it"
        )

        // Search buttons
        val SEARCH = listOf(
            // Arabic
            "بحث", "ابحث", "البحث", "البحث عن عروض", "بحث عن أفضل سعر",
            // English
            "Search", "Find", "Find offers", "Find best price"
        )

        // Book/Order buttons
        val BOOK = listOf(
            // Arabic
            "احجز", "طلب", "اطلب",
            // English
            "Book", "Order", "Request"
        )

        // Accept/Reject buttons
        val ACCEPT = listOf("قبول", "Accept", "نعم", "Yes")
        val REJECT = listOf("رفض", "Reject", "لا", "No")
        val CANCEL = listOf("إلغاء", "Cancel", "الغاء")

        // Back/Close buttons
        val BACK = listOf("رجوع", "Back", "عودة", "Close", "إغلاق")
    }

    // ============================================================
    // LOCATION FIELDS
    // ============================================================
    object Location {
        // Destination field hints
        val DESTINATION_HINTS = listOf(
            // Arabic
            "إلى أين", "إلى أين؟", "الوجهة", "وجهتك", "أين تريد الذهاب",
            "ابحث عن مكان", "نقطة الوصول", "إلى",
            // English
            "Where to?", "Where to", "Destination", "Enter destination",
            "Search destination", "To", "Drop-off"
        )

        // Pickup field hints
        val PICKUP_HINTS = listOf(
            // Arabic
            "من أين", "من أين؟", "موقعك", "موقعك الحالي", "نقطة الانطلاق",
            "الموقع الحالي", "موقعي الحالي",
            // English
            "From", "Pickup", "Your location", "Current location",
            "Pickup point", "Pick-up"
        )

        // Current location options
        val CURRENT_LOCATION = listOf(
            // Arabic
            "موقعي الحالي", "الموقع الحالي", "موقعك الحالي",
            // English
            "Current location", "Your location", "My location"
        )
    }

    // ============================================================
    // CAREEM-SPECIFIC
    // ============================================================
    object Careem {
        // Main service buttons on home screen
        val CAR_BUTTON = listOf(
            "سيارة", "سياره",  // Arabic variations
            "Car", "Ride"      // English
        )

        val OTHER_SERVICES = listOf(
            "حول محليا", "طلب المال", "توصيل", "دراجة",
            "Send locally", "Request money", "Delivery", "Bike"
        )

        // Search bar identifiers
        val SEARCH_BAR_HINTS = listOf(
            "إلى أين؟", "Where to?", "ابحث", "Search",
            "أين تريد الذهاب؟", "Where do you want to go?"
        )

        // Price screen indicators
        val PRICE_SCREEN_INDICATORS = listOf(
            "أقل سعر", "Lowest price",
            "اختر سيارة", "Choose a ride",
            "السعر المقدر", "Estimated fare"
        )
    }

    // ============================================================
    // UBER-SPECIFIC
    // ============================================================
    object Uber {
        val WHERE_TO = listOf(
            "Where to?", "إلى أين؟", "Search", "بحث",
            "Enter destination", "Where to"
        )

        val VEHICLE_TYPES = listOf(
            "UberX", "Uber Comfort", "UberXL", "Uber Black",
            "أوبر إكس", "أوبر كومفورت"
        )
    }

    // ============================================================
    // DIDI-SPECIFIC
    // ============================================================
    object DiDi {
        val WHERE_TO = listOf(
            "Where to?", "Where to", "إلى أين", "إلى أين؟",
            "Destination", "Search", "输入目的地", "去哪儿"
        )

        val VEHICLE_TYPES = listOf(
            "DiDi Express", "DiDi Comfort",
            "دي دي اكسبرس", "دي دي كومفورت"
        )
    }

    // ============================================================
    // INDRIVER-SPECIFIC
    // ============================================================
    object InDriver {
        val SEARCH_OFFERS = listOf(
            "البحث عن عروض", "بحث عن عروض",
            "Search for offers", "Find offers"
        )

        val VEHICLE_TYPES = listOf(
            "رحلة", "مريحة", "درجة نارية", "بريميوم",
            "Economy", "Comfort", "Motorcycle", "Premium"
        )

        // Price screen indicators
        val PRICE_SCREEN_INDICATORS = listOf(
            "البحث عن عروض", "عروض السائقين",
            "Driver offers", "Searching for offers"
        )
    }

    // ============================================================
    // BOLT-SPECIFIC
    // ============================================================
    object Bolt {
        val WHERE_TO = listOf(
            "Where to?", "إلى أين؟", "Search", "Enter destination", "Where to"
        )

        val VEHICLE_TYPES = listOf(
            "Bolt", "Bolt Comfort", "Bolt XL",
            "بولت", "بولت كومفورت"
        )
    }

    // ============================================================
    // ELEMENTS TO SKIP/IGNORE
    // ============================================================
    object Skip {
        // Text elements that should NOT be clicked
        val DO_NOT_CLICK = listOf(
            "Back", "Where to", "Add stop", "Pickup point", "Search",
            "رجوع", "إضافة محطة", "نقطة الانطلاق"
        )

        // Text that indicates we're NOT on a price screen
        val NOT_PRICE_SCREEN = listOf(
            "سيارة", "Where", "إلى أين",
            "Enter destination", "Search for"
        )
    }

    // ============================================================
    // HELPER FUNCTIONS
    // ============================================================

    /**
     * Check if text contains any of the given patterns (case-insensitive)
     */
    fun containsAny(text: String, patterns: List<String>): Boolean {
        val normalizedText = normalizeArabic(text.lowercase())
        return patterns.any { pattern ->
            normalizedText.contains(normalizeArabic(pattern.lowercase()))
        }
    }

    /**
     * Check if text equals any of the given patterns (case-insensitive)
     */
    fun equalsAny(text: String, patterns: List<String>): Boolean {
        val normalizedText = normalizeArabic(text.trim().lowercase())
        return patterns.any { pattern ->
            normalizedText == normalizeArabic(pattern.trim().lowercase())
        }
    }

    /**
     * Normalize Arabic text (remove diacritics, normalize letters)
     */
    fun normalizeArabic(text: String): String {
        return text
            // Remove Arabic diacritics
            .replace(Regex("[\\u064B-\\u0652]"), "")
            // Normalize Alef variations
            .replace('أ', 'ا')
            .replace('إ', 'ا')
            .replace('آ', 'ا')
            // Normalize Yaa variations
            .replace('ى', 'ي')
            // Normalize Haa variations
            .replace('ة', 'ه')
            // Remove RTL/LTR marks
            .replace("\u200F", "")
            .replace("\u200E", "")
            // Remove zero-width characters
            .replace("\u200B", "")
            .replace("\u200C", "")
            .replace("\u200D", "")
            .trim()
    }
}
