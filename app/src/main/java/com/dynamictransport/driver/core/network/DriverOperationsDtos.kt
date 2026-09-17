@file:UseSerializers(BigDecimalSerializer::class)

package com.dynamictransport.driver.core.network

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal

// Wire models of the Driver App operations contract (backend TH-2026-0439, dynamictransport/DynamicONE#83,
// commit 72cd609): trip execution, document slots, payroll, signed personnel documents and messages.
// Every field is nullable here; the mapping layer decides what is required. Unknown fields are ignored.

@Serializable
data class DriverLocationDto(
    val stopId: String? = null,
    val name: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val coordinateStatus: String? = null,
)

@Serializable
data class DriverAvailableActionDto(
    val code: String? = null,
    val label: String? = null,
    val stopId: String? = null,
    val containerId: String? = null,
)

@Serializable
data class DriverExecutionResponseDto(
    val tripId: String? = null,
    val version: String? = null,
    val status: String? = null,
    val workflow: String? = null,
    val availableActions: List<DriverAvailableActionDto>? = null,
    val documents: List<DriverDocumentSlotDto>? = null,
)

/**
 * POST trips/{tripId}/actions. The same [commandId] must always carry exactly the same payload:
 * the backend answers a replay with the recorded result and a changed payload with 409.
 */
@Serializable
data class DriverActionCommandDto(
    val commandId: String,
    val expectedVersion: String,
    val action: String,
    val stopId: String?,
    val containerId: String?,
    val note: String?,
    val observedAtUtc: String?,
    val latitude: Double?,
    val longitude: Double?,
    val accuracyMeters: Double?,
)

@Serializable
data class DriverExecutionResultDto(
    val succeeded: Boolean = false,
    val code: String? = null,
    val message: String? = null,
    val version: String? = null,
)

@Serializable
data class DriverActionReceiptDto(
    val id: String,
    val action: String? = null,
    val stopId: String? = null,
    val containerId: String? = null,
    val note: String? = null,
    val receivedAtUtc: String? = null,
    val observedAtUtc: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracyMeters: Double? = null,
)

@Serializable
data class DriverDocumentSlotDto(
    val slot: String? = null,
    val type: String? = null,
    val label: String? = null,
    val stopId: String? = null,
    val containerId: String? = null,
    val required: Boolean? = null,
    val state: String? = null,
    val documentId: String? = null,
    val fileName: String? = null,
    val reviewNote: String? = null,
    val revision: String? = null,
)

@Serializable
data class DriverPayrollLineDto(
    val type: String? = null,
    val amount: BigDecimal? = null,
    val effectiveFrom: String? = null,
)

@Serializable
data class DriverPayrollCurrencyDto(
    val currencyCode: String? = null,
    val baseGross: BigDecimal? = null,
    val additions: List<DriverPayrollLineDto>? = null,
    val deductions: List<DriverPayrollLineDto>? = null,
    val grossEarnings: BigDecimal? = null,
    val totalDeductions: BigDecimal? = null,
    val balanceBeforeStatutoryDeductions: BigDecimal? = null,
)

@Serializable
data class DriverPayrollSnapshotDto(
    val year: Int? = null,
    val month: Int? = null,
    val state: String? = null,
    val currencies: List<DriverPayrollCurrencyDto>? = null,
    val finalizedBaseGross: BigDecimal? = null,
    val finalizedOvertimeGross: BigDecimal? = null,
    val finalizedOvertimeHours: BigDecimal? = null,
    val finalizedCurrency: String? = null,
)

@Serializable
data class DriverPayrollResponseDto(
    val payroll: DriverPayrollSnapshotDto? = null,
    val publishedAtUtc: String? = null,
    val state: String? = null,
)

@Serializable
data class DriverSignedDocumentDto(
    val id: String,
    val type: String? = null,
    val signedAtUtc: String? = null,
)

@Serializable
data class DriverMessageDto(
    val id: String,
    val driverId: String? = null,
    val tripId: String? = null,
    val body: String? = null,
    val direction: String? = null,
    val createdAtUtc: String? = null,
    val readAtUtc: String? = null,
    /** Requested from the backend (docs/12 #10); backends without it do not send the field. */
    val senderDisplayName: String? = null,
)

/** POST messages. `tripId` is omitted for the general conversation. */
@Serializable
data class DriverSendMessageDto(
    val commandId: String,
    val body: String,
    val tripId: String?,
)

/** POST messages/{messageId}/read: always `{}`; the server checks the message's conversation itself. */
@Serializable
data object DriverMessageReadDto

/** POST me/devices (TH-2026-0444). `appVersion` and `locale` are optional. */
@Serializable
data class DriverDeviceRequestDto(
    val installationId: String,
    val platform: String,
    val pushToken: String,
    val appVersion: String?,
    val locale: String?,
)

/**
 * JSON numbers (C# `decimal`) read from their literal text, so money never passes through a binary
 * floating point value on the device.
 */
object BigDecimalSerializer : KSerializer<BigDecimal> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("java.math.BigDecimal", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): BigDecimal {
        val text = if (decoder is JsonDecoder) decoder.decodeJsonElement().jsonPrimitive.content else decoder.decodeString()
        return try {
            BigDecimal(text)
        } catch (e: NumberFormatException) {
            throw SerializationException("Invalid decimal value", e)
        }
    }

    override fun serialize(encoder: Encoder, value: BigDecimal) {
        encoder.encodeString(value.toPlainString())
    }
}

/** Case-sensitive execution names of the trip action workflow. */
object DriverExecutionNames {
    const val WORKFLOW_TRIP_ACTIONS = "TripActions"
    const val WORKFLOW_LEGACY_STATUSES = "LegacyStatuses"
    const val ACTION_START = "Start"
    const val ACTION_COMPLETE_STOP = "CompleteStop"
    const val ACTION_REST = "Rest"
    const val ACTION_PROBLEM = "Problem"
    const val COORDINATES_AVAILABLE = "Available"
    const val COORDINATES_MISSING = "Missing"
}

object DriverSlotStateNames {
    const val MISSING = "Missing"
    const val NOT_REQUIRED = "NotRequired"
    const val PENDING_REVIEW = "PendingReview"
    const val APPROVED = "Approved"
    const val REJECTED = "Rejected"
}

object DriverPayrollStateNames {
    const val PUBLISHED = "Published"
    const val UNPUBLISHED = "Unpublished"
}

object DriverMessageNames {
    const val FROM_DRIVER = "FromDriver"
    const val TO_DRIVER = "ToDriver"
}

object DriverSignedDocumentTypeNames {
    const val COMPENSATION = "Compensation"
    const val LEAVE = "Leave"
}
