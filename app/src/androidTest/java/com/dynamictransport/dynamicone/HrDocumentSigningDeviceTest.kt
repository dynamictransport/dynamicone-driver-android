package com.dynamictransport.dynamicone

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dynamictransport.dynamicone.core.design.theme.DynamicOneTheme
import com.dynamictransport.dynamicone.core.pdf.AndroidPdfDocumentOpener
import com.dynamictransport.dynamicone.domain.model.HrDocument
import com.dynamictransport.dynamicone.domain.model.HrDocumentLanguage
import com.dynamictransport.dynamicone.domain.model.HrDocumentLookup
import com.dynamictransport.dynamicone.domain.model.HrDocumentStatus
import com.dynamictransport.dynamicone.domain.model.LocalSignature
import com.dynamictransport.dynamicone.domain.model.LocalSignatureState
import com.dynamictransport.dynamicone.feature.hrdocuments.HrDocumentDetailScreen
import com.dynamictransport.dynamicone.feature.hrdocuments.HrDocumentDetailUiState
import com.dynamictransport.dynamicone.feature.hrdocuments.HrDocumentRow
import com.dynamictransport.dynamicone.feature.hrdocuments.HrDocumentsScreen
import com.dynamictransport.dynamicone.feature.hrdocuments.HrDocumentsUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.Locale

/**
 * The signing screen on a real Android runtime: the platform PDF renderer, a finger signature and the sign button
 * (docs/17). Screenshots are written to the app's external files (`screenshots/`) for review:
 * `adb pull /sdcard/Android/data/com.dynamictransport.dynamicone/files/screenshots`.
 */
