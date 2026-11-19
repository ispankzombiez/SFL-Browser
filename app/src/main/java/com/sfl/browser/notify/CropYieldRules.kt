package com.sfl.browser.notify

internal enum class CropTier {
    BASIC,
    MEDIUM,
    ADVANCED,
}

internal object CropYieldRules {
    val basic = setOf("Sunflower", "Potato", "Rhubarb", "Pumpkin", "Zucchini")
    val medium = setOf(
        "Carrot", "Yam", "Cabbage", "Broccoli", "Soybean", "Beetroot",
        "Pepper", "Cauliflower", "Parsnip", "Saltwort",
    )
    val advanced = setOf(
        "Eggplant", "Corn", "Onion", "Radish", "Wheat", "Turnip", "Kale",
        "Artichoke", "Barley", "Crystal Leaf",
    )
    val fieldCrops = basic + medium + advanced
    val overnight = setOf("Radish", "Wheat", "Turnip", "Kale", "Artichoke", "Barley", "Crystal Leaf")

    private val spring = setOf(
        "Sunflower", "Rhubarb", "Carrot", "Cabbage", "Soybean", "Corn", "Wheat", "Kale", "Barley",
    )
    private val winter = setOf(
        "Potato", "Cabbage", "Beetroot", "Cauliflower", "Parsnip", "Onion", "Turnip", "Wheat", "Kale",
    )

    fun tier(crop: String): CropTier? = when (crop) {
        in basic -> CropTier.BASIC
        in medium -> CropTier.MEDIUM
        in advanced -> CropTier.ADVANCED
        else -> null
    }

    fun isSeasonal(crop: String, season: String): Boolean = when (season.lowercase()) {
        "spring" -> crop in spring
        "winter" -> crop in winter
        else -> false
    }
}
