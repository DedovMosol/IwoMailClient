package com.exchange.mailclient.eas

/**
 * EAS WBXML Code Pages для Exchange 2007 (EAS 2.5, 12.0, 12.1)
 */
object EasCodePages {
    
    // Code Page 0: AirSync
    private val PAGE_AIRSYNC = mapOf(
        0x05 to "Sync",
        0x06 to "Responses",
        0x07 to "Add",
        0x08 to "Change",
        0x09 to "Delete",
        0x0A to "Fetch",
        0x0B to "SyncKey",
        0x0C to "ClientId",
        0x0D to "ServerId",
        0x0E to "Status",
        0x0F to "Collection",
        0x10 to "Class",
        0x12 to "CollectionId",
        0x13 to "GetChanges",
        0x14 to "MoreAvailable",
        0x15 to "WindowSize",
        0x16 to "Commands",
        0x17 to "Options",
        0x18 to "FilterType",
        0x1B to "Conflict",
        0x1C to "Collections",
        0x1D to "ApplicationData",
        0x1E to "DeletesAsMoves",
        0x20 to "Supported",
        0x21 to "SoftDelete",
        0x22 to "MIMESupport",
        0x23 to "MIMETruncation",
        0x24 to "Wait",
        0x25 to "Limit",
        0x26 to "Partial"
    )
    
    // Code Page 1: Contacts
    private val PAGE_CONTACTS = mapOf(
        0x05 to "Anniversary",
        0x06 to "AssistantName",
        0x07 to "AssistantPhoneNumber",
        0x08 to "Birthday",
        0x0C to "Business2PhoneNumber",
        0x0D to "BusinessCity",
        0x0E to "BusinessCountry",
        0x0F to "BusinessPostalCode",
        0x10 to "BusinessState",
        0x11 to "BusinessStreet",
        0x12 to "BusinessFaxNumber",
        0x13 to "BusinessPhoneNumber",
        0x15 to "CompanyName",
        0x16 to "Department",
        0x17 to "Email1Address",
        0x18 to "Email2Address",
        0x19 to "Email3Address",
        0x1A to "FileAs",
        0x1B to "FirstName",
        0x1D to "Home2PhoneNumber",
        0x1E to "HomeCity",
        0x1F to "HomeCountry",
        0x20 to "HomePostalCode",
        0x21 to "HomeState",
        0x22 to "HomeStreet",
        0x23 to "HomeFaxNumber",
        0x24 to "HomePhoneNumber",
        0x25 to "JobTitle",
        0x26 to "LastName",
        0x27 to "MiddleName",
        0x28 to "MobilePhoneNumber",
        0x2A to "OtherCity",
        0x2B to "OtherCountry",
        0x2C to "OtherPostalCode",
        0x2D to "OtherState",
        0x2E to "OtherStreet",
        0x30 to "Spouse",
        0x31 to "Suffix",
        0x32 to "Title",
        0x33 to "WebPage",
        0x36 to "Picture"
    )
    
    // Code Page 2: Email
    private val PAGE_EMAIL = mapOf(
        0x0F to "DateReceived",
        0x11 to "DisplayTo",
        0x12 to "Importance",
        0x13 to "MessageClass",
        0x14 to "Subject",
        0x15 to "Read",
        0x16 to "To",
        0x17 to "Cc",
        0x18 to "From",
        0x19 to "ReplyTo",
        0x1A to "AllDayEvent",
        0x1B to "Categories",
        0x1C to "Category",
        0x1D to "DtStamp",
        0x1E to "EndTime",
        0x1F to "InstanceType",
        0x20 to "BusyStatus",
        0x21 to "Location",
        0x22 to "MeetingRequest",
        0x23 to "Organizer",
        0x24 to "RecurrenceId",
        0x25 to "Reminder",
        0x26 to "ResponseRequested",
        0x27 to "Recurrences",
        0x28 to "Recurrence",
        0x29 to "Type",
        0x2A to "Until",
        0x2B to "Occurrences",
        0x2C to "Interval",
        0x2D to "DayOfWeek",
        0x2E to "DayOfMonth",
        0x2F to "WeekOfMonth",
        0x30 to "MonthOfYear",
        0x31 to "StartTime",
        0x32 to "Sensitivity",
        0x33 to "TimeZone",
        0x34 to "GlobalObjId",
        0x35 to "ThreadTopic",
        0x39 to "InternetCPID",
        0x3A to "Flag",
        0x3B to "FlagStatus",
        0x3C to "ContentClass",
        0x3D to "FlagType",
        0x3E to "CompleteTime"
    )

    
    // Code Page 4: Calendar
    private val PAGE_CALENDAR = mapOf(
        0x05 to "TimeZone",
        0x06 to "AllDayEvent",
        0x07 to "Attendees",
        0x08 to "Attendee",
        0x09 to "Email",
        0x0A to "Name",
        0x0D to "BusyStatus",
        0x0E to "Categories",
        0x0F to "Category",
        0x11 to "DtStamp",
        0x12 to "EndTime",
        0x13 to "Exception",
        0x14 to "Exceptions",
        0x15 to "Deleted",
        0x16 to "ExceptionStartTime",
        0x17 to "Location",
        0x18 to "MeetingStatus",
        0x19 to "OrganizerEmail",
        0x1A to "OrganizerName",
        0x1B to "Recurrence",
        0x1C to "Type",
        0x1D to "Until",
        0x1E to "Occurrences",
        0x1F to "Interval",
        0x20 to "DayOfWeek",
        0x21 to "DayOfMonth",
        0x22 to "WeekOfMonth",
        0x23 to "MonthOfYear",
        0x24 to "Reminder",
        0x25 to "Sensitivity",
        0x26 to "Subject",
        0x27 to "StartTime",
        0x28 to "UID"
    )
    
