package com.dynamictransport.dynamicone.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dynamictransport.dynamicone.core.design.theme.DynamicOneTheme
import com.dynamictransport.dynamicone.domain.model.CashPaymentKind
import com.dynamictransport.dynamicone.domain.model.CashPaymentLookup
import com.dynamictransport.dynamicone.domain.model.CashPaymentScope
import com.dynamictransport.dynamicone.domain.model.CashPaymentState
import com.dynamictransport.dynamicone.domain.model.LocalCashSignature
import com.dynamictransport.dynamicone.domain.model.LocalSignatureState
import com.dynamictransport.dynamicone.domain.model.SignaturePoint
import com.dynamictransport.dynamicone.feature.cashpayments.CashPaymentDetailScreen
import com.dynamictransport.dynamicone.feature.cashpayments.CashPaymentDetailUiState
import com.dynamictransport.dynamicone.feature.cashpayments.CashPaymentRow
import com.dynamictransport.dynamicone.feature.cashpayments.CashPaymentsScreen
import com.dynamictransport.dynamicone.feature.cashpayments.CashPaymentsUiState
import com.dynamictransport.dynamicone.testing.TEST_NOW
import com.dynamictransport.dynamicone.testing.VALID_STROKE
import com.dynamictransport.dynamicone.testing.cashPayment
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.time.Instant
import java.util.TimeZone

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class CashPaymentScreensTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val opened = mutableListOf<String>()
    private val filters = mutableListOf<CashPaymentState>()
    private val strokes = mutableListOf<List<SignaturePoint>>()
    private var declaration: Boolean? = null
    private var signs = 0
    private var resends = 0
    private var receipts = 0
    private lateinit var defaultZone: TimeZone

    // Times are shown in the device time zone.
    @Before
    fun setUp() {
        defaultZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun tearDown() = TimeZone.setDefault(defaultZone)

    private fun setList(state: CashPaymentsUiState) = composeRule.setContent {
        DynamicOneTheme(darkTheme = false) {
            CashPaymentsScreen(
                state = state,
                onOpenPayment = { opened += it },
                onBack = null,
                onRefresh = {},
                onFilterSelected = { filters += it },
                onLoadMore = {},
                onMessageShown = {},
            )
        }
    }

    private fun setDetail(state: CashPaymentDetailUiState) = composeRule.setContent {
        DynamicOneTheme(darkTheme = false) {
            CashPaymentDetailScreen(
                state = state,
                onBack = {},
                onDeclarationChanged = { declaration = it },
                onStroke = { strokes += it },
                onClearSignature = {},
                onSign = { signs++ },
                onResend = { resends++ },
                onOpenReceipt = { receipts++ },
                onRetryLoad = {},
                onMessageShown = {},
            )
        }
    }

    /** Status banners are rows: their text sits in child nodes. */
    private fun assertTextUnder(tag: String, expected: String) {
        val texts = composeRule.onAllNodes(hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .flatMap { node -> node.config.getOrElseNullable(SemanticsProperties.Text) { null }.orEmpty().map { it.text } }
        assertTrue("'$expected' not in $texts", texts.any { expected in it })
    }

    private fun toSign(ready: Boolean = false, local: LocalCashSignature? = null) = CashPaymentDetailUiState(
        lookup = CashPaymentLookup.Found(cashPayment(), local),
        fresh = true,
        declarationAccepted = ready,
        strokes = if (ready) listOf(VALID_STROKE) else emptyList(),
    )

    @Test
    fun theQueueShowsWhoGetsHowMuchAndOpensARow() {
        setList(
            CashPaymentsUiState(
                scope = CashPaymentScope.DISBURSEMENTS,
                items = listOf(
                    CashPaymentRow(cashPayment(), local = null),
                    CashPaymentRow(cashPayment(id = "pay-2", kind = CashPaymentKind.SALARY, amount = BigDecimal("4500.00"), currency = "PLN"), local = null),
                ),
            ),
        )

        composeRule.onNodeWithText("Cash paid to personnel").assertIsDisplayed()
        composeRule.onNodeWithTag("cash_filter_APPROVED").assertIsDisplayed()
        composeRule.onNodeWithTag("cash_payment_pay-1").assertTextContains("Mehmet Yıldız", substring = true)
        composeRule.onNodeWithTag("cash_payment_pay-1").assertTextContains("No. 1042", substring = true)
        composeRule.onNodeWithTag("cash_payment_pay-1").assertTextContains("Advance", substring = true)
        composeRule.onNodeWithTag("cash_amount_pay-1", useUnmergedTree = true).assertTextContains("100.00", substring = true)
        composeRule.onNodeWithTag("cash_amount_pay-1", useUnmergedTree = true).assertTextContains("€", substring = true)
        composeRule.onNodeWithTag("cash_amount_pay-2", useUnmergedTree = true).assertTextContains("4,500.00", substring = true)
        composeRule.onNodeWithTag("cash_payment_pay-2").assertTextContains("Salary", substring = true)
        assertTextUnder("cash_state_approved", "Planned for")

        composeRule.onNodeWithTag("cash_payment_pay-1").performClick()
        assertEquals(listOf("pay-1"), opened)
        composeRule.onNodeWithTag("cash_filter_RECEIVED").performClick()
        assertEquals(listOf(CashPaymentState.RECEIVED), filters)
    }

    @Test
    fun handedOverCancelledAndWaitingRowsSayWhatTheyAreWithTextNotColourAlone() {
        val waiting = LocalCashSignature("cash-1", CashPaymentScope.OWN, "pay-3", TEST_NOW, LocalSignatureState.PENDING, null)
        setList(
            CashPaymentsUiState(
                scope = CashPaymentScope.OWN,
                filter = CashPaymentState.RECEIVED,
                items = listOf(
                    CashPaymentRow(cashPayment(id = "pay-1", state = CashPaymentState.RECEIVED, receivedAt = Instant.parse("2026-10-28T10:05:00Z")), local = null),
                    CashPaymentRow(cashPayment(id = "pay-2", state = CashPaymentState.CANCELLED), local = null),
                    CashPaymentRow(cashPayment(id = "pay-3"), local = waiting),
                ),
            ),
        )

        composeRule.onNodeWithText("My cash payments").assertIsDisplayed()
        assertTextUnder("cash_state_received", "Handed over: 28 Oct 2026, 10:05")
        assertTextUnder("cash_state_cancelled", "Finance cancelled this payment")
        assertTextUnder("cash_state_waiting", "Waiting for the server to confirm")
    }

    @Test
    fun emptyAndAccessStatesAreExplained() {
        setList(CashPaymentsUiState(items = emptyList()))
        assertTextUnder("cash_empty", "No cash payments waiting to be handed over.")
    }

    @Test
    fun theDetailShowsTheApprovedFormAndSignsOnlyWithDeclarationAndDrawing() {
        setDetail(toSign())

        assertTextUnder("cash_header", "Mehmet Yıldız")
        composeRule.onNodeWithTag("cash_detail_amount").assertTextContains("100.00", substring = true)
        assertTextUnder("cash_form", "Avans ödeme formu")
        assertTextUnder("cash_form", "Tutar: 100,00 EUR")
        composeRule.onNodeWithTag("cash_declaration_text", useUnmergedTree = true)
            .assertTextContains("Yukarıda yazılı tutarı nakit olarak eksiksiz teslim aldım.", substring = true)
        composeRule.onNodeWithTag("cash_sign")
            .assertTextContains("I RECEIVED THE MONEY")
            .assertHeightIsAtLeast(64.dp)
            .assertIsNotEnabled()
        composeRule.onNodeWithText("Tick the declaration and sign to continue.").assertIsDisplayed()

        composeRule.onNodeWithTag("cash_detail").performScrollToKey("sign")
        composeRule.onNodeWithTag("cash_declaration").assertIsOff().performClick()
        assertEquals(true, declaration)
        composeRule.onNodeWithTag("hr_signature_pad").performTouchInput {
            down(centerLeft)
            moveTo(center)
            moveTo(centerRight)
            up()
        }
        assertEquals(1, strokes.size)
        assertTrue(strokes.single().size >= 2)
    }

    @Test
    fun aReadyDetailSignsOnceAndAWaitingOneOffersToSendAgain() {
        setDetail(toSign(ready = true))
        composeRule.onNodeWithTag("cash_sign").assertIsEnabled().performClick()
        assertEquals(1, signs)
    }

    @Test
    fun aWaitingSignatureIsNeverShownAsHandedOver() {
        val waiting = LocalCashSignature("cash-1", CashPaymentScope.DISBURSEMENTS, "pay-1", TEST_NOW, LocalSignatureState.PENDING, null)
        setDetail(toSign(local = waiting))

        assertTextUnder("cash_state_waiting", "Signed on the phone")
        assertTrue(composeRule.onAllNodes(hasTestTag("cash_sign")).fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodes(hasTestTag("cash_state_received")).fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithTag("cash_resend").assertTextContains("SEND AGAIN").assertIsEnabled().performClick()
        assertEquals(1, resends)
        composeRule.onNodeWithText("Not completed until the server confirms it. The same request is sent again.").assertIsDisplayed()
    }

    @Test
    fun aHandedOverPaymentOpensItsSignedReceipt() {
        setDetail(
            CashPaymentDetailUiState(
                lookup = CashPaymentLookup.Found(cashPayment(state = CashPaymentState.RECEIVED, receivedAt = Instant.parse("2026-10-28T10:05:00Z")), null),
                fresh = true,
            ),
        )

        assertTextUnder("cash_state_received", "Handed over: 28 Oct 2026, 10:05")
        assertTrue(composeRule.onAllNodes(hasTestTag("cash_sign_section")).fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithTag("cash_open_receipt").assertTextContains("OPEN SIGNED RECEIPT").assertIsEnabled().performClick()
        assertEquals(1, receipts)
    }

    @Test
    fun aStaleDetailFromTheListCannotBeSignedBeforeTheServerAnswered() {
        setDetail(toSign(ready = true).copy(fresh = false))

        assertTrue(composeRule.onAllNodes(hasTestTag("cash_sign")).fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodes(hasTestTag("cash_sign_section")).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun aMissingPaymentSaysSo() {
        setDetail(CashPaymentDetailUiState(lookup = CashPaymentLookup.Missing))
        assertTextUnder("cash_missing", "This payment is no longer available to you.")
    }
}

/** The same detail on a phone held sideways (tablets turn freely, W6): header, form and the action stay reachable. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w800dp-h400dp-land")
class CashPaymentLandscapeScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun theLandscapeDetailKeepsTheDominantActionAndTheSignaturePad() {
        var signs = 0
        composeRule.setContent {
            DynamicOneTheme(darkTheme = false) {
                CashPaymentDetailScreen(
                    state = CashPaymentDetailUiState(
                        lookup = CashPaymentLookup.Found(cashPayment(), null),
                        fresh = true,
                        declarationAccepted = true,
                        strokes = listOf(VALID_STROKE),
                    ),
                    onBack = {},
                    onDeclarationChanged = {},
                    onStroke = {},
                    onClearSignature = {},
                    onSign = { signs++ },
                    onResend = {},
                    onOpenReceipt = {},
                    onRetryLoad = {},
                    onMessageShown = {},
                )
            }
        }

        composeRule.onNodeWithTag("cash_header").assertIsDisplayed()
        composeRule.onNodeWithTag("cash_sign").assertIsDisplayed().assertIsEnabled().performClick()
        assertEquals(1, signs)
        composeRule.onNodeWithTag("cash_detail").performScrollToKey("sign")
        composeRule.onNodeWithTag("hr_signature_pad").assertIsDisplayed()
    }
}
