package com.sfl.browser.data

enum class TimerCategory(val key: String) {
    CROPS("crops"),
    FRUIT("fruit"),
    TREES("trees"),
    RESOURCES("resources"),
    OIL("oil"),
    BUILDINGS("buildings"),
    CRAFTING_BOX("crafting_box"),
    GREENHOUSE("greenhouse"),
    FLOWERS("flowers"),
    CRAB_TRAPS("crab_traps"),
    LAVA_PITS("lava_pits"),
    EXPANSION("expansion"),
    COLLECTIBLES("collectibles"),
    CAVE("cave"),
    CHICKEN_FEEDING("chicken_feeding"),
    COW_FEEDING("cow_feeding"),
    SHEEP_FEEDING("sheep_feeding"),
    PIG_FEEDING("pig_feeding"),
    ANIMAL_LOVE("animal_love"),
    CROP_MACHINE("crop_machine"),
    DISPLAY_ONLY("display_only"),
    OTHER("other");

    val isFeeding: Boolean
        get() = this == CHICKEN_FEEDING || this == COW_FEEDING ||
            this == SHEEP_FEEDING || this == PIG_FEEDING

    companion object {
        fun feedingFor(species: String): TimerCategory? = when (species) {
            "Chicken" -> CHICKEN_FEEDING
            "Cow" -> COW_FEEDING
            "Sheep" -> SHEEP_FEEDING
            "Pig" -> PIG_FEEDING
            else -> null
        }

        fun fromKey(key: String): TimerCategory? =
            values().firstOrNull { it.key == key }

        fun fromTimerKey(key: String): TimerCategory =
            when (key.substringBefore(':')) {
                "crop" -> CROPS
                "fruit" -> FRUIT
                "tree" -> TREES
                "stones", "iron", "gold", "crimstones", "sunstones", "ascensionCrystals" -> RESOURCES
                "oil" -> OIL
                "building", "craft" -> BUILDINGS
                "cbox" -> CRAFTING_BOX
                "gh" -> GREENHOUSE
                "flower" -> FLOWERS
                "trap" -> CRAB_TRAPS
                "lava" -> LAVA_PITS
                "expansion" -> EXPANSION
                "collectible" -> COLLECTIBLES
                "cave" -> CAVE
                "animalLove" -> ANIMAL_LOVE
                else -> OTHER
            }
    }
}
