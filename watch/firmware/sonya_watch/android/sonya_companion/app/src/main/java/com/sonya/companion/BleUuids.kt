package com.sonya.companion

import java.util.UUID

/**
 * UUIDs as observed by nRF Connect / Windows (NimBLE byte order).
 */
object BleUuids {
    val SVC: UUID = UUID.fromString("f0debc9a-7856-3412-7856-341278563412")
    val RX: UUID = UUID.fromString("f0debc9a-7956-3412-7856-341278563412")
    val TX: UUID = UUID.fromString("f0debc9a-7a56-3412-7856-341278563412")
}

