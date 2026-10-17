package com.eyue.reader.data.cloud

import org.junit.Assert.*
import org.junit.Test

class WebDavListingTest {
    private fun response(href: String, collection: Boolean = true) = """<d:response><d:href>$href</d:href><d:propstat><d:prop><d:resourcetype>${if (collection) "<d:collection/>" else ""}</d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"""

    @Test fun listingAcceptsOnlyDirectCollectionsWithinFixedCloudRoot() {
        val xml = "<d:multistatus xmlns:d=\"DAV:\">" + listOf(
            response("/dav/ERead/books/"), response("/dav/ERead/books/book-1/"),
            response("https://dav.jianguoyun.com/dav/ERead/books/book-2/"),
            response("https://DAV.JIANGUOYUN.COM:443/dav/ERead/books/book-4/"),
            response("https://evil.test/dav/ERead/books/book-3/"),
            response("/dav/ERead/books/book-1/nested/"), response("/dav/ERead/books/../outside/"),
            response("/dav/ERead/books/%2e%2e/"), response("/dav/ERead/books/file", false),
            response("/dav/ERead/books/book-1/")
        ).joinToString("") + "</d:multistatus>"
        assertEquals(listOf("book-1", "book-2", "book-4"), WebDavCloudTransport.parseCollections(xml.toByteArray(), "books/"))
    }

    @Test fun xmlListingRejectsExternalEntities() {
        val xml = """<?xml version="1.0"?><!DOCTYPE x [<!ENTITY ext SYSTEM "file:///private/config">]><d:multistatus xmlns:d="DAV:"><d:response><d:href>&ext;</d:href></d:response></d:multistatus>"""
        assertThrows(Exception::class.java) { WebDavCloudTransport.parseCollections(xml.toByteArray(), "books/") }
    }

    @Test fun configuredRootRejectsOtherServersAndOtherDirectoriesOnTheSameServer() {
        val root = "http://example.invalid:8080/cloud/ERead/"
        val xml = "<d:multistatus xmlns:d=\"DAV:\">" + listOf(
            response("/cloud/ERead/books/first/"), response("second/"),
            response("http://example.invalid:8080/other/ERead/books/wrong/"),
            response("http://example.invalid:8081/cloud/ERead/books/wrong/"),
            response("https://example.invalid:8080/cloud/ERead/books/wrong/"),
            response("http://evil.invalid:8080/cloud/ERead/books/wrong/")
        ).joinToString("") + "</d:multistatus>"
        assertEquals(listOf("first", "second"), WebDavCloudTransport.parseCollections(xml.toByteArray(), "books/", root))
    }
}
