package com.tomasmcguinness.mcc.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * How controller responses are read. Every field other than `nodeId` may be missing or null and
 * falls back to its default, rather than failing the whole response.
 */
val MccJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
}

/**
 * A Matter node commissioned onto a controller's fabric.
 *
 * Mirrors the JSON built by the controller's `nodes_get_handler` (see the ESP32 Heating Monitor
 * firmware's `app_main.cpp` for the reference implementation this API is based on).
 */
@Serializable
data class Node(
    // cJSON prints anything that doesn't fit an int in scientific notation, which a plain
    // integer decode chokes on -- Thread extended addresses always land in that range.
    @Serializable(with = LenientULongSerializer::class) val nodeId: ULong = 0u,
    val isIcd: Boolean = false,
    val vendorName: String? = null,
    val productName: String? = null,
    val nodeName: String? = null,
    val powerSource: Int = 0,
    val batteryPercent: Int? = null,
    val batteryVoltage: Int? = null,
    @Serializable(with = LenientULongSerializer::class) val extAddress: ULong = 0u,
    val hasSubscription: Boolean = false,
    val endpoints: List<Endpoint> = emptyList(),
) {
    /** The node's battery, if it is on one and has reported since the controller last booted. */
    val battery: BatteryReading? get() = BatteryReading.of(batteryPercent, batteryVoltage)

    /**
     * What to show in a list. The controller leaves `nodeName` null until the user sets one,
     * and `productName` null until the node has answered a Basic Information read.
     */
    val displayName: String
        get() = when {
            !nodeName.isNullOrEmpty() -> nodeName
            !productName.isNullOrEmpty() -> productName
            else -> "Node $nodeIdHex"
        }

    val nodeIdHex: String get() = "0x" + nodeId.toString(16).uppercase()

    val subtitle: String?
        get() = listOfNotNull(vendorName, productName)
            .filter { it.isNotEmpty() }
            .takeIf { it.isNotEmpty() }
            ?.joinToString(" · ")
}

@Serializable
data class Endpoint(
    val endpointId: Int = 0,
    val endpointName: String? = null,
    val powerSource: Int = 0,
    val batteryPercent: Int? = null,
    val batteryVoltage: Int? = null,
    val measuredValue: Double? = null,
    /** Long rather than Int: manufacturer-specific ids like 0xFFF10001 don't fit. */
    val deviceTypes: List<Long> = emptyList(),
) {
    val battery: BatteryReading? get() = BatteryReading.of(batteryPercent, batteryVoltage)

    /** The controller reports temperatures and flow rates in hundredths, the way the Matter clusters carry them. */
    val scaledValue: Double? get() = measuredValue?.let { it / 100 }

    val deviceTypeNames: List<String> get() = deviceTypes.map(MatterDeviceType::name)
}

/**
 * A battery powered node's PowerSource state.
 *
 * `BatPercentRemaining` and `BatVoltage` are both optional in Matter, so a device may report
 * either, both, or neither.
 */
data class BatteryReading(
    /** Whole percent, 0-100. */
    val percent: Int?,
    /** Millivolts, as the cluster reports it. */
    val millivolts: Int?,
) {
    enum class Level { UNKNOWN, LOW, WARNING, GOOD }

    val level: Level
        get() = when {
            percent == null -> Level.UNKNOWN
            percent <= 15 -> Level.LOW
            percent <= 30 -> Level.WARNING
            else -> Level.GOOD
        }

    override fun toString(): String = listOfNotNull(
        percent?.let { "$it%" },
        millivolts?.let { String.format(java.util.Locale.ROOT, "%.2f V", it / 1000.0) },
    ).joinToString(" · ")

    companion object {
        /** Null when there is nothing to show. */
        fun of(percent: Int?, voltage: Int?): BatteryReading? {
            if (percent == null && voltage == null) return null

            return BatteryReading(percent?.coerceIn(0, 100), voltage)
        }
    }
}

/** The subset of Matter device type ids this system runs into. */
object MatterDeviceType {

    fun name(id: Long): String = when (id) {
        14L -> "Aggregator"
        15L -> "Generic Switch"
        17L -> "Power Source"
        19L -> "Bridged Device"
        117L -> "Dishwasher"
        266L -> "On/Off Plug-in Unit"
        269L -> "Extended Color Light"
        769L -> "Thermostat"
        770L -> "Temperature Sensor"
        773L -> "Pressure Sensor"
        774L -> "Flow Sensor"
        775L -> "Humidity Sensor"
        777L -> "Heat Pump"
        1293L -> "Device Energy Manager"
        1296L -> "Electrical Sensor"
        0xFFF1_0001L -> "Heat Meter"
        else -> "Type $id"
    }
}

// region Request and response bodies

/**
 * Asks the controller to commission a device that Google Play Services has already put on the
 * network and opened a commissioning window on. Android never sees the setup payload, only
 * where the device is and its passcode, so this is the on-network variant of the iOS request.
 */
@Serializable
data class OnNetworkCommissionRequest(
    val inUse: Boolean,
    val passcode: Long,
    val ipAddress: String,
    val port: Int,
)

@Serializable
data class CommissionResponse(
    @Serializable(with = LenientULongSerializer::class) val nodeId: ULong = 0u,
)

@Serializable
data class RenameRequest(val name: String)

/** Everything a controller needs to send this phone a notification through the push relay. */
@Serializable
data class NotificationTarget(
    val relayUrl: String,
    val username: String,
    val password: String,
    /** The FCM registration token. Controllers pass it back unchanged. */
    val deviceToken: String,
    /** Always `fcm` from Android; tells the relay to use FCM rather than APNs. */
    val environment: String,
)

/** The body of `POST /v1/push` on the push relay. Controllers send the same thing. */
@Serializable
data class PushRequest(
    val deviceToken: String,
    val environment: String,
    val title: String,
    val body: String,
)

// endregion

// region Lenient number decoding

/**
 * Decodes a 64-bit id that the controller may have emitted as an integer, as a float in
 * scientific notation, or as a string. Unparseable values decode as 0 rather than failing the
 * whole response -- these are display fields, not something worth dropping a device list over.
 */
object LenientULongSerializer : KSerializer<ULong> {

    override val descriptor = PrimitiveSerialDescriptor("LenientULong", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): ULong {
        val json = decoder as? JsonDecoder ?: return decoder.decodeString().toULongOrNull() ?: 0u

        return lenientULong(json.decodeJsonElement())
    }

    override fun serialize(encoder: Encoder, value: ULong) {
        encoder.encodeString(value.toString())
    }
}

internal fun lenientULong(element: JsonElement): ULong {
    val primitive = element as? JsonPrimitive ?: return 0u

    if (primitive is JsonNull) return 0u

    primitive.content.toULongOrNull()?.let { return it }

    if (primitive.isString) return 0u

    val value = primitive.content.toDoubleOrNull() ?: return 0u

    // 2^64, the first double past ULong.MAX_VALUE.
    return if (value >= 0 && value < 18446744073709551616.0) value.toULong() else 0u
}

// endregion
