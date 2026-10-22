package com.dynamictransport.dynamicone.feature.common

import com.dynamictransport.dynamicone.domain.model.CheckTemplate
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** Every language the app offers must be complete, so a new language cannot ship half translated. */
class LocalizationTest {

    private val res = File("src/main/res")

    /** Plural quantities each language must provide (CLDR cardinal rules). */
    private val requiredPlurals = mapOf(
        "pl" to setOf("one", "few", "many", "other"),
        "tr" to setOf("one", "other"),
        "ru" to setOf("one", "few", "many", "other"),
    )

    private data class Entry(val text: String, val plurals: Map<String, String>?)

    private fun read(dir: String): Map<String, Entry> {
        val files = File(res, dir).listFiles { f -> f.name.startsWith("strings") && f.name.endsWith(".xml") }.orEmpty()
        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        return files.flatMap { file ->
            val root = builder.parse(file).documentElement
            val children = root.childNodes
            (0 until children.length).mapNotNull { children.item(it) as? Element }
                .filter { it.getAttribute("translatable") != "false" }
                .mapNotNull { element ->
                    val name = element.getAttribute("name")
                    when (element.tagName) {
                        "string" -> name to Entry(element.textContent, null)
                        "plurals" -> {
                            val items = element.getElementsByTagName("item")
                            name to Entry("", (0 until items.length).associate {
                                val item = items.item(it) as Element
                                item.getAttribute("quantity") to item.textContent
                            })
                        }
                        else -> null
                    }
                }
        }.toMap()
    }

    private fun placeholders(text: String): Set<String> =
        Regex("%(\\d+\\$)?[sd]").findAll(text).map { it.value }.toSet()

    private val translations = AppLanguage.entries.mapNotNull { it.tag }.filter { it != "en" }

    @Test
    fun everyOfferedLanguageTranslatesEveryString() {
        val english = read("values")
        for (tag in translations) {
            val translated = read("values-$tag")
            assertEquals("missing or extra strings in values-$tag", english.keys, translated.keys)
        }
    }

    @Test
    fun placeholdersMatchEnglish() {
        val english = read("values")
        for (tag in translations) {
            for ((name, entry) in read("values-$tag")) {
                val source = english.getValue(name)
                val expected = placeholders(source.text + source.plurals?.get("other").orEmpty())
                // A plural's "one" may spell the number out; its "other" keeps every placeholder.
                val text = entry.plurals?.get("other") ?: entry.text
                assertEquals("$tag/$name: $text", expected, placeholders(text))
            }
        }
    }

    @Test
    fun pluralsHaveEveryQuantityTheLanguageNeeds() {
        for (tag in translations) {
            val required = requiredPlurals[tag] ?: error("add the plural rules of $tag to this test")
            read("values-$tag").filterValues { it.plurals != null }.forEach { (name, entry) ->
                assertTrue("$tag/$name has ${entry.plurals!!.keys}", entry.plurals.keys.containsAll(required))
            }
        }
    }

    @Test
    fun pickerAndSystemLanguageSettingsOfferTheSameLanguages() {
        val config = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(res, "xml/locales_config.xml")).getElementsByTagName("locale")
        val systemLanguages = (0 until config.length).map { (config.item(it) as Element).getAttribute("android:name") }.toSet()

        assertEquals(systemLanguages, AppLanguage.entries.mapNotNull { it.tag }.toSet())
        assertTrue("ru" in systemLanguages)
    }

    @Test
    fun builtInVehicleCheckIsTranslatedForEveryLanguage() {
        for (tag in translations) {
            CheckTemplate.Default.items.forEach { item ->
                assertNotEquals("${item.key} in $tag", item.title("en"), item.title(tag))
                if (item.hint("en") != null) assertNotEquals("${item.key} hint in $tag", item.hint("en"), item.hint(tag))
            }
        }
    }
}
