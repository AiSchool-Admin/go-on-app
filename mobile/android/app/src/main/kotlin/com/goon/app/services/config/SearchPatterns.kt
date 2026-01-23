package com.goon.app.services.config

/**
 * Centralized search patterns for UI element detection
 *
 * These patterns are used to find and interact with UI elements across
 * different ride-hailing apps (DiDi, InDriver, Uber, Careem, Bolt).
 *
 * Supports: English, Arabic, Chinese
 */
object SearchPatterns {

    // ============================================================
    // DESTINATION/WHERE TO PATTERNS
    // ============================================================

    /**
     * Patterns for "Where to?" / destination field
     */
    val WHERE_TO = listOf(
        // English
        "Where to?", "Where to", "Destination", "Search", "Enter destination",
        "Tap to enter your destination",
        // Arabic
        "إلى أين؟", "إلى أين", "الى اين", "الى اين؟", "الوجهة",
        "وجهتك", "أين تريد الذهاب", "بحث",
        // Chinese (DiDi)
        "去哪儿", "输入目的地", "你要去哪", "目的地"
    )

    /**
     * App-specific WHERE_TO patterns
     */
    fun getWhereToPatterns(packageName: String): List<String> = when {
        packageName.contains("uber") -> listOf(
            "Where to?", "إلى أين؟", "Search", "بحث", "Enter destination", "Where to"
        )
        packageName.contains("didi") -> listOf(
            "Tap to enter your destination",
            "إلى أين؟", "إلى أين", "الى اين", "الى اين؟",
            "الوجهة", "وين رايح", "أين تريد الذهاب",
            "Where to?Button", "Where to?", "Where to",
            "去哪儿", "输入目的地", "你要去哪", "目的地",
            "Destination", "Search", "بحث"
        )
        packageName.contains("bolt") -> listOf(
            "Where to?", "إلى أين؟", "Search", "Enter destination", "Where to"
        )
        packageName.contains("careem") -> listOf(
            "Where to?", "Where to", "Search", "Enter destination",
            "إلى أين؟", "إلى أين", "وجهتك", "أين تريد الذهاب", "بحث"
        )
        packageName.contains("inDriver") -> listOf(
            "To", "إلى", "Where to?", "إلى أين؟", "إلى أين",
            "Destination", "الوجهة"
        )
        else -> WHERE_TO
    }

    // ============================================================
    // PICKUP POINT PATTERNS
    // ============================================================

    /**
     * Patterns for pickup point field
     */
    val PICKUP_POINT = listOf(
        // English
        "Pickup point", "Your location", "Current location", "From", "From where",
        "Pick up", "Pickup",
        // Arabic
        "نقطة الاقلال", "نقطة الإقلال", "موقعك الحالي", "من أين", "من",
        "نقطة الانطلاق",
        // Chinese
        "上车点", "起点", "当前位置"
    )

    // ============================================================
    // MAP/CHOOSE ON MAP PATTERNS
    // ============================================================

    /**
     * Patterns for "Choose on map" option
     */
    val CHOOSE_ON_MAP = listOf(
        // English
        "Choose on map", "Pin your location on the map", "Pin on map",
        "Select on map", "Set location on map",
        // Arabic
        "اختر على الخريطة", "حدد على الخريطة", "على الخريطة", "الخريطة"
    )

    /**
     * Patterns for "No results" message
     */
    val NO_RESULTS = listOf(
        // English
        "No results", "No results found",
        // Arabic
        "لا توجد نتائج", "لا توجد", "نتائج"
    )

    // ============================================================
    // CONFIRMATION BUTTONS
    // ============================================================

    /**
     * Patterns for Done/Confirm buttons
     */
    val DONE_CONFIRM = listOf(
        // English
        "Done", "Confirm", "OK", "Apply", "Set",
        // Arabic
        "تم", "تأكيد", "موافق", "تطبيق", "تعيين"
    )

    /**
     * Patterns for Set Destination/Pickup buttons
     */
    val SET_LOCATION = listOf(
        // English
        "Set Destination", "Set Pickup", "Set Location", "Confirm location",
        // Arabic
        "تعيين الوجهة", "تعيين نقطة", "تعيين موقع", "تأكيد الموقع"
    )

    // ============================================================
    // PRICE SCREEN INDICATORS
    // ============================================================

    /**
     * Patterns indicating price/fare screen
     */
    val PRICE_SCREEN_INDICATORS = listOf(
        // Arabic
        "البحث عن عروض", "الأجر المقترح", "اطلب رحلة", "السعر المقترح",
        "عرض السعر", "اختر سيارة",
        // English
        "Search for offers", "Suggested fare", "Request ride", "Book now",
        "Choose your driver"
    )

    /**
     * Vehicle type indicators (for detecting price screen)
     */
    val VEHICLE_TYPES = listOf(
        // Arabic
        "رحلة", "مريحة", "درجة نارية", "بريميوم", "اقتصادي",
        // English
        "Economy", "Comfort", "Premium", "Express", "Motorcycle"
    )

    // ============================================================
    // LOCATION/ADDRESS INDICATORS
    // ============================================================

    /**
     * Patterns indicating location suggestions
     */
    val LOCATION_INDICATORS = listOf(
        // Arabic
        "محافظة", "قسم", "مصر", "القاهرة", "العبور", "الحي",
        "شارع", "ميدان", "حي", "مدينة", "منطقة", "مركز",
        // English
        "Egypt", "Cairo", "Governorate", "Street", "District",
        "Obour", "Nasr", "Heliopolis", "Maadi", "Zamalek", "Mohandessin"
    )

    /**
     * Distance indicators (for pickup suggestions)
     */
    val DISTANCE_INDICATORS = listOf(
        "متر", "كم", "km", "m ", " m"
    )

    // ============================================================
    // FILTER PATTERNS (elements to skip)
    // ============================================================

    /**
     * Patterns for elements that should be filtered out from suggestions
     */
    val FILTER_OUT = listOf(
        "اختر على الخريطة", "Choose on map", "على الخريطة",
        "لا توجد نتائج", "No results",
        "خرائط Google", "Google Maps", "google",
        "إلى أين", "Where to", "بحث", "Search"
    )

    /**
     * Button texts to filter from suggestions
     */
    val BUTTON_FILTER = listOf(
        "تم", "Done", "بحث", "Search", "مسح", "إغلاق", "Clear", "Close"
    )

    // ============================================================
    // HELPER FUNCTIONS
    // ============================================================

    /**
     * Check if text contains any of the patterns
     */
    fun containsAny(text: String, patterns: List<String>, ignoreCase: Boolean = true): Boolean {
        return patterns.any { pattern ->
            if (ignoreCase) text.contains(pattern, ignoreCase = true)
            else text.contains(pattern)
        }
    }

    /**
     * Check if text matches any pattern exactly
     */
    fun matchesAny(text: String, patterns: List<String>, ignoreCase: Boolean = true): Boolean {
        return patterns.any { pattern ->
            if (ignoreCase) text.equals(pattern, ignoreCase = true)
            else text == pattern
        }
    }
}
