package com.sortopia.theme

import com.sortopia.model.LevelId

/** One item's look: [art] names its drawing in the art atlas, [color] (hex) tints its body. */
data class ItemDef(
    val art: String,
    val color: String,
)

/** Idle background motion for a theme (SPEC §13). */
enum class Particle {
    LEAF,
    SPARKLE,
    STAR,
    ENVELOPE,
    FLOUR,
    BUBBLE,
    SNOWFLAKE,
    GLINT,
    DOT,
    ;

    /** Its sprite in the art atlas. */
    val art: String get() = "p." + name.lowercase()
}

/**
 * A world's look (SPEC §6, §13). [id] names the art for its tube dressing and background,
 * [items] are its item types in order, [track] its music loop.
 */
data class ThemeDef(
    val id: String,
    val items: List<ItemDef>,
    val particle: Particle,
    val track: String,
)

/** The theme a level is drawn with, and the items standing for its item types. */
data class Look(
    val theme: ThemeDef,
    val items: List<ItemDef>,
) {
    /** The look of board item type [type]; wraps rather than failing on unexpected input. */
    fun item(type: Int): ItemDef = items[type.mod(items.size)]
}

/**
 * Every world's items, tube dressing, particles and music (SPEC §6, §13). Presentation only:
 * nothing here affects level generation, so it can change without a content version bump.
 */
object ThemeCatalog {
    const val MENU_TRACK = "menu"
    private const val ENDLESS_MIX_LEVELS = 10

    /** The standard palette, in the order the Endless mixes assign it. */
    private val standardPalette =
        listOf(
            "EF5350",
            "FFA726",
            "FFD54F",
            "66BB6A",
            "26C6DA",
            "42A5F5",
            "5C6BC0",
            "AB47BC",
            "F06292",
            "8D6E63",
            "90A4AE",
            "2E4057",
            "C0CA33",
            "00897B",
        )

    /** High-distinction palette (Okabe-Ito plus Tol) for the colorblind setting (SPEC §14). */
    private val colorblindPalette =
        listOf(
            "E69F00",
            "56B4E9",
            "009E73",
            "F0E442",
            "0072B2",
            "D55E00",
            "CC79A7",
            "222222",
            "999999",
            "AA4499",
            "332288",
            "44AA99",
            "999933",
            "882255",
        )

    private fun set(
        theme: String,
        vararg items: Pair<String, String>,
    ): List<ItemDef> = items.map { (name, color) -> ItemDef("$theme.$name", color) }

