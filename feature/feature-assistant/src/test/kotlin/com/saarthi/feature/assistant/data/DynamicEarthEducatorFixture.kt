package com.saarthi.feature.assistant.data

/**
 * Phase 0 — synthetic Dynamic Earth Educator Guide corpus for JVM replay.
 * Section markers mirror the NASA golden chat; not a full PDF extract.
 */
internal object DynamicEarthEducatorFixture {
    const val URI = "content://dynamic-earth-guide"
    const val NAME = "DynamicEarthEducatorGuide.pdf"

  private val body = """
        --- Page 1 ---
        Dynamic Earth Educator Guide
        This guide supplements a viewing of the planetarium show Dynamic Earth.
        Classroom resources address Earth's climate systems and climate change.
        Content overview, reference guides, and four classroom activities are included.

        --- Page 2 ---
        Section I — Purpose of this guide
        The main purpose is to supplement the planetarium show with classroom resources
        that reinforce topics of Earth's climate systems and climate change.

        --- Page 3 ---
        Section II — Components of Earth's climate system
        The main components are the atmosphere, hydrosphere (oceans), biosphere, and
        interactions among the Sun, ocean, atmosphere, clouds, ice, land, and life.

        --- Page 4 ---
        1. The Sun is the primary source of energy for Earth's climate system.
        Sunlight reaching Earth heats the land, ocean, and atmosphere.
        Wind is the driving force of weather; wind forms when temperature differences
        create pressure variations that drive global wind circulations.

        --- Page 5 ---
        Section IV — How is Weather different from Climate?
        Weather is what we get; climate is what we expect.
        Climate change is a significant persistent change in the statistical distribution
        of weather conditions over decades to millions of years.

        --- Page 6 ---
        The ocean exerts a major control on climate through its capacity to store and
        transport heat. Ocean currents redistribute heat around the globe and influence
        atmospheric circulation and regional climate patterns.

        --- Page 7 ---
        The atmosphere regulates Earth's surface temperature through greenhouse gases
        such as water vapor, carbon dioxide, and methane that trap outgoing heat.
        Greenhouse gases effectively trap solar radiation and affect surface temperature.

        --- Page 8 ---
        Human activities are impacting the climate system. Natural processes alone do not
        explain the rapid climate change observed in recent decades. Human impacts play
        an increasing role in recent climate change.

        --- Page 9 ---
        Melting of ice sheets and glaciers, combined with thermal expansion of seawater
        as oceans warm, is leading to sea level rise. Storm surges from hurricanes pose
        greater risk when sea level is higher.

        --- Page 10 ---
        Major factors that can cause Earth's climate to change include changes in the
        Sun's energy output, volcanic eruptions, plate tectonics affecting the carbon
        cycle, and life including microbes, plants, animals, and humans.

        --- Page 11 ---
        Venus lacks a magnetic field to shield it from solar radiation that strips water
        from the atmosphere, contributing to Venus' inability to sustain liquid water
        and life as we know it.

        --- Page 12 ---
        Climate models are mathematical models used to describe, simulate, and analyze
        interactions between the atmosphere and underlying surfaces such as ocean, land,
        and ice. Results inspire more observations and experiments.

        --- Page 13 ---
        Natural influences on climate include slow carbon removal by oceans and forests.
        Human influences have affected land, oceans, and atmosphere, altering global
        climate patterns through greenhouse gas emissions from fossil fuels.

        --- Page 14 ---
        Feedback mechanisms in the climate system include the ice-albedo feedback:
        melting ice reduces reflectivity and leads to further warming. Students can
        discuss how global wind patterns might shift under global climate change.

        --- Page 15 ---
        Environmental observations form the foundation for understanding climate.
        Instruments on weather stations, buoys, satellites, and other platforms collect
        climate data. Tree rings, ice cores, and sedimentary layers document past climates.

        --- Page 16 ---
        Classroom activities recommended in this guide include using scientific
        instruments to make environmental observations and using visualization
        software to describe patterns in graphed climate data sets.

        --- Page 17 ---
        The carbon cycle moves carbon between atmosphere, ocean, land, and rock.
        Plate tectonics powers internal Earth energy that affects the carbon cycle
        through weathering and volcanic gas release over geologic time.
    """.trimIndent()

    val doc = GoldenDoc(uri = URI, name = NAME, text = body)

    val singleDoc = listOf(doc)
}
