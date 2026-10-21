package com.p2pchat.messenger.data

import androidx.room.TypeConverter
import com.p2pchat.messenger.mesh.DeliveryState

/**
 * Room type converters.
 *
 * ByteArray columns are stored natively as BLOBs by Room and need no
 * converter; this class covers enum <-> String so entities can stay
 * stringly-typed where the shared contract stores state as a name.
 */
class Converters {
    @TypeConverter
    fun deliveryStateToString(state: DeliveryState?): String? = state?.name

    @TypeConverter
    fun stringToDeliveryState(value: String?): DeliveryState? =
        value?.let { runCatching { DeliveryState.valueOf(it) }.getOrNull() }
}
