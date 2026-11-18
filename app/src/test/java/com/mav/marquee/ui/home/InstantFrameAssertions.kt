package com.mav.marquee.ui.home

/**
 * Compose 1.11's MainTestClockImpl.hasAwaiters is a construction-time boolean,
 * not a live getter. Inspect the SAME environment frame clock mainClock advances.
 * Recomposer's perpetual frame registration is not an animation; every other
 * frame callback fails this gate. Dependency changes fail closed.
 */
object InstantFrameAssertions {
    fun hasRunningAnimations(rule: Any): Boolean {
        val environment = rule.javaClass.getDeclaredField("environment").apply { isAccessible = true }.get(rule)
        var type: Class<*>? = environment.javaClass
        while (type != null) {
            val field = type.declaredFields.firstOrNull { it.name == "frameClock" }
            if (field != null) {
                val clock = field.apply { isAccessible = true }.get(environment)
                // LaunchedEffect/rememberCoroutineScope animations await the Recomposer's
                // BroadcastFrameClock. It is multiplexed into the test clock as one callback.
                val recomposer = type.getDeclaredField("recomposer").apply { isAccessible = true }.get(environment)
                val broadcast = recomposer.javaClass.getDeclaredField("broadcastFrameClock").apply { isAccessible = true }.get(recomposer)
                if (broadcast.javaClass.getMethod("getHasAwaiters").invoke(broadcast) as Boolean) return true
                val awaiters = clock.javaClass.getDeclaredField("awaiters").apply { isAccessible = true }.get(clock) as List<*>
                return awaiters.any { awaiter ->
                    requireNotNull(awaiter)
                    val callback = awaiter.javaClass.getDeclaredField("\$onFrame").apply { isAccessible = true }.get(awaiter)
                    !callback.javaClass.name.startsWith("androidx.compose.runtime.Recomposer\$runRecomposeAndApplyChanges")
                }
            }
            type = type.superclass
        }
        error("Compose environment frameClock not found; instant gate cannot skip clock assertion")
    }
}
