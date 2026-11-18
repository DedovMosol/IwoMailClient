import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Эмпирическая проверка находок аудита на реальном сервере Exchange 2007 (EAS 12.1).
 *
 * ТОЛЬКО ЧТЕНИЕ почтового ящика. Команды: OPTIONS, Provision (создаёт партнёрство устройства
 * с отдельным DeviceId IWOAUDITPROBE2026, его можно удалить в OWA / EMS), FolderSync, Sync без
 * клиентских команд, GetItemEstimate, ItemOperations Fetch, GetAttachment, Ping (короткий),
 * EWS GetFolder/GetItem. Никаких SendMail, Add/Change/Delete, MoveItems, CreateItem.
 * Попыток входа с неверным паролем нет (чтобы не приблизить блокировку учётной записи).
 *
 * Конфиденциальность: в вывод не попадают темы, адреса, тела, имена вложений и пароль.
 * Пишутся только коды статусов, размеры, счётчики, время, имена кодировок и элементы политики.
 *
 * Пароль берётся из переменной окружения IWO_AUDIT_PASS.
 * Запуск (JDK 17, TLS 1.0 включается только для этого процесса):
 *   java -Djava.security.properties=audit_tls.security -Djdk.tls.client.protocols=TLSv1,TLSv1.1,TLSv1.2
 *        -Djdk.http.ntlm.transparentAuth=disabled -Dfile.encoding=UTF-8 EasProbe.java [tls|full]
 */
public class EasProbe {

    // Сервер и учётная запись — только из окружения: в репозитории реальных адресов и логинов нет
    static final String HOST = env("IWO_AUDIT_HOST");
    static final String BASE = "https://" + HOST;
    static final String DOMAIN = env("IWO_AUDIT_DOMAIN"), USER = env("IWO_AUDIT_USER");

