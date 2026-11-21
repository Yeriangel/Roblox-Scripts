package com.diagnosepro.app.obd

/** Offline VIN decoding: manufacturer (WMI), region/country, model year, check digit. */
object VinDecoder {

    data class VinInfo(
        val vin: String,
        val wmi: String,
        val manufacturer: String?,
        val countryKey: String,      // stable key, mapped to a localized name in the UI
        val modelYear: Int?,
        val checkDigitValid: Boolean?,
        val serial: String,
    )

    private val validChars = Regex("^[A-HJ-NPR-Z0-9]{17}$")

    fun isValid(vin: String) = validChars.matches(vin.uppercase())

    fun decode(vinRaw: String, currentYear: Int = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)): VinInfo? {
        val vin = vinRaw.trim().uppercase()
        if (!isValid(vin)) return null
        val wmi = vin.substring(0, 3)
        return VinInfo(
            vin = vin,
            wmi = wmi,
            manufacturer = manufacturer(wmi),
            countryKey = country(vin.substring(0, 2)),
            modelYear = modelYear(vin, currentYear),
            checkDigitValid = checkDigit(vin),
            serial = vin.substring(11),
        )
    }

    // Position 10 year code. Letters I, O, Q, U, Z and digit 0 are not used.
    private const val YEAR_CODES = "ABCDEFGHJKLMNPRSTVWXY123456789"

    /**
     * Year code repeats every 30 years. For North-American VINs, a letter in position 7
     * means 2010+ ; otherwise we pick the most recent year that is not in the future.
     */
    fun modelYear(vin: String, currentYear: Int): Int? {
        val idx = YEAR_CODES.indexOf(vin[9])
        if (idx < 0) return null
        val base1980 = 1980 + idx
        val candidates = listOf(base1980, base1980 + 30, base1980 + 60)
        val pos7Letter = vin[6].isLetter()
        val northAmerica = vin[0] in "12345"
        if (northAmerica) {
            return if (pos7Letter) base1980 + 30 else base1980
        }
        return candidates.filter { it <= currentYear + 1 }.maxOrNull()
    }

    private val translit = mapOf(
        'A' to 1, 'B' to 2, 'C' to 3, 'D' to 4, 'E' to 5, 'F' to 6, 'G' to 7, 'H' to 8,
        'J' to 1, 'K' to 2, 'L' to 3, 'M' to 4, 'N' to 5, 'P' to 7, 'R' to 9,
        'S' to 2, 'T' to 3, 'U' to 4, 'V' to 5, 'W' to 6, 'X' to 7, 'Y' to 8, 'Z' to 9,
    )
    private val weights = intArrayOf(8, 7, 6, 5, 4, 3, 2, 10, 0, 9, 8, 7, 6, 5, 4, 3, 2)

    /** ISO 3779 / NHTSA check digit (position 9). Mandatory only in North America. */
    fun checkDigit(vin: String): Boolean? {
        var sum = 0
        for (i in 0 until 17) {
            val c = vin[i]
            val v = if (c.isDigit()) c - '0' else translit[c] ?: return null
            sum += v * weights[i]
        }
        val r = sum % 11
        val expected = if (r == 10) 'X' else ('0' + r)
        return vin[8] == expected
    }

    fun country(prefix: String): String {
        val a = prefix[0]
        val b = prefix[1]
        return when (a) {
            '1', '4', '5' -> "us"
            '2' -> "ca"
            '3' -> if (b in 'A'..'W') "mx" else "na"
            '6' -> "au"
            '7' -> "nz"
            '8' -> "sa"
            '9' -> "br"
            'J' -> "jp"
            'K' -> if (b in 'L'..'R') "kr" else "asia"
            'L' -> "cn"
            'M' -> if (b in 'A'..'E') "in" else if (b in 'L'..'R') "th" else "asia"
            'N' -> if (b in 'L'..'R') "tr" else "asia"
            'P', 'R' -> "asia"
            'S' -> if (b in 'A'..'M') "uk" else if (b in 'N'..'T') "de" else "eu"
            'T' -> if (b in 'J'..'P') "cz" else if (b in 'A'..'H') "ch" else "eu"
            'U' -> if (b in '5'..'7') "ro" else "eu"
            'V' -> if (b in 'F'..'R') "fr" else if (b in 'S'..'W') "es" else "eu"
            'W' -> "de"
            'X' -> "ru"
            'Y' -> if (b in 'S'..'W') "se" else if (b in 'A'..'E') "be" else "eu"
            'Z' -> "it"
            'A' -> "za"
            else -> "unknown"
        }
    }

    /** Common WMIs. Falls back to the first 2 chars for brands with many WMIs. */
    private val wmiTable = mapOf(
        "1G1" to "Chevrolet", "1GC" to "Chevrolet", "1G6" to "Cadillac", "1GT" to "GMC", "2G1" to "Chevrolet",
        "1FA" to "Ford", "1FT" to "Ford", "1FM" to "Ford", "2FA" to "Ford", "3FA" to "Ford",
        "1C3" to "Chrysler", "1C4" to "Jeep/Chrysler", "1C6" to "Ram", "2C3" to "Chrysler", "1J4" to "Jeep",
        "1HG" to "Honda", "2HG" to "Honda", "JHM" to "Honda", "SHH" to "Honda", "JHL" to "Honda",
        "1N4" to "Nissan", "JN1" to "Nissan", "JN8" to "Nissan", "SJN" to "Nissan", "VSK" to "Nissan",
        "4T1" to "Toyota", "JT2" to "Toyota", "JTD" to "Toyota", "JTE" to "Toyota", "JTM" to "Toyota",
        "SB1" to "Toyota", "NMT" to "Toyota", "MR0" to "Toyota", "JTH" to "Lexus", "JTJ" to "Lexus",
        "KMH" to "Hyundai", "KMF" to "Hyundai", "TMA" to "Hyundai", "NLH" to "Hyundai", "MAL" to "Hyundai",
        "KNA" to "Kia", "KND" to "Kia", "U5Y" to "Kia", "U6Y" to "Kia",
        "JM1" to "Mazda", "JMZ" to "Mazda", "JS3" to "Suzuki", "JSA" to "Suzuki", "TSM" to "Suzuki", "MA3" to "Suzuki",
        "JF1" to "Subaru", "JF2" to "Subaru", "JA3" to "Mitsubishi", "JA4" to "Mitsubishi", "JMB" to "Mitsubishi",
        "WVW" to "Volkswagen", "WV1" to "Volkswagen", "WV2" to "Volkswagen", "3VW" to "Volkswagen", "1VW" to "Volkswagen",
        "WAU" to "Audi", "WA1" to "Audi", "TRU" to "Audi", "WBA" to "BMW", "WBS" to "BMW M", "WBY" to "BMW i",
        "WMW" to "MINI", "WDB" to "Mercedes-Benz", "WDD" to "Mercedes-Benz", "WDC" to "Mercedes-Benz", "W1K" to "Mercedes-Benz",
        "W1N" to "Mercedes-Benz", "WME" to "smart", "WP0" to "Porsche", "WP1" to "Porsche", "W0L" to "Opel", "W0V" to "Opel",
        "VF1" to "Renault", "VF3" to "Peugeot", "VF7" to "Citroën", "VR3" to "Peugeot", "VR7" to "Citroën",
        "UU1" to "Dacia", "VSS" to "SEAT", "VS6" to "Ford Spain", "TMB" to "Škoda",
        "ZFA" to "Fiat", "ZFF" to "Ferrari", "ZAR" to "Alfa Romeo", "ZHW" to "Lamborghini", "ZAM" to "Maserati",
        "YV1" to "Volvo", "YV4" to "Volvo", "LVS" to "Ford China", "LRW" to "Tesla (China)", "5YJ" to "Tesla", "7SA" to "Tesla",
        "SAL" to "Land Rover", "SAJ" to "Jaguar", "SCC" to "Lotus", "SCF" to "Aston Martin",
        "LSJ" to "MG (SAIC)", "LFV" to "FAW-Volkswagen", "LGX" to "BYD", "LC0" to "BYD", "LB3" to "Geely",
        "LVV" to "Chery", "LJD" to "Dongfeng", "L6T" to "Geely", "LNB" to "BAIC",
        "MMB" to "Mitsubishi (Thailand)", "MNB" to "Ford (Thailand)", "MMA" to "Mitsubishi (Thailand)",
        "NM0" to "Ford (Turkey)", "NMB" to "Mercedes-Benz (Turkey)", "VNK" to "Toyota (France)",
        "KPT" to "SsangYong", "KL1" to "Chevrolet (Korea)", "KLA" to "Daewoo/Chevrolet",
    )

    fun manufacturer(wmi: String): String? = wmiTable[wmi] ?: when (wmi.take(2)) {
        "JT" -> "Toyota"; "JN" -> "Nissan"; "JH" -> "Honda"; "KM" -> "Hyundai"; "KN" -> "Kia"
        "WV" -> "Volkswagen"; "WD" -> "Mercedes-Benz"; "WB" -> "BMW"; "VF" -> "Renault/PSA"
        "1G" -> "General Motors"; "1F" -> "Ford"; "SA" -> "Jaguar Land Rover"
        else -> null
    }
}
