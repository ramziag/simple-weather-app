package io.github.ramziag.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** jsonv2 replies shaped like real ones (fields, nesting and quirks as Nominatim sends them). */
class NominatimTest {

    private fun reply(addresstype: String, name: String, address: String) =
        """{"place_id":312411907,"licence":"Data © OpenStreetMap contributors, ODbL 1.0. http://osm.org/copyright",""" +
            """"osm_type":"relation","osm_id":123,"lat":"39.7990175","lon":"-89.6439575","category":"boundary",""" +
            """"type":"administrative","place_rank":16,"importance":0.5,"addresstype":"$addresstype","name":"$name",""" +
            """"display_name":"$name","address":{$address},"boundingbox":["39.6","39.9","-89.8","-89.5"]}"""

    @Test
    fun town() {
        val body = reply(
            "city", "Springfield",
            """"city":"Springfield","county":"Sangamon County","state":"Illinois","ISO3166-2-lvl4":"US-IL",""" +
                """"country":"United States","country_code":"us"""",
        )
        assertEquals("Springfield" to "Illinois, United States", Nominatim.parse(body, "en"))
    }

    @Test
    fun suburbFallsBackToItsCity() {
        val body = reply(
            "suburb", "Nokomis",
            """"suburb":"Nokomis","city":"Minneapolis","county":"Hennepin County","state":"Minnesota",""" +
                """"ISO3166-2-lvl4":"US-MN","country":"United States","country_code":"us"""",
        )
        assertEquals("Minneapolis" to "Minnesota, United States", Nominatim.parse(body, "en"))
    }

    /** "address.city" is an administrative area here; the village itself is the answer. */
    @Test
    fun villageInsideAdministrativeCity() {
        val body = reply(
            "village", "Field",
            """"village":"Field","city":"Area A (Kicking Horse/Kinbasket Lake)",""" +
                """"county":"Columbia-Shuswap Regional District","state":"British Columbia","ISO3166-2-lvl4":"CA-BC",""" +
                """"postcode":"V0A 1G0","country":"Canada","country_code":"ca"""",
        )
        assertEquals("Field" to "British Columbia, Canada", Nominatim.parse(body, "en"))
    }

    /** Tokyo's wards come without the prefecture. */
    @Test
    fun tokyoWard() {
        val body = reply(
            "city", "Shinjuku",
            """"city":"Shinjuku","ISO3166-2-lvl4":"JP-13","postcode":"160-8484","country":"Japan","country_code":"jp"""",
        )
        assertEquals("Shinjuku" to "Japan", Nominatim.parse(body, "en"))
    }

    @Test
    fun parisSkipsRegion() {
        val body = reply(
            "suburb", "1st Arrondissement",
            """"suburb":"1st Arrondissement","city_district":"Paris","city":"Paris","ISO3166-2-lvl6":"FR-75C",""" +
                """"state":"Ile-de-France","ISO3166-2-lvl4":"FR-IDF","region":"Metropolitan France","postcode":"75001",""" +
                """"country":"France","country_code":"fr"""",
        )
        assertEquals("Paris" to "Ile-de-France, France", Nominatim.parse(body, "en"))
    }

    @Test
    fun cityOfPrefixDroppedInEnglish() {
        val body = reply(
            "suburb", "Mayfair",
            """"suburb":"Mayfair","city":"City of Westminster","state_district":"Greater London","state":"England",""" +
                """"ISO3166-2-lvl4":"GB-ENG","postcode":"W1J","country":"United Kingdom","country_code":"gb"""",
        )
        assertEquals("Westminster" to "England, United Kingdom", Nominatim.parse(body, "en"))
        assertEquals("City of Westminster", Nominatim.parse(body, "de")?.first)
    }

    @Test
    fun nothingThere() {
        assertNull(Nominatim.parse("""{"error":"Unable to geocode"}""", "en"))
    }

    @Test
    fun blankFieldsSkipped() {
        val body = """{"addresstype":"town","name":" ","address":{"town":"","village":"Oakley","state":"  ",""" +
            """"province":"Ontario","country":"Canada"}}"""
        assertEquals("Oakley" to "Ontario, Canada", Nominatim.parse(body, "en"))
        val nulls = """{"addresstype":"village","name":null,"address":{"city":null,"county":"Bell County",""" +
            """"state":"Kentucky","country":"United States"}}"""
        assertEquals("Bell County" to "Kentucky, United States", Nominatim.parse(nulls, "en"))
    }

    @Test
    fun urlSendsTwoDecimals() {
        assertEquals(
            "https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=39.80&lon=-89.64&zoom=13&layer=address" +
                "&addressdetails=1&accept-language=en",
            Nominatim.url(39.80172, -89.64371, "en"),
        )
        val zero = Nominatim.url(-0.004, -0.001, "fr")
        assertEquals(true, zero.contains("&lat=0.00&lon=0.00&"))
        assertEquals(true, zero.endsWith("accept-language=fr"))
    }
}
