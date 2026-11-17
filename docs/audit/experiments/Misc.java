import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Воспроизведение механик из находок аудита (PIM-01, PIM-03, PIM-16, CMP-02, NAV-01).
 * Каждый блок повторяет выражение из кода проекта; ссылки на file:line в комментариях.
 *
 * Запуск: java Misc.java   (JDK 17)
 */
public class Misc {
    public static void main(String[] args) throws Exception {
        pim01();
        pim03();
        pim16();
        cmp02();
        nav01();
    }

    /** PIM-01: MonthView.kt:103,108 — calendar.set(Calendar.MONTH, currentMonth ± 1) на 29..31 числе. */
    static void pim01() {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Moscow"));
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd");
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(2026, Calendar.JANUARY, 31);
        c.set(Calendar.MONTH, c.get(Calendar.MONTH) + 1);
        System.out.println("PIM-01: выбрано 31 января, 'следующий месяц' -> месяц=" + c.get(Calendar.MONTH)
                + " (0=янв, 1=фев, 2=мар), дата=" + f.format(c.getTime()));

        Calendar d = Calendar.getInstance();
        d.clear();
        d.set(2026, Calendar.MARCH, 31);
        d.set(Calendar.MONTH, d.get(Calendar.MONTH) - 1);
        System.out.println("PIM-01: выбрано 31 марта, 'предыдущий месяц' -> месяц=" + d.get(Calendar.MONTH)
                + " (ожидалось 1=фев), дата=" + f.format(d.getTime()));
    }

    /** PIM-03: CreateEventDialog.kt:1195-1230, TasksScreen.kt:1879-1921 — локальный формат против UTC-контракта DatePicker. */
    static void pim03() throws Exception {
        String[] zones = {"Europe/Moscow", "Asia/Tokyo", "UTC", "America/New_York", "America/Los_Angeles"};
        for (String z : zones) {
            TimeZone.setDefault(TimeZone.getTimeZone(z));
            SimpleDateFormat local = new SimpleDateFormat("dd.MM.yyyy");
            long initialSelectedDateMillis = local.parse("10.05.2026").getTime();
            SimpleDateFormat utc = new SimpleDateFormat("dd.MM.yyyy");
            utc.setTimeZone(TimeZone.getTimeZone("UTC"));
            String shownByPicker = utc.format(new Date(initialSelectedDateMillis));
            long pickedUtcMidnight = 1778371200000L; // 2026-05-10T00:00:00Z — пользователь выбрал 10 мая
            String saved = local.format(new Date(pickedUtcMidnight));
            System.out.printf("PIM-03: зона %-20s пикер подсвечивает %s (в поле 10.05.2026); выбрали 10.05 -> сохранится %s%n",
                    z, shownByPicker, saved);
        }
    }

    /** PIM-16: ContactRepository.kt:742 — "(?m)^$field[^:]*:(.*)$" для поля "N" совпадает с NOTE:. */
    static void pim16() {
        Pattern p = Pattern.compile("(?m)^N[^:]*:(.*)$");
        String v = "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Ivan Petrov\r\nNOTE:call after 5pm\r\nN:Petrov;Ivan;;;\r\nEND:VCARD";
        Matcher m = p.matcher(v);
        if (m.find()) {
            System.out.println("PIM-16: первое совпадение поля 'N' = [" + m.group(0).trim() + "]");
        }
    }

    /** CMP-02: ComposeTextUtils.kt:20 — NORMALIZE_EMAIL_REGEX только ASCII, нераспознанные токены отбрасываются. */
    static void cmp02() {
        Pattern em = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
        for (String s : new String[]{"ivan@почта.рф", "admin@mail", "иван@example.com", "Ivan <ivan@example.com>"}) {
            Matcher mm = em.matcher(s);
            System.out.println("CMP-02: normalizeRecipients(\"" + s + "\") -> " + (mm.find() ? "[" + mm.group() + "]" : "ОТБРОШЕН"));
        }
    }

    /** NAV-01: AppLockManager.kt:55,64 — PBKDF2-HMAC-SHA256, 600 000 итераций, минимальная длина пароля 4. */
    static void nav01() throws Exception {
        SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        byte[] salt = new byte[16];
        for (int i = 0; i < 3; i++) {
            f.generateSecret(new PBEKeySpec("warmup".toCharArray(), salt, 600000, 256)).getEncoded();
        }
        int n = 12;
        long t = System.nanoTime();
        for (int i = 0; i < n; i++) {
            f.generateSecret(new PBEKeySpec(("1234" + i).toCharArray(), salt, 600000, 256)).getEncoded();
        }
        double ms = (System.nanoTime() - t) / 1e6 / n;
        System.out.printf("NAV-01: PBKDF2-600k на этом ПК: %.0f мс/попытку (1 поток)%n", ms);
        System.out.printf("NAV-01: PIN из 4 цифр (10^4): %.1f мин; 4 строчные латинские (26^4): %.1f ч; 4 любых печатных ASCII (95^4): %.1f ч%n",
                ms * 1e4 / 60000, ms * Math.pow(26, 4) / 3600000, ms * Math.pow(95, 4) / 3600000);
        System.out.println("NAV-01: на телефоне одна попытка ~в 3-10 раз медленнее, но счётчика попыток и паузы в коде нет.");
    }
}
