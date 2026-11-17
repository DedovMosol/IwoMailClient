import java.util.*;

/**
 * Проверка предложенных исправлений PIM-02 и PIM-12 (аудит v2).
 * Исходные алгоритмы скопированы из RecSim.java и RecSimWeeklyShortRanges.java
 * (они повторяют RecurrenceHelper.generateDaily / generateWeekly). Рядом лежат исправленные версии.
 *
 * Исправление PIM-02: лимит MAX ограничивает число ВЫДАННЫХ вхождений (results.size),
 *   а лимит occurrences (COUNT в правиле) считается отдельным индексом от начала серии.
 * Исправление PIM-12: у клона startOfWeek до set(DAY_OF_WEEK, SUNDAY) ставится
 *   setFirstDayOfWeek(SUNDAY), чтобы "воскресенье" было началом недели в любой локали;
 *   вхождения раньше старта серии отсекаются; для серий с occurrences > 0 быстрая перемотка
 *   не используется (иначе счётчик пропущенных неточен).
 *
 * Запуск: java RecSimFix.java   (JDK 17)
 */
public class RecSimFix {

    static final int MAX = 500;
    static final long DAY = 24L * 60 * 60 * 1000;

    // ---------- ежедневные ----------

    static List<Long> dailyOrig(Calendar cal, int interval, long rs, long re, int maxCount) {
        List<Long> results = new ArrayList<>();
        int skipped = 0;
        if (cal.getTimeInMillis() < rs && interval > 0) {
            long diffDays = (rs - cal.getTimeInMillis()) / DAY;
            int skip = (int) (diffDays / interval);
            if (skip > 1) { cal.add(Calendar.DAY_OF_YEAR, (skip - 1) * interval); skipped = skip - 1; }
        }
        int count = skipped;
        while (cal.getTimeInMillis() <= re && count < maxCount) {
            if (cal.getTimeInMillis() >= rs) results.add(cal.getTimeInMillis());
            cal.add(Calendar.DAY_OF_YEAR, interval);
            count++;
        }
        return results;
    }

    /** occurrences = 0 означает "без ограничения по числу". */
    static List<Long> dailyFix(Calendar cal, int interval, long rs, long re, int occurrences) {
        List<Long> results = new ArrayList<>();
        int index = 0; // номер вхождения от начала серии
        if (cal.getTimeInMillis() < rs && interval > 0) {
            long diffDays = (rs - cal.getTimeInMillis()) / DAY;
            int skip = (int) (diffDays / interval);
            if (skip > 1) { cal.add(Calendar.DAY_OF_YEAR, (skip - 1) * interval); index = skip - 1; }
        }
        while (cal.getTimeInMillis() <= re && results.size() < MAX) {
            if (occurrences > 0 && index >= occurrences) break;
            if (cal.getTimeInMillis() >= rs) results.add(cal.getTimeInMillis());
            cal.add(Calendar.DAY_OF_YEAR, interval);
            index++;
        }
        return results;
    }

    static int dailyExpected(Calendar start, long rs, long re, int occurrences) {
        Calendar c = (Calendar) start.clone();
        int n = 0;
        for (int i = 0; i < 20000; i++) {
            if (occurrences > 0 && i >= occurrences) break;
            long t = c.getTimeInMillis();
            if (t > re) break;
            if (t >= rs) n++;
            c.add(Calendar.DAY_OF_YEAR, 1);
        }
        return Math.min(n, MAX);
    }

    // ---------- недельные ----------

