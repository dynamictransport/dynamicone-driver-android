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
import com.dynamictransport.dynamicone.domain.model.HrDocument
import com.dynamictransport.dynamicone.domain.model.HrDocumentLookup
import com.dynamictransport.dynamicone.domain.model.HrDocumentStatus
import com.dynamictransport.dynamicone.domain.model.LocalSignature
import com.dynamictransport.dynamicone.domain.model.LocalSignatureState
import com.dynamictransport.dynamicone.domain.model.SignaturePoint
import com.dynamictransport.dynamicone.feature.hrdocuments.HrDocumentDetailScreen
import com.dynamictransport.dynamicone.feature.hrdocuments.HrDocumentDetailUiState
import com.dynamictransport.dynamicone.feature.hrdocuments.HrDocumentRow
import com.dynamictransport.dynamicone.feature.hrdocuments.HrDocumentsScreen
import com.dynamictransport.dynamicone.feature.hrdocuments.HrDocumentsUiState
import com.dynamictransport.dynamicone.feature.hrdocuments.PendingSignatures
import com.dynamictransport.dynamicone.feature.hrdocuments.PendingSignaturesCard
import com.dynamictransport.dynamicone.testing.FakePdfPages
import com.dynamictransport.dynamicone.testing.VALID_STROKE
import com.dynamictransport.dynamicone.testing.hrDocument
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.TimeZone

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class HrDocumentScreensTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val now: Instant = Instant.parse("2026-09-21T08:00:00Z")
    private val opened = mutableListOf<String>()
    private val strokes = mutableListOf<List<SignaturePoint>>()
    private var declaration: Boolean? = null
    private var signs = 0
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

    private fun setList(state: HrDocumentsUiState) = composeRule.setContent {
        DynamicOneTheme(darkTheme = false) {
            HrDocumentsScreen(
                state = state,
                onOpenDocument = { opened += it },
                onBack = null,
                onRefresh = {},
                onLoadMore = {},
                onMessageShown = {},
                now = now,
            )
        }
    }

    private fun setDetail(state: HrDocumentDetailUiState) = composeRule.setContent {
        DynamicOneTheme(darkTheme = false) {
            HrDocumentDetailScreen(
                state = state,
                onBack = {},
                onSelectLanguage = {},
                onDeclarationChanged = { declaration = it },
                onStroke = { strokes += it },
                onClearSignature = {},
                onSign = { signs++ },
                onOpenReceipt = { receipts++ },
                onRetryContent = {},
                onRetryLoad = {},
                onMessageShown = {},
                now = now,
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

    private fun toSign(document: HrDocument = hrDocument(), local: LocalSignature? = null, ready: Boolean = false) = HrDocumentDetailUiState(
        lookup = HrDocumentLookup.Found(document, local),
        selectedLanguage = document.language("en"),
        pages = FakePdfPages(pageCount = 2),
        declarationAccepted = ready,
        strokes = if (ready) listOf(VALID_STROKE) else emptyList(),
    )

    @Test
    fun overdueDocumentsAreAWarningWithTextAndSignedOnesAreListedBelow() {
        setList(
            HrDocumentsUiState(
                pending = listOf(HrDocumentRow(hrDocument(), local = null)),
                signed = listOf(hrDocument(id = "doc-0", status = HrDocumentStatus.SIGNED, signedAt = Instant.parse("2026-09-01T10:15:00Z"))),
            ),
        )

        composeRule.onNodeWithText("Waiting for your signature").assertIsDisplayed()
        // The app language (English here) picks the version whose title is shown.
        composeRule.onNodeWithTag("hr_document_doc-1").assertTextContains("GDPR privacy notice", substring = true)
        assertTextUnder("hr_state_overdue", "Deadline passed (20 Sep 2026, 21:59)")
        assertTextUnder("hr_state_signed", "Signed: 1 Sep 2026, 10:15")

        composeRule.onNodeWithTag("hr_document_doc-1").performClick()
        assertEquals(listOf("doc-1"), opened)
    }

    @Test
    fun aSignatureOnThePhoneIsShownAsWaitingNotAsSigned() {
        val document = hrDocument()
        setList(
            HrDocumentsUiState(
                pending = listOf(HrDocumentRow(document, LocalSignature("sig-1", document.id, "en", now, LocalSignatureState.PENDING, null))),
                signed = emptyList(),
            ),
        )

        assertTextUnder("hr_state_waiting", "Signed on the phone, waiting to be sent")
        composeRule.onNodeWithTag("hr_none_signed").assertIsDisplayed()
    }

    @Test
    fun theSignButtonOnlyAppearsAtTheEndOfTheDocument() {
        setDetail(toSign())

        composeRule.onNodeWithTag("hr_page_0").assertIsDisplayed()
        assertTextUnder("hr_scroll_hint", "Read to the end of the document to sign it")
        assertTrue(composeRule.onAllNodes(hasTestTag("hr_sign")).fetchSemanticsNodes().isEmpty())

        composeRule.onNodeWithTag("hr_detail").performScrollToKey("sign")

        composeRule.onNodeWithTag("hr_sign")
            .assertTextContains("SIGN AND SEND")
            .assertHeightIsAtLeast(64.dp)
            .assertIsNotEnabled()
        composeRule.onNodeWithText("Tick the box and sign to continue.").assertIsDisplayed()
        composeRule.onNodeWithTag("hr_declaration").assertIsOff().performClick()
        assertEquals(true, declaration)
    }

    @Test
    fun aDeclarationAndASignatureEnableSigning() {
        setDetail(toSign(ready = true))

        composeRule.onNodeWithTag("hr_detail").performScrollToKey("sign")
        composeRule.onNodeWithTag("hr_sign").assertIsEnabled().performClick()

        assertEquals(1, signs)
    }

    @Test
    fun aFingerStrokeInTheBoxIsReportedRelativeToTheBox() {
        setDetail(toSign())
        composeRule.onNodeWithTag("hr_detail").performScrollToKey("sign")

        composeRule.onNodeWithTag("hr_signature_pad").performTouchInput {
            down(centerLeft.copy(x = width * 0.1f))
            moveTo(center.copy(y = height * 0.3f))
            moveTo(centerRight.copy(x = width * 0.9f))
            up()
        }

        val stroke = strokes.single()
        assertTrue(stroke.size >= 2)
        assertTrue(stroke.all { it.x in 0f..1f && it.y in 0f..1f })
        assertTrue(stroke.first().x < 0.2f && stroke.last().x > 0.8f)
    }

    @Test
    fun aRejectedSignatureCanBeSignedAgain() {
        val document = hrDocument()
        setDetail(toSign(local = LocalSignature("sig-1", document.id, "en", now, LocalSignatureState.FAILED, null)))

        assertTextUnder("hr_state_failed", "Not accepted: sign again")
        composeRule.onNodeWithTag("hr_detail").performScrollToKey("sign")
        composeRule.onNodeWithTag("hr_signature_pad").assertIsDisplayed()
        composeRule.onNodeWithTag("hr_sign").assertIsNotEnabled()
    }

    @Test
    fun aSignatureWaitingOnThePhoneOffersNoSecondSignature() {
        val document = hrDocument()
        setDetail(toSign(local = LocalSignature("sig-1", document.id, "en", now, LocalSignatureState.PENDING, null), ready = true))

        assertTextUnder("hr_state_waiting", "Signed on the phone, waiting to be sent")
        composeRule.onNodeWithTag("hr_detail").performScrollToKey("page_1")
        assertTrue(composeRule.onAllNodes(hasTestTag("hr_sign_section")).fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodes(hasTestTag("hr_sign")).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun aSignedDocumentOpensItsSignedPdfOnceItIsReady() {
        val signed = hrDocument(status = HrDocumentStatus.SIGNED, signedAt = Instant.parse("2026-09-19T06:41:00Z"), signedLanguage = "tr")
        setDetail(HrDocumentDetailUiState(lookup = HrDocumentLookup.Found(signed.copy(receiptAvailable = true), null)))

        assertTextUnder("hr_state_signed", "Signed: 19 Sep 2026, 06:41")
        assertTextUnder("hr_signed_details", "Türkçe")
        composeRule.onNodeWithTag("hr_open_receipt").assertIsEnabled().performClick()
        assertEquals(1, receipts)
    }

    @Test
    fun aSignedPdfThatIsNotReadyCannotBeOpened() {
        val signed = hrDocument(status = HrDocumentStatus.SIGNED, signedAt = now, signedLanguage = "pl")
        setDetail(HrDocumentDetailUiState(lookup = HrDocumentLookup.Found(signed, null)))

        composeRule.onNodeWithTag("hr_open_receipt").assertIsNotEnabled()
        composeRule.onNodeWithText("The signed PDF is being prepared. Try again in a few minutes.").assertIsDisplayed()
    }

    @Test
    fun theReminderCardCountsDocumentsAndOpensTheOnlyOne() {
        var openedWith: String? = "none"
        composeRule.setContent {
            DynamicOneTheme(darkTheme = false) {
                PendingSignaturesCard(
                    pending = PendingSignatures(count = 1, soonestDue = Instant.parse("2026-09-21T21:59:00Z"), singleDocumentId = "doc-1"),
                    onOpen = { openedWith = it },
                    now = now,
                )
            }
        }

        assertTextUnder("hr_pending_banner", "1 document is waiting for your signature")
        assertTextUnder("hr_pending_banner", "Last day today, 21:59")
        composeRule.onNodeWithText("Open").performClick()
        assertEquals("doc-1", openedWith)
    }
}
