import java.util.*;
import java.util.regex.Pattern;

/**
 * Проверка предложенного исправления MAIL-03 (аудит v2).
 * Парные ленивые выражения вида (?si)<tag[^>]*>.*?</tag> из HtmlUtils.kt заменяются
 * линейным сканером на indexOf. Семантика должна совпасть: находится "<tag", затем первый '>',
 * затем первое "</tag>" (без учёта регистра, только ASCII, как (?i) в java.util.regex).
 * Если закрывающего тега дальше нет, совпадений дальше тоже не будет, и сканер останавливается.
 *
 * Проверяется: 1) совпадение результата с исходными выражениями на 300 000 случайных строк;
 * 2) время на "письме-яде" и на обычном письме.
 *
 * Запуск: java SanitizerLinearFix.java   (JDK 17)
 */
public class SanitizerLinearFix {

    static final String[] TAGS = {"script", "iframe", "object", "applet"};
    static final Map<String, Pattern> PAIRED = new HashMap<>();
    static {
        for (String t : TAGS) PAIRED.put(t, Pattern.compile("(?si)<" + t + "[^>]*>.*?</" + t + ">"));
    }

    static String origRemovePaired(String h, String tag) {
        return PAIRED.get(tag).matcher(h).replaceAll("");
    }

    /** ASCII-нижний регистр с сохранением длины (как (?i) без UNICODE_CASE). */
    static String asciiLower(String s) {
        char[] c = s.toCharArray();
        for (int i = 0; i < c.length; i++) if (c[i] >= 'A' && c[i] <= 'Z') c[i] = (char) (c[i] + 32);
        return new String(c);
    }

    static String fixRemovePaired(String h, String tag) {
        String low = asciiLower(h);
        String open = "<" + tag, close = "</" + tag + ">";
        StringBuilder out = null;
        int copyFrom = 0, p = low.indexOf(open);
        while (p >= 0) {
            int gt = low.indexOf('>', p + open.length());
            if (gt < 0) break;                                  // дальше нет '>' -> совпадений нет
            int c = low.indexOf(close, gt + 1);
            if (c < 0) break;                                   // дальше нет закрывающего -> совпадений нет
            if (out == null) out = new StringBuilder(h.length());
            out.append(h, copyFrom, p);
            copyFrom = c + close.length();
            p = low.indexOf(open, copyFrom);
        }
        if (out == null) return h;
        out.append(h, copyFrom, h.length());
        return out.toString();
    }

    static String orig(String h) { for (String t : TAGS) h = origRemovePaired(h, t); return h; }
    static String fix(String h) { for (String t : TAGS) h = fixRemovePaired(h, t); return h; }

    public static void main(String[] args) {
        String[] frags = {"<script>", "</script>", "<SCRIPT a=1>", "</SCRIPT>", "<scripts>", "<script", "</scr", "ipt>",
                "<iframe>", "</iframe>", "<IfRaMe src=x>", "<object>", "</object>", "<applet>", "</applet>",
                "x", "Привет", ">", "<", "</", "\n", " ", "<b>", "</b>", "İ", "ß"};
        Random r = new Random(42);
        int n = 300_000, diff = 0;
        for (int i = 0; i < n; i++) {
            StringBuilder sb = new StringBuilder();
            int len = 1 + r.nextInt(25);
            for (int k = 0; k < len; k++) sb.append(frags[r.nextInt(frags.length)]);
            String s = sb.toString();
            String a = orig(s), b = fix(s);
            if (!a.equals(b)) {
                diff++;
                if (diff <= 5) System.out.println("РАСХОЖДЕНИЕ: вход=" + s + " | regex=" + a + " | сканер=" + b);
            }
        }
        System.out.printf("Эквивалентность: %,d случайных строк, расхождений %d%n", n, diff);

        String[] fixed = {"<scr<script>ipt>alert(1)</scr</script>ipt>", "<p>a</p><script>x</script><p>b</p>",
                "<SCRIPT>x</script>tail", "<iframe src=//e>x</IFRAME><iframe>"};
        for (String s : fixed) System.out.println("  " + s + "  ->  regex=" + orig(s) + "  сканер=" + fix(s) + "  равны=" + orig(s).equals(fix(s)));

        System.out.println();
        System.out.println("Время (только парные выражения, 4 тега):");
        for (String tag : new String[]{"<iframe>", "<script>"}) {
            for (int k : new int[]{10_000, 20_000, 40_000, 80_000, 1_000_000}) {
                String body = tag.repeat(k);
                long t = System.nanoTime();
                fix(body);
                double fixMs = (System.nanoTime() - t) / 1e6;
                String origMs = "не запускалось (квадратично)";
                if (k <= 20_000) {
                    t = System.nanoTime();
                    orig(body);
                    origMs = String.format("%,.0f мс", (System.nanoTime() - t) / 1e6);
                }
                System.out.printf("  %-9s x %,9d (%,10d байт): сканер %,8.1f мс | regex %s%n", tag, k, body.length(), fixMs, origMs);
            }
        }
        String normal = "<p>Hello <b>world</b></p><script>x</script>".repeat(40_000);
        long t = System.nanoTime();
        String a = orig(normal);
        double o = (System.nanoTime() - t) / 1e6;
        t = System.nanoTime();
        String b = fix(normal);
        double f = (System.nanoTime() - t) / 1e6;
        System.out.printf("  обычный HTML (%,d байт): regex %,.0f мс, сканер %,.1f мс, результаты равны=%b%n", normal.length(), o, f, a.equals(b));
    }
}
