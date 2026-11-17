import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Эксперимент PIM-02: точное воспроизведение RecurrenceHelper.generateDaily
 * (app/src/main/java/com/dedovmosol/iwomail/data/repository/RecurrenceHelper.kt, MAX_OCCURRENCES = 500).
 * Окно "Все" в CalendarScreen.kt:350-359: [сегодня - 90 дней; сегодня + 365 дней].
 *
 * Запуск: java RecSim.java   (JDK 17)
 */
public class RecSim {

    static List<Long> daily(Calendar cal, int interval, long rangeStart, long rangeEnd, int maxCount) {
        List<Long> results = new ArrayList<>();
        int skippedCount = 0;
        if (cal.getTimeInMillis() < rangeStart && interval > 0) {
            long diffDays = (rangeStart - cal.getTimeInMillis()) / (24L * 60 * 60 * 1000);
            int skipIntervals = (int) (diffDays / interval);
            if (skipIntervals > 1) {
                cal.add(Calendar.DAY_OF_YEAR, (skipIntervals - 1) * interval);
                skippedCount = skipIntervals - 1;
            }
        }
        int count = skippedCount;
        while (cal.getTimeInMillis() <= rangeEnd && count < maxCount) {
            if (cal.getTimeInMillis() >= rangeStart) results.add(cal.getTimeInMillis());
            cal.add(Calendar.DAY_OF_YEAR, interval);
            count++;
        }
        return results;
    }

    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Moscow"));
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd");

        Calendar today = Calendar.getInstance();
        today.clear();
        today.set(2026, Calendar.NOVEMBER, 16, 0, 0, 0);
        long t0 = today.getTimeInMillis();

        Calendar rs = (Calendar) today.clone(); rs.add(Calendar.DAY_OF_YEAR, -90);
        Calendar re = (Calendar) today.clone(); re.add(Calendar.DAY_OF_YEAR, 365);
        Calendar tomorrow = (Calendar) today.clone(); tomorrow.add(Calendar.DAY_OF_YEAR, 1);
        Calendar week = (Calendar) today.clone(); week.add(Calendar.DAY_OF_YEAR, 7);

        System.out.println("Ежедневная серия без даты окончания (interval=1), 'сегодня' = 2026-11-16");
        System.out.println("Ожидается: 455 вхождений в фильтре 'Все' (09:00 от -90 до +364 дня), 1 в 'Сегодня', 7 в 'Неделя'");
        System.out.println("возраст_дней | в_Все | последнее_в_Все | Сегодня | Неделя");
        int[] ages = {30, 100, 300, 400, 450, 490, 500, 591, 600, 700, 1000};
        for (int age : ages) {
            Calendar s = (Calendar) today.clone();
            s.add(Calendar.DAY_OF_YEAR, -age);
            s.set(Calendar.HOUR_OF_DAY, 9);
            List<Long> all = daily((Calendar) s.clone(), 1, rs.getTimeInMillis(), re.getTimeInMillis(), 500);
            List<Long> td = daily((Calendar) s.clone(), 1, t0, tomorrow.getTimeInMillis(), 500);
            List<Long> wk = daily((Calendar) s.clone(), 1, t0, week.getTimeInMillis(), 500);
            String last = all.isEmpty() ? "-" : fmt.format(new Date(all.get(all.size() - 1)));
            System.out.printf("%12d | %5d | %15s | %7d | %d%n", age, all.size(), last, td.size(), wk.size());
        }
    }
}
