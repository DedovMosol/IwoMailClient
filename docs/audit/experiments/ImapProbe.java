import javax.mail.*;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.*;

/**
 * Эмпирическая проверка UI2-01, UI2-02, UI2-05, UI2-07 (IMAP/POP3) с той же библиотекой, что в приложении:
 * com.sun.mail:android-mail:1.6.7 (jar из кэша Gradle проекта).
 *
 * Часть A (локально, 127.0.0.1, фиктивный пароль "fake-secret"): поддельные IMAP/POP3-серверы объявляют
 * РОВНО те возможности, что объявил реальный сервер 192.0.2.10 (см. output_EasProbe.txt, T2),
 * а клиент JavaMail создаётся с теми же свойствами, что ImapClient.kt:26-49 и Pop3Client.kt.
 * Сервер записывает, что клиент прислал. Настоящий пароль по открытому каналу не отправляется.
 *
 * Часть B (реальный сервер, только IMAPS 993, т.е. внутри TLS, только чтение): список папок и
 * классификация по правилу ImapClient.getFolders; статистика Message-ID (основа идентификатора письма
 * в MessageToEntityConverter: accountId_Message-ID без папки). Вывод без тем, адресов и тел.
 *
 * Запуск: см. run_imap_probe.txt
 */
public class ImapProbe {

    static final PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
    static final String REAL_CAPS = "IMAP4 IMAP4rev1 AUTH=NTLM AUTH=GSSAPI AUTH=PLAIN STARTTLS IDLE NAMESPACE LITERAL+";

    // ---------------- поддельный IMAP ----------------

