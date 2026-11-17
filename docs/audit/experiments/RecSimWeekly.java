import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Эксперимент PIM-02 (недельные серии): точное воспроизведение RecurrenceHelper.generateWeekly
 * (app/src/main/java/com/dedovmosol/iwomail/data/repository/RecurrenceHelper.kt:330-380), MAX_OCCURRENCES = 500.
 * Окно "Все" в CalendarScreen.kt:350-359: [сегодня - 90 дней; сегодня + 365 дней].
 *
 * Эталон (сколько вхождений ДОЛЖНО быть в окне) считается независимо: перебором каждого дня окна.
 * Запуск: java RecSimWeekly.java   (JDK 17)
 */
public class RecSimWeekly {

    static final int MAX = 500;

    static List<Long> weekly(Calendar cal, int interval, int mask, long rangeStart, long rangeEnd, int maxCount) {
        List<Long> results = new ArrayList<>();
        if (mask == 0) mask = 1 << (cal.get(Calendar.DAY_OF_WEEK) - 1);
        int hour = cal.get(Calendar.HOUR_OF_DAY), minute = cal.get(Calendar.MINUTE), second = cal.get(Calendar.SECOND);
        Calendar startOfWeek = (Calendar) cal.clone();
        startOfWeek.set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY);

        int skippedOccurrences = 0;
        if (startOfWeek.getTimeInMillis() < rangeStart && interval > 0) {
            long diffWeeks = (rangeStart - startOfWeek.getTimeInMillis()) / (7L * 24 * 60 * 60 * 1000);
            int skipIntervals = (int) (diffWeeks / interval);
            if (skipIntervals > 1) {
                startOfWeek.add(Calendar.WEEK_OF_YEAR, (skipIntervals - 1) * interval);
                int daysPerWeek = Integer.bitCount(mask & 0x7F);
                skippedOccurrences = (skipIntervals - 1) * daysPerWeek;
            }
        }
        int count = skippedOccurrences;
        int safety = 0;
        while (startOfWeek.getTimeInMillis() <= rangeEnd && count < maxCount && safety < MAX) {
            for (int dayBit = 0; dayBit <= 6; dayBit++) {
                if ((mask & (1 << dayBit)) != 0) {
                    Calendar dayCal = (Calendar) startOfWeek.clone();
                    dayCal.set(Calendar.DAY_OF_WEEK, dayBit + 1);
                    dayCal.set(Calendar.HOUR_OF_DAY, hour);
                    dayCal.set(Calendar.MINUTE, minute);
                    dayCal.set(Calendar.SECOND, second);
                    long time = dayCal.getTimeInMillis();
                    if (time >= rangeStart && time <= rangeEnd && count < maxCount) {
                        results.add(time);
                        count++;
                    }
                }
            }
            startOfWeek.add(Calendar.WEEK_OF_YEAR, interval);
            safety++;
        }
        return results;
    }

    /** Независимый эталон: сколько дней окна попадает на выбранные дни недели, начиная с даты старта серии. */
    static int expected(Calendar seriesStart, int mask, long rangeStart, long rangeEnd) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(rangeStart);
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        int n = 0;
        Calendar start = (Calendar) seriesStart.clone();
        start.set(Calendar.HOUR_OF_DAY, 0); start.set(Calendar.MINUTE, 0); start.set(Calendar.SECOND, 0); start.set(Calendar.MILLISECOND, 0);
        while (c.getTimeInMillis() <= rangeEnd) {
            int bit = c.get(Calendar.DAY_OF_WEEK) - 1;
            if ((mask & (1 << bit)) != 0 && c.getTimeInMillis() >= start.getTimeInMillis()) n++;
            c.add(Calendar.DAY_OF_YEAR, 1);
        }
        return n;
    }

    static void run(Locale loc) {
        Locale.setDefault(loc);
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Moscow"));
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd");
        Calendar today = Calendar.getInstance();
        today.clear();
        today.set(2026, Calendar.NOVEMBER, 16, 0, 0, 0);
        Calendar rs = (Calendar) today.clone(); rs.add(Calendar.DAY_OF_YEAR, -90);
        Calendar re = (Calendar) today.clone(); re.add(Calendar.DAY_OF_YEAR, 365);

        System.out.println("##### Локаль: " + loc + " (первый день недели: "
                + Calendar.getInstance().getFirstDayOfWeek() + "; 1=Вс, 2=Пн)");
        int[][] series = {{62, 0}, {2, 0}};
        String[] names = {"Пн-Пт каждую неделю (маска 62)", "только Пн каждую неделю (маска 2)"};
        int[] ages = {100, 300, 500, 600, 700, 1000, 2000, 3650};
        for (int s = 0; s < series.length; s++) {
            int mask = series[s][0];
            System.out.println("--- " + names[s] + ", 09:00, фильтр 'Все' ---");
            System.out.println("возраст_дней | ожидается | показано | потеряно | последнее_показанное");
            for (int age : ages) {
                Calendar start = (Calendar) today.clone();
                start.add(Calendar.DAY_OF_YEAR, -age);
                while (((1 << (start.get(Calendar.DAY_OF_WEEK) - 1)) & mask) == 0) start.add(Calendar.DAY_OF_YEAR, 1);
                start.set(Calendar.HOUR_OF_DAY, 9);
                List<Long> got = weekly((Calendar) start.clone(), 1, mask, rs.getTimeInMillis(), re.getTimeInMillis(), MAX);
                int exp = expected(start, mask, rs.getTimeInMillis(), re.getTimeInMillis());
                String last = got.isEmpty() ? "-" : fmt.format(new Date(got.get(got.size() - 1)));
                System.out.printf("%12d | %9d | %8d | %8d | %s%n", age, exp, got.size(), exp - got.size(), last);
            }
        }
        System.out.println();
    }

    public static void main(String[] args) {
        run(Locale.forLanguageTag("ru-RU"));
        run(Locale.US);
    }
}
