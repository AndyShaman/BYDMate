package com.bydmate.app.ui.settings

/** DM-i: « · 2.50 /л» after a tariff period summary on a hybrid with a litre price, else nothing. */
internal fun fuelPriceSuffix(price: Double?, hybrid: Boolean, litreUnit: String): String =
    if (hybrid && price != null) " · %.2f /%s".format(price, litreUnit) else ""