    // Code Page 7: FolderHierarchy
    // Теги согласно AOSP Tags.java и MS-ASCMD спецификации
    private val PAGE_FOLDER = mapOf(
        0x05 to "Folders",
        0x06 to "Folder",
        0x07 to "DisplayName",
        0x08 to "ServerId",
        0x09 to "ParentId",
        0x0A to "Type",
        0x0B to "Response",
        0x0C to "Status",
        0x0D to "ContentClass",
        0x0E to "Changes",
        0x0F to "Add",
        0x10 to "Delete",
        0x11 to "Update",
        0x12 to "SyncKey",        // 0x12 = 18 - правильный ID для SyncKey
        0x13 to "FolderCreate",
        0x14 to "FolderDelete",
        0x15 to "FolderUpdate",
        0x16 to "FolderSync",     // 0x16 = 22 - правильный ID для FolderSync
        0x17 to "Count"
    )
    
    // Code Page 9: Move
    private val PAGE_MOVE = mapOf(
        0x05 to "MoveItems",
        0x06 to "Move",
        0x07 to "SrcMsgId",
        0x08 to "SrcFldId",
        0x09 to "DstFldId",
        0x0A to "Response",
        0x0B to "Status",
        0x0C to "DstMsgId"
    )
    
    // Code Page 10: GetItemEstimate
    private val PAGE_ESTIMATE = mapOf(
        0x05 to "GetItemEstimate",
        0x06 to "Version",
        0x07 to "Collections",
        0x08 to "Collection",
        0x09 to "Class",
        0x0A to "CollectionId",
        0x0B to "DateTime",
        0x0C to "Estimate",
        0x0D to "Response",
        0x0E to "Status"
    )
    
    // Code Page 14: Provision
    // Согласно AOSP Tags.java - PROVISION_STATUS = 0x0B
    // ВАЖНО: Имя тега должно быть "Status" (как в XML), но на code page 14!
    private val PAGE_PROVISION = mapOf(
        0x05 to "Provision",
        0x06 to "Policies",
        0x07 to "Policy",
        0x08 to "PolicyType",
        0x09 to "PolicyKey",
        0x0A to "Data",
        0x0B to "Status",  // Status в контексте Provision (code page 14)
        0x0C to "RemoteWipe",
        0x0D to "EASProvisionDoc"
    )
    
    // Code Page 15: Search
    private val PAGE_SEARCH = mapOf(
        0x05 to "Search",
        0x07 to "Store",
        0x08 to "Name",
        0x09 to "Query",
        0x0A to "Options",
        0x0B to "Range",
        0x0C to "Status",
        0x0D to "Response",
        0x0E to "Result",
        0x0F to "Properties",
        0x10 to "Total",
        0x11 to "EqualTo",
        0x12 to "Value",
        0x13 to "And",
        0x14 to "Or",
        0x15 to "FreeText",
        0x17 to "DeepTraversal",
        0x18 to "LongId",
        0x19 to "RebuildResults",
        0x1A to "LessThan",
        0x1B to "GreaterThan",
        0x1E to "UserName",
        0x1F to "Password",
        0x20 to "ConversationId"
    )
    
    // Code Page 16: GAL (Global Address List)
    private val PAGE_GAL = mapOf(
        0x05 to "DisplayName",
        0x06 to "Phone",
        0x07 to "Office",
        0x08 to "Title",
        0x09 to "Company",
        0x0A to "Alias",
        0x0B to "FirstName",
        0x0C to "LastName",
        0x0D to "HomePhone",
        0x0E to "MobilePhone",
        0x0F to "EmailAddress"
    )
    
    // Code Page 17: AirSyncBase
    private val PAGE_AIRSYNCBASE = mapOf(
        0x05 to "BodyPreference",
        0x06 to "Type",
        0x07 to "TruncationSize",
        0x08 to "AllOrNone",
        0x0A to "Body",
        0x0B to "Data",
        0x0C to "EstimatedDataSize",
        0x0D to "Truncated",
        0x0E to "Attachments",
        0x0F to "Attachment",
        0x10 to "DisplayName",
        0x11 to "FileReference",
        0x12 to "Method",
        0x13 to "ContentId",
        0x14 to "ContentLocation",
        0x15 to "IsInline",
        0x16 to "NativeBodyType",
        0x17 to "ContentType",
        0x18 to "Preview"
    )
    