    static List<String> fakeImap(ServerSocket ss, boolean tls) {
        List<String> got = Collections.synchronizedList(new ArrayList<>());
        Thread t = new Thread(() -> {
            try (Socket s = ss.accept()) {
                s.setSoTimeout(8000);
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1));
                Writer w = new OutputStreamWriter(s.getOutputStream(), StandardCharsets.ISO_8859_1);
                w.write("* OK Microsoft Exchange Server 2007 IMAP4 service ready\r\n"); w.flush();
                String l;
                while ((l = in.readLine()) != null) {
                    got.add(l);
                    String[] p = l.split(" ", 3);
                    String tag = p[0], cmd = p.length > 1 ? p[1].toUpperCase(Locale.ROOT) : "";
                    switch (cmd) {
                        case "CAPABILITY" -> { w.write("* CAPABILITY " + REAL_CAPS + "\r\n" + tag + " OK CAPABILITY completed.\r\n"); }
                        case "STARTTLS" -> { w.write(tag + " BAD audit-stop\r\n"); w.flush(); return; }
                        case "AUTHENTICATE" -> {
                            w.write("+ \r\n"); w.flush();
                            String resp = in.readLine();
                            got.add("  [ответ на AUTHENTICATE] " + resp + "  => base64-декод: " + decodePlain(resp));
                            w.write(tag + " NO AUTHENTICATE failed.\r\n"); w.flush(); return;
                        }
                        case "LOGIN" -> { w.write(tag + " NO LOGIN failed.\r\n"); w.flush(); return; }
                        case "LOGOUT" -> { w.write("* BYE\r\n" + tag + " OK\r\n"); w.flush(); return; }
                        default -> w.write(tag + " BAD unknown\r\n");
                    }
                    w.flush();
                }
            } catch (Exception e) {
                got.add("[сервер: " + e.getClass().getSimpleName() + ": " + e.getMessage() + "]");
            }
        });
        t.setDaemon(true);
        t.start();
        return got;
    }

    static String decodePlain(String b64) {
        try {
            byte[] d = Base64.getDecoder().decode(b64.trim());
            return new String(d, StandardCharsets.UTF_8).replace('\0', '|');
        } catch (Exception e) { return "(не base64)"; }
    }

    static Properties appImapProps(boolean useSSL, boolean acceptAll, int port) {
        Properties props = new Properties();
        String protocol = useSSL ? "imaps" : "imap";
        props.put("mail.store.protocol", protocol);
        props.put("mail.imap.host", "127.0.0.1");
        props.put("mail.imaps.host", "127.0.0.1");
        props.put("mail.imap.port", String.valueOf(port));
        props.put("mail.imaps.port", String.valueOf(port));
        props.put("mail.imap.ssl.enable", String.valueOf(useSSL));
        props.put("mail.imaps.ssl.enable", String.valueOf(useSSL));
        props.put("mail.imap.starttls.enable", "false");
        props.put("mail.imaps.starttls.enable", "false");
        if (acceptAll) {
            props.put("mail.imap.ssl.trust", "*");
            props.put("mail.imaps.ssl.trust", "*");
            props.put("mail.imap.ssl.checkserveridentity", "false");
            props.put("mail.imaps.ssl.checkserveridentity", "false");
        }
        props.put("mail.imap.connectiontimeout", "5000");
        props.put("mail.imap.timeout", "5000");
        props.put("mail.imaps.connectiontimeout", "5000");
        props.put("mail.imaps.timeout", "5000");
        return props;
    }

    static void tryConnect(String label, Properties props, String protocol, int port, List<String> got) {
        String result;
        try {
            Store st = Session.getInstance(props).getStore(protocol);
            st.connect("127.0.0.1", port, "u", "fake-secret");
            result = "подключено";
            st.close();
        } catch (Exception e) {
            result = e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()).replaceAll("\\s+", " ");
        }
        try { Thread.sleep(300); } catch (InterruptedException ignored) { }
        out.println("  " + label);
        out.println("    исход у клиента: " + result);
        synchronized (got) { for (String g : got) out.println("    клиент -> сервер: " + g); }
        boolean leaked = false;
        synchronized (got) { for (String g : got) if (g.contains("fake-secret")) leaked = true; }
        out.println("    поддельный сервер получил пароль в читаемом виде: " + (leaked ? "ДА" : "нет"));
    }

    static void partA() throws Exception {
        out.println("=== A1. IMAP порт 143, свойства как в ImapClient (useSSL=false, starttls.enable=false), возможности как у реального сервера ===");
        try (ServerSocket ss = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            List<String> got = fakeImap(ss, false);
            tryConnect("JavaMail 1.6.7 + свойства приложения", appImapProps(false, false, ss.getLocalPort()), "imap", ss.getLocalPort(), got);
        }
        out.println();
        out.println("=== A2. То же, но предлагаемое исправление: starttls.enable=true, starttls.required=true ===");
        try (ServerSocket ss = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            List<String> got = fakeImap(ss, false);
            Properties p = appImapProps(false, false, ss.getLocalPort());
            p.put("mail.imap.starttls.enable", "true");
            p.put("mail.imap.starttls.required", "true");
            tryConnect("JavaMail 1.6.7 + STARTTLS required", p, "imap", ss.getLocalPort(), got);
        }
        out.println();
        out.println("=== A3. POP3 порт 110, свойства как в Pop3Client, CAPA как у реального сервера ===");
        try (ServerSocket ss = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            List<String> got = Collections.synchronizedList(new ArrayList<>());
            Thread t = new Thread(() -> {
                try (Socket s = ss.accept()) {
                    s.setSoTimeout(8000);
                    BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1));
                    Writer w = new OutputStreamWriter(s.getOutputStream(), StandardCharsets.ISO_8859_1);
                    w.write("+OK Microsoft Exchange Server 2007 POP3 service ready\r\n"); w.flush();
                    String l;
                    while ((l = in.readLine()) != null) {
                        got.add(l);
                        String c = l.split(" ")[0].toUpperCase(Locale.ROOT);
                        if (c.equals("CAPA")) w.write("+OK\r\nTOP\r\nUIDL\r\nSASL NTLM GSSAPI PLAIN\r\nUSER\r\nSTLS\r\n.\r\n");
                        else if (c.equals("USER")) w.write("+OK\r\n");
                        else if (c.equals("PASS")) { w.write("-ERR fail\r\n"); w.flush(); return; }
                        else if (c.equals("AUTH")) { w.write("+ \r\n"); w.flush(); String r = in.readLine(); got.add("  [ответ на AUTH] " + r + " => " + decodePlain(r)); w.write("-ERR fail\r\n"); w.flush(); return; }
                        else if (c.equals("STLS")) { w.write("-ERR audit-stop\r\n"); w.flush(); return; }
                        else if (c.equals("QUIT")) { w.write("+OK\r\n"); w.flush(); return; }
                        else w.write("-ERR\r\n");
                        w.flush();
                    }
                } catch (Exception e) { got.add("[сервер: " + e + "]"); }
            });
            t.setDaemon(true);
            t.start();
            Properties p = new Properties();
            int port = ss.getLocalPort();
            p.put("mail.store.protocol", "pop3");
            p.put("mail.pop3.host", "127.0.0.1");
            p.put("mail.pop3.port", String.valueOf(port));
            p.put("mail.pop3.ssl.enable", "false");
            p.put("mail.pop3.starttls.enable", "false");
            p.put("mail.pop3.connectiontimeout", "5000");
            p.put("mail.pop3.timeout", "5000");
            tryConnect("JavaMail 1.6.7 + свойства Pop3Client", p, "pop3", port, got);
        }
        out.println();
        out.println("=== A4. IMAPS: сертификат доверенный, но выписан на другое имя (CN=evil.example), useSSL=true, acceptAllCerts=false ===");
        partA4(false);
        out.println();
        out.println("=== A5. То же с предлагаемым исправлением mail.imaps.ssl.checkserveridentity=true ===");
        partA4(true);
    }

    static void partA4(boolean check) throws Exception {
        String ks = System.getProperty("audit.evilKeystore");
        if (ks == null) { out.println("  нет -Daudit.evilKeystore, пропуск"); return; }
        KeyStoreHolder.init(ks);
        SSLServerSocket ss = (SSLServerSocket) KeyStoreHolder.serverCtx.getServerSocketFactory()
                .createServerSocket(0, 1, InetAddress.getLoopbackAddress());
        try (ss) {
            List<String> got = fakeImap(ss, true);
            Properties p = appImapProps(true, false, ss.getLocalPort());
            if (check) p.put("mail.imaps.ssl.checkserveridentity", "true");
            tryConnect("JavaMail 1.6.7, imaps, checkserveridentity=" + (check ? "true" : "не задан (как в приложении)"), p, "imaps", ss.getLocalPort(), got);
        }
    }

    static final class KeyStoreHolder {
        static SSLContext serverCtx;
        static void init(String path) throws Exception {
            if (serverCtx != null) return;
            java.security.KeyStore k = java.security.KeyStore.getInstance("PKCS12");
            try (FileInputStream f = new FileInputStream(path)) { k.load(f, "changeit".toCharArray()); }
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(k, "changeit".toCharArray());
            serverCtx = SSLContext.getInstance("TLS");
            serverCtx.init(kmf.getKeyManagers(), null, null);
        }
    }

    // ---------------- реальный сервер, IMAPS 993, только чтение ----------------

    static final class PinTM implements X509TrustManager {
        public void checkClientTrusted(X509Certificate[] c, String a) throws CertificateException { throw new CertificateException(); }
        public void checkServerTrusted(X509Certificate[] chain, String a) throws CertificateException {
            if (!chain[0].getSubjectX500Principal().getName().contains(System.getenv("IWO_AUDIT_CERT_CN"))) throw new CertificateException("unexpected");
            chain[0].checkValidity();
        }
        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }

    static int appType(String name) {
        if (name.equalsIgnoreCase("INBOX")) return 2;
        if (name.toLowerCase(Locale.ROOT).contains("sent")) return 5;
        if (name.toLowerCase(Locale.ROOT).contains("draft")) return 3;
        if (name.toLowerCase(Locale.ROOT).contains("trash")) return 4;
        if (name.toLowerCase(Locale.ROOT).contains("junk") || name.toLowerCase(Locale.ROOT).contains("spam")) return 11;
        return 1;
    }

    static void partB() throws Exception {
        out.println();
        out.println("=== B1. Реальный сервер, IMAPS 993 (TLS), только чтение: папки и классификация как в ImapClient.getFolders ===");
        String pass = System.getenv("IWO_AUDIT_PASS");
        if (pass == null) { out.println("  нет IWO_AUDIT_PASS, пропуск"); return; }
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[]{new PinTM()}, null);
        Properties p = new Properties();
        p.put("mail.store.protocol", "imaps");
        p.put("mail.imaps.ssl.enable", "true");
        p.put("mail.imaps.ssl.socketFactory", ctx.getSocketFactory());
        p.put("mail.imaps.ssl.checkserveridentity", "false"); // подключение по IP; сертификат закреплён PinTM
        p.put("mail.imaps.connectiontimeout", "15000");
        p.put("mail.imaps.timeout", "60000");
        Store st = Session.getInstance(p).getStore("imaps");
        long t0 = System.currentTimeMillis();
        try {
            // Сервер и учётная запись — только из окружения (в репозитории их нет)
            st.connect(System.getenv("IWO_AUDIT_HOST"), 993,
                    System.getenv("IWO_AUDIT_DOMAIN") + "\\" + System.getenv("IWO_AUDIT_USER"), pass);
        } catch (Exception e) {
            out.println("  вход по IMAPS не удался: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return;
        }
        out.println("  вход по IMAPS: OK за " + (System.currentTimeMillis() - t0) + " мс");
        Folder[] all = st.getDefaultFolder().list("*");
        Map<Integer, Integer> types = new TreeMap<>();
        int noselect = 0, children = 0;
        List<Folder> mailFolders = new ArrayList<>();
        Map<String, Integer> std = new LinkedHashMap<>();
        for (Folder f : all) {
            int t = appType(f.getName());
            types.merge(t, 1, Integer::sum);
            if ((f.getType() & Folder.HOLDS_MESSAGES) == 0) noselect++;
            if (f instanceof com.sun.mail.imap.IMAPFolder) {
                String[] attrs = ((com.sun.mail.imap.IMAPFolder) f).getAttributes();
                if (attrs != null) for (String a : attrs) if (a.equalsIgnoreCase("\\Noselect")) noselect++;
            }
            if ((f.getType() & Folder.HOLDS_MESSAGES) != 0) mailFolders.add(f);
        }
        String[] stdNames = {"INBOX", "Отправленные", "Черновики", "Удаленные", "Нежелательная почта", "Sent Items", "Drafts", "Deleted Items", "Junk E-mail"};
        for (String n : stdNames) {
            boolean exists = false;
            for (Folder f : all) if (f.getFullName().equalsIgnoreCase(n)) exists = true;
            if (exists) std.put(n, appType(n));
        }
        out.println("  Папок в LIST: " + all.length + "; тип по правилу приложения {тип=число}: " + types + " (1 = пользовательская)");
        out.println("  Стандартные папки Exchange, найденные в LIST, и тип, который им присвоит приложение: " + std);
        out.println("  Папок без HOLDS_MESSAGES или с \\Noselect: " + noselect);

        out.println();
        out.println("=== B2. Message-ID как основа id письма (MessageToEntityConverter: accountId_Message-ID или случайный UUID) ===");
        Map<String, String> firstFolderById = new HashMap<>();
        int totalMsgs = 0, noMid = 0, dupInFolder = 0, dupAcross = 0;
        String[] targets = {"INBOX", "Отправленные", "Sent Items", "Черновики", "Drafts", "Удаленные", "Deleted Items"};
        for (String name : targets) {
            Folder f = null;
            for (Folder x : mailFolders) if (x.getFullName().equalsIgnoreCase(name)) f = x;
            if (f == null) continue;
            f.open(Folder.READ_ONLY);
            int n = f.getMessageCount();
            int from = Math.max(1, n - 2999);
            Message[] msgs = n == 0 ? new Message[0] : f.getMessages(from, n);
            FetchProfile fp = new FetchProfile();
            fp.add("Message-ID");
            fp.add(UIDFolder.FetchProfileItem.UID);
            f.fetch(msgs, fp);
            Set<String> local = new HashSet<>();
            int localNo = 0, localDup = 0, across = 0;
            for (Message m : msgs) {
                String[] h = m.getHeader("Message-ID");
                if (h == null || h.length == 0 || h[0].isBlank()) { localNo++; continue; }
                String id = h[0].trim();
                if (!local.add(id)) localDup++;
                String prev = firstFolderById.putIfAbsent(id, f.getFullName());
                if (prev != null && !prev.equals(f.getFullName())) across++;
            }
            long uidv = ((UIDFolder) f).getUIDValidity();
            out.printf("  %s: сообщений %d (проверено %d), без Message-ID %d, повтор Message-ID внутри папки %d, совпадение с другой проверенной папкой %d, UIDVALIDITY есть: %b%n",
                    "тип " + appType(f.getFullName()) + (f.getFullName().equalsIgnoreCase("INBOX") ? " (INBOX)" : ""), n, msgs.length, localNo, localDup, across, uidv > 0);
            totalMsgs += msgs.length; noMid += localNo; dupInFolder += localDup; dupAcross += across;
            f.close(false);
        }
        out.printf("  Итого проверено %d: без Message-ID %d (получат случайный UUID при каждой синхронизации), повторов внутри папки %d, совпадений между папками %d (одна строка БД на два письма)%n",
                totalMsgs, noMid, dupInFolder, dupAcross);
        st.close();
    }

    public static void main(String[] args) throws Exception {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC")); // часовой пояс машины — не в отчёт
        out.println("ImapProbe " + new Date() + ", JavaMail " + Session.class.getPackage().getImplementationVersion());
        partA();
        if (args.length > 0 && args[0].equals("real")) partB();
    }
}