    val worlds: List<ThemeDef> =
        listOf(
            ThemeDef(
                "fruit",
                set(
                    "fruit",
                    "apple" to "E53935",
                    "banana" to "FDD835",
                    "grapes" to "8E44AD",
                    "orange" to "FB8C00",
                    "pear" to "9CCC65",
                    "blueberries" to "3F6FD8",
                ),
                Particle.LEAF,
                "world1",
            ),
            ThemeDef(
                "candy",
                set(
                    "candy",
                    "lollipop" to "F06292",
                    "wrapped" to "FF9800",
                    "gumdrop" to "4CAF50",
                    "cane" to "E53935",
                    "chocolate" to "795548",
                    "jellybean" to "9C27B0",
                    "rockcandy" to "29B6F6",
                ),
                Particle.SPARKLE,
                "world2",
            ),
            ThemeDef(
                "toys",
                set(
                    "toys",
                    "teddy" to "9C6B4E",
                    "duck" to "FFD54F",
                    "block" to "E53935",
                    "ball" to "42A5F5",
                    "car" to "66BB6A",
                    "top" to "AB47BC",
                    "robot" to "90A4AE",
                    "kite" to "FF8A3D",
                ),
                Particle.STAR,
                "world3",
            ),
            ThemeDef(
                "post",
                set(
                    "post",
                    "envelope" to "F3E5C0",
                    "parcel" to "B07A4A",
                    "stamp" to "E53935",
                    "postcard" to "64B5F6",
                    "loveletter" to "F48FB1",
                    "poster" to "81C784",
                    "mailer" to "FFD54F",
                    "waxseal" to "8E5BC6",
                ),
                Particle.ENVELOPE,
                "world4",
            ),
            ThemeDef(
                "bakery",
                set(
                    "bakery",
                    "croissant" to "E3A23B",
                    "donut" to "F48FB1",
                    "cupcake" to "AB7BD6",
                    "pretzel" to "8D5A3B",
                    "pie" to "D32F2F",
                    "muffin" to "5C8DE0",
                    "macaron" to "9CCC65",
                    "roll" to "F1DDB5",
                    "tart" to "FFE24D",
                ),
                Particle.FLOUR,
                "world5",
            ),
            ThemeDef(
                "aquarium",
                set(
                    "aquarium",
                    "clownfish" to "FF8A3D",
                    "puffer" to "FFD54F",
                    "octopus" to "9C6ADE",
                    "crab" to "E53935",
                    "jellyfish" to "F48FB1",
                    "turtle" to "66BB6A",
                    "tang" to "3F7FE0",
                    "seahorse" to "26A69A",
                    "shell" to "F3E0C8",
                    "angler" to "34495E",
                ),
                Particle.BUBBLE,
                "world6",
            ),
            ThemeDef(
                "icecream",
                set(
                    "icecream",
                    "cone" to "F48FB1",
                    "popsicle" to "8D5A3B",
                    "softserve" to "9EE6C8",
                    "sundae" to "5C8DE0",
                    "sandwich" to "F3E5C0",
                    "sorbet" to "FFE24D",
                    "bombpop" to "9C6ADE",
                    "creamsicle" to "FF9F43",
                    "mochi" to "E53935",
                    "bowl" to "26A69A",
                    "affogato" to "4E4A57",
                ),
                Particle.SNOWFLAKE,
                "world7",
            ),
            ThemeDef(
                "gems",
                set(
                    "gems",
                    "ruby" to "E0245A",
                    "emerald" to "2EAD5B",
                    "sapphire" to "2F6FE0",
                    "amethyst" to "9B59D0",
                    "topaz" to "FF8A3D",
                    "citrine" to "FFD23F",
                    "diamond" to "D8F1FF",
                    "pearl" to "F5EBDD",
                    "quartz" to "F7A8C8",
                    "turquoise" to "2EC4C4",
                    "onyx" to "3A3A48",
                    "nugget" to "C9962E",
                    "garnet" to "8E1B3A",
                ),
                Particle.GLINT,
                "world8",
            ),
        )

    /**
     * Hand-picked 14-item sets drawn from every world for Endless (SPEC §9). Each takes the
     * standard 14-colour palette, so colours stay distinct even where two worlds' items clash.
     */
    val endlessMixes: List<List<ItemDef>> =
        listOf(
            mix(
                "fruit.apple",
                "aquarium.clownfish",
                "toys.duck",
                "aquarium.turtle",
                "candy.rockcandy",
                "toys.ball",
                "gems.sapphire",
                "fruit.grapes",
                "bakery.donut",
                "toys.teddy",
                "toys.robot",
                "aquarium.angler",
                "bakery.macaron",
                "aquarium.seahorse",
            ),
            mix(
                "post.stamp",
                "icecream.creamsicle",
                "fruit.banana",
                "gems.emerald",
                "gems.turquoise",
                "post.postcard",
                "bakery.muffin",
                "aquarium.octopus",
                "post.loveletter",
                "bakery.pretzel",
                "aquarium.shell",
                "gems.onyx",
                "candy.gumdrop",
                "icecream.bowl",
            ),
            mix(
                "aquarium.crab",
                "bakery.croissant",
                "gems.citrine",
                "toys.car",
                "icecream.softserve",
                "candy.lollipop",
                "icecream.sundae",
                "gems.amethyst",
                "fruit.orange",
                "post.parcel",
                "post.envelope",
                "icecream.affogato",
                "toys.kite",
                "toys.top",
            ),
        )

    val neutral: ThemeDef = ThemeDef("neutral", endlessMixes.first(), Particle.DOT, MENU_TRACK)

    private fun mix(vararg arts: String): List<ItemDef> = arts.mapIndexed { i, art -> ItemDef(art, standardPalette[i]) }

    fun world(world: Int): ThemeDef = worlds[(world - 1).coerceIn(0, worlds.lastIndex)]

    /** Body colour for item type [type] when the colorblind palette is on; silhouettes stay. */
    fun colorblindColor(type: Int): String = colorblindPalette[type.mod(colorblindPalette.size)]

    fun lookFor(id: LevelId): Look =
        when (id) {
            is LevelId.Campaign -> world(id.world).let { Look(it, it.items) }
            is LevelId.Daily -> world(id.tier).let { Look(it, it.items) }
            is LevelId.Endless -> Look(neutral, endlessMixes[(id.number - 1) / ENDLESS_MIX_LEVELS % endlessMixes.size])
        }
}
