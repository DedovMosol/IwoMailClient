/**
 * Эксперимент SET-08 / SET-09 / MAIL-19: контраст по формуле WCAG 2.x (relative luminance).
 * Цвета взяты из кода:
 *   ui/theme/Theme.kt:12-90      — AppColorTheme (primaryLight, gradientStart, gradientEnd)
 *   ui/theme/Theme.kt:702-725    — AppColors (delete, trash, folder)
 *   widget/MailWidget.kt:270-274 — textWhiteAlpha = 0xCCFFFFFF, textDimmed = 0x80EEEEEE
 *   ui/components/DragSelectionIndicator.kt:30 — DragSelectionUncheckedColor = 0xFFF5F7F7, зелёный 0xFF68C957
 * Полупрозрачный цвет смешивается с фоном (alpha compositing) перед расчётом.
 *
 * Запуск: java Contrast.java   (JDK 17)
 */
public class Contrast {

    static double chan(int c8) {
        double c = c8 / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    static double lum(int rgb) {
        return 0.2126 * chan((rgb >> 16) & 0xFF) + 0.7152 * chan((rgb >> 8) & 0xFF) + 0.0722 * chan(rgb & 0xFF);
    }

    static int over(int fgRgb, int alpha, int bgRgb) {
        double a = alpha / 255.0;
        int r = (int) Math.round(((fgRgb >> 16) & 0xFF) * a + ((bgRgb >> 16) & 0xFF) * (1 - a));
        int g = (int) Math.round(((fgRgb >> 8) & 0xFF) * a + ((bgRgb >> 8) & 0xFF) * (1 - a));
        int b = (int) Math.round((fgRgb & 0xFF) * a + (bgRgb & 0xFF) * (1 - a));
        return (r << 16) | (g << 8) | b;
    }

    static double ratio(int a, int b) {
        double la = lum(a), lb = lum(b);
        double hi = Math.max(la, lb), lo = Math.min(la, lb);
        return (hi + 0.05) / (lo + 0.05);
    }

    static void row(String label, double r, double need) {
        System.out.printf("%-62s %5.2f:1  %s%n", label, r, r >= need ? "ok" : "НИЖЕ " + need + ":1");
    }

    public static void main(String[] args) {
        int white = 0xFFFFFF;
        int purple = 0x5C00D4, blue = 0x1565C0, yellow = 0xC77700, green = 0x2E7D32;
        int yellowEnd = 0xA85E00;
        String[] names = {"PURPLE", "BLUE", "YELLOW", "GREEN"};
        int[] bgs = {purple, blue, yellow, green};

        System.out.println("--- Виджет (MailWidget.kt): текст на фоне цвета темы, порог 4.5:1 ---");
        for (int i = 0; i < 4; i++) {
            int bg = bgs[i];
            int tWhiteAlpha = over(0xFFFFFF, 0xCC, bg);
            int tDim = over(0xEEEEEE, 0x80, bg);
            row(names[i] + ": белый (textWhite)", ratio(white, bg), 4.5);
            row(names[i] + ": textWhiteAlpha 0xCCFFFFFF", ratio(tWhiteAlpha, bg), 4.5);
            row(names[i] + ": textDimmed 0x80EEEEEE", ratio(tDim, bg), 4.5);
        }

        System.out.println();
        System.out.println("--- Кнопки ThemeOutlinedButton: белый текст на градиенте, порог 4.5:1 ---");
        row("YELLOW левый край C77700", ratio(white, yellow), 4.5);
        row("YELLOW правый край A85E00", ratio(white, yellowEnd), 4.5);
        row("PURPLE левый край 5C00D4", ratio(white, purple), 4.5);
        row("BLUE 1565C0", ratio(white, blue), 4.5);
        row("GREEN 2E7D32", ratio(white, green), 4.5);

        System.out.println();
        System.out.println("--- Прочие пары из Theme.kt, порог 4.5:1 (текст) ---");
        row("белый на delete F44336 (DeleteButton)", ratio(white, 0xF44336), 4.5);
        row("белый на trash E53935", ratio(white, 0xE53935), 4.5);
        row("белый на secondary 0097A7 (onSecondary=White)", ratio(white, 0x0097A7), 4.5);
        row("белый на tertiary 388E3C (onTertiary по умолч.)", ratio(white, 0x388E3C), 4.5);
        row("AppColors.folder E6A800 на белом (иконка, порог 3:1)", ratio(0xE6A800, white), 3.0);
        row("YELLOW primaryLight C77700 как текст на белом", ratio(yellow, white), 4.5);
        row("YELLOW primaryLight C77700 как текст на фоне FAFAFA", ratio(yellow, 0xFAFAFA), 4.5);

        System.out.println();
        System.out.println("--- Статусные цвета AccountSettingsScreen, текст на белом, порог 4.5:1 ---");
        row("FF9800 (orange)", ratio(0xFF9800, white), 4.5);
        row("4CAF50 (green)", ratio(0x4CAF50, white), 4.5);
        row("2196F3 (blue)", ratio(0x2196F3, white), 4.5);
        row("FFA000 (amber)", ratio(0xFFA000, white), 4.5);

        System.out.println();
        System.out.println("--- Индикатор выбора письма (DragSelectionIndicator), нетекстовый порог 3:1 ---");
        row("белая галочка на зелёном 68C957", ratio(white, 0x68C957), 3.0);
        row("пустой круг F5F7F7 на белом фоне строки", ratio(0xF5F7F7, white), 3.0);
    }
}
