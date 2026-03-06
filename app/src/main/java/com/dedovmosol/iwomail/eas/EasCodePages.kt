package com.dedovmosol.iwomail.eas

/**
 * EAS WBXML Code Pages для Exchange 2007 (EAS 2.5, 12.0, 12.1)
 */
object EasCodePages {
    
    // Code Page 0: AirSync
    // Verified against MS-ASWBXML v24.0 spec (May 2025)
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
        0x19 to "Truncation",
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
    // Verified against MS-ASWBXML v24.0 spec (May 2025)
    private val PAGE_CONTACTS = mapOf(
        0x05 to "Anniversary",
        0x06 to "AssistantName",
        0x07 to "AssistantPhoneNumber",
        0x08 to "Birthday",
        0x09 to "Body",
        0x0A to "BodySize",
        0x0B to "BodyTruncated",
        0x0C to "Business2PhoneNumber",
        0x0D to "BusinessAddressCity",
        0x0E to "BusinessAddressCountry",
        0x0F to "BusinessAddressPostalCode",
        0x10 to "BusinessAddressState",
        0x11 to "BusinessAddressStreet",
        0x12 to "BusinessFaxNumber",
        0x13 to "BusinessPhoneNumber",
        0x14 to "CarPhoneNumber",
        0x15 to "Categories",
        0x16 to "Category",
        0x17 to "Children",
        0x18 to "Child",
        0x19 to "CompanyName",
        0x1A to "Department",
        0x1B to "Email1Address",
        0x1C to "Email2Address",
        0x1D to "Email3Address",
        0x1E to "FileAs",
        0x1F to "FirstName",
        0x20 to "Home2PhoneNumber",
        0x21 to "HomeAddressCity",
        0x22 to "HomeAddressCountry",
        0x23 to "HomeAddressPostalCode",
        0x24 to "HomeAddressState",
        0x25 to "HomeAddressStreet",
        0x26 to "HomeFaxNumber",
        0x27 to "HomePhoneNumber",
        0x28 to "JobTitle",
        0x29 to "LastName",
        0x2A to "MiddleName",
        0x2B to "MobilePhoneNumber",
        0x2C to "OfficeLocation",
        0x2D to "OtherAddressCity",
        0x2E to "OtherAddressCountry",
        0x2F to "OtherAddressPostalCode",
        0x30 to "OtherAddressState",
        0x31 to "OtherAddressStreet",
        0x32 to "PagerNumber",
        0x33 to "RadioPhoneNumber",
        0x34 to "Spouse",
        0x35 to "Suffix",
        0x36 to "Title",
        0x37 to "WebPage",
        0x38 to "YomiCompanyName",
        0x39 to "YomiFirstName",
        0x3A to "YomiLastName",
        0x3B to "CompressedRTF",
        0x3C to "Picture",
        0x3D to "Alias",
        0x3E to "WeightedRank"
    )
    
    // Code Page 2: Email
    // Verified against MS-ASWBXML + AOSP Tags.java
    // 0x3B is "Status" per newer spec, but kept as "FlagStatus" to disambiguate (10+ code references).
    private val PAGE_EMAIL = mapOf(
        0x05 to "Attachment",
        0x06 to "Attachments",
        0x07 to "AttName",
        0x08 to "AttSize",
        0x09 to "Att0Id",
        0x0A to "AttMethod",
        0x0B to "AttRemoved",
        0x0C to "Body",
        0x0D to "BodySize",
        0x0E to "BodyTruncated",
        0x0F to "DateReceived",
        0x10 to "DisplayName",
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
        0x36 to "MIMEData",
        0x37 to "MIMETruncated",
        0x38 to "MIMESize",
        0x39 to "InternetCPID",
        0x3A to "Flag",
        0x3B to "FlagStatus",
        0x3C to "ContentClass",
        0x3D to "FlagType",
        0x3E to "CompleteTime",
        0x3F to "DisallowNewTimeProposal"
    )

    // Code Page 5: Move
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
    
