import java.util.regex.Pattern;

/**
 * Уточнение к MAIL-08: переживает ли вложенный <script> один проход sanitizeEmailHtml
 * (HtmlUtils.kt:80-115) и одну CSP-независимую проверку "остался ли тег script в результате".
 * Выражения и порядок те же, что в приложении (см. SanitizerSim.java).
 *
 * Запуск: java SanitizerNesting.java   (JDK 17)
 */
public class SanitizerNesting {
    static final Pattern[] P = {
            Pattern.compile("(?si)<script[^>]*>.*?</script>"), Pattern.compile("(?i)</?script[^>]*>"),
            Pattern.compile("(?si)<iframe[^>]*>.*?</iframe>"), Pattern.compile("(?i)</?iframe[^>]*>"),
            Pattern.compile("(?si)<object[^>]*>.*?</object>"), Pattern.compile("(?i)</?object[^>]*>"),
            Pattern.compile("(?i)<embed\\b[^>]*/?>"),
            Pattern.compile("(?si)<applet[^>]*>.*?</applet>"), Pattern.compile("(?i)</?applet[^>]*>")};

    static String pass(String h) {
        for (Pattern p : P) h = p.matcher(h).replaceAll("");
        return h;
    }

    public static void main(String[] a) {
        String[][] cases = {
                {"1 уровень", "<scr<script>ipt>alert(1)</scr</script>ipt>"},
                {"2 уровня", "<scr<scr<script>ipt>ipt>alert(1)</script>"},
                {"2 уровня, оба закрыты", "<scr<scr<script>ipt>ipt>alert(1)</scr</scr</script>ipt>ipt>"},
                {"3 уровня", "<s<s<s<script>cript>cript>cript>alert(1)</script>"},
                {"iframe, 2 уровня", "<ifr<ifr<iframe>ame>ame src=//evil.example>"},
                {"разрыв через комментарий", "<scr<!-- -->ipt>alert(1)</scr<!-- -->ipt>"},
        };
        for (String[] c : cases) {
            String once = pass(c[1]);
            String twice = pass(once);
            boolean liveOnce = once.toLowerCase().contains("<script") || once.toLowerCase().contains("<iframe");
            boolean liveTwice = twice.toLowerCase().contains("<script") || twice.toLowerCase().contains("<iframe");
            System.out.printf("%-26s | после 1 прохода: [%s] живой тег: %-5b | после 2: [%s] живой тег: %-5b | идемпотентна: %b%n",
                    c[0], once, liveOnce, twice, liveTwice, once.equals(twice));
        }
    }
}
