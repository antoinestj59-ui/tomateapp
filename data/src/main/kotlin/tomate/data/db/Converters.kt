package tomate.data.db

import androidx.room.TypeConverter
import tomate.core.model.CommunityRole
import tomate.core.model.MembershipStatus
import tomate.core.model.MessageDeliveryState
import tomate.core.model.TransportKind
import tomate.core.model.Visibility

/** Enums are stored as stable strings (never ordinals) so migrations cannot silently remap them. */
class Converters {
    @TypeConverter
    fun visibilityToString(value: Visibility?): String? = value?.name

    @TypeConverter
    fun stringToVisibility(value: String?): Visibility? =
        value?.let { runCatching { Visibility.valueOf(it) }.getOrNull() }

    @TypeConverter
    fun deliveryToString(value: MessageDeliveryState?): String? = value?.name

    @TypeConverter
    fun stringToDelivery(value: String?): MessageDeliveryState? =
        value?.let { runCatching { MessageDeliveryState.valueOf(it) }.getOrNull() }

    @TypeConverter
    fun transportToString(value: TransportKind?): String? = value?.name

    @TypeConverter
    fun stringToTransport(value: String?): TransportKind? =
        value?.let { runCatching { TransportKind.valueOf(it) }.getOrNull() }

    @TypeConverter
    fun roleToString(value: CommunityRole?): String? = value?.name

    @TypeConverter
    fun stringToRole(value: String?): CommunityRole? =
        value?.let { runCatching { CommunityRole.valueOf(it) }.getOrNull() }

    @TypeConverter
    fun membershipToString(value: MembershipStatus?): String? = value?.name

    @TypeConverter
    fun stringToMembership(value: String?): MembershipStatus? =
        value?.let { runCatching { MembershipStatus.valueOf(it) }.getOrNull() }
}
