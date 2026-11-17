import java.util.*;

/**
 * Эксперимент PIM-12 / PIM-02 (недельные серии, короткие окна 'Сегодня' и 'Неделя'):
 * воспроизведение RecurrenceHelper.generateWeekly (RecurrenceHelper.kt:330-380) с MAX_OCCURRENCES = 500.
 * Окна совпадают с CalendarScreen.kt:362-383: Сегодня = [00:00; +1 день), Неделя = [00:00; +7 дней).
 *
 * Для каждого дня недели в роли "сегодня" считается, сколько вхождений серии Пн-Пт 09:00
 * попадает в окно по эталону (перебор дней) и сколько вернул движок. Прогон для ru_RU и en_US.
 *
 * Запуск: java RecSimWeeklyShortRanges.java   (JDK 17)
 */
public class RecSimWeeklyShortRanges {

    static final int MAX = 500;

    static List<Long> weekly(Calendar cal, int interval, int mask, long rangeStart, long rangeEnd, int maxCount) {
        List<Long> results = new ArrayList<>();
        if (mask == 0) mask = 1 << (cal.get(Calendar.DAY_OF_WEEK) - 1);
        int hour = cal.get(Calendar.HOUR_OF_DAY), minute = cal.get(Calendar.MINUTE), second = cal.get(Calendar.SECOND);
        Calendar startOfWeek = (Calendar) cal.clone();
        startOfWeek.set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY);
        int skipped = 0;
        if (startOfWeek.getTimeInMillis() < rangeStart && interval > 0) {
            long diffWeeks = (rangeStart - startOfWeek.getTimeInMillis()) / (7L * 24 * 60 * 60 * 1000);
            int skipIntervals = (int) (diffWeeks / interval);
            if (skipIntervals > 1) {
                startOfWeek.add(Calendar.WEEK_OF_YEAR, (skipIntervals - 1) * interval);
                skipped = (skipIntervals - 1) * Integer.bitCount(mask & 0x7F);
            }
        }
        int count = skipped, safety = 0;
        while (startOfWeek.getTimeInMillis() <= rangeEnd && count < maxCount && safety < MAX) {
            for (int bit = 0; bit <= 6; bit++) {
                if ((mask & (1 << bit)) != 0) {
                    Calendar d = (Calendar) startOfWeek.clone();
                    d.set(Calendar.DAY_OF_WEEK, bit + 1);
                    d.set(Calendar.HOUR_OF_DAY, hour);
                    d.set(Calendar.MINUTE, minute);
                    d.set(Calendar.SECOND, second);
                    long t = d.getTimeInMillis();
                    if (t >= rangeStart && t <= rangeEnd && count < maxCount) {
                        results.add(t);
                        count++;
                    }
                }
            }
            startOfWeek.add(Calendar.WEEK_OF_YEAR, interval);
            safety++;
        }
        return results;
    }

    /** Эталон: вхождения (дни с маской, 09:00) в [rangeStart; rangeEnd], не раньше старта серии. */
    static int expected(Calendar seriesStart, int mask, long rangeStart, long rangeEnd) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(rangeStart);
        c.set(Calendar.HOUR_OF_DAY, 9); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        int n = 0;
        for (int i = 0; i < 400; i++) {
            long t = c.getTimeInMillis();
            if (t >= rangeStart && t <= rangeEnd && t >= seriesStart.getTimeInMillis()
                    && (mask & (1 << (c.get(Calendar.DAY_OF_WEEK) - 1))) != 0) n++;
            c.add(Calendar.DAY_OF_YEAR, 1);
        }
        return n;
    }

    static void run(Locale loc) {
        Locale.setDefault(loc);
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Moscow"));
        String[] dn = {"Вс", "Пн", "Вт", "Ср", "Чт", "Пт", "Сб"};
        int mask = 62; // Пн-Пт
        System.out.println("##### Локаль: " + loc + ", первый день недели = " + Calendar.getInstance().getFirstDayOfWeek()
                + " (1=Вс, 2=Пн). Серия Пн-Пт 09:00, начата ~100 дней назад.");
        System.out.println("'сегодня' | Сегодня: ожид/движок | Неделя: ожид/движок | Месяц(+30д): ожид/движок");
        int bad = 0, total = 0;
        Calendar monday = Calendar.getInstance();
        monday.clear();
        monday.set(2026, Calendar.NOVEMBER, 16, 0, 0, 0); // понедельник
        for (int k = 0; k < 7; k++) {
            Calendar today = (Calendar) monday.clone();
            today.add(Calendar.DAY_OF_YEAR, k);
            Calendar start = (Calendar) today.clone();
            start.add(Calendar.DAY_OF_YEAR, -100);
            while (((1 << (start.get(Calendar.DAY_OF_WEEK) - 1)) & mask) == 0) start.add(Calendar.DAY_OF_YEAR, 1);
            start.set(Calendar.HOUR_OF_DAY, 9);

            Calendar tomorrow = (Calendar) today.clone(); tomorrow.add(Calendar.DAY_OF_YEAR, 1);
            Calendar week = (Calendar) today.clone(); week.add(Calendar.DAY_OF_YEAR, 7);
            Calendar month = (Calendar) today.clone(); month.add(Calendar.MONTH, 1);

            long[][] ranges = {
                    {today.getTimeInMillis(), tomorrow.getTimeInMillis()},
                    {today.getTimeInMillis(), week.getTimeInMillis()},
                    {today.getTimeInMillis(), month.getTimeInMillis()}};
            StringBuilder sb = new StringBuilder(String.format("%-9s |", dn[today.get(Calendar.DAY_OF_WEEK) - 1]));
            for (long[] r : ranges) {
                int exp = expected(start, mask, r[0], r[1]);
                int got = weekly((Calendar) start.clone(), 1, mask, r[0], r[1], MAX).size();
                total++;
                if (exp != got) bad++;
                sb.append(String.format(" %3d / %-3d %s|", exp, got, exp == got ? "  " : "<<"));
            }
            System.out.println(sb);
        }
        System.out.println("Окон с расхождением: " + bad + " из " + total + "  ('<<' = движок вернул не то, что должен)");
        System.out.println();
    }

    public static void main(String[] args) {
        run(Locale.forLanguageTag("ru-RU"));
        run(Locale.US);
    }
}
