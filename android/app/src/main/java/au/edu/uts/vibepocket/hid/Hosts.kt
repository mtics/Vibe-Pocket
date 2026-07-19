package au.edu.uts.vibepocket.hid

internal fun preferred(
    registered: Boolean,
    connectedAddress: String?,
    connectingAddress: String?,
    preferredAddress: String?,
    bondedAddresses: Set<String>,
    computerAddresses: Set<String>,
): String? = preferredAddress?.takeIf {
    registered &&
        connectedAddress == null &&
        connectingAddress == null &&
        it in bondedAddresses &&
        it in computerAddresses
}

internal fun delay(attempt: Int): Long? = when (attempt) {
    0 -> 500L
    1 -> 1_200L
    2 -> 2_500L
    else -> null
}

internal fun infer(
    registered: Boolean,
    connectedAddress: String?,
    connectingAddress: String?,
    preferredAddress: String?,
    bondedAddresses: Set<String>,
    computerAddresses: Set<String>,
): String? = preferred(
    registered = registered,
    connectedAddress = connectedAddress,
    connectingAddress = connectingAddress,
    preferredAddress = preferredAddress,
    bondedAddresses = bondedAddresses,
    computerAddresses = computerAddresses,
) ?: computerAddresses.singleOrNull()?.takeIf {
    registered && connectedAddress == null && connectingAddress == null && it in bondedAddresses
}

internal fun newlyBondedComputer(address: String?, bonded: Boolean, computer: Boolean): String? =
    address?.takeIf { bonded && computer }

internal fun eligibleComputer(majorDeviceClass: Int?, computerDeviceClass: Int): Boolean =
    majorDeviceClass == computerDeviceClass