    static String env(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) throw new IllegalStateException("set " + name);
        return v;
    }
    static final String DEVICE_ID = "IWOAUDITPROBE2026", DEVICE_TYPE = "Android";
    static final String UA = "Android/12-EAS-2.0"; // как в приложении
    /** Ожидаемый CN сертификата сервера (закрепление на время проверки), из окружения. */
    static final String EXPECTED_CN = "CN=" + env("IWO_AUDIT_CERT_CN");
    static final Charset UTF8 = StandardCharsets.UTF_8;

    static String pass;
    static String policyKey;
    static SSLSocketFactory sf;
    static X509Certificate seenCert;
    static final PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, UTF8);

    // ============================== WBXML ==============================

    static final Map<Integer, String[]> PAGES = new HashMap<>();
    static {
        PAGES.put(0, ("Sync,Responses,Add,Change,Delete,Fetch,SyncKey,ClientId,ServerId,Status,Collection,Class,Version," +
                "CollectionId,GetChanges,MoreAvailable,WindowSize,Commands,Options,FilterType,Truncation,RTFTruncation,Conflict," +
                "Collections,ApplicationData,DeletesAsMoves,NotifyGUID,Supported,SoftDelete,MIMESupport,MIMETruncation,Wait,Limit,Partial").split(","));
        PAGES.put(2, ("Attachment,Attachments,AttName,AttSize,Att0Id,AttMethod,AttRemoved,Body,BodySize,BodyTruncated,DateReceived," +
                "DisplayName,DisplayTo,Importance,MessageClass,Subject,Read,To,Cc,From,ReplyTo,AllDayEvent,Categories,Category,DtStamp," +
                "EndTime,InstanceType,BusyStatus,Location,MeetingRequest,Organizer,RecurrenceId,Reminder,ResponseRequested,Recurrences," +
                "Recurrence,Type,Until,Occurrences,Interval,DayOfWeek,DayOfMonth,WeekOfMonth,MonthOfYear,StartTime,Sensitivity,TimeZone," +
                "GlobalObjId,ThreadTopic,MIMEData,MIMETruncated,MIMESize,InternetCPID,Flag,FlagStatus,ContentClass,FlagType,CompleteTime," +
                "DisallowNewTimeProposal").split(","));
        PAGES.put(6, "GetItemEstimate,Version,Collections,Collection,Class,CollectionId,DateTime,Estimate,Response,Status".split(","));
        PAGES.put(7, ("Folders,Folder,DisplayName,ServerId,ParentId,Type,Response,Status,ContentClass,Changes,Add,Delete,Update,SyncKey," +
                "FolderCreate,FolderDelete,FolderUpdate,FolderSync,Count").split(","));
        PAGES.put(13, "Ping,AutdState,Status,HeartbeatInterval,Folders,Folder,Id,Class,MaxFolders".split(","));
        PAGES.put(14, ("Provision,Policies,Policy,PolicyType,PolicyKey,Data,Status,RemoteWipe,EASProvisionDoc,DevicePasswordEnabled," +
                "AlphanumericDevicePasswordRequired,RequireStorageCardEncryption,PasswordRecoveryEnabled,-,AttachmentsEnabled," +
                "MinDevicePasswordLength,MaxInactivityTimeDeviceLock,MaxDevicePasswordFailedAttempts,MaxAttachmentSize," +
                "AllowSimpleDevicePassword,DevicePasswordExpiration,DevicePasswordHistory,AllowStorageCard,AllowCamera," +
                "RequireDeviceEncryption,AllowUnsignedApplications,AllowUnsignedInstallationPackages,MinDevicePasswordComplexCharacters," +
                "AllowWiFi,AllowTextMessaging,AllowPOPIMAPEmail,AllowBluetooth,AllowIrDA,RequireManualSyncWhenRoaming,AllowDesktopSync," +
                "MaxCalendarAgeFilter,AllowHTMLEmail,MaxEmailAgeFilter,MaxEmailBodyTruncationSize,MaxEmailHTMLBodyTruncationSize," +
                "RequireSignedSMIMEMessages,RequireEncryptedSMIMEMessages,RequireSignedSMIMEAlgorithm,RequireEncryptionSMIMEAlgorithm," +
                "AllowSMIMEEncryptionAlgorithmNegotiation,AllowSMIMESoftCerts,AllowBrowser,AllowConsumerEmail,AllowRemoteDesktop," +
                "AllowInternetSharing,UnapprovedInROMApplicationList,ApplicationName,ApprovedApplicationList,Hash").split(","));
        PAGES.put(17, ("BodyPreference,Type,TruncationSize,AllOrNone,-,Body,Data,EstimatedDataSize,Truncated,Attachments,Attachment," +
                "DisplayName,FileReference,Method,ContentId,ContentLocation,IsInline,NativeBodyType,ContentType,Preview").split(","));
        PAGES.put(20, ("ItemOperations,Fetch,Store,Options,Range,Total,Properties,Data,Status,Response,Version,Schema,Part," +
                "EmptyFolderContents,DeleteSubFolders,UserName,Password,Move,DstFldId,ConversationId,MoveAlways").split(","));
    }

    static int tagId(int page, String name) {
        String[] t = PAGES.get(page);
        for (int i = 0; i < t.length; i++) if (t[i].equals(name)) return 0x05 + i;
        throw new IllegalArgumentException("tag " + name + " not on page " + page);
    }

    static String tagName(int page, int id) {
        String[] t = PAGES.get(page);
        int i = id - 0x05;
        if (t == null || i < 0 || i >= t.length) return "U_" + page + "_" + id;
        return t[i];
    }

    static final class N {
        final int page; final String name; final List<N> kids = new ArrayList<>();
        ByteArrayOutputStream rawBuf; int tok; // 0x03 STR_I, 0xC3 OPAQUE
        N(int page, String name) { this.page = page; this.name = name; }
        byte[] raw() { return rawBuf == null ? null : rawBuf.toByteArray(); }
        String text() { return rawBuf == null ? null : new String(rawBuf.toByteArray(), UTF8); }
        N child(String n) { for (N k : kids) if (k.name.equals(n)) return k; return null; }
        List<N> children(String n) { List<N> r = new ArrayList<>(); for (N k : kids) if (k.name.equals(n)) r.add(k); return r; }
        N find(String n) { if (name.equals(n)) return this; for (N k : kids) { N r = k.find(n); if (r != null) return r; } return null; }
        N find(int p, String n) { if (page == p && name.equals(n)) return this; for (N k : kids) { N r = k.find(p, n); if (r != null) return r; } return null; }
        void findAll(String n, List<N> acc) { if (name.equals(n)) acc.add(this); for (N k : kids) k.findAll(n, acc); }
        String val(String n) { N x = find(n); return x == null ? null : x.text(); }
        String cval(String n) { N x = child(n); return x == null ? null : x.text(); }
    }

    static N e(int page, String name, Object... kids) {
        N n = new N(page, name);
        for (Object k : kids) {
            if (k instanceof N) n.kids.add((N) k);
            else if (k instanceof String) { n.rawBuf = new ByteArrayOutputStream(); n.rawBuf.writeBytes(((String) k).getBytes(UTF8)); n.tok = 3; }
        }
        return n;
    }

    static byte[] enc(N root) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(3); o.write(1); o.write(0x6A); o.write(0);
        int[] cp = {-1};
        w(root, o, cp);
        return o.toByteArray();
    }

    static void w(N n, ByteArrayOutputStream o, int[] cp) {
        if (n.page != cp[0]) { o.write(0); o.write(n.page); cp[0] = n.page; }
        boolean content = n.rawBuf != null || !n.kids.isEmpty();
        o.write(tagId(n.page, n.name) | (content ? 0x40 : 0));
        if (!content) return;
        if (n.rawBuf != null) { o.write(3); o.writeBytes(n.raw()); o.write(0); }
        for (N k : n.kids) w(k, o, cp);
        o.write(1);
    }

    static int mb(InputStream in) throws IOException {
        int r = 0;
        for (int i = 0; i < 5; i++) {
            int b = in.read();
            if (b < 0) throw new EOFException();
            r = (r << 7) | (b & 0x7F);
            if ((b & 0x80) == 0) return r;
        }
        throw new IOException("mb too long");
    }

    static N dec(byte[] data) throws IOException {
        if (data == null || data.length == 0) return null;
        ByteArrayInputStream in = new ByteArrayInputStream(data);
        in.read(); mb(in); mb(in);
        int st = mb(in);
        in.skipNBytes(st);
        N root = new N(-1, "#root");
        Deque<N> stack = new ArrayDeque<>();
        stack.push(root);
        int page = 0;
        while (true) {
            int t = in.read();
            if (t < 0) break;
            if (t == 0x00) { page = in.read(); continue; }
            if (t == 0x01) { if (stack.size() > 1) stack.pop(); continue; }
            N cur = stack.peek();
            if (t == 0x03) {
                if (cur.rawBuf == null) cur.rawBuf = new ByteArrayOutputStream();
                int b;
                while ((b = in.read()) > 0) cur.rawBuf.write(b);
                cur.tok = cur.tok == 0 ? 3 : cur.tok;
                continue;
            }
            if (t == 0xC3) {
                int len = mb(in);
                byte[] b = in.readNBytes(len);
                if (cur.rawBuf == null) cur.rawBuf = new ByteArrayOutputStream();
                cur.rawBuf.writeBytes(b);
                cur.tok = 0xC3;
                continue;
            }
            if (t == 0x02) {
                int cpt = mb(in);
                if (cur.rawBuf == null) cur.rawBuf = new ByteArrayOutputStream();
                cur.rawBuf.writeBytes(new String(Character.toChars(cpt)).getBytes(UTF8));
                continue;
            }
            int id = t & 0x3F;
            N n = new N(page, tagName(page, id));
            cur.kids.add(n);
            if ((t & 0x80) != 0) { int a; while ((a = in.read()) != 0x01 && a >= 0) { } }
            if ((t & 0x40) != 0) stack.push(n);
        }
        return root.kids.isEmpty() ? root : root.kids.get(0);
    }

    // ============================== HTTP ==============================

    static final class Resp {
        int code; Map<String, List<String>> headers = new HashMap<>(); byte[] body = new byte[0]; long ms; String error;
        String h(String name) {
            for (Map.Entry<String, List<String>> en : headers.entrySet())
                if (en.getKey() != null && en.getKey().equalsIgnoreCase(name)) return String.join(", ", en.getValue());
            return null;
        }
    }

    static String basic() {
        return "Basic " + Base64.getEncoder().encodeToString((DOMAIN + "\\" + USER + ":" + pass).getBytes(UTF8));
    }

    static Resp http(String method, String url, byte[] body, Map<String, String> hdr, boolean auth) {
        Resp r = new Resp();
        long t0 = System.nanoTime();
        try {
            HttpsURLConnection c = (HttpsURLConnection) new URL(url).openConnection();
            c.setSSLSocketFactory(sf);
            c.setHostnameVerifier((h, s) -> true); // сертификат проверяется PinTM по CN и сроку
            c.setRequestMethod(method);
            c.setConnectTimeout(15_000);
            c.setReadTimeout(240_000);
            c.setInstanceFollowRedirects(false);
            c.setUseCaches(false);
            if (auth) c.setRequestProperty("Authorization", basic());
            for (Map.Entry<String, String> en : hdr.entrySet()) c.setRequestProperty(en.getKey(), en.getValue());
            if (body != null) {
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(body.length);
                try (OutputStream o = c.getOutputStream()) { o.write(body); }
            }
            r.code = c.getResponseCode();
            r.headers = c.getHeaderFields();
            InputStream in = r.code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in != null) try (in) { r.body = in.readAllBytes(); }
        } catch (Exception ex) {
            r.error = ex.getClass().getSimpleName() + ": " + ex.getMessage();
        }
        r.ms = (System.nanoTime() - t0) / 1_000_000;
        return r;
    }

    static String easUrl(String cmd, String extra) {
        return BASE + "/Microsoft-Server-ActiveSync/default.eas?Cmd=" + cmd +
                "&User=" + URLEncoder.encode(DOMAIN + "\\" + USER, UTF8) +
                "&DeviceId=" + DEVICE_ID + "&DeviceType=" + DEVICE_TYPE + (extra == null ? "" : extra);
    }

    static Map<String, String> easHeaders(boolean withPolicy) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("MS-ASProtocolVersion", "12.1");
        h.put("Content-Type", "application/vnd.ms-sync.wbxml");
        h.put("User-Agent", UA);
        if (withPolicy && policyKey != null) h.put("X-MS-PolicyKey", policyKey);
        return h;
    }

    static Resp eas(String cmd, N req) { return eas(cmd, req, true, Map.of()); }

    static Resp eas(String cmd, N req, boolean withPolicy, Map<String, String> extra) {
        Map<String, String> h = easHeaders(withPolicy);
        h.putAll(extra);
        return http("POST", easUrl(cmd, null), enc(req), h, true);
    }

    static N parse(Resp r) {
        try { return dec(r.body); } catch (Exception ex) { return null; }
    }

    // ============================== TLS ==============================

    static final class PinTM implements X509TrustManager {
        public void checkClientTrusted(X509Certificate[] c, String a) throws CertificateException { throw new CertificateException("client"); }
        public void checkServerTrusted(X509Certificate[] chain, String a) throws CertificateException {
            X509Certificate c = chain[0];
            if (!c.getSubjectX500Principal().getName().contains(EXPECTED_CN.substring(3)))
                throw new CertificateException("Неожиданный сертификат: " + c.getSubjectX500Principal());
            c.checkValidity();
            seenCert = c;
        }
        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }

    static SSLContext ctx(String proto) throws Exception {
        SSLContext ctx = SSLContext.getInstance(proto);
        ctx.init(null, new TrustManager[]{new PinTM()}, null);
        return ctx;
    }

    static void sectionTls() throws Exception {
        h1("T1. TLS и сертификат");
        for (String[] p : new String[][]{{"TLSv1.2"}, {"TLSv1.1"}, {"TLSv1"}}) {
            try {
                SSLContext c = ctx("TLS");
                try (SSLSocket s = (SSLSocket) c.getSocketFactory().createSocket()) {
                    s.connect(new InetSocketAddress(HOST, 443), 10_000);
                    s.setSoTimeout(10_000);
                    s.setEnabledProtocols(p);
                    s.startHandshake();
                    SSLSession ss = s.getSession();
                    out.printf("  только %-8s: рукопожатие OK, протокол=%s, шифр=%s%n", p[0], ss.getProtocol(), ss.getCipherSuite());
                }
            } catch (Exception ex) {
                out.printf("  только %-8s: ОТКАЗ (%s)%n", p[0], ex.getClass().getSimpleName());
            }
        }
        if (seenCert != null) {
            X509Certificate c = seenCert;
            // Имена в сертификате — идентификаторы организации: в журнал только их число и тип
            out.println("  Subject совпадает с IWO_AUDIT_CERT_CN: да; выпущен внутренним CA: "
                    + !c.getIssuerX500Principal().equals(c.getSubjectX500Principal()));
            out.println("  Срок:    " + c.getNotBefore() + " .. " + c.getNotAfter());
            out.println("  Алгоритм подписи: " + c.getSigAlgName() + ", ключ: " + c.getPublicKey().getAlgorithm()
                    + " " + (c.getPublicKey() instanceof java.security.interfaces.RSAPublicKey
                    ? ((java.security.interfaces.RSAPublicKey) c.getPublicKey()).getModulus().bitLength() + " бит" : ""));
            java.util.Collection<java.util.List<?>> san = c.getSubjectAlternativeNames();
            out.println("  SAN: DNS-имён " + (san == null ? 0 : san.size()) + ", IP-адресов " + (san == null ? 0 : san.stream().filter(e -> Integer.valueOf(7).equals(e.get(0))).count()));
            out.println("  Адрес подключения в SAN: нет (подключение по IP)");
            out.println("  Самоподписан (issuer == subject): " + c.getIssuerX500Principal().equals(c.getSubjectX500Principal()));
        }
    }

    // ============================== Порты ==============================

    static void sectionPorts() {
        h1("T2. Почтовые порты (без отправки учётных данных)");
        int[] ports = {25, 110, 143, 465, 587, 993, 995, 80};
        for (int p : ports) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(HOST, p), 4000);
                s.setSoTimeout(5000);
                String info = "открыт";
                if (p == 143 || p == 110 || p == 25 || p == 587) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1));
                    Writer wr = new OutputStreamWriter(s.getOutputStream(), StandardCharsets.ISO_8859_1);
                    String banner = br.readLine();
                    info += "; баннер: " + redactHost(banner);
                    if (p == 143) {
                        wr.write("a1 CAPABILITY\r\n"); wr.flush();
                        String cap = readUntil(br, "a1 ");
                        info += "; CAPABILITY: " + cap;
                        wr.write("a2 LOGOUT\r\n"); wr.flush();
                    } else if (p == 110) {
                        wr.write("CAPA\r\n"); wr.flush();
                        String cap = readPopMulti(br);
                        info += "; CAPA: " + cap;
                        wr.write("QUIT\r\n"); wr.flush();
                    } else {
                        wr.write("EHLO audit.invalid\r\n"); wr.flush();
                        String ehlo = readSmtp(br);
                        info += "; EHLO: " + redactHost(ehlo);
                        wr.write("QUIT\r\n"); wr.flush();
                    }
                }
                out.printf("  порт %-4d %s%n", p, info);
            } catch (Exception ex) {
                out.printf("  порт %-4d закрыт/фильтруется (%s)%n", p, ex.getClass().getSimpleName());
            }
        }
    }

    /** Имена хостов и IP-адреса из баннеров — в «<host>»: журналы лежат в репозитории. */
    static String redactHost(String s) {
        return s == null ? "null" : s.replaceAll("[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+", "<host>").replaceAll("\\s+", " ").trim();
    }

    static String readUntil(BufferedReader br, String tagPrefix) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            String l = br.readLine();
            if (l == null) break;
            sb.append(l.trim()).append(" | ");
            if (l.startsWith(tagPrefix)) break;
        }
        return sb.toString();
    }

    static String readPopMulti(BufferedReader br) throws IOException {
        StringBuilder sb = new StringBuilder();
        String first = br.readLine();
        sb.append(first);
        if (first != null && first.startsWith("+OK")) {
            for (int i = 0; i < 30; i++) { String l = br.readLine(); if (l == null || l.equals(".")) break; sb.append(" ").append(l); }
        }
        return sb.toString();
    }

    static String readSmtp(BufferedReader br) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            String l = br.readLine();
            if (l == null) break;
            sb.append(l.length() > 4 ? l.substring(4) : l).append(" ");
            if (l.length() > 3 && l.charAt(3) == ' ') break;
        }
        return sb.toString().trim();
    }

    // ============================== EAS ==============================

    static final class Folder { String id, parent, name; int type; }
    static final List<Folder> folders = new ArrayList<>();

    static final class Item {
        String serverId, fp, messageClass, cpid, nativeType, bodyTrunc, dateReceived;
        long bodyEst; int bodyType; int attCount; long attMax; long attSum; String attMaxRef; long attMinSize = Long.MAX_VALUE; String attMinRef;
        String subject;
        /** {FileReference, DisplayName, EstimatedDataSize, ContentId, IsInline} — для att2 (сопоставление с EWS). */
        final List<String[]> attList = new ArrayList<>();
    }

    static void sectionOptions() {
        h1("T3. OPTIONS и схемы аутентификации");
        Resp r = http("OPTIONS", BASE + "/Microsoft-Server-ActiveSync", null, Map.of("User-Agent", UA), true);
        out.printf("  OPTIONS c Basic: HTTP %d за %d мс%n", r.code, r.ms);
        out.println("  MS-ASProtocolVersions: " + r.h("MS-ASProtocolVersions"));
        out.println("  MS-ASProtocolCommands: " + r.h("MS-ASProtocolCommands"));
        out.println("  MS-Server-ActiveSync: " + r.h("MS-Server-ActiveSync") + ", Server: " + r.h("Server"));
        Resp u = http("OPTIONS", BASE + "/Microsoft-Server-ActiveSync", null, Map.of("User-Agent", UA), false);
        out.printf("  OPTIONS без учётных данных: HTTP %d, WWW-Authenticate: %s%n", u.code, u.h("WWW-Authenticate"));
        Resp ews = http("POST", BASE + "/EWS/Exchange.asmx", "<x/>".getBytes(UTF8), Map.of("Content-Type", "text/xml; charset=utf-8"), false);
        out.printf("  EWS без учётных данных: HTTP %d, WWW-Authenticate: %s%n", ews.code, ews.h("WWW-Authenticate"));
    }

    static boolean sectionProvision() {
        h1("T4. Provision (двухфазный, MS-ASPROV) и политика сервера");
        N req1 = e(14, "Provision", e(14, "Policies", e(14, "Policy", e(14, "PolicyType", "MS-EAS-Provisioning-WBXML"))));
        Resp r0 = eas("FolderSync", e(7, "FolderSync", e(7, "SyncKey", "0")), false, Map.of());
        out.printf("  FolderSync до Provision (без X-MS-PolicyKey): HTTP %d%n", r0.code);
        Resp r1 = eas("Provision", req1, false, Map.of());
        N x1 = parse(r1);
        out.printf("  Фаза 1: HTTP %d за %d мс, Status=%s%n", r1.code, r1.ms, x1 == null ? null : x1.cval("Status"));
        if (x1 == null) return false;
        if (x1.find("RemoteWipe") != null) { out.println("  !!! Сервер прислал RemoteWipe. Остановка без подтверждения."); return false; }
        N policy = x1.find(14, "Policy");
        String tempKey = policy == null ? null : policy.cval("PolicyKey");
        out.println("  Status политики: " + (policy == null ? null : policy.cval("Status")) + ", временный PolicyKey получен: " + (tempKey != null));
        N doc = x1.find("EASProvisionDoc");
        if (doc != null) {
            out.println("  Элементы политики (EASProvisionDoc):");
            for (N k : doc.kids) {
                String v = k.text();
                if (!k.kids.isEmpty()) v = "[" + k.kids.size() + " вложенных]";
                out.printf("    %-45s = %s%n", k.name, v == null ? "(пусто)" : v);
            }
        }
        N req2 = e(14, "Provision", e(14, "Policies", e(14, "Policy",
                e(14, "PolicyType", "MS-EAS-Provisioning-WBXML"), e(14, "PolicyKey", tempKey == null ? "0" : tempKey), e(14, "Status", "1"))));
        Map<String, String> hk = new HashMap<>();
        if (tempKey != null) hk.put("X-MS-PolicyKey", tempKey);
        Resp r2 = eas("Provision", req2, false, hk);
        N x2 = parse(r2);
        N p2 = x2 == null ? null : x2.find(14, "Policy");
        policyKey = p2 == null ? null : p2.cval("PolicyKey");
        out.printf("  Фаза 2 (подтверждение): HTTP %d, Status=%s, итоговый PolicyKey получен: %b, совпадает с временным: %b%n",
                r2.code, x2 == null ? null : x2.cval("Status"), policyKey != null, Objects.equals(policyKey, tempKey));
        return policyKey != null;
    }

    static void sectionFolderSync() {
        h1("T5. FolderSync");
        Resp r = eas("FolderSync", e(7, "FolderSync", e(7, "SyncKey", "0")));
        N x = parse(r);
        out.printf("  HTTP %d за %d мс, %d байт, Status=%s%n", r.code, r.ms, r.body.length, x == null ? null : x.cval("Status"));
        if (x == null) return;
        List<N> adds = new ArrayList<>();
        x.findAll("Add", adds);
        Map<Integer, Integer> byType = new TreeMap<>();
        for (N a : adds) {
            Folder f = new Folder();
            f.id = a.cval("ServerId"); f.parent = a.cval("ParentId"); f.name = a.cval("DisplayName");
            f.type = Integer.parseInt(Objects.requireNonNullElse(a.cval("Type"), "0"));
            folders.add(f);
            byType.merge(f.type, 1, Integer::sum);
        }
        out.println("  Папок: " + folders.size() + ", по типам (MS-ASCMD FolderHierarchy Type): " + byType);
        out.println("  Junk найден по имени: " + (junk() != null));
    }

    static Folder byType(int t) { for (Folder f : folders) if (f.type == t) return f; return null; }

    static Folder junk() {
        for (Folder f : folders) {
            String n = f.name == null ? "" : f.name.toLowerCase(Locale.ROOT);
            if (n.contains("junk") || n.contains("нежелательн") || n.contains("спам") || n.contains("spam")) return f;
        }
        return null;
    }

    static String initialSync(Folder f) {
        Resp r = eas("Sync", e(0, "Sync", e(0, "Collections", e(0, "Collection", e(0, "SyncKey", "0"), e(0, "CollectionId", f.id)))));
        N x = parse(r);
        if (x == null) { out.printf("  Начальный Sync: HTTP %d, тело не WBXML (%d байт)%n", r.code, r.body.length); return null; }
        N col = x.find(0, "Collection");
        return col == null ? null : col.cval("SyncKey");
    }

    static N syncReq(String key, String colId, int window, Integer bodyType, Integer trunc, boolean mime) {
        List<Object> opts = new ArrayList<>();
        opts.add(e(0, "FilterType", "0"));
        if (bodyType != null) {
            List<Object> bp = new ArrayList<>();
            bp.add(e(17, "Type", String.valueOf(bodyType)));
            if (trunc != null) bp.add(e(17, "TruncationSize", String.valueOf(trunc)));
            opts.add(e(17, "BodyPreference", bp.toArray()));
        }
        if (mime) opts.add(e(0, "MIMESupport", "2"));
        return e(0, "Sync", e(0, "Collections", e(0, "Collection",
                e(0, "SyncKey", key), e(0, "CollectionId", colId), e(0, "DeletesAsMoves", "1"), e(0, "GetChanges"),
                e(0, "WindowSize", String.valueOf(window)), e(0, "Options", opts.toArray()))));
    }

    static final class PassStats { int pages, items; long bytes, ms, maxPageBytes, maxPageMs; boolean capped; String lastStatus; Map<String, Integer> bodyTypes = new TreeMap<>(); }

    static PassStats fullPass(Folder f, int window, Integer bodyType, Integer trunc, boolean mime, int capItems, List<Item> sink) throws Exception {
        PassStats ps = new PassStats();
        String key = initialSync(f);
        if (key == null) { ps.lastStatus = "нет SyncKey"; return ps; }
        long deadline = System.currentTimeMillis() + 15 * 60_000L;
        while (true) {
            Resp r = eas("Sync", syncReq(key, f.id, window, bodyType, trunc, mime));
            ps.pages++;
            ps.bytes += r.body.length; ps.ms += r.ms;
            ps.maxPageBytes = Math.max(ps.maxPageBytes, r.body.length); ps.maxPageMs = Math.max(ps.maxPageMs, r.ms);
            if (r.code != 200) { ps.lastStatus = "HTTP " + r.code + (r.error == null ? "" : " " + r.error); break; }
            N x = parse(r);
            if (x == null) { ps.lastStatus = r.body.length == 0 ? "пустой ответ 200" : "не WBXML"; break; }
            N col = x.find(0, "Collection");
            if (col == null) { ps.lastStatus = "Sync Status=" + x.cval("Status"); break; }
            ps.lastStatus = "Status=" + col.cval("Status");
            String nk = col.cval("SyncKey");
            if (nk != null) key = nk;
            N cmds = col.child("Commands");
            if (cmds != null) for (N a : cmds.children("Add")) {
                ps.items++;
                if (sink != null) sink.add(item(a));
                N body = a.find(17, "Body");
                if (body != null) ps.bodyTypes.merge("Type " + body.cval("Type"), 1, Integer::sum);
            }
            boolean more = col.child("MoreAvailable") != null;
            if (!more) break;
            if (ps.items >= capItems || System.currentTimeMillis() > deadline) { ps.capped = true; break; }
        }
        return ps;
    }

    static Item item(N add) throws Exception {
        Item it = new Item();
        it.serverId = add.cval("ServerId");
        N ad = add.child("ApplicationData");
        if (ad == null) return it;
        it.messageClass = ad.cval("MessageClass");
        it.cpid = ad.cval("InternetCPID");
        it.dateReceived = ad.cval("DateReceived");
        N nb = ad.find(17, "NativeBodyType");
        it.nativeType = nb == null ? null : nb.text();
        N body = ad.find(17, "Body");
        if (body != null) {
            it.bodyEst = parseLong(body.cval("EstimatedDataSize"));
            it.bodyTrunc = body.cval("Truncated");
            it.bodyType = (int) parseLong(body.cval("Type"));
        }
        List<N> atts = new ArrayList<>();
        ad.findAll("Attachment", atts);
        for (N at : atts) {
            if (at.page != 17) continue;
            long sz = parseLong(at.cval("EstimatedDataSize"));
            it.attCount++; it.attSum += sz;
            String ref = at.cval("FileReference");
            if (ref != null) allAtts.add(new long[]{sz, allAttRefs.size()});
            if (ref != null) allAttRefs.add(ref);
            if (sz > it.attMax) { it.attMax = sz; it.attMaxRef = ref; }
            if (sz > 0 && sz < it.attMinSize) { it.attMinSize = sz; it.attMinRef = ref; }
            if (ref != null) it.attList.add(new String[]{ref, at.cval("DisplayName"), String.valueOf(sz), at.cval("ContentId"), at.cval("IsInline")});
        }
        it.subject = ad.cval("Subject");
        String fpSrc = it.dateReceived + "|" + ad.cval("Subject") + "|" + ad.cval("From") + "|" + it.bodyEst;
        it.fp = hex(MessageDigest.getInstance("SHA-256").digest(fpSrc.getBytes(UTF8)));
        return it;
    }

    static long parseLong(String s) { try { return s == null ? 0 : Long.parseLong(s.trim()); } catch (Exception e) { return 0; } }

    static void printPass(String label, PassStats ps) {
        out.printf("  %s: элементов %d, страниц %d, байт %,d, суммарно %,d мс, худшая страница %,d байт / %,d мс, %s%s, типы тел %s%n",
                label, ps.items, ps.pages, ps.bytes, ps.ms, ps.maxPageBytes, ps.maxPageMs, ps.lastStatus, ps.capped ? ", ОСТАНОВЛЕНО ПО ЛИМИТУ" : "", ps.bodyTypes);
    }

    static List<Item> inboxItems = new ArrayList<>();
    static List<Item> sentItems = new ArrayList<>();

    static void sectionMailboxSize() throws Exception {
        h1("T6. Полная синхронизация папок как в приложении (FilterType=0, WindowSize=50, Type=2, TruncationSize=10240)");
        Folder inbox = byType(2), sent = byType(5), drafts = byType(3), deleted = byType(4), jk = junk();
        if (inbox != null) printPass("Входящие", fullPass(inbox, 50, 2, 10240, false, 20_000, inboxItems));
        if (sent != null) {
            printPass("Отправленные (MIMESupport=2, как при инкрементальной синхронизации)", fullPass(sent, 50, 2, 10240, true, 3_000, sentItems));
        }
        if (drafts != null) printPass("Черновики (как приложение: Type=2 без TruncationSize)", fullPass(drafts, 50, 2, null, false, 5_000, null));
        List<Item> del = new ArrayList<>();
        if (deleted != null) printPass("Удалённые (Type=1, TruncationSize=0)", fullPass(deleted, 200, 1, 0, false, 50_000, del));
        List<Item> jl = new ArrayList<>();
        if (jk != null) printPass("Нежелательная (Type=1, TruncationSize=0)", fullPass(jk, 200, 1, 0, false, 50_000, jl));
        ageReport("Удалённые", del);
        ageReport("Нежелательная", jl);
        sizeReport("Входящие", inboxItems);
        sizeReport("Отправленные", sentItems);
    }

    static void ageReport(String label, List<Item> items) {
        if (items.isEmpty()) { out.println("  " + label + ": элементов 0"); return; }
        long now = System.currentTimeMillis();
        int lt7 = 0, lt30 = 0, lt60 = 0, ge60 = 0, unknown = 0;
        for (Item it : items) {
            long t = parseIso(it.dateReceived);
            if (t <= 0) { unknown++; continue; }
            long days = (now - t) / 86_400_000L;
            if (days < 7) lt7++; else if (days < 30) lt30++; else if (days < 60) lt60++; else ge60++;
        }
        out.printf("  %s: всего %d; по дате получения: <7 дн %d, 7-29 дн %d, 30-59 дн %d, >=60 дн %d, без даты %d%n",
                label, items.size(), lt7, lt30, lt60, ge60, unknown);
    }

    static long parseIso(String s) {
        if (s == null) return 0;
        try { return java.time.Instant.parse(s.endsWith("Z") ? s : s + "Z").toEpochMilli(); } catch (Exception e) { return 0; }
    }

    static void sizeReport(String label, List<Item> items) {
        if (items.isEmpty()) return;
        long[] b = items.stream().mapToLong(i -> i.bodyEst).sorted().toArray();
        long[] a = items.stream().mapToLong(i -> i.attMax).sorted().toArray();
        int over1m = 0, over12m = 0, attOver12m = 0, attOver16m = 0, withAtt = 0;
        Map<String, Integer> cpids = new TreeMap<>(), natives = new TreeMap<>(), classes = new TreeMap<>();
        for (Item it : items) {
            if (it.bodyEst > 1_048_576) over1m++;
            if (it.bodyEst > 12_000_000) over12m++;
            if (it.attMax > 12_000_000) attOver12m++;
            if (it.attMax > 16_000_000) attOver16m++;
            if (it.attCount > 0) withAtt++;
            cpids.merge(String.valueOf(it.cpid), 1, Integer::sum);
            natives.merge(String.valueOf(it.nativeType), 1, Integer::sum);
            classes.merge(String.valueOf(it.messageClass), 1, Integer::sum);
        }
        out.printf("  %s: тело HTML EstimatedDataSize медиана %,d, p95 %,d, макс %,d байт; тел > 1 МиБ: %d; > 12 МБ: %d%n",
                label, b[b.length / 2], b[(int) (b.length * 0.95)], b[b.length - 1], over1m, over12m);
        out.printf("  %s: писем с вложениями %d; крупнейшее вложение %,d байт; вложений > 12 МБ: %d, > 16 МБ: %d%n",
                label, withAtt, a[a.length - 1], attOver12m, attOver16m);
        out.println("  " + label + ": InternetCPID " + cpids + "; NativeBodyType " + natives);
        out.println("  " + label + ": MessageClass " + classes);
    }

    static void sectionEstimate() {
        h1("T7. GetItemEstimate (формат 12.x: CollectionId, FilterType, SyncKey)");
        for (Folder f : new Folder[]{byType(2), byType(4), junk()}) {
            if (f == null) continue;
            String key = initialSync(f);
            N req = e(6, "GetItemEstimate", e(6, "Collections", e(6, "Collection",
                    e(6, "CollectionId", f.id), e(0, "FilterType", "0"), e(0, "SyncKey", key == null ? "0" : key))));
            Resp r = eas("GetItemEstimate", req);
            N x = parse(r);
            out.printf("  тип %d: HTTP %d, Status=%s, Estimate=%s%n", f.type, r.code,
                    x == null ? null : x.val("Status"), x == null ? null : x.val("Estimate"));
        }
    }

    static void sectionServerIdStability() throws Exception {
        h1("T8. Стабильность ServerId после сброса SyncKey=0 (основа PROTO-08 и reconcile)");
        Folder inbox = byType(2);
        if (inbox == null || inboxItems.isEmpty()) { out.println("  нет данных"); return; }
        List<Item> second = new ArrayList<>();
        PassStats ps = fullPass(inbox, 50, 2, 10240, false, 20_000, second);
        printPass("Повторный проход Входящих", ps);
        Map<String, String> firstByFp = new HashMap<>();
        int dupFp = 0;
        for (Item it : inboxItems) if (firstByFp.put(it.fp, it.serverId) != null) dupFp++;
        int same = 0, changed = 0, missing = 0;
        for (Item it : second) {
            String sid = firstByFp.get(it.fp);
            if (sid == null) missing++; else if (sid.equals(it.serverId)) same++; else changed++;
        }
        out.printf("  Совпали по отпечатку: ServerId тот же %d, ServerId другой %d, нет пары %d (неуникальных отпечатков %d)%n", same, changed, missing, dupFp);
        // Fetch по старому ServerId после сброса
        if (!inboxItems.isEmpty()) {
            String oldSid = inboxItems.get(0).serverId;
            N fx = fetchBody(inbox.id, oldSid, 1, 100, false, null);
            out.println("  ItemOperations Fetch по ServerId первого прохода после сброса: Status=" + fetchStatus(fx));
        }
        N bad = fetchBody(inbox.id, inbox.id + ":999999", 1, 100, false, null);
        out.println("  ItemOperations Fetch по несуществующему ServerId: Status=" + fetchStatus(bad) + " (приложение при 6 удаляет письмо локально)");
    }

    static N fetchBody(String colId, String sid, int type, int trunc, boolean mime, Resp[] keep) {
        List<Object> opts = new ArrayList<>();
        if (mime) opts.add(e(0, "MIMESupport", "2"));
        opts.add(e(17, "BodyPreference", e(17, "Type", String.valueOf(type)), e(17, "TruncationSize", String.valueOf(trunc))));
        N req = e(20, "ItemOperations", e(20, "Fetch", e(20, "Store", "Mailbox"), e(0, "CollectionId", colId), e(0, "ServerId", sid),
                e(20, "Options", opts.toArray())));
        Resp r = eas("ItemOperations", req);
        if (keep != null) keep[0] = r;
        return parse(r);
    }

    static String fetchStatus(N x) {
        if (x == null) return "нет ответа";
        N f = x.find(20, "Fetch");
        String fs = f == null ? null : f.cval("Status");
        return "top=" + x.cval("Status") + ", fetch=" + fs;
    }

    static void sectionBigBodies() {
        h1("T9. Крупные тела: ItemOperations Fetch Type=2, TruncationSize=1 048 576 (как loadEmailBody)");
        Folder inbox = byType(2);
        List<Item> all = new ArrayList<>(inboxItems);
        all.sort((a, b) -> Long.compare(b.bodyEst, a.bodyEst));
        int n = Math.min(6, all.size());
        for (int i = 0; i < n; i++) {
            Item it = all.get(i);
            Resp[] k = new Resp[1];
            N x = fetchBody(inbox.id, it.serverId, 2, 1_048_576, false, k);
            N body = x == null ? null : x.find(17, "Body");
            byte[] data = body == null || body.child("Data") == null ? new byte[0] : body.child("Data").raw();
            out.printf("  #%d: EstimatedDataSize %,d; получено Data %,d байт; Truncated=%s; ответ %,d байт за %,d мс; %s%n",
                    i + 1, it.bodyEst, data == null ? 0 : data.length, body == null ? null : body.cval("Truncated"),
                    k[0].body.length, k[0].ms, fetchStatus(x));
        }
        // Контроль механизма на малом лимите: письмо крупнее 10 КБ с лимитом 10 240
        for (Item it : all) {
            if (it.bodyEst > 20_000) {
                N x = fetchBody(inbox.id, it.serverId, 2, 10_240, false, null);
                N body = x == null ? null : x.find(17, "Body");
                byte[] data = body == null || body.child("Data") == null ? new byte[0] : body.child("Data").raw();
                out.printf("  Контроль: EstimatedDataSize %,d, TruncationSize 10 240 -> Data %,d байт, Truncated=%s%n",
                        it.bodyEst, data == null ? 0 : data.length, body == null ? null : body.cval("Truncated"));
                break;
            }
        }
    }

    static final Pattern CHARSET = Pattern.compile("(?i)charset\\s*=\\s*\"?([A-Za-z0-9_\\-:.]+)");
    static final Pattern CTE = Pattern.compile("(?im)^content-transfer-encoding:\\s*([A-Za-z0-9\\-]+)");

    static void sectionMime() {
        h1("T10. MIME через ItemOperations (MIMESupport=2, Type=4): токен WBXML и валидность UTF-8 (PROTO-02)");
        Folder inbox = byType(2), sent = byType(5);
        mimeSample("Входящие", inbox, inboxItems, 200);
        mimeSample("Отправленные", sent, sentItems, 60);
        // Контроль: тело Type=2 (HTML) всегда ли валидный UTF-8
        int valid = 0, invalid = 0;
        for (int i = 0; i < Math.min(40, inboxItems.size()); i++) {
            N x = fetchBody(inbox.id, inboxItems.get(i).serverId, 2, 262_144, false, null);
            N body = x == null ? null : x.find(17, "Body");
            N d = body == null ? null : body.child("Data");
            if (d == null) continue;
            byte[] raw = d.raw();
            boolean tr = "1".equals(body.cval("Truncated"));
            if (validUtf8(raw, tr)) valid++; else invalid++;
        }
        out.printf("  Контроль Type=2 (HTML) на 40 письмах: валидный UTF-8 %d, невалидный %d%n", valid, invalid);
    }

    static void mimeSample(String label, Folder f, List<Item> items, int max) {
        if (f == null || items.isEmpty()) return;
        int n = 0, opaque = 0, stri = 0, noData = 0, eightBit = 0, invalid = 0, truncated = 0, fffd = 0;
        Map<String, Integer> charsets = new TreeMap<>(), ctes = new TreeMap<>(), invalidCharsets = new TreeMap<>();
        for (Item it : items) {
            if (n >= max) break;
            n++;
            N x = fetchBody(f.id, it.serverId, 4, 262_144, true, null);
            N body = x == null ? null : x.find(17, "Body");
            N d = body == null ? null : body.child("Data");
            if (d == null) { noData++; continue; }
            if (d.tok == 0xC3) opaque++; else stri++;
            byte[] raw = d.raw();
            boolean tr = "1".equals(body.cval("Truncated"));
            if (tr) truncated++;
            boolean has8 = false;
            for (byte b : raw) if ((b & 0x80) != 0) { has8 = true; break; }
            String latin = new String(raw, StandardCharsets.ISO_8859_1);
            Set<String> cs = new TreeSet<>();
            Matcher m = CHARSET.matcher(latin);
            while (m.find()) cs.add(m.group(1).toLowerCase(Locale.ROOT));
            for (String c : cs) charsets.merge(c, 1, Integer::sum);
            Set<String> ce = new TreeSet<>();
            Matcher m2 = CTE.matcher(latin);
            while (m2.find()) ce.add(m2.group(1).toLowerCase(Locale.ROOT));
            for (String c : ce) ctes.merge(c, 1, Integer::sum);
            if (has8) {
                eightBit++;
                if (!validUtf8(raw, tr)) {
                    invalid++;
                    for (String c : cs) invalidCharsets.merge(c, 1, Integer::sum);
                    String asApp = new String(raw, UTF8); // как WbxmlParser: String(bytes, UTF_8)
                    if (asApp.indexOf('\uFFFD') >= 0) fffd++;
                }
            }
        }
        out.printf("  %s: проверено %d; Data как OPAQUE %d, как STR_I %d, без Data %d; MIME обрезан на 256 КБ: %d%n",
                label, n, opaque, stri, noData, truncated);
        out.printf("  %s: с 8-битными байтами %d; из них НЕвалидный UTF-8 %d (после new String(bytes, UTF_8) появляется U+FFFD: %d)%n",
                label, eightBit, invalid, fffd);
        out.println("  " + label + ": charset в MIME " + charsets);
        out.println("  " + label + ": Content-Transfer-Encoding " + ctes);
        out.println("  " + label + ": charset у писем с невалидным UTF-8 " + invalidCharsets);
    }

    static boolean validUtf8(byte[] raw, boolean truncated) {
        int len = raw.length;
        if (truncated) len = Math.max(0, len - 4);
        CharsetDecoder d = UTF8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        try { d.decode(ByteBuffer.wrap(raw, 0, len)); return true; } catch (CharacterCodingException e) { return false; }
    }

    static void sectionAttachments() {
        h1("T11. Вложения: ItemOperations по FileReference, GetAttachment, MultiPart (PROTO-13, MAIL-04)");
        Item big = null, small = null;
        for (Item it : inboxItems) {
            if (it.attMaxRef != null && it.attMax <= 40_000_000 && (big == null || it.attMax > big.attMax)) big = it;
            if (it.attMinRef != null && it.attMinSize < 200_000 && (small == null || it.attMinSize < small.attMinSize)) small = it;
        }
        if (small != null) {
            String ref = small.attMinRef;
            String decoded = URLDecoder.decode(ref.replace("+", "%2B"), UTF8);
            boolean looksEncoded = ref.contains("%");
            out.printf("  Малое вложение %,d байт; FileReference содержит %%-кодирование: %b%n", small.attMinSize, looksEncoded);
            attFetch("ItemOperations, ref как прислал сервер", ref, Map.of(), small.attMinSize);
            if (!decoded.equals(ref)) attFetch("ItemOperations, ref после URLDecoder (как 2-я попытка приложения)", decoded, Map.of(), small.attMinSize);
            String appDecoded = URLDecoder.decode(ref, UTF8); // как в приложении: '+' -> ' '
            if (!appDecoded.equals(ref)) attFetch("ItemOperations, ref после URLDecoder.decode как в коде ('+' станет пробелом)", appDecoded, Map.of(), small.attMinSize);
            getAtt("GetAttachment GET, User без домена, имя как decode() (как приложение, 1-я попытка)", "GET", appDecoded, false, small.attMinSize);
            getAtt("GetAttachment GET, User без домена, имя как прислал сервер (как приложение, 2-я попытка)", "GET", ref, false, small.attMinSize);
            getAtt("GetAttachment POST, User=домен\\пользователь, имя URL-кодировано (по MS-ASCMD)", "POST", URLEncoder.encode(ref, UTF8), true, small.attMinSize);
        }
        if (big != null) {
            out.printf("  Крупнейшее вложение во Входящих: %,d байт%n", big.attMax);
            attFetch("ItemOperations, обычный ответ", big.attMaxRef, Map.of(), big.attMax);
            attFetch("ItemOperations, MS-ASAcceptMultiPart: T", big.attMaxRef, Map.of("MS-ASAcceptMultiPart", "T"), big.attMax);
        }
    }

    static void attFetch(String label, String ref, Map<String, String> extra, long expected) {
        N req = e(20, "ItemOperations", e(20, "Fetch", e(20, "Store", "Mailbox"), e(17, "FileReference", ref)));
        Resp r = eas("ItemOperations", req, true, extra);
        String ct = r.h("Content-Type");
        String status;
        long decodedLen = -1;
        int tok = -1;
        if (ct != null && ct.contains("multipart")) {
            status = "multipart, " + r.body.length + " байт";
        } else {
            N x = parse(r);
            status = fetchStatus(x);
            N d = x == null ? null : x.find(20, "Data");
            if (d != null) {
                tok = d.tok;
                try { decodedLen = Base64.getMimeDecoder().decode(d.raw()).length; } catch (Exception ex) { decodedLen = -2; }
            }
        }
        out.printf("    %s: HTTP %d, %s, Content-Type %s, ответ %,d байт (x%.2f к размеру), Data токен %s, после base64 %,d байт, %,d мс%n",
                label, r.code, status, ct, r.body.length, expected > 0 ? (double) r.body.length / expected : 0,
                tok == 0xC3 ? "OPAQUE" : tok == 3 ? "STR_I" : "-", decodedLen, r.ms);
    }

    static void getAtt(String label, String method, String name, boolean withDomain, long expected) {
        String user = withDomain ? URLEncoder.encode(DOMAIN + "\\" + USER, UTF8) : URLEncoder.encode(USER, UTF8);
        String url = BASE + "/Microsoft-Server-ActiveSync?Cmd=GetAttachment&AttachmentName=" + name + "&User=" + user +
                "&DeviceId=" + DEVICE_ID + "&DeviceType=" + DEVICE_TYPE;
        Map<String, String> h = easHeaders(true);
        h.remove("Content-Type");
        Resp r;
        try {
            r = http(method, url, method.equals("POST") ? new byte[0] : null, h, true);
        } catch (Exception ex) { r = new Resp(); r.error = ex.toString(); }
        out.printf("    %s: HTTP %d, %,d байт (ожидалось ~%,d)%s%n", label, r.code, r.body.length, expected, r.error == null ? "" : ", " + r.error);
    }

    static void sectionErrors() {
        h1("T12. Ошибочные состояния протокола (PROTO-03, PROTO-04/05)");
        Folder inbox = byType(2);
        if (inbox != null) {
            Resp r = eas("Sync", syncReq("{5C9A7C2B-0000-0000-0000-000000000000}7", inbox.id, 5, 2, 10240, false));
            N x = parse(r);
            N col = x == null ? null : x.find(0, "Collection");
            out.printf("  Sync с чужим SyncKey: HTTP %d, Status=%s%n", r.code, col == null ? (x == null ? null : x.cval("Status")) : col.cval("Status"));
            Resp r2 = eas("Sync", syncReq("0", "999999", 5, 2, 10240, false));
            N x2 = parse(r2);
            N col2 = x2 == null ? null : x2.find(0, "Collection");
            out.printf("  Sync несуществующей папки: HTTP %d, Status=%s%n", r2.code, col2 == null ? (x2 == null ? null : x2.cval("Status")) : col2.cval("Status"));
        }
        String saved = policyKey;
        policyKey = "123456";
        Resp r3 = eas("FolderSync", e(7, "FolderSync", e(7, "SyncKey", "0")));
        out.printf("  FolderSync с неверным X-MS-PolicyKey: HTTP %d%n", r3.code);
        policyKey = null;
        Resp r4 = eas("FolderSync", e(7, "FolderSync", e(7, "SyncKey", "0")), false, Map.of());
        out.printf("  FolderSync без X-MS-PolicyKey после Provision: HTTP %d%n", r4.code);
        policyKey = saved;
        Resp r5 = eas("FolderSync", e(7, "FolderSync", e(7, "SyncKey", "0")));
        out.printf("  FolderSync с верным PolicyKey: HTTP %d%n", r5.code);
        Resp r6 = http("POST", easUrl("Sync", null), new byte[]{3, 1, 0x6A, 0, 0x45, 0x5C}, easHeaders(true), true);
        out.printf("  Обрезанный WBXML в запросе: HTTP %d, %d байт ответа%n", r6.code, r6.body.length);
    }

    static void sectionPing() {
        h1("T13. Ping: допустимые границы HeartbeatInterval (BG-06/07)");
        Folder inbox = byType(2);
        if (inbox == null) return;
        for (String hb : new String[]{"1", "100000"}) {
            N req = e(13, "Ping", e(13, "HeartbeatInterval", hb), e(13, "Folders", e(13, "Folder", e(13, "Id", inbox.id), e(13, "Class", "Email"))));
            Resp r = eas("Ping", req);
            N x = parse(r);
            out.printf("  HeartbeatInterval=%s: HTTP %d за %,d мс, Status=%s, сервер предлагает HeartbeatInterval=%s%n",
                    hb, r.code, r.ms, x == null ? null : x.cval("Status"), x == null ? null : x.cval("HeartbeatInterval"));
        }
    }

    static void sectionEws() {
        h1("T14. EWS: какие ответы приложение принимает за повод повторить запрос через NTLM (PROTO-06)");
        String hdr = "<?xml version=\"1.0\" encoding=\"utf-8\"?><soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                "xmlns:t=\"http://schemas.microsoft.com/exchange/services/2006/types\" xmlns:m=\"http://schemas.microsoft.com/exchange/services/2006/messages\">" +
                "<soap:Header><t:RequestServerVersion Version=\"Exchange2007_SP1\"/></soap:Header><soap:Body>";
        String ftr = "</soap:Body></soap:Envelope>";
        Map<String, String> h = Map.of("Content-Type", "text/xml; charset=utf-8", "User-Agent", UA);
        String[][] cases = {
                {"GetFolder inbox (корректный запрос)", "<m:GetFolder><m:FolderShape><t:BaseShape>IdOnly</t:BaseShape></m:FolderShape><m:FolderIds><t:DistinguishedFolderId Id=\"inbox\"/></m:FolderIds></m:GetFolder>"},
                {"GetItem с битым ItemId (ошибка уровня операции)", "<m:GetItem><m:ItemShape><t:BaseShape>IdOnly</t:BaseShape></m:ItemShape><m:ItemIds><t:ItemId Id=\"AAAA\"/></m:ItemIds></m:GetItem>"},
                {"Нарушение схемы (неизвестный элемент)", "<m:GetFolder><m:Bogus/></m:GetFolder>"},
        };
        for (String[] c : cases) {
            Resp r = http("POST", BASE + "/EWS/Exchange.asmx", (hdr + c[1] + ftr).getBytes(UTF8), h, true);
            String b = new String(r.body, UTF8);
            String rc = find(b, "ResponseCode>([^<]+)<");
            String fault = find(b, "faultcode>([^<]+)<");
            String cls = find(b, "ResponseClass=\"([A-Za-z]+)\"");
            out.printf("  %s: HTTP %d за %,d мс; ResponseClass=%s, ResponseCode=%s, faultcode=%s%n", c[0], r.code, r.ms, cls, rc, fault);
        }
    }

    static String find(String s, String re) { Matcher m = Pattern.compile(re).matcher(s); return m.find() ? m.group(1) : null; }

    // ============================== followup ==============================

    static String fetchSt(String colId, String sid) {
        N x = fetchBody(colId, sid, 1, 100, false, null);
        if (x == null) return "нет ответа";
        N f = x.find(20, "Fetch");
        return f == null ? "top=" + x.cval("Status") : f.cval("Status");
    }

    static void followup() throws Exception {
        if (!sectionProvision()) { out.println("Provision не удался"); return; }
        sectionFolderSync();
        Folder inbox = byType(2);

        h1("F1. PROTO-08: ItemOperations Fetch по ServerId во время полного ресинка (SyncKey=0)");
        inboxItems.clear();
        printPass("Полный проход Входящих", fullPass(inbox, 50, 2, 10240, false, 20_000, inboxItems));
        int nItems = inboxItems.size();
        Item a = inboxItems.get(0), b = inboxItems.get(nItems / 2), c = inboxItems.get(nItems - 1);
        out.printf("  После полного прохода: Fetch первого=%s, среднего=%s, последнего=%s%n",
                fetchSt(inbox.id, a.serverId), fetchSt(inbox.id, b.serverId), fetchSt(inbox.id, c.serverId));
        String key = initialSync(inbox);
        long tReset = System.currentTimeMillis();
        out.printf("  Сразу после Sync SyncKey=0 (сброс): Fetch первого=%s, среднего=%s, последнего=%s%n",
                fetchSt(inbox.id, a.serverId), fetchSt(inbox.id, b.serverId), fetchSt(inbox.id, c.serverId));
        Resp r = eas("Sync", syncReq(key, inbox.id, 50, 2, 10240, false));
        N x = parse(r);
        N col = x == null ? null : x.find(0, "Collection");
        Set<String> page1 = new HashSet<>();
        if (col != null && col.child("Commands") != null) for (N ad : col.child("Commands").children("Add")) page1.add(ad.cval("ServerId"));
        String nk = col == null ? null : col.cval("SyncKey");
        out.printf("  После 1-й страницы ресинка (%d писем): Fetch первого=%s (на странице: %b), среднего=%s (%b), последнего=%s (%b)%n",
                page1.size(), fetchSt(inbox.id, a.serverId), page1.contains(a.serverId), fetchSt(inbox.id, b.serverId), page1.contains(b.serverId),
                fetchSt(inbox.id, c.serverId), page1.contains(c.serverId));
        int pages = 1;
        while (col != null && col.child("MoreAvailable") != null && nk != null && pages < 500) {
            r = eas("Sync", syncReq(nk, inbox.id, 50, 2, 10240, false));
            x = parse(r);
            col = x == null ? null : x.find(0, "Collection");
            if (col != null && col.cval("SyncKey") != null) nk = col.cval("SyncKey");
            pages++;
            if (pages == nItems / 100 + 1)
                out.printf("  На середине ресинка (страница %d): Fetch первого=%s, среднего=%s, последнего=%s%n",
                        pages, fetchSt(inbox.id, a.serverId), fetchSt(inbox.id, b.serverId), fetchSt(inbox.id, c.serverId));
        }
        out.printf("  После завершения ресинка (%d страниц, %,d мс от сброса): Fetch первого=%s, среднего=%s, последнего=%s%n",
                pages, System.currentTimeMillis() - tReset, fetchSt(inbox.id, a.serverId), fetchSt(inbox.id, b.serverId), fetchSt(inbox.id, c.serverId));

        h1("F2. Тела после корректной синхронизации (PROTO-01)");
        sectionBigBodies();

        h1("F3. MIME Входящих после корректной синхронизации (PROTO-02)");
        mimeSample("Входящие", inbox, inboxItems, 400);
        int valid = 0, invalid = 0, none = 0;
        for (int i = 0; i < Math.min(60, inboxItems.size()); i++) {
            N fx = fetchBody(inbox.id, inboxItems.get(i).serverId, 2, 262_144, false, null);
            N body = fx == null ? null : fx.find(17, "Body");
            N d = body == null ? null : body.child("Data");
            if (d == null) { none++; continue; }
            if (validUtf8(d.raw(), "1".equals(body.cval("Truncated")))) valid++; else invalid++;
        }
        out.printf("  Контроль Type=2 (HTML) на 60 письмах: валидный UTF-8 %d, невалидный %d, без Data %d%n", valid, invalid, none);

        h1("F4. Вложения (PROTO-13, MAIL-04)");
        followupAttachments();

        h1("F5. EWS через NTLM (как executeNtlmRequest): коды ответов на ошибки (PROTO-06)");
        ewsNtlm();
    }

    static final List<long[]> allAtts = new ArrayList<>();
    static final List<String> allAttRefs = new ArrayList<>();

    static void attMode() throws Exception {
        if (!sectionProvision()) return;
        sectionFolderSync();
        printPass("Входящие", fullPass(byType(2), 50, 2, 10240, false, 20_000, inboxItems));
        printPass("Отправленные", fullPass(byType(5), 50, 2, 10240, false, 20_000, sentItems));
        h1("G1. Порог размера вложения для ItemOperations Fetch и размер ответа против лимита WbxmlParser 16 МиБ");
        List<long[]> big = new ArrayList<>();
        for (long[] a : allAtts) if (a[0] >= 4_000_000) big.add(a);
        big.sort(Comparator.comparingLong(a -> a[0]));
        long lastSize = -1;
        int tested = 0;
        for (long[] a : big) {
            if (a[0] == lastSize) continue;
            lastSize = a[0];
            if (tested++ >= 12) break;
            attFetch(String.format("вложение %,d байт", a[0]), allAttRefs.get((int) a[1]), Map.of(), a[0]);
        }
        h1("G3. GetAttachment POST (Content-Type message/rfc822) для вложений, которые ItemOperations отдаёт со Status 11");
        lastSize = -1;
        for (long[] a : big) {
            if (a[0] < 10_000_000 || a[0] == lastSize) continue;
            lastSize = a[0];
            long t0 = System.nanoTime();
            getAtt2(String.format("вложение %,d байт", a[0]), "POST", allAttRefs.get((int) a[1]), true, a[0]);
            out.printf("      время %,d мс%n", (System.nanoTime() - t0) / 1_000_000);
        }
        h1("G2. GetAttachment: варианты запроса");
        long[] small = null;
        for (long[] a : allAtts) if (a[0] > 1000 && a[0] < 100_000 && (small == null || a[0] < small[0])) small = a;
        if (small != null) {
            String ref = allAttRefs.get((int) small[1]);
            String dec = URLDecoder.decode(ref, UTF8);
            out.printf("  Вложение %,d байт; вид FileReference: %s%n", small[0], ref.replaceAll("[A-Za-z0-9]", "x"));
            for (String method : new String[]{"POST", "GET"})
                for (boolean dom : new boolean[]{true, false})
                    for (String[] nm : new String[][]{{"как прислал сервер", ref}, {"после decode", dec}}) {
                        getAtt2(method + ", User " + (dom ? "с доменом" : "без домена") + ", имя " + nm[0], method, nm[1], dom, small[0]);
                    }
        }
    }

    static void getAtt2(String label, String method, String name, boolean withDomain, long expected) {
        String user = withDomain ? URLEncoder.encode(DOMAIN + "\\" + USER, UTF8) : URLEncoder.encode(USER, UTF8);
        String url = BASE + "/Microsoft-Server-ActiveSync?Cmd=GetAttachment&AttachmentName=" + name + "&User=" + user +
                "&DeviceId=" + DEVICE_ID + "&DeviceType=" + DEVICE_TYPE;
        Map<String, String> h = easHeaders(true);
        if (method.equals("GET")) h.remove("Content-Type"); else h.put("Content-Type", "message/rfc822");
        Resp r = http(method, url, method.equals("POST") ? new byte[0] : null, h, true);
        out.printf("    %s: HTTP %d, %,d байт (ожидалось ~%,d), Content-Type %s%s%n", label, r.code, r.body.length, expected, r.h("Content-Type"),
                r.error == null ? "" : ", " + r.error);
    }

    static void followupAttachments() {
        Item big = null, small = null, mid = null;
        for (Item it : inboxItems) {
            if (it.attMaxRef != null && it.attMax <= 40_000_000 && (big == null || it.attMax > big.attMax)) big = it;
            if (it.attMinRef != null && it.attMinSize > 1000 && it.attMinSize < 200_000 && (small == null || it.attMinSize < small.attMinSize)) small = it;
            if (it.attMaxRef != null && it.attMax > 5_000_000 && it.attMax < 12_000_000 && (mid == null || it.attMax > mid.attMax)) mid = it;
        }
        if (small != null) {
            String ref = small.attMinRef;
            out.printf("  Малое вложение %,d байт; FileReference содержит %%: %b, содержит '+': %b%n", small.attMinSize, ref.contains("%"), ref.contains("+"));
            attFetch("ItemOperations, ref как прислал сервер (1-я попытка приложения)", ref, Map.of(), small.attMinSize);
            String dec = URLDecoder.decode(ref, UTF8);
            if (!dec.equals(ref)) attFetch("ItemOperations, ref после URLDecoder.decode (2-я попытка приложения)", dec, Map.of(), small.attMinSize);
            getAtt("GetAttachment GET, User без домена, имя после decode (1-я попытка приложения)", "GET", dec, false, small.attMinSize);
            getAtt("GetAttachment GET, User без домена, имя как прислал сервер (2-я попытка приложения)", "GET", ref, false, small.attMinSize);
            getAtt("GetAttachment POST, домен\\пользователь, имя как прислал сервер", "POST", ref, true, small.attMinSize);
            getAtt("GetAttachment POST, домен\\пользователь, имя URL-кодировано ещё раз", "POST", URLEncoder.encode(ref, UTF8), true, small.attMinSize);
        }
        if (mid != null) {
            out.printf("  Вложение 5-12 МБ: %,d байт%n", mid.attMax);
            attFetch("ItemOperations, обычный ответ", mid.attMaxRef, Map.of(), mid.attMax);
        }
        if (big != null) {
            out.printf("  Крупнейшее вложение: %,d байт (лимит WbxmlParser MAX_OPAQUE_SIZE/MAX_STRING_SIZE = 16 777 216)%n", big.attMax);
            attFetch("ItemOperations, обычный ответ", big.attMaxRef, Map.of(), big.attMax);
            attFetch("ItemOperations, MS-ASAcceptMultiPart: T", big.attMaxRef, Map.of("MS-ASAcceptMultiPart", "T"), big.attMax);
        }
    }

    static Resp ewsCall(String inner) {
        String env = "<?xml version=\"1.0\" encoding=\"utf-8\"?><soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                "xmlns:t=\"http://schemas.microsoft.com/exchange/services/2006/types\" xmlns:m=\"http://schemas.microsoft.com/exchange/services/2006/messages\">" +
                "<soap:Header><t:RequestServerVersion Version=\"Exchange2007_SP1\"/></soap:Header><soap:Body>" + inner + "</soap:Body></soap:Envelope>";
        Resp r = new Resp();
        long t0 = System.nanoTime();
        try {
            HttpsURLConnection c = (HttpsURLConnection) new URL(BASE + "/EWS/Exchange.asmx").openConnection();
            c.setSSLSocketFactory(sf);
            c.setHostnameVerifier((h, s) -> true);
            c.setAuthenticator(new Authenticator() {
                @Override protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(DOMAIN + "\\" + USER, pass.toCharArray());
                }
            });
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(15_000);
            c.setReadTimeout(300_000);
            c.setRequestProperty("Content-Type", "text/xml; charset=utf-8");
            try (OutputStream o = c.getOutputStream()) { o.write(env.getBytes(UTF8)); }
            r.code = c.getResponseCode();
            InputStream in = r.code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in != null) try (in) { r.body = in.readAllBytes(); }
        } catch (Exception ex) { r.error = ex.getClass().getSimpleName() + ": " + ex.getMessage(); }
        r.ms = (System.nanoTime() - t0) / 1_000_000;
        return r;
    }

    static String sha(byte[] b) {
        try { return hex(MessageDigest.getInstance("SHA-256").digest(b)).substring(0, 16); } catch (Exception e) { return "?"; }
    }

    static int le32(byte[] b, int off) { return (b[off] & 0xFF) | (b[off + 1] & 0xFF) << 8 | (b[off + 2] & 0xFF) << 16 | (b[off + 3] & 0xFF) << 24; }

    /** ItemOperations Fetch с MS-ASAcceptMultiPart: T — разбор двоичного ответа (MS-ASCMD 2.2.2.10 / 2.2.3.130 Part). */
    static byte[] multipartFetch(String ref, long expected) throws IOException {
        N req = e(20, "ItemOperations", e(20, "Fetch", e(20, "Store", "Mailbox"), e(17, "FileReference", ref)));
        Resp r = eas("ItemOperations", req, true, Map.of("MS-ASAcceptMultiPart", "T"));
        String ct = r.h("Content-Type");
        out.printf("      ItemOperations multipart: HTTP %d, Content-Type %s, %,d байт, %,d мс%n", r.code, ct, r.body.length, r.ms);
        if (ct == null || !ct.contains("multipart")) {
            N x = parse(r);
            out.printf("        ответ не multipart, Status Fetch=%s%n", fetchStatus(x));
            return null;
        }
        byte[] b = r.body;
        int parts = le32(b, 0);
        StringBuilder layout = new StringBuilder();
        int[][] meta = new int[parts][2];
        for (int i = 0; i < parts; i++) {
            meta[i][0] = le32(b, 4 + 8 * i);
            meta[i][1] = le32(b, 8 + 8 * i);
            layout.append(String.format("[%d: offset %,d, length %,d]", i, meta[i][0], meta[i][1]));
        }
        out.printf("        частей %d, заголовок %d байт, %s; части подряд без зазоров: %b%n", parts, 4 + 8 * parts, layout,
                parts == 0 || meta[0][0] == 4 + 8 * parts);
        if (parts == 0) return null;
        N x = dec(Arrays.copyOfRange(b, meta[0][0], meta[0][0] + meta[0][1]));
        N fetch = x == null ? null : x.find(20, "Fetch");
        N part = fetch == null ? null : fetch.find(20, "Part");
        N data = fetch == null ? null : fetch.find(20, "Data");
        out.printf("        часть 0 (WBXML): Status=%s, Fetch Status=%s, Part=%s, Data внутри: %b%n",
                x == null ? null : x.cval("Status"), fetch == null ? null : fetch.cval("Status"), part == null ? null : part.text(), data != null);
        if (part == null) return null;
        int idx = Integer.parseInt(part.text().trim());
        byte[] content = Arrays.copyOfRange(b, meta[idx][0], meta[idx][0] + meta[idx][1]);
        out.printf("        часть %d: %,d байт (EstimatedDataSize %,d), SHA-256 %s%n", idx, content.length, expected, sha(content));
        return content;
    }

    static byte[] plainFetch(String ref) throws IOException {
        N req = e(20, "ItemOperations", e(20, "Fetch", e(20, "Store", "Mailbox"), e(17, "FileReference", ref)));
        Resp r = eas("ItemOperations", req, true, Map.of());
        N x = parse(r);
        N d = x == null ? null : x.find(20, "Data");
        byte[] data = d == null ? null : Base64.getMimeDecoder().decode(d.raw());
        out.printf("      ItemOperations обычный (base64 в WBXML): Fetch Status=%s, %s%n", fetchStatus(x),
                data == null ? "данных нет" : String.format("%,d байт, SHA-256 %s", data.length, sha(data)));
        return data;
    }

    static byte[] getAttachmentPost(String ref, boolean defaultEas) {
        String url = BASE + "/Microsoft-Server-ActiveSync" + (defaultEas ? "/default.eas" : "") + "?Cmd=GetAttachment&AttachmentName=" + ref +
                "&User=" + URLEncoder.encode(DOMAIN + "\\" + USER, UTF8) + "&DeviceId=" + DEVICE_ID + "&DeviceType=" + DEVICE_TYPE;
        Map<String, String> h = easHeaders(true);
        h.put("Content-Type", "message/rfc822");
        Resp r = http("POST", url, new byte[0], h, true);
        out.printf("      GetAttachment POST%s: HTTP %d, %,d байт, Content-Type %s%s%n", defaultEas ? " (/default.eas)" : "", r.code, r.body.length,
                r.h("Content-Type"), r.code == 200 ? ", SHA-256 " + sha(r.body) : "");
        return r.code == 200 ? r.body : null;
    }

    /** EWS: письмо по теме и времени (как приложение без Message-ID), вложения, GetAttachment выбранного по имени. */
    static byte[] ewsAttachment(Item it, String[] att) {
        Instant t = Instant.parse(it.dateReceived.endsWith("Z") ? it.dateReceived : it.dateReceived + "Z");
        String subj = it.subject == null ? "" : it.subject.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;");
        Resp f = ewsCall("<m:FindItem Traversal=\"Shallow\"><m:ItemShape><t:BaseShape>IdOnly</t:BaseShape></m:ItemShape>" +
                "<m:IndexedPageItemView MaxEntriesReturned=\"2\" Offset=\"0\" BasePoint=\"Beginning\"/><m:Restriction><t:And>" +
                "<t:Contains ContainmentMode=\"FullString\" ContainmentComparison=\"IgnoreCaseAndNonSpacingCharacters\"><t:FieldURI FieldURI=\"item:Subject\"/><t:Constant Value=\"" + subj + "\"/></t:Contains>" +
                "<t:IsGreaterThanOrEqualTo><t:FieldURI FieldURI=\"item:DateTimeReceived\"/><t:FieldURIOrConstant><t:Constant Value=\"" + t.minusSeconds(15) + "\"/></t:FieldURIOrConstant></t:IsGreaterThanOrEqualTo>" +
                "<t:IsLessThanOrEqualTo><t:FieldURI FieldURI=\"item:DateTimeReceived\"/><t:FieldURIOrConstant><t:Constant Value=\"" + t.plusSeconds(15) + "\"/></t:FieldURIOrConstant></t:IsLessThanOrEqualTo>" +
                "</t:And></m:Restriction><m:ParentFolderIds><t:DistinguishedFolderId Id=\"inbox\"/></m:ParentFolderIds></m:FindItem>");
        String fb = new String(f.body, UTF8);
        List<String> ids = new ArrayList<>();
        Matcher im = Pattern.compile("<t:ItemId Id=\"([^\"]+)\"").matcher(fb);
        while (im.find()) ids.add(im.group(1));
        out.printf("      EWS FindItem (тема + ±15 с): HTTP %d, найдено %d%n", f.code, ids.size());
        if (ids.size() != 1) return null;
        Resp g = ewsCall("<m:GetItem><m:ItemShape><t:BaseShape>IdOnly</t:BaseShape><t:AdditionalProperties><t:FieldURI FieldURI=\"item:Attachments\"/>" +
                "</t:AdditionalProperties></m:ItemShape><m:ItemIds><t:ItemId Id=\"" + ids.get(0) + "\"/></m:ItemIds></m:GetItem>");
        String gb = new String(g.body, UTF8);
        Matcher am = Pattern.compile("<t:FileAttachment>(.*?)</t:FileAttachment>", Pattern.DOTALL).matcher(gb);
        String chosen = null;
        int total = 0, sameName = 0;
        while (am.find()) {
            String a = am.group(1);
            total++;
            String name = find(a, "<t:Name>([^<]*)</t:Name>");
            String id = find(a, "<t:AttachmentId Id=\"([^\"]+)\"");
            out.printf("        FileAttachment: имя совпадает с EAS DisplayName: %b, ContentId есть: %b (совпадает: %b), t:Size: %s, t:IsInline: %s, t:ContentType есть: %b%n",
                    att[1] != null && att[1].equals(unxml(name)), a.contains("<t:ContentId>"),
                    att[3] != null && att[3].equals(unxml(find(a, "<t:ContentId>([^<]*)</t:ContentId>"))),
                    find(a, "<t:Size>([^<]*)</t:Size>"), find(a, "<t:IsInline>([^<]*)</t:IsInline>"), a.contains("<t:ContentType>"));
            if (att[1] != null && att[1].equals(unxml(name))) { sameName++; chosen = id; }
        }
        out.printf("      EWS GetItem item:Attachments: HTTP %d, FileAttachment %d, с тем же именем %d; ItemAttachment: %b%n", g.code, total, sameName,
                gb.contains("<t:ItemAttachment>"));
        if (sameName != 1) return null;
        Resp a = ewsCall("<m:GetAttachment><m:AttachmentIds><t:AttachmentId Id=\"" + chosen + "\"/></m:AttachmentIds></m:GetAttachment>");
        String ab = new String(a.body, UTF8);
        int ci = ab.indexOf("Content>");
        String head = ci < 0 ? ab.substring(0, Math.min(ab.length(), 400)) : ab.substring(0, ci + 8);
        head = head.replaceAll("Id=\"[^\"]+\"", "Id=\"…\"").replaceAll("<t:Name>[^<]*</t:Name>", "<t:Name>…</t:Name>")
                .replaceAll("<t:ContentId>[^<]*</t:ContentId>", "<t:ContentId>…</t:ContentId>");
        String b64 = find(ab, "<t:Content>([^<]*)</t:Content>");
        byte[] content = b64 == null ? null : Base64.getMimeDecoder().decode(b64);
        out.printf("      EWS GetAttachment: HTTP %d, %,d байт ответа, %,d мс, Content-Type %s%n        начало ответа до содержимого: %s%n        %s%n",
                a.code, a.body.length, a.ms, a.h("Content-Type"), head.substring(Math.max(0, head.length() - 700)),
                content == null ? "t:Content нет" : String.format("содержимое %,d байт, SHA-256 %s", content.length, sha(content)));
        return content;
    }

    static String unxml(String s) {
        return s == null ? null : s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&");
    }

    static void att2Mode() throws Exception {
        if (!sectionProvision()) return;
        sectionFolderSync();
        printPass("Входящие", fullPass(byType(2), 50, 2, 10240, false, 20_000, inboxItems));
        h1("G4. Вложения: multipart ItemOperations, GetAttachment POST, EWS — одинаковые ли байты (PROTO-17)");
        int dupNames = 0, withCid = 0, inline = 0;
        for (Item it : inboxItems) {
            Set<String> names = new HashSet<>();
            for (String[] a : it.attList) {
                if (a[1] != null && !names.add(a[1])) dupNames++;
                if (a[3] != null) withCid++;
                if ("1".equals(a[4])) inline++;
            }
        }
        out.printf("  Писем с повтором имени вложения: %d; вложений с ContentId: %d; IsInline=1: %d%n", dupNames, withCid, inline);
        Item small = null, mid = null, big = null;
        String[] sA = null, mA = null, bA = null;
        for (Item it : inboxItems) for (String[] a : it.attList) {
            long sz = Long.parseLong(a[2]);
            if (sz > 1000 && sz < 100_000 && (sA == null || sz < Long.parseLong(sA[2]))) { small = it; sA = a; }
            if (sz > 5_000_000 && sz < 9_500_000 && (mA == null || sz > Long.parseLong(mA[2]))) { mid = it; mA = a; }
            if (sz > 12_000_000 && sz <= 40_000_000 && (bA == null || sz > Long.parseLong(bA[2]))) { big = it; bA = a; }
        }
        Object[][] cases = {{"малое", small, sA}, {"5-9 МБ", mid, mA}, {"больше 10 МБ", big, bA}};
        for (Object[] c : cases) {
            Item it = (Item) c[1];
            String[] a = (String[]) c[2];
            if (it == null) { out.printf("  %s: нет такого вложения%n", c[0]); continue; }
            long sz = Long.parseLong(a[2]);
            out.printf("  Вложение %s: EstimatedDataSize %,d, вид FileReference %s%n", c[0], sz, a[0].replaceAll("[A-Za-z0-9]", "x"));
            byte[] mp = multipartFetch(a[0], sz);
            byte[] pl = sz < 12_000_000 ? plainFetch(a[0]) : null;
            byte[] ga1 = getAttachmentPost(a[0], false);
            byte[] ga2 = getAttachmentPost(a[0], true);
            byte[] ews = ewsAttachment(it, a);
            String ref = mp != null ? sha(mp) : ews != null ? sha(ews) : null;
            out.printf("      Совпадение байтов с эталоном %s: обычный=%s, GetAttachment=%s/%s, EWS=%s%n", ref,
                    pl == null ? "-" : sha(pl).equals(ref), ga1 == null ? "-" : sha(ga1).equals(ref), ga2 == null ? "-" : sha(ga2).equals(ref),
                    ews == null ? "-" : sha(ews).equals(ref));
        }
        h1("G5. EWS GetAttachment с несуществующим AttachmentId: вид ошибки");
        Resp bad = ewsCall("<m:GetAttachment><m:AttachmentIds><t:AttachmentId Id=\"AAMkAGBhZDNiAAA=\"/></m:AttachmentIds></m:GetAttachment>");
        String bb = new String(bad.body, UTF8);
        out.printf("  HTTP %d, ResponseClass=%s, ResponseCode=%s, faultstring=%s%n", bad.code, find(bb, "ResponseClass=\"([A-Za-z]+)\""),
                find(bb, "ResponseCode>([^<]+)<"), find(bb, "faultstring[^>]*>([^<]{0,160})"));
    }

    static void ewsAttMode() {
        h1("H1. EWS (NTLM): можно ли скачать вложение больше 10 МБ, которое EAS не отдаёт");
        Resp f = ewsCall("<m:FindItem Traversal=\"Shallow\"><m:ItemShape><t:BaseShape>IdOnly</t:BaseShape>" +
                "<t:AdditionalProperties><t:FieldURI FieldURI=\"item:Size\"/></t:AdditionalProperties></m:ItemShape>" +
                "<m:IndexedPageItemView MaxEntriesReturned=\"5\" Offset=\"0\" BasePoint=\"Beginning\"/>" +
                "<m:Restriction><t:IsGreaterThan><t:FieldURI FieldURI=\"item:Size\"/><t:FieldURIOrConstant><t:Constant Value=\"10000000\"/></t:FieldURIOrConstant></t:IsGreaterThan></m:Restriction>" +
                "<m:ParentFolderIds><t:DistinguishedFolderId Id=\"inbox\"/></m:ParentFolderIds></m:FindItem>");
        String fb = new String(f.body, UTF8);
        out.printf("  FindItem Size>10 МБ: HTTP %d за %,d мс, ResponseClass=%s%n", f.code, f.ms, find(fb, "ResponseClass=\"([A-Za-z]+)\""));
        Matcher m = Pattern.compile("<t:ItemId Id=\"([^\"]+)\"").matcher(fb);
        if (!m.find()) { out.println("  писем нет"); return; }
        String itemId = m.group(1);
        Resp g = ewsCall("<m:GetItem><m:ItemShape><t:BaseShape>IdOnly</t:BaseShape><t:AdditionalProperties><t:FieldURI FieldURI=\"item:Attachments\"/></t:AdditionalProperties></m:ItemShape>" +
                "<m:ItemIds><t:ItemId Id=\"" + itemId + "\"/></m:ItemIds></m:GetItem>");
        String gb = new String(g.body, UTF8);
        Matcher am = Pattern.compile("<t:AttachmentId Id=\"([^\"]+)\"").matcher(gb);
        Matcher sm = Pattern.compile("<t:Size>(\\d+)</t:Size>").matcher(gb);
        out.printf("  GetItem вложения: HTTP %d за %,d мс%n", g.code, g.ms);
        String bestId = null; long best = -1;
        List<String> ids = new ArrayList<>();
        while (am.find()) ids.add(am.group(1));
        List<Long> sizes = new ArrayList<>();
        while (sm.find()) sizes.add(Long.parseLong(sm.group(1)));
        for (int i = 0; i < ids.size(); i++) {
            long s = i < sizes.size() ? sizes.get(i) : 0;
            if (s > best) { best = s; bestId = ids.get(i); }
        }
        out.printf("  вложений %d, крупнейшее по t:Size %,d байт%n", ids.size(), best);
        if (bestId == null) return;
        Resp a = ewsCall("<m:GetAttachment><m:AttachmentIds><t:AttachmentId Id=\"" + bestId + "\"/></m:AttachmentIds></m:GetAttachment>");
        String ab = new String(a.body, UTF8);
        String content = find(ab, "<t:Content>([^<]+)</t:Content>");
        long dec = -1;
        if (content != null) try { dec = Base64.getMimeDecoder().decode(content).length; } catch (Exception ex) { dec = -2; }
        out.printf("  GetAttachment: HTTP %d за %,d мс, ResponseClass=%s, ResponseCode=%s, ответ %,d байт, содержимое после base64 %,d байт%s%n",
                a.code, a.ms, find(ab, "ResponseClass=\"([A-Za-z]+)\""), find(ab, "ResponseCode>([^<]+)<"), a.body.length, dec,
                a.error == null ? "" : " (" + a.error + ")");
    }

    /**
     * Каждый FieldURI, который приложение шлёт в EWS (список — аргумент через запятую), — в запросе к Exchange 2007:
     * проверка схемы на сервере идёт для всего запроса, неизвестное поле ломает его целиком (HTTP 500).
     */
    static void fieldUriMode(String list) {
        h1("J1. FieldURI приложения против схемы Exchange 2007 (RequestServerVersion Exchange2007_SP1)");
        for (String f : list.split(",")) {
            if (f.isBlank()) continue;
            String folder = f.startsWith("calendar:") ? "calendar" : f.startsWith("task:") ? "tasks" : "inbox";
            String body = f.startsWith("folder:")
                    ? "<m:FindFolder Traversal=\"Shallow\"><m:FolderShape><t:BaseShape>IdOnly</t:BaseShape><t:AdditionalProperties>" +
                      "<t:FieldURI FieldURI=\"" + f + "\"/></t:AdditionalProperties></m:FolderShape>" +
                      "<m:ParentFolderIds><t:DistinguishedFolderId Id=\"msgfolderroot\"/></m:ParentFolderIds></m:FindFolder>"
                    : "<m:FindItem Traversal=\"Shallow\"><m:ItemShape><t:BaseShape>IdOnly</t:BaseShape><t:AdditionalProperties>" +
                      "<t:FieldURI FieldURI=\"" + f + "\"/></t:AdditionalProperties></m:ItemShape>" +
                      "<m:IndexedPageItemView MaxEntriesReturned=\"1\" Offset=\"0\" BasePoint=\"Beginning\"/>" +
                      "<m:ParentFolderIds><t:DistinguishedFolderId Id=\"" + folder + "\"/></m:ParentFolderIds></m:FindItem>";
            Resp r = ewsCall(body);
            String b = new String(r.body, UTF8);
            String fault = find(b, "faultstring[^>]*>([^<]{0,140})");
            out.printf("  %-34s %-9s HTTP %d %s%s%n", f, folder, r.code, r.code == 200 ? "OK " + find(b, "ResponseCode>([^<]+)<") : "ОТКАЗ",
                    fault == null ? "" : " — " + fault);
        }
    }

    /** FindItem CalendarView как CalendarExceptionService (с item:Body и без). */
    static void calendarViewMode() {
        h1("K1. FindItem CalendarView как CalendarExceptionService.supplementRecurringExceptionsViaEws");
        String[][] sets = {
                {"как приложение (с item:Body)", "item:Subject,item:Body,calendar:Start,calendar:End,calendar:Location,calendar:CalendarItemType,calendar:UID,calendar:OriginalStart"},
                {"без item:Body", "item:Subject,calendar:Start,calendar:End,calendar:Location,calendar:CalendarItemType,calendar:UID,calendar:OriginalStart"},
                {"без item:Body и calendar:OriginalStart", "item:Subject,calendar:Start,calendar:End,calendar:Location,calendar:CalendarItemType,calendar:UID"},
        };
        for (String[] s : sets) {
            StringBuilder ap = new StringBuilder();
            for (String field : s[1].split(",")) ap.append("<t:FieldURI FieldURI=\"").append(field).append("\"/>");
            Resp r = ewsCall("<m:FindItem Traversal=\"Shallow\"><m:ItemShape><t:BaseShape>IdOnly</t:BaseShape><t:AdditionalProperties>" + ap +
                    "</t:AdditionalProperties></m:ItemShape><m:CalendarView StartDate=\"2025-01-01T00:00:00Z\" EndDate=\"2027-01-01T00:00:00Z\" MaxEntriesReturned=\"2000\"/>" +
                    "<m:ParentFolderIds><t:DistinguishedFolderId Id=\"calendar\"/></m:ParentFolderIds></m:FindItem>");
            String b = new String(r.body, UTF8);
            int n = 0;
            Matcher m = Pattern.compile("<t:CalendarItem>").matcher(b);
            while (m.find()) n++;
            int exc = 0;
            Matcher em = Pattern.compile("<t:CalendarItemType>Exception</t:CalendarItemType>").matcher(b);
            while (em.find()) exc++;
            out.printf("  %s: HTTP %d, ResponseClass=%s, ResponseCode=%s, MessageText=%s, событий %d, Exception %d, IncludesLastItemInRange=%s%n", s[0], r.code,
                    find(b, "ResponseClass=\"([A-Za-z]+)\""), find(b, "ResponseCode>([^<]+)<"), find(b, "MessageText>([^<]{0,120})"), n, exc,
                    find(b, "IncludesLastItemInRange=\"([a-z]+)\""));
        }
        // GetItem для Exception-вхождения: можно ли получить Body и OriginalStart отдельным запросом
        Resp r = ewsCall("<m:FindItem Traversal=\"Shallow\"><m:ItemShape><t:BaseShape>IdOnly</t:BaseShape><t:AdditionalProperties>" +
                "<t:FieldURI FieldURI=\"calendar:CalendarItemType\"/></t:AdditionalProperties></m:ItemShape>" +
                "<m:CalendarView StartDate=\"2025-01-01T00:00:00Z\" EndDate=\"2027-01-01T00:00:00Z\" MaxEntriesReturned=\"2000\"/>" +
                "<m:ParentFolderIds><t:DistinguishedFolderId Id=\"calendar\"/></m:ParentFolderIds></m:FindItem>");
        String b = new String(r.body, UTF8);
        Matcher ci = Pattern.compile("<t:CalendarItem>(.*?)</t:CalendarItem>", Pattern.DOTALL).matcher(b);
        String anyId = null, excId = null;
        while (ci.find()) {
            String id = find(ci.group(1), "<t:ItemId Id=\"([^\"]+)\"");
            if (anyId == null) anyId = id;
            if (ci.group(1).contains(">Exception<") && excId == null) excId = id;
        }
        String target = excId != null ? excId : anyId;
        out.println("  Для GetItem выбрано: " + (excId != null ? "Exception" : (anyId != null ? "обычное событие" : "нет событий")));
        if (target == null) return;
        Resp g = ewsCall("<m:GetItem><m:ItemShape><t:BaseShape>IdOnly</t:BaseShape><t:BodyType>Text</t:BodyType><t:AdditionalProperties>" +
                "<t:FieldURI FieldURI=\"item:Body\"/><t:FieldURI FieldURI=\"calendar:OriginalStart\"/><t:FieldURI FieldURI=\"calendar:UID\"/>" +
                "<t:FieldURI FieldURI=\"calendar:CalendarItemType\"/></t:AdditionalProperties></m:ItemShape>" +
                "<m:ItemIds><t:ItemId Id=\"" + target + "\"/></m:ItemIds></m:GetItem>");
        String gb = new String(g.body, UTF8);
        out.printf("  GetItem Body+OriginalStart: HTTP %d, ResponseClass=%s, ResponseCode=%s, t:Body есть: %b, t:OriginalStart есть: %b%n", g.code,
                find(gb, "ResponseClass=\"([A-Za-z]+)\""), find(gb, "ResponseCode>([^<]+)<"), gb.contains("<t:Body"), gb.contains("<t:OriginalStart>"));
    }

    /**
     * Полный сценарий исключения повторяющейся серии: создаёт синтетическую серию (5 дней, 2026-12-01),
     * меняет 2-е вхождение, проверяет исправленный путь CalendarExceptionService
     * (FindItem без Body/OriginalStart + GetItem по Exception) и удаляет серию в finally.
     */
    static void calendarExceptionMode() {
        h1("K2. Исключение серии: FindItem CalendarView + GetItem (исправленный путь)");
        String masterId = null;
        try {
            Resp c = ewsCall("<m:CreateItem SendMeetingInvitations=\"SendToNone\"><m:SavedItemFolderId><t:DistinguishedFolderId Id=\"calendar\"/></m:SavedItemFolderId>" +
                    "<m:Items><t:CalendarItem><t:Subject>IWO probe series</t:Subject><t:Body BodyType=\"Text\">series body</t:Body>" +
                    "<t:Start>2026-12-01T09:00:00Z</t:Start><t:End>2026-12-01T10:00:00Z</t:End><t:Location>Room A</t:Location>" +
                    "<t:Recurrence><t:DailyRecurrence><t:Interval>1</t:Interval></t:DailyRecurrence>" +
                    "<t:NumberedRecurrence><t:StartDate>2026-12-01Z</t:StartDate><t:NumberOfOccurrences>5</t:NumberOfOccurrences></t:NumberedRecurrence></t:Recurrence>" +
                    "</t:CalendarItem></m:Items></m:CreateItem>");
            String cb = new String(c.body, UTF8);
            masterId = find(cb, "<t:ItemId Id=\"([^\"]+)\"");
            out.printf("  CreateItem серии: HTTP %d, ResponseCode=%s, MessageText=%s, ItemId есть: %b%n", c.code,
                    find(cb, "ResponseCode>([^<]+)<"), find(cb, "MessageText>([^<]{0,160})"), masterId != null);
            if (masterId == null) return;

            Resp o = ewsCall("<m:GetItem><m:ItemShape><t:BaseShape>IdOnly</t:BaseShape></m:ItemShape><m:ItemIds>" +
                    "<t:OccurrenceItemId RecurringMasterId=\"" + masterId + "\" InstanceIndex=\"2\"/></m:ItemIds></m:GetItem>");
            String ob = new String(o.body, UTF8);
            String occId = find(ob, "<t:ItemId Id=\"([^\"]+)\"");
            String occCk = find(ob, "<t:ItemId Id=\"[^\"]+\" ChangeKey=\"([^\"]+)\"");
            out.printf("  GetItem OccurrenceItemId #2: HTTP %d, ResponseCode=%s%n", o.code, find(ob, "ResponseCode>([^<]+)<"));
            if (occId == null) return;

            String[][] updates = {
                    {"Subject+Body+Start/End (+2 ч)",
                            "<t:SetItemField><t:FieldURI FieldURI=\"item:Subject\"/><t:CalendarItem><t:Subject>IWO probe changed</t:Subject></t:CalendarItem></t:SetItemField>" +
                            "<t:SetItemField><t:FieldURI FieldURI=\"item:Body\"/><t:CalendarItem><t:Body BodyType=\"Text\">exception body</t:Body></t:CalendarItem></t:SetItemField>" +
                            "<t:SetItemField><t:FieldURI FieldURI=\"calendar:Start\"/><t:CalendarItem><t:Start>2026-12-02T11:00:00Z</t:Start></t:CalendarItem></t:SetItemField>" +
                            "<t:SetItemField><t:FieldURI FieldURI=\"calendar:End\"/><t:CalendarItem><t:End>2026-12-02T12:00:00Z</t:End></t:CalendarItem></t:SetItemField>"},
                    {"только Subject+Body",
                            "<t:SetItemField><t:FieldURI FieldURI=\"item:Subject\"/><t:CalendarItem><t:Subject>IWO probe changed</t:Subject></t:CalendarItem></t:SetItemField>" +
                            "<t:SetItemField><t:FieldURI FieldURI=\"item:Body\"/><t:CalendarItem><t:Body BodyType=\"Text\">exception body</t:Body></t:CalendarItem></t:SetItemField>"},
            };
            boolean updated = false;
            for (String[] u : updates) {
                Resp up = ewsCall("<m:UpdateItem ConflictResolution=\"AlwaysOverwrite\" SendMeetingInvitationsOrCancellations=\"SendToNone\"><m:ItemChanges>" +
                        "<t:ItemChange><t:ItemId Id=\"" + occId + "\"" + (occCk != null ? " ChangeKey=\"" + occCk + "\"" : "") + "/><t:Updates>" + u[1] +
                        "</t:Updates></t:ItemChange></m:ItemChanges></m:UpdateItem>");
                String ub = new String(up.body, UTF8);
                String code = find(ub, "ResponseCode>([^<]+)<");
                out.printf("  UpdateItem вхождения [%s]: HTTP %d, ResponseCode=%s, MessageText=%s%n", u[0], up.code, code,
                        find(ub, "MessageText>([^<]{0,160})"));
                if ("NoError".equals(code)) { updated = true; break; }
                String newCk = find(ub, "ChangeKey=\"([^\"]+)\"");
                if (newCk != null) occCk = newCk;
            }
            if (!updated) return;

            String calView = "<m:CalendarView StartDate=\"2026-11-30T00:00:00Z\" EndDate=\"2026-12-07T00:00:00Z\" MaxEntriesReturned=\"2000\"/>" +
                    "<m:ParentFolderIds><t:DistinguishedFolderId Id=\"calendar\"/></m:ParentFolderIds>";
            Resp oldReq = ewsCall("<m:FindItem Traversal=\"Shallow\"><m:ItemShape><t:BaseShape>IdOnly</t:BaseShape><t:AdditionalProperties>" +
                    "<t:FieldURI FieldURI=\"item:Subject\"/><t:FieldURI FieldURI=\"item:Body\"/><t:FieldURI FieldURI=\"calendar:Start\"/>" +
                    "<t:FieldURI FieldURI=\"calendar:End\"/><t:FieldURI FieldURI=\"calendar:Location\"/><t:FieldURI FieldURI=\"calendar:CalendarItemType\"/>" +
                    "<t:FieldURI FieldURI=\"calendar:UID\"/><t:FieldURI FieldURI=\"calendar:OriginalStart\"/></t:AdditionalProperties></m:ItemShape>" + calView + "</m:FindItem>");
            String oldB = new String(oldReq.body, UTF8);
            out.printf("  Старый запрос приложения при наличии исключения: ResponseCode=%s%n", find(oldB, "ResponseCode>([^<]+)<"));

            Resp f = ewsCall("<m:FindItem Traversal=\"Shallow\"><m:ItemShape><t:BaseShape>IdOnly</t:BaseShape><t:AdditionalProperties>" +
                    "<t:FieldURI FieldURI=\"item:Subject\"/><t:FieldURI FieldURI=\"calendar:Start\"/><t:FieldURI FieldURI=\"calendar:End\"/>" +
                    "<t:FieldURI FieldURI=\"calendar:Location\"/><t:FieldURI FieldURI=\"calendar:CalendarItemType\"/><t:FieldURI FieldURI=\"calendar:UID\"/>" +
                    "</t:AdditionalProperties></m:ItemShape>" + calView + "</m:FindItem>");
            String fb = new String(f.body, UTF8);
            Matcher ci = Pattern.compile("<t:CalendarItem>(.*?)</t:CalendarItem>", Pattern.DOTALL).matcher(fb);
            int total = 0;
            String excId = null, excStart = null;
            while (ci.find()) {
                String x = ci.group(1);
                if (!x.contains("IWO probe")) continue;
                total++;
                if (x.contains(">Exception<")) { excId = find(x, "<t:ItemId Id=\"([^\"]+)\""); excStart = find(x, "<t:Start>([^<]+)<"); }
            }
            out.printf("  Новый FindItem: ResponseCode=%s, IncludesLastItemInRange=%s, вхождений серии %d, Exception найден: %b, Start=%s%n",
                    find(fb, "ResponseCode>([^<]+)<"), find(fb, "IncludesLastItemInRange=\"([a-z]+)\""), total, excId != null, excStart);
            if (excId == null) return;

            Resp g = ewsCall("<m:GetItem><m:ItemShape><t:BaseShape>IdOnly</t:BaseShape><t:AdditionalProperties>" +
                    "<t:FieldURI FieldURI=\"item:Body\"/><t:FieldURI FieldURI=\"calendar:OriginalStart\"/><t:FieldURI FieldURI=\"calendar:UID\"/>" +
                    "</t:AdditionalProperties></m:ItemShape><m:ItemIds><t:ItemId Id=\"" + excId + "\"/></m:ItemIds></m:GetItem>");
            String gb = new String(g.body, UTF8);
            out.printf("  GetItem Exception: HTTP %d, ResponseCode=%s, Body=%s, OriginalStart=%s (ожидается 2026-12-02T09:00:00Z)%n", g.code,
                    find(gb, "ResponseCode>([^<]+)<"), find(gb, "<t:Body[^>]*>([^<]{0,60})"), find(gb, "<t:OriginalStart>([^<]+)<"));
        } finally {
            if (masterId != null) {
                Resp d = ewsCall("<m:DeleteItem DeleteType=\"HardDelete\" SendMeetingCancellations=\"SendToNone\"><m:ItemIds><t:ItemId Id=\"" + masterId +
                        "\"/></m:ItemIds></m:DeleteItem>");
                out.printf("  DeleteItem серии (уборка): ResponseCode=%s%n", find(new String(d.body, UTF8), "ResponseCode>([^<]+)<"));
            }
        }
    }

    /** Как приложение ищет письмо в EWS по Message-ID: какие ограничения FindItem принимает Exchange 2007 SP2. */
    static void ewsMessageIdMode() {
        h1("I1. EWS FindItem по Message-ID");
        Resp f = ewsCall("<m:FindItem Traversal=\"Shallow\"><m:ItemShape><t:BaseShape>IdOnly</t:BaseShape>" +
                "<t:AdditionalProperties><t:FieldURI FieldURI=\"message:InternetMessageId\"/></t:AdditionalProperties></m:ItemShape>" +
                "<m:IndexedPageItemView MaxEntriesReturned=\"3\" Offset=\"0\" BasePoint=\"Beginning\"/>" +
                "<m:ParentFolderIds><t:DistinguishedFolderId Id=\"inbox\"/></m:ParentFolderIds></m:FindItem>");
        String fb = new String(f.body, UTF8);
        out.printf("  FindItem с AdditionalProperties InternetMessageId: HTTP %d, ResponseCode=%s%n", f.code, find(fb, "ResponseCode>([^<]+)<"));
        String mid = find(fb, "<t:InternetMessageId>([^<]+)</t:InternetMessageId>");
        out.println("  Message-ID получен: " + (mid != null) + (mid == null ? "" : ", вид: " + mid.replaceAll("[A-Za-z0-9]", "x")));
        if (mid == null) return;
        String[][] variants = {
                {"FieldURI message:InternetMessageId IsEqualTo (как приложение)",
                        "<t:IsEqualTo><t:FieldURI FieldURI=\"message:InternetMessageId\"/><t:FieldURIOrConstant><t:Constant Value=\"" + mid + "\"/></t:FieldURIOrConstant></t:IsEqualTo>"},
                {"ExtendedFieldURI PR_INTERNET_MESSAGE_ID 0x1035 IsEqualTo",
                        "<t:IsEqualTo><t:ExtendedFieldURI PropertyTag=\"0x1035\" PropertyType=\"String\"/><t:FieldURIOrConstant><t:Constant Value=\"" + mid + "\"/></t:FieldURIOrConstant></t:IsEqualTo>"},
        };
        // GetItem ровно как EasClient.fetchEmailBodyViaEws и варианты без отдельных полей
        Resp fi = ewsCall("<m:FindItem Traversal=\"Shallow\"><m:ItemShape><t:BaseShape>IdOnly</t:BaseShape></m:ItemShape>" +
                "<m:IndexedPageItemView MaxEntriesReturned=\"1\" Offset=\"0\" BasePoint=\"Beginning\"/>" +
                "<m:ParentFolderIds><t:DistinguishedFolderId Id=\"inbox\"/></m:ParentFolderIds></m:FindItem>");
        String itemId = find(new String(fi.body, UTF8), "<t:ItemId Id=\"([^\"]+)\"");
        String[] fieldSets = {
                "message:InternetMessageId,message:InReplyTo,item:Subject,item:DateTimeReceived,item:DateTimeSent",
                "message:InternetMessageId,item:Subject,item:DateTimeReceived,item:DateTimeSent",
                "item:InReplyTo",
                "message:References"
        };
        for (String fs : fieldSets) {
            StringBuilder ap = new StringBuilder();
            for (String field : fs.split(",")) ap.append("<t:FieldURI FieldURI=\"").append(field).append("\"/>");
            Resp g = ewsCall("<m:GetItem><m:ItemShape><t:BaseShape>Default</t:BaseShape><t:IncludeMimeContent>false</t:IncludeMimeContent>" +
                    "<t:BodyType>HTML</t:BodyType><t:AdditionalProperties>" + ap + "</t:AdditionalProperties></m:ItemShape>" +
                    "<m:ItemIds><t:ItemId Id=\"" + itemId + "\"/></m:ItemIds></m:GetItem>");
            String b = new String(g.body, UTF8);
            out.printf("  GetItem [%s]: HTTP %d, ResponseClass=%s, ResponseCode=%s, faultstring=%s, t:Body есть: %b%n", fs, g.code,
                    find(b, "ResponseClass=\"([A-Za-z]+)\""), find(b, "ResponseCode>([^<]+)<"),
                    find(b, "faultstring[^>]*>([^<]{0,160})"), b.contains("<t:Body"));
        }
        for (String[] v : variants) {
            Resp r = ewsCall("<m:FindItem Traversal=\"Shallow\"><m:ItemShape><t:BaseShape>IdOnly</t:BaseShape></m:ItemShape>" +
                    "<m:IndexedPageItemView MaxEntriesReturned=\"2\" Offset=\"0\" BasePoint=\"Beginning\"/>" +
                    "<m:Restriction>" + v[1] + "</m:Restriction>" +
                    "<m:SortOrder><t:FieldOrder Order=\"Descending\"><t:FieldURI FieldURI=\"item:DateTimeReceived\"/></t:FieldOrder></m:SortOrder>" +
                    "<m:ParentFolderIds><t:DistinguishedFolderId Id=\"inbox\"/></m:ParentFolderIds></m:FindItem>");
            String b = new String(r.body, UTF8);
            int n = 0;
            Matcher m = Pattern.compile("<t:ItemId Id=").matcher(b);
            while (m.find()) n++;
            out.printf("  %s: HTTP %d, ResponseClass=%s, ResponseCode=%s, MessageText=%s, найдено %d%n", v[0], r.code,
                    find(b, "ResponseClass=\"([A-Za-z]+)\""), find(b, "ResponseCode>([^<]+)<"), find(b, "MessageText>([^<]+)<"), n);
        }
    }

    static void ewsNtlm() {
        Authenticator auth = new Authenticator() {
            @Override protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(DOMAIN + "\\" + USER, pass.toCharArray());
            }
        };
        String hdr = "<?xml version=\"1.0\" encoding=\"utf-8\"?><soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                "xmlns:t=\"http://schemas.microsoft.com/exchange/services/2006/types\" xmlns:m=\"http://schemas.microsoft.com/exchange/services/2006/messages\">" +
                "<soap:Header><t:RequestServerVersion Version=\"Exchange2007_SP1\"/></soap:Header><soap:Body>";
        String ftr = "</soap:Body></soap:Envelope>";
        String[][] cases = {
                {"GetFolder inbox (корректный)", "<m:GetFolder><m:FolderShape><t:BaseShape>IdOnly</t:BaseShape></m:FolderShape><m:FolderIds><t:DistinguishedFolderId Id=\"inbox\"/></m:FolderIds></m:GetFolder>"},
                {"GetItem с битым ItemId", "<m:GetItem><m:ItemShape><t:BaseShape>IdOnly</t:BaseShape></m:ItemShape><m:ItemIds><t:ItemId Id=\"AAAA\"/></m:ItemIds></m:GetItem>"},
                {"Нарушение схемы", "<m:GetFolder><m:Bogus/></m:GetFolder>"},
                {"Не XML", null},
        };
        for (String[] cs : cases) {
            Resp r = new Resp();
            long t0 = System.nanoTime();
            try {
                HttpsURLConnection c = (HttpsURLConnection) new URL(BASE + "/EWS/Exchange.asmx").openConnection();
                c.setSSLSocketFactory(sf);
                c.setHostnameVerifier((h, s) -> true);
                c.setAuthenticator(auth);
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setConnectTimeout(15_000);
                c.setReadTimeout(60_000);
                c.setRequestProperty("Content-Type", "text/xml; charset=utf-8");
                c.setRequestProperty("User-Agent", UA);
                byte[] body = (cs[1] == null ? "not xml at all" : hdr + cs[1] + ftr).getBytes(UTF8);
                try (OutputStream o = c.getOutputStream()) { o.write(body); }
                r.code = c.getResponseCode();
                InputStream in = r.code >= 400 ? c.getErrorStream() : c.getInputStream();
                if (in != null) try (in) { r.body = in.readAllBytes(); }
            } catch (Exception ex) { r.error = ex.getClass().getSimpleName() + ": " + ex.getMessage(); }
            r.ms = (System.nanoTime() - t0) / 1_000_000;
            String b = new String(r.body, UTF8);
            boolean soap = b.toLowerCase(Locale.ROOT).contains("soap");
            String verdict;
            if (r.code >= 200 && r.code < 300) verdict = "приложение вернёт тело (успех)";
            else if (r.code == 500 && soap) verdict = "приложение вернёт тело SOAP fault (без повтора)";
            else verdict = "приложение получит null и ПОВТОРИТ весь запрос (attempt 1)";
            out.printf("  %s: HTTP %d за %,d мс; ResponseClass=%s, ResponseCode=%s, faultcode=%s, тело содержит 'soap': %b -> %s%s%n",
                    cs[0], r.code, r.ms, find(b, "ResponseClass=\"([A-Za-z]+)\""), find(b, "ResponseCode>([^<]+)<"), find(b, "faultcode>([^<]+)<"),
                    soap, verdict, r.error == null ? "" : " (" + r.error + ")");
        }
    }

    // ============================== main ==============================

    static void h1(String s) { out.println(); out.println("=== " + s + " ==="); }

    static String hex(byte[] b) { StringBuilder sb = new StringBuilder(); for (byte x : b) sb.append(String.format("%02x", x)); return sb.toString(); }

    public static void main(String[] args) throws Exception {
        // Время в выводе — в UTC: часовой пояс машины не должен попадать в коммитимые отчёты
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
        String mode = args.length > 0 ? args[0] : "full";
        pass = System.getenv("IWO_AUDIT_PASS");
        if (pass == null || pass.isEmpty()) { out.println("Нет IWO_AUDIT_PASS"); return; }
        sf = ctx("TLS").getSocketFactory();
        out.println("EasProbe, режим " + mode + ", запуск " + new Date() + ", JDK " + System.getProperty("java.version"));
        // Адрес, учётная запись и сертификат в журнал не пишутся: журналы лежат в репозитории
        out.println("Сервер и учётная запись — из IWO_AUDIT_*, DeviceId " + DEVICE_ID + " (отдельное партнёрство аудита)");
        sectionTls();
        if (mode.equals("tls")) return;
        if (mode.equals("followup")) { followup(); return; }
        if (mode.equals("att")) { attMode(); return; }
        if (mode.equals("ewsatt")) { ewsAttMode(); return; }
        if (mode.equals("ewsmid")) { ewsMessageIdMode(); return; }
        if (mode.equals("fielduris")) { fieldUriMode(args.length > 1 ? args[1] : ""); return; }
        if (mode.equals("calview")) { calendarViewMode(); return; }
        if (mode.equals("calexc")) { calendarExceptionMode(); return; }
        if (mode.equals("att2")) { att2Mode(); return; }
        sectionPorts();
        sectionOptions();
        if (!sectionProvision()) { out.println("Provision не удался, остановка."); return; }
        sectionFolderSync();
        sectionMailboxSize();
        sectionEstimate();
        sectionBigBodies();
        sectionMime();
        sectionAttachments();
        sectionServerIdStability();
        sectionErrors();
        sectionPing();
        sectionEws();
        out.println();
        out.println("Готово " + new Date());
    }
}