@RunWith(AndroidJUnit4::class)
class HrDocumentSigningDeviceTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val screenshots = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
    private val now: Instant = Instant.now()
    private lateinit var defaultLocale: Locale

    @Before
    fun setUp() {
        defaultLocale = Locale.getDefault()
        Locale.setDefault(TURKISH)
    }

    @After
    fun tearDown() = Locale.setDefault(defaultLocale)

    @Test
    fun thePlatformRendererShowsThePdfAndAFingerSignatureEnablesSigning() {
        val pages = runBlocking { AndroidPdfDocumentOpener(Dispatchers.IO).open(samplePdf().path) }
        assertNotNull(pages)
        assertEquals(2, pages!!.pageCount)
        assertNotNull(runBlocking { pages.render(0, 800) })

        val document = rodo(dueAt = now.plus(Duration.ofDays(1)))
        var state by mutableStateOf(
            HrDocumentDetailUiState(lookup = HrDocumentLookup.Found(document, null), selectedLanguage = document.language("tr"), pages = pages),
        )
        composeRule.setContent {
            Turkish {
                DynamicOneTheme(darkTheme = false) {
                    HrDocumentDetailScreen(
                        state = state,
                        onBack = {},
                        onSelectLanguage = { code -> state = state.copy(selectedLanguage = document.language(code), declarationAccepted = false) },
                        onDeclarationChanged = { state = state.copy(declarationAccepted = it) },
                        onStroke = { state = state.copy(strokes = state.strokes + listOf(it)) },
                        onClearSignature = { state = state.copy(strokes = emptyList()) },
                        onSign = {},
                        onOpenReceipt = {},
                        onRetryContent = {},
                        onRetryLoad = {},
                        onMessageShown = {},
                        now = now,
                    )
                }
            }
        }

        // The page image is drawn by the platform renderer.
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodes(hasContentDescription("Sayfa 1 / 2")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("hr_scroll_hint").assertIsDisplayed()
        screenshot("1_evrak_okuma.png")

        composeRule.onNodeWithTag("hr_detail").performScrollToKey("sign")
        composeRule.onNodeWithTag("hr_sign").assertIsNotEnabled()
        composeRule.onNodeWithTag("hr_signature_pad").performTouchInput {
            // A signature-like scribble.
            down(percentOffset(0.08f, 0.62f))
            moveTo(percentOffset(0.18f, 0.25f))
            moveTo(percentOffset(0.26f, 0.7f))
            moveTo(percentOffset(0.36f, 0.3f))
            moveTo(percentOffset(0.46f, 0.66f))
            moveTo(percentOffset(0.58f, 0.4f))
            moveTo(percentOffset(0.7f, 0.58f))
            moveTo(percentOffset(0.9f, 0.45f))
            up()
        }
        composeRule.onNodeWithTag("hr_declaration").performClick()
        composeRule.onNodeWithTag("hr_sign").assertIsEnabled()
        screenshot("2_beyan_ve_imza.png")
        pages.close()
    }

    @Test
    fun theListShowsWhatWaitsForTheSignatureAndWhatWasSigned() {
        val overdue = rodo(id = "doc-1", dueAt = now.minus(Duration.ofDays(2)))
        val waiting = rodo(id = "doc-2", dueAt = now.plus(Duration.ofDays(5)), title = "İş sağlığı ve güvenliği talimatı")
        val signed = rodo(id = "doc-0", dueAt = null, title = "Şirket içi yönetmelik").copy(
            status = HrDocumentStatus.SIGNED,
            signedAt = now.minus(Duration.ofDays(20)),
            signedLanguage = "tr",
        )
        composeRule.setContent {
            Turkish {
                DynamicOneTheme(darkTheme = false) {
                    HrDocumentsScreen(
                        state = HrDocumentsUiState(
                            pending = listOf(
                                HrDocumentRow(overdue, null),
                                HrDocumentRow(waiting, LocalSignature("sig-1", waiting.id, "tr", now, LocalSignatureState.PENDING, null)),
                            ),
                            signed = listOf(signed),
                        ),
                        onOpenDocument = {},
                        onBack = null,
                        onRefresh = {},
                        onLoadMore = {},
                        onMessageShown = {},
                        now = now,
                    )
                }
            }
        }

        composeRule.onNodeWithTag("hr_document_doc-1").assertIsDisplayed()
        screenshot("3_evrak_listesi.png")
    }

    private fun screenshot(name: String) {
        composeRule.waitForIdle()
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        File(screenshots, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** A two-page PDF made on the device; its text only stands in for a real document. */
    private fun samplePdf(): File {
        val pdf = PdfDocument()
        val body = Paint().apply {
            textSize = 11f
            isAntiAlias = true
        }
        val heading = Paint(body).apply {
            textSize = 16f
            isFakeBoldText = true
        }
        listOf("GDPR (RODO) AYDINLATMA METNİ", "15. Çalışan teyidi").forEachIndexed { index, title ->
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, index + 1).create())
            var y = 70f
            page.canvas.drawText(title, 50f, y, heading)
            SAMPLE_LINES.forEach { line ->
                y += 20f
                page.canvas.drawText(line, 50f, y, body)
            }
            pdf.finishPage(page)
        }
        val file = File(context.cacheDir, "rodo-sample.pdf")
        file.outputStream().use { pdf.writeTo(it) }
        pdf.close()
        return file
    }

    private fun rodo(id: String = "doc-1", dueAt: Instant?, title: String = "DynamicONE çalışanları için RODO aydınlatma metni") = HrDocument(
        id = id,
        version = "1.0",
        referenceLanguage = "pl",
        sentAt = now.minus(Duration.ofDays(3)),
        dueAt = dueAt,
        status = HrDocumentStatus.PENDING,
        firstOpenedAt = null,
        signedAt = null,
        signedLanguage = null,
        receiptAvailable = false,
        languages = listOf(
            HrDocumentLanguage("pl", "Klauzula informacyjna RODO", "Potwierdzam, że otrzymałem(-am) niniejszą informację.", "A".repeat(64), null, 2),
            HrDocumentLanguage(
                "tr",
                title,
                "DynamicONE sistemini kullanmaya başlamadan önce kişisel verilerimin işlenmesine ilişkin bu bilgilendirmeyi aldığımı " +
                    "ve içeriğini inceleme fırsatı bulduğumu teyit ederim.",
                "B".repeat(64),
                null,
                2,
            ),
            HrDocumentLanguage("en", "GDPR privacy notice", "I confirm that I received this information.", "C".repeat(64), null, 2),
            HrDocumentLanguage("az", "GDPR (RODO) üzrə məlumatlandırma bildirişi", "Bu məlumatı aldığımı təsdiq edirəm.", "D".repeat(64), null, 2),
        ),
    )

    @Composable
    private fun Turkish(content: @Composable () -> Unit) {
        val base = LocalContext.current
        val current = LocalConfiguration.current
        val configuration = remember(current) { Configuration(current).apply { setLocale(TURKISH) } }
        val localized = remember(configuration) { base.createConfigurationContext(configuration) }
        CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration, content = content)
    }

    private companion object {
        val TURKISH: Locale = Locale.forLanguageTag("tr-TR")
        val SAMPLE_LINES = listOf(
            "Kişisel verilerin Veri Sorumlusu: Dynamic Transport.",
            "Kişisel veriler DynamicONE ve onunla entegre sistemlerde yalnızca",
            "aşağıdaki amaçlar için gerekli olduğu ölçüde işlenebilir.",
            "",
            "(Örnek belge — cihaz testi için telefonda oluşturuldu.)",
        )
    }
}
