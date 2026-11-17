import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Проверка предложенного исправления MAIL-25 (аудит v2).
 * Исходная e-mail-ветвь BODY_LINK_REGEX (EmailUtils.kt:11-17) против варианта с границами длины
 * по RFC 5321: локальная часть до 64 символов, домен до 253, TLD до 63.
 *
 * Запуск: java BodyLinkFix.java   (JDK 17)
 */
public class BodyLinkFix {
    static final Pattern ORIG = Pattern.compile("(\\b[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}\\b)");
    static final Pattern FIX = Pattern.compile("(\\b[a-zA-Z0-9._%+\\-]{1,64}@[a-zA-Z0-9.\\-]{1,253}\\.[a-zA-Z]{2,63}\\b)");

    static List<String> all(Pattern p, String s) {
        List<String> r = new ArrayList<>();
        Matcher m = p.matcher(s);
        while (m.find()) r.add(m.start() + ":" + m.group());
        return r;
    }

    public static void main(String[] args) {
        System.out.println("Время на 'a.' x N:");
        for (int n : new int[]{2_500, 5_000, 10_000, 20_000, 500_000}) {
            String s = "a.".repeat(n);
            long t = System.nanoTime();
            boolean f = FIX.matcher(s).find();
            double fixMs = (System.nanoTime() - t) / 1e6;
            String o = "не запускалось (квадратично)";
            if (n <= 10_000) {
                t = System.nanoTime();
                ORIG.matcher(s).find();
                o = String.format("%,.0f мс", (System.nanoTime() - t) / 1e6);
            }
            System.out.printf("  %,9d симв.: исправленный %,8.1f мс (найдено=%b) | исходный %s%n", s.length(), fixMs, f, o);
        }
        // Доменная часть: длинный домен без TLD
        String dom = "x@" + "a-".repeat(200_000);
        long t = System.nanoTime();
        FIX.matcher(dom).find();
        System.out.printf("  'x@'+'a-' x 200000: исправленный %,.1f мс%n", (System.nanoTime() - t) / 1e6);

        // Эквивалентность на случайных коротких строках (локальная часть заведомо < 64)
        String[] fr = {"a", "B", "1", ".", "-", "_", "%", "+", "@", " ", "ru", "com", "x.", "иван", "\n", ","};
        Random r = new Random(7);
        int diff = 0, total = 200_000;
        for (int i = 0; i < total; i++) {
            StringBuilder sb = new StringBuilder();
            int len = 1 + r.nextInt(20);
            for (int k = 0; k < len; k++) sb.append(fr[r.nextInt(fr.length)]);
            String s = sb.toString();
            if (!all(ORIG, s).equals(all(FIX, s))) {
                diff++;
                if (diff <= 5) System.out.println("  РАСХОЖДЕНИЕ: " + s + " | " + all(ORIG, s) + " | " + all(FIX, s));
            }
        }
        System.out.printf("Эквивалентность на %,d случайных строках длиной до ~60: расхождений %d%n", total, diff);
        String normal = "Привет, пишите на ivan@example.com или звоните. ".repeat(20_000);
        System.out.printf("Обычный текст: исходный нашёл %d, исправленный %d%n", all(ORIG, normal).size(), all(FIX, normal).size());
        String longLocal = "a".repeat(70) + "@example.com";
        System.out.println("Локальная часть 70 символов: исходный=" + all(ORIG, longLocal) + " исправленный=" + all(FIX, longLocal));
    }
}
