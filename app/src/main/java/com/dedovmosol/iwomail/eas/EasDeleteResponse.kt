package com.dedovmosol.iwomail.eas

import org.xmlpull.v1.XmlPullParser

/** A successful Sync collection can advance the key even if one of its Delete commands was rejected. */
internal data class EasDeleteResponse(val syncKey: String, val confirmedIds: Set<String>) {
    companion object {
        fun parse(xml: String, collectionId: String, requestedIds: List<String>): EasDeleteResponse {
            val parser = EasXmlParser.createParser(xml)
            val p = EasSyncStream
            require(parser.nextTag() == XmlPullParser.START_TAG && p.localName(parser.name) == "Sync") {
                "Expected Sync response to Delete"
            }
            val requested = requestedIds.toSet()
            val statuses = HashMap<String, Int>()
            var collectionFound = false
            var collectionStatus: Int? = null
            var syncKey: String? = null

            p.children(parser) { rootChild ->
                when (rootChild) {
                    "Status" -> checkSuccess(p.leafText(parser)?.toIntOrNull())
                    "Collections" -> p.children(parser) { collectionChild ->
                        require(collectionChild == "Collection" && !collectionFound) { "Unexpected Delete collection" }
                        collectionFound = true
                        p.children(parser) { child ->
                            when (child) {
                                "Status" -> {
                                    require(collectionStatus == null) { "Duplicate Delete collection status" }
                                    collectionStatus = p.leafText(parser)?.toIntOrNull()
                                    checkSuccess(collectionStatus)
                                }
                                "SyncKey" -> {
                                    require(syncKey == null) { "Duplicate Delete response SyncKey" }
                                    syncKey = p.leafText(parser)
                                }
                                "CollectionId" -> require(p.leafText(parser) == collectionId) { "Wrong Delete collection" }
                                "Responses" -> p.children(parser) { response ->
                                    require(response == "Delete") { "Unexpected item response to Delete" }
                                    val item = p.leafMap(parser)
                                    val id = requireNotNull(item["ServerId"]) { "Missing Delete response ServerId" }
                                    val status = requireNotNull(item["Status"]?.toIntOrNull()) { "Missing Delete item status" }
                                    require(id in requested && statuses.put(id, status) == null) { "Unexpected Delete response ServerId" }
                                }
                                // The request uses GetChanges=0. Do not consume a key with unapplied server commands.
                                "Commands" -> p.children(parser) { error("Unexpected server commands in Delete response") }
                                "MoreAvailable" -> error("Unexpected continuation in Delete response")
                                else -> EasXmlParser.skip(parser)
                            }
                        }
                    }
                    else -> EasXmlParser.skip(parser)
                }
            }
            require(parser.next() == XmlPullParser.END_DOCUMENT && collectionStatus == 1) { "Incomplete Delete response" }
            val newKey = requireNotNull(syncKey?.takeIf { it != "0" }) { "Missing Delete response SyncKey" }
            // EAS omits successful Delete responses. Item-level 8 means this particular item is already absent.
            val confirmed = requested.filterTo(HashSet()) { id -> statuses[id].let { it == null || it == 1 || it == 8 } }
            return EasDeleteResponse(newKey, confirmed)
        }

        private fun checkSuccess(status: Int?) {
            if (status == 3) error("INVALID_SYNCKEY")
            check(status == 1) { "Delete collection Status=$status" }
        }
    }
}
