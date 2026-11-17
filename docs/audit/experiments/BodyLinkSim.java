import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Эксперимент MAIL-25: регулярное выражение адреса электронной почты из util/EmailUtils.kt:11-17,
 * применяется к тексту письма в composition на главном потоке (EmailDetailScreen.kt:1706).
 * Здесь проверяется только e-mail-ветвь: (\b[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}\b)
 *
 * Запуск: java BodyLinkSim.java   (JDK 17)
 */
public class BodyLinkSim {
    public static void main(String[] args) {
        Pattern p = Pattern.compile("(\\b[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}\\b)");
        // Размеры подобраны так, чтобы прогон укладывался в минуты: рост времени виден уже на 4 точках.
        int[] sizes = {2_500, 5_000, 10_000, 20_000};
        for (int n : sizes) {
            String s = "a.".repeat(n);
            long t = System.nanoTime();
            Matcher m = p.matcher(s);
            boolean found = m.find();
            double ms = (System.nanoTime() - t) / 1e6;
            System.out.printf("'a.' x %,6d (%,7d симв.): найдено=%b, %,8.0f мс%n", n, s.length(), found, ms);
        }
        String normal = "Привет, пишите на ivan@example.com или звоните. ".repeat(20_000);
        long t = System.nanoTime();
        int c = 0;
        Matcher m = p.matcher(normal);
        while (m.find()) c++;
        System.out.printf("обычный текст (%,d симв., %,d адресов): %,.0f мс%n", normal.length(), c, (System.nanoTime() - t) / 1e6);
    }
}
