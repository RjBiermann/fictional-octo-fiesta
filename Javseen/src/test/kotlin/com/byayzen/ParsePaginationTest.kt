package com.byayzen

import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Javseen categories now paginate as {cat}/{sort}/{page}/ (e.g. /big-tits/recent/2/).
 * The sort segment is only visible in the page-1 AJAX response's "pagination" field;
 * Parse.categoryPageUrl must extract this page's path from it.
 */
class ParsePaginationTest {

    private val categoryAjaxPage1 = ParsePaginationTest::class.java
        .getResourceAsStream("/javseen-category-ajax-page1.json")!!
        .readBytes().decodeToString()

    private val pagination: String =
        jacksonObjectMapper().readValue<Map<String, String>>(categoryAjaxPage1)["pagination"]!!

    @Test fun `page 2 path comes from real pagination field`() {
        assertEquals("/big-tits/recent/2/", Parse.categoryPageUrl(pagination, 2))
    }

    @Test fun `page 2664 does not collide with the 4 substring`() {
        assertEquals("/big-tits/recent/4/", Parse.categoryPageUrl(pagination.replace("/big-tits/recent/2/", "/big-tits/recent/4/"), 4))
    }

    @Test fun `page higher than listed pagination yields null for fallback`() {
        assertNull(Parse.categoryPageUrl(pagination, 999))
    }
}