    // Code Page 4: Calendar
    // Verified against MS-ASWBXML + AOSP Tags.java
    private val PAGE_CALENDAR = mapOf(
        0x05 to "Timezone",
        0x06 to "AllDayEvent",
        0x07 to "Attendees",
        0x08 to "Attendee",
        0x09 to "Email",
        0x0A to "Name",
        0x0B to "Body",
        0x0C to "BodyTruncated",
        0x0D to "BusyStatus",
        0x0E to "Categories",
        0x0F to "Category",
        0x10 to "CompressedRTF",
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
        0x28 to "UID",
        0x29 to "AttendeeStatus",
        0x2A to "AttendeeType",
        0x2B to "Attachment",
        0x2C to "Attachments",
        0x2D to "AttName",
        0x2E to "AttSize",
        0x2F to "AttOid",
        0x30 to "AttMethod",
        0x31 to "AttRemoved",
        0x32 to "DisplayName",
        0x33 to "DisallowNewTimeProposal",
        0x34 to "ResponseRequested",
        0x35 to "AppointmentReplyTime",
        0x36 to "ResponseType",
        0x37 to "CalendarType",
        0x38 to "IsLeapMonth",
        0x39 to "FirstDayOfWeek",
        0x3A to "OnlineMeetingConfLink",
        0x3B to "OnlineMeetingExternalLink"
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
    
    // Code Page 9: Tasks
    private val PAGE_TASKS = mapOf(
        0x05 to "Body",
        0x06 to "BodySize",
        0x07 to "BodyTruncated",
        0x08 to "Categories",
        0x09 to "Category",
        0x0A to "Complete",
        0x0B to "DateCompleted",
        0x0C to "DueDate",
        0x0D to "UtcDueDate",
        0x0E to "Importance",
        0x0F to "Recurrence",
        0x10 to "Type",
        0x11 to "Start",
        0x12 to "Until",
        0x13 to "Occurrences",
        0x14 to "Interval",
        0x15 to "DayOfMonth",
        0x16 to "DayOfWeek",
        0x17 to "WeekOfMonth",
        0x18 to "MonthOfYear",
        0x19 to "Regenerate",
        0x1A to "DeadOccur",
        0x1B to "ReminderSet",
        0x1C to "ReminderTime",
        0x1D to "Sensitivity",
        0x1E to "StartDate",
        0x1F to "UtcStartDate",
        0x20 to "Subject",
        0x21 to "CompressedRTF",
        0x22 to "OrdinalDate",
        0x23 to "SubOrdinalDate",
        0x24 to "CalendarType",
        0x25 to "IsLeapMonth",
        0x26 to "FirstDayOfWeek"
    )
    
    // Code Page 6: GetItemEstimate
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
    
    // Code Page 8: MeetingResponse (EAS 2.5+, Exchange 2007 SP1)
    // Verified against MS-ASWBXML v24.0 spec — 0x0D is unassigned, InstanceId is 0x0E
    private val PAGE_MEETINGRESPONSE = mapOf(
        0x05 to "CalendarId",
        0x06 to "CollectionId",
        0x07 to "MeetingResponse",
        0x08 to "RequestId",
        0x09 to "Request",
        0x0A to "Result",
        0x0B to "Status",
        0x0C to "UserResponse",
        0x0E to "InstanceId"
    )

    // Code Page 10: ResolveRecipients (EAS 2.5+, Exchange 2007 SP1)
    // Verified against MS-ASWBXML spec and AOSP Tags.java
    private val PAGE_RESOLVERECIPIENTS = mapOf(
        0x05 to "ResolveRecipients",
        0x06 to "Response",
        0x07 to "Status",
        0x08 to "Type",
        0x09 to "Recipient",
        0x0A to "DisplayName",
        0x0B to "EmailAddress",
        0x0C to "Certificates",
        0x0D to "Certificate",
        0x0E to "MiniCertificate",
        0x0F to "Options",
        0x10 to "To",
        0x11 to "CertificateRetrieval",
        0x12 to "RecipientCount",
        0x13 to "MaxCertificates",
        0x14 to "MaxAmbiguousRecipients",
        0x15 to "CertificateCount",
        0x16 to "Availability",
        0x17 to "StartTime",
        0x18 to "EndTime",
        0x19 to "MergedFreeBusy",
        0x1A to "Picture",
        0x1B to "MaxSize",
        0x1C to "Data",
        0x1D to "MaxPictures"
    )

    // Code Page 11: ValidateCert (EAS 2.5+, Exchange 2007 SP1)
    private val PAGE_VALIDATECERT = mapOf(
        0x05 to "ValidateCert",
        0x06 to "Certificates",
        0x07 to "Certificate",
        0x08 to "CertificateChain",
        0x09 to "CheckCRL",
        0x0A to "Status"
    )

    // Code Page 12: Contacts2 (EAS 2.5+, Exchange 2007 SP1)
    private val PAGE_CONTACTS2 = mapOf(
        0x05 to "CustomerId",
        0x06 to "GovernmentId",
        0x07 to "IMAddress",
        0x08 to "IMAddress2",
        0x09 to "IMAddress3",
        0x0A to "ManagerName",
        0x0B to "CompanyMainPhone",
        0x0C to "AccountName",
        0x0D to "NickName",
        0x0E to "MMS"
    )

    // Code Page 13: Ping
    private val PAGE_PING = mapOf(
        0x05 to "Ping",
        0x06 to "AutdState",
        0x07 to "Status",
        0x08 to "HeartbeatInterval",
        0x09 to "Folders",
        0x0A to "Folder",
        0x0B to "Id",
        0x0C to "Class",
        0x0D to "MaxFolders"
    )
    
    // Code Page 14: Provision
    // Verified against MS-ASWBXML v24.0 spec (May 2025)
    // EAS 12.0+ policy tags (0x0E-0x3A) needed for Exchange 2007 SP1
    private val PAGE_PROVISION = mapOf(
        0x05 to "Provision",
        0x06 to "Policies",
        0x07 to "Policy",
        0x08 to "PolicyType",
        0x09 to "PolicyKey",
        0x0A to "Data",
        0x0B to "Status",
        0x0C to "RemoteWipe",
        0x0D to "EASProvisionDoc",
        0x0E to "DevicePasswordEnabled",
        0x0F to "AlphanumericDevicePasswordRequired",
        0x10 to "RequireStorageCardEncryption",
        0x11 to "PasswordRecoveryEnabled",
        0x13 to "AttachmentsEnabled",
        0x14 to "MinDevicePasswordLength",
        0x15 to "MaxInactivityTimeDeviceLock",
        0x16 to "MaxDevicePasswordFailedAttempts",
        0x17 to "MaxAttachmentSize",
        0x18 to "AllowSimpleDevicePassword",
        0x19 to "DevicePasswordExpiration",
        0x1A to "DevicePasswordHistory",
        0x1B to "AllowStorageCard",
        0x1C to "AllowCamera",
        0x1D to "RequireDeviceEncryption",
        0x1E to "AllowUnsignedApplications",
        0x1F to "AllowUnsignedInstallationPackages",
        0x20 to "MinDevicePasswordComplexCharacters",
        0x21 to "AllowWiFi",
        0x22 to "AllowTextMessaging",
        0x23 to "AllowPOPIMAPEmail",
        0x24 to "AllowBluetooth",
        0x25 to "AllowIrDA",
        0x26 to "RequireManualSyncWhenRoaming",
        0x27 to "AllowDesktopSync",
        0x28 to "MaxCalendarAgeFilter",
        0x29 to "AllowHTMLEmail",
        0x2A to "MaxEmailAgeFilter",
        0x2B to "MaxEmailBodyTruncationSize",
        0x2C to "MaxEmailHTMLBodyTruncationSize",
        0x2D to "RequireSignedSMIMEMessages",
        0x2E to "RequireEncryptedSMIMEMessages",
        0x2F to "RequireSignedSMIMEAlgorithm",
        0x30 to "RequireEncryptionSMIMEAlgorithm",
        0x31 to "AllowSMIMEEncryptionAlgorithmNegotiation",
        0x32 to "AllowSMIMESoftCerts",
        0x33 to "AllowBrowser",
        0x34 to "AllowConsumerEmail",
        0x35 to "AllowRemoteDesktop",
        0x36 to "AllowInternetSharing",
        0x37 to "UnapprovedInROMApplicationList",
        0x38 to "ApplicationName",
        0x39 to "ApprovedApplicationList",
        0x3A to "Hash"
    )
    
    // Code Page 15: Search
    private val PAGE_SEARCH = mapOf(
        0x05 to "Search",
        0x06 to "Stores",
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
        0x1F to "SMTPAddress",
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
    
    // Code Page 21: ComposeMail (EAS 14.0+)
    // Verified against MS-ASWBXML v24.0 spec (May 2025)
    // Note: 0x0A and 0x14 are gaps (not assigned)
    private val PAGE_COMPOSEMAIL = mapOf(
        0x05 to "SendMail",
        0x06 to "SmartForward",
        0x07 to "SmartReply",
        0x08 to "SaveInSentItems",
        0x09 to "ReplaceMime",
        0x0B to "Source",
        0x0C to "FolderId",
        0x0D to "ItemId",
        0x0E to "LongId",
        0x0F to "InstanceId",
        0x10 to "Mime",
        0x11 to "ClientId",
        0x12 to "Status",
        0x13 to "AccountId"
    )

    // Code Page 22: Email2 (EAS 14.0+)
    private val PAGE_EMAIL2 = mapOf(
        0x05 to "UmCallerID",
        0x06 to "UmUserNotes",
        0x07 to "UmAttDuration",
        0x08 to "UmAttOrder",
        0x09 to "ConversationId",
        0x0A to "ConversationIndex",
        0x0B to "LastVerbExecuted",
        0x0C to "LastVerbExecutionTime",
        0x0D to "ReceivedAsBcc",
        0x0E to "Sender",
        0x0F to "CalendarType",
        0x10 to "IsLeapMonth",
        0x11 to "AccountId",
        0x12 to "FirstDayOfWeek",
        0x13 to "MeetingMessageType"
    )

    // Code Page 23: Notes (EAS 14.0+)
    private val PAGE_NOTES = mapOf(
        0x05 to "Subject",
        0x06 to "MessageClass",
        0x07 to "LastModifiedDate",
        0x08 to "Categories",
        0x09 to "Category"
    )
    
    private val allPages = mapOf(
        0 to PAGE_AIRSYNC,
        1 to PAGE_CONTACTS,
        2 to PAGE_EMAIL,
        4 to PAGE_CALENDAR,
        5 to PAGE_MOVE,
        6 to PAGE_ESTIMATE,
        7 to PAGE_FOLDER,
        8 to PAGE_MEETINGRESPONSE,
        9 to PAGE_TASKS,
        10 to PAGE_RESOLVERECIPIENTS,
        11 to PAGE_VALIDATECERT,
        12 to PAGE_CONTACTS2,
        13 to PAGE_PING,
        14 to PAGE_PROVISION,
        15 to PAGE_SEARCH,
        16 to PAGE_GAL,
        17 to PAGE_AIRSYNCBASE,
        18 to PAGE_SETTINGS,
        19 to PAGE_DOCUMENTLIBRARY,
        20 to PAGE_ITEMOPERATIONS,
        21 to PAGE_COMPOSEMAIL,
        22 to PAGE_EMAIL2,
        23 to PAGE_NOTES
    )
    
    private val reversePages: Map<String, Pair<Int, Int>> by lazy {
        val map = mutableMapOf<String, Pair<Int, Int>>()
        allPages.forEach { (pageId, tags) ->
            tags.forEach { (tagId, tagName) ->
                if (!map.containsKey(tagName)) {
                    map[tagName] = pageId to tagId
                }
            }
        }
        map
    }
    
    private val reversePagesFull: Map<String, List<Pair<Int, Int>>> by lazy {
        val map = mutableMapOf<String, MutableList<Pair<Int, Int>>>()
        allPages.forEach { (pageId, tags) ->
            tags.forEach { (tagId, tagName) ->
                map.getOrPut(tagName) { mutableListOf() }.add(pageId to tagId)
            }
        }
        map
    }
    
    fun getTagName(codePage: Int, tagId: Int): String {
        return allPages[codePage]?.get(tagId) ?: "Unknown_${codePage}_${tagId}"
    }
    
    fun getTagId(tagName: String, currentPage: Int = -1): Pair<Int, Int> {
        if (currentPage >= 0) {
            val onCurrent = getTagIdOnPage(tagName, currentPage)
            if (onCurrent != null) return onCurrent
        }
        return reversePages[tagName]
            ?: throw IllegalArgumentException("Unknown WBXML tag: $tagName")
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