    static List<Long> weeklyOrig(Calendar cal, int interval, int mask, long rs, long re) {
        List<Long> results = new ArrayList<>();
        int hour = cal.get(Calendar.HOUR_OF_DAY), minute = cal.get(Calendar.MINUTE);
        Calendar sow = (Calendar) cal.clone();
        sow.set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY);
        int skipped = 0;
        if (sow.getTimeInMillis() < rs && interval > 0) {
            long diffWeeks = (rs - sow.getTimeInMillis()) / (7 * DAY);
            int skip = (int) (diffWeeks / interval);
            if (skip > 1) { sow.add(Calendar.WEEK_OF_YEAR, (skip - 1) * interval); skipped = (skip - 1) * Integer.bitCount(mask & 0x7F); }
        }
        int count = skipped, safety = 0;
        while (sow.getTimeInMillis() <= re && count < MAX && safety < MAX) {
            for (int bit = 0; bit <= 6; bit++) {
                if ((mask & (1 << bit)) == 0) continue;
                Calendar d = (Calendar) sow.clone();
                d.set(Calendar.DAY_OF_WEEK, bit + 1);
                d.set(Calendar.HOUR_OF_DAY, hour); d.set(Calendar.MINUTE, minute);
                long t = d.getTimeInMillis();
                if (t >= rs && t <= re && count < MAX) { results.add(t); count++; }
            }
            sow.add(Calendar.WEEK_OF_YEAR, interval);
            safety++;
        }
        return results;
    }

    static List<Long> weeklyFix(Calendar cal, int interval, int mask, long rs, long re, int occurrences) {
        List<Long> results = new ArrayList<>();
        long seriesStart = cal.getTimeInMillis();
        int hour = cal.get(Calendar.HOUR_OF_DAY), minute = cal.get(Calendar.MINUTE);
        Calendar sow = (Calendar) cal.clone();
        sow.setFirstDayOfWeek(Calendar.SUNDAY);           // ключевая правка PIM-12
        sow.set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY);
        int index = 0;
        if (occurrences == 0 && sow.getTimeInMillis() < rs && interval > 0) {
            long diffWeeks = (rs - sow.getTimeInMillis()) / (7 * DAY);
            int skip = (int) (diffWeeks / interval);
            if (skip > 1) sow.add(Calendar.WEEK_OF_YEAR, (skip - 1) * interval);
        }
        int safety = 0;
        outer:
        while (sow.getTimeInMillis() <= re && results.size() < MAX && safety < 20000) {
            for (int bit = 0; bit <= 6; bit++) {
                if ((mask & (1 << bit)) == 0) continue;
                Calendar d = (Calendar) sow.clone();
                d.set(Calendar.DAY_OF_WEEK, bit + 1);
                d.set(Calendar.HOUR_OF_DAY, hour); d.set(Calendar.MINUTE, minute);
                long t = d.getTimeInMillis();
                if (t < seriesStart) continue;                 // нет фантомов до старта
                if (occurrences > 0 && index >= occurrences) break outer;
                index++;
                if (t >= rs && t <= re) { results.add(t); if (results.size() >= MAX) break outer; }
            }
            sow.add(Calendar.WEEK_OF_YEAR, interval);
            safety++;
        }
        return results;
    }

    static int weeklyExpected(Calendar start, int mask, long rs, long re, int occurrences) {
        Calendar c = (Calendar) start.clone();
        int n = 0, idx = 0;
        for (int i = 0; i < 20000; i++) {
            long t = c.getTimeInMillis();
            if (t > re) break;
            if ((mask & (1 << (c.get(Calendar.DAY_OF_WEEK) - 1))) != 0) {
                if (occurrences > 0 && idx >= occurrences) break;
                idx++;
                if (t >= rs) n++;
            }
            c.add(Calendar.DAY_OF_YEAR, 1);
        }
        return Math.min(n, MAX);
    }

    // ---------- прогоны ----------

    static Calendar today() {
        Calendar t = Calendar.getInstance();
        t.clear();
        t.set(2026, Calendar.NOVEMBER, 16, 0, 0, 0);
        return t;
    }

    static long[][] windows(Calendar today) {
        Calendar a = (Calendar) today.clone(); a.add(Calendar.DAY_OF_YEAR, -90);
        Calendar b = (Calendar) today.clone(); b.add(Calendar.DAY_OF_YEAR, 365);
        Calendar d1 = (Calendar) today.clone(); d1.add(Calendar.DAY_OF_YEAR, 1);
        Calendar d7 = (Calendar) today.clone(); d7.add(Calendar.DAY_OF_YEAR, 7);
        Calendar m1 = (Calendar) today.clone(); m1.add(Calendar.MONTH, 1);
        return new long[][]{
                {today.getTimeInMillis(), d1.getTimeInMillis()},
                {today.getTimeInMillis(), d7.getTimeInMillis()},
                {today.getTimeInMillis(), m1.getTimeInMillis()},
                {a.getTimeInMillis(), b.getTimeInMillis()}};
    }

    static void daily(Locale loc) {
        Locale.setDefault(loc);
        int[] ages = {1, 30, 100, 135, 300, 500, 591, 2000, 10000};
        int[] occs = {0, 10, 200, 1000};
        int origBad = 0, fixBad = 0, total = 0;
        for (int age : ages) for (int occ : occs) {
            Calendar today = today();
            Calendar s = (Calendar) today.clone(); s.add(Calendar.DAY_OF_YEAR, -age); s.set(Calendar.HOUR_OF_DAY, 9);
            for (long[] w : windows(today)) {
                int exp = dailyExpected(s, w[0], w[1], occ);
                // исходный код: лимит count = min(occurrences, 500), если occurrences > 0
                int origLimit = occ > 0 ? Math.min(occ, MAX) : MAX;
                int o = dailyOrig((Calendar) s.clone(), 1, w[0], w[1], origLimit).size();
                int f = dailyFix((Calendar) s.clone(), 1, w[0], w[1], occ).size();
                total++;
                if (o != exp) origBad++;
                if (f != exp) { fixBad++; System.out.printf("  FIX MISMATCH daily age=%d occ=%d exp=%d got=%d%n", age, occ, exp, f); }
            }
        }
        System.out.printf("Ежедневные, %s: окон %d, расхождений: исходный код %d, исправленный %d%n", loc, total, origBad, fixBad);
    }

    static void weekly(Locale loc) {
        Locale.setDefault(loc);
        int[] masks = {62 /*Пн-Пт*/, 2 /*Пн*/, 1 /*Вс*/, 65 /*Сб+Вс*/, 127 /*все дни*/};
        int[] ages = {3, 10, 100, 700, 3000};
        int[] occs = {0, 15};
        int origBad = 0, fixBad = 0, total = 0;
        Calendar monday = today();
        for (int k = 0; k < 7; k++) {
            Calendar today = (Calendar) monday.clone(); today.add(Calendar.DAY_OF_YEAR, k);
            for (int mask : masks) for (int age : ages) for (int occ : occs) {
                Calendar s = (Calendar) today.clone(); s.add(Calendar.DAY_OF_YEAR, -age);
                while (((1 << (s.get(Calendar.DAY_OF_WEEK) - 1)) & mask) == 0) s.add(Calendar.DAY_OF_YEAR, 1);
                s.set(Calendar.HOUR_OF_DAY, 9);
                for (long[] w : windows(today)) {
                    int exp = weeklyExpected(s, mask, w[0], w[1], occ);
                    int f = weeklyFix((Calendar) s.clone(), 1, mask, w[0], w[1], occ).size();
                    total++;
                    if (occ == 0) {
                        int o = weeklyOrig((Calendar) s.clone(), 1, mask, w[0], w[1]).size();
                        if (o != exp) origBad++;
                    }
                    if (f != exp) {
                        fixBad++;
                        if (fixBad <= 10) System.out.printf("  FIX MISMATCH weekly today+%d mask=%d age=%d occ=%d exp=%d got=%d%n", k, mask, age, occ, exp, f);
                    }
                }
            }
        }
        System.out.printf("Недельные, %s (первый день недели=%d): окон %d, расхождений: исходный код %d (только occ=0), исправленный %d%n",
                loc, Calendar.getInstance().getFirstDayOfWeek(), total, origBad, fixBad);
    }

    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Moscow"));
        for (Locale l : new Locale[]{Locale.forLanguageTag("ru-RU"), Locale.US, Locale.GERMANY}) {
            daily(l);
            weekly(l);
        }
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York")); // зона с переходом на летнее время
        System.out.println("--- America/New_York (DST) ---");
        daily(Locale.forLanguageTag("ru-RU"));
        weekly(Locale.forLanguageTag("ru-RU"));
    }
}
