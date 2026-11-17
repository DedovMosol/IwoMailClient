import java.util.regex.Pattern;

/**
 * Контроль к MAIL-02 / MAIL-03: сколько sanitizeEmailHtml занимает на ОБЫЧНОМ (не вредоносном) письме.
 * Те же 17 выражений и тот же порядок, что в app/.../util/HtmlUtils.kt:80-115.
 * Показывает, что главный поток блокируется не только "ядовитыми" письмами: цена линейна от размера.
 *
 * Запуск: java SanitizerControl.java   (JDK 17)
 */
public class SanitizerControl {
    static final Pattern[] P = {
            Pattern.compile("(?si)<script[^>]*>.*?</script>"), Pattern.compile("(?i)</?script[^>]*>"),
            Pattern.compile("(?si)<iframe[^>]*>.*?</iframe>"), Pattern.compile("(?i)</?iframe[^>]*>"),
            Pattern.compile("(?si)<object[^>]*>.*?</object>"), Pattern.compile("(?i)</?object[^>]*>"),
            Pattern.compile("(?i)<embed\\b[^>]*/?>"),
            Pattern.compile("(?si)<applet[^>]*>.*?</applet>"), Pattern.compile("(?i)</?applet[^>]*>"),
            Pattern.compile("(?i)<meta\\b[^>]*http-equiv\\s*=\\s*['\"]?refresh['\"]?[^>]*>"),
            Pattern.compile("(?i)\\s+on\\w+\\s*=\\s*\"[^\"]*\""), Pattern.compile("(?i)\\s+on\\w+\\s*=\\s*'[^']*'"),
            Pattern.compile("(?i)\\s+on\\w+\\s*=\\s*[^\\s>\"']+"),
            Pattern.compile("(?i)(href|src|action|formaction|xlink:href)\\s*=\\s*\"\\s*javascript:[^\"]*\""),
            Pattern.compile("(?i)(href|src|action|formaction|xlink:href)\\s*=\\s*'\\s*javascript:[^']*'"),
            Pattern.compile("(?i)(href|src|action|formaction|xlink:href)\\s*=\\s*\"\\s*data:text/html[^\"]*\""),
            Pattern.compile("(?i)(href|src|action|formaction|xlink:href)\\s*=\\s*'\\s*data:text/html[^']*'")};

    static String run(String h) {
        for (Pattern p : P) h = p.matcher(h).replaceAll("$0".equals("x") ? "" : "");
        return h;
    }

    public static void main(String[] a) {
        for (int i = 0; i < 3; i++) run("<p>warmup</p>".repeat(1000));
        String unit = "<table><tr><td style=\"color:#333\"><p>Hello <b>world</b> <a href=\"https://example.com/x\">link</a></p></td></tr></table>\n";
        for (int mult : new int[]{500, 2_000, 8_000, 16_000}) {
            String body = unit.repeat(mult);
            long t = System.nanoTime();
            run(body);
            System.out.printf("обычный HTML, %,9d байт: %,6.0f мс%n", body.length(), (System.nanoTime() - t) / 1e6);
        }
        System.out.println("Примечание: 'замена' здесь пустая строка, для замеров поиска этого достаточно.");
    }
}
