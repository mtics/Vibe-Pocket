package au.edu.uts.vibepocket.session

internal data class Retry(
    val timeoutMillis: Long = 3_000L,
    val initialDelayMillis: Long = 250L,
    val maxDelayMillis: Long = 4_000L,
)