    // Code Page 18: Settings
    private val PAGE_SETTINGS = mapOf(
        0x05 to "Settings",
        0x06 to "Status",
        0x07 to "Get",
        0x08 to "Set",
        0x09 to "Oof",
        0x0A to "OofState",
        0x0B to "StartTime",
        0x0C to "EndTime",
        0x0D to "OofMessage",
        0x0E to "AppliesToInternal",
        0x0F to "AppliesToExternalKnown",
        0x10 to "AppliesToExternalUnknown",
        0x11 to "Enabled",
        0x12 to "ReplyMessage",
        0x13 to "BodyType",
        0x14 to "DevicePassword",
        0x15 to "Password",
        0x16 to "DeviceInformation",
        0x17 to "Model",
        0x18 to "IMEI",
        0x19 to "FriendlyName",
        0x1A to "OS",
        0x1B to "OSLanguage",
        0x1C to "PhoneNumber",
        0x1D to "UserInformation",
        0x1E to "EmailAddresses",
        0x1F to "SmtpAddress",
        0x20 to "UserAgent"
    )
    
    // Code Page 19: DocumentLibrary
    private val PAGE_DOCUMENTLIBRARY = mapOf(
        0x05 to "LinkId",
        0x06 to "DisplayName",
        0x07 to "IsFolder",
        0x08 to "CreationDate",
        0x09 to "LastModifiedDate",
        0x0A to "IsHidden",
        0x0B to "ContentLength",
        0x0C to "ContentType"
    )
    
    // Code Page 20: ItemOperations
    private val PAGE_ITEMOPERATIONS = mapOf(
        0x05 to "ItemOperations",
        0x06 to "Fetch",
        0x07 to "Store",
        0x08 to "Options",
        0x09 to "Range",
        0x0A to "Total",
        0x0B to "Properties",
        0x0C to "Data",
        0x0D to "Status",
        0x0E to "Response",
        0x0F to "Version",
        0x10 to "Schema",
        0x11 to "Part",
        0x12 to "EmptyFolderContents",
        0x13 to "DeleteSubFolders",
        0x14 to "UserName",
        0x15 to "Password",
        0x16 to "Move",
        0x17 to "DstFldId",
        0x18 to "ConversationId",
        0x19 to "MoveAlways"
    )
    
    // Code Page 21: ComposeMail
    // Согласно AOSP Tags.java
    private val PAGE_COMPOSEMAIL = mapOf(
        0x05 to "SendMail",
        0x06 to "SmartForward",
        0x07 to "SmartReply",
        0x08 to "SaveInSentItems",
        0x09 to "ReplaceMime",
        0x0A to "Source",
        0x0B to "FolderId",
        0x0C to "Mime",
        0x0D to "ClientId",
        0x0E to "Status",
        0x0F to "AccountId"
    )
    
    private val allPages = mapOf(
        0 to PAGE_AIRSYNC,
        1 to PAGE_CONTACTS,
        2 to PAGE_EMAIL,
        4 to PAGE_CALENDAR,
        5 to PAGE_MOVE,  // Move code page = 5 (согласно MS-ASWBXML и AOSP)
        7 to PAGE_FOLDER,
        10 to PAGE_ESTIMATE,
        14 to PAGE_PROVISION,
        15 to PAGE_SEARCH,
        16 to PAGE_GAL,
        17 to PAGE_AIRSYNCBASE,
        18 to PAGE_SETTINGS,
        19 to PAGE_DOCUMENTLIBRARY,
        20 to PAGE_ITEMOPERATIONS,
        21 to PAGE_COMPOSEMAIL
    )
    
    private val reversePages: Map<String, Pair<Int, Int>> by lazy {
        val map = mutableMapOf<String, Pair<Int, Int>>()
        allPages.forEach { (pageId, tags) ->
            tags.forEach { (tagId, tagName) ->
                // Для FolderSync используем 0x15 (первый), не перезаписываем на 0x16
                if (tagName == "FolderSync" && map.containsKey(tagName)) {
                    return@forEach
                }
                map[tagName] = pageId to tagId
            }
        }
        map
    }
    
    fun getTagName(codePage: Int, tagId: Int): String {
        return allPages[codePage]?.get(tagId) ?: "Unknown_${codePage}_${tagId}"
    }
    
    fun getTagId(tagName: String): Pair<Int, Int> {
        return reversePages[tagName] ?: (0 to 0x05)
    }
    
    /**
     * Ищет тег на конкретной code page
     * Возвращает null если тег не найден на этой странице
     */
    fun getTagIdOnPage(tagName: String, codePage: Int): Pair<Int, Int>? {
        val page = allPages[codePage] ?: return null
        val tagId = page.entries.find { it.value == tagName }?.key ?: return null
        return codePage to tagId
    }
}

