using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Media;
using Windows.UI;

namespace TunesLinkBridge;

internal static class ThemeContrast
{
    public static void Verify(ResourceDictionary theme)
    {
        foreach ((string foreground, string background) in new[]
        {
            ("PrimaryTextColor", "CanvasColor"), ("PrimaryTextColor", "SurfaceColor"),
            ("SecondaryTextColor", "CanvasColor"), ("SecondaryTextColor", "SurfaceColor"),
            ("AccentTextColor", "CanvasColor"), ("SuccessColor", "CanvasColor"),
            ("DangerColor", "CanvasColor"), ("SuccessColor", "SuccessTintColor"),
            ("DangerColor", "DangerTintColor"), ("PrimaryTextColor", "DangerTintColor"),
            ("SecondaryTextColor", "DangerTintColor"), ("HeroAccentStartColor", "CanvasColor"),
            ("HeroAccentMidColor", "CanvasColor"), ("HeroAccentEndColor", "CanvasColor")
        })
            Require((Color)theme[foreground], (Color)theme[background], foreground + " on " + background);

        foreach (GradientStop stop in ((LinearGradientBrush)theme["PrimaryFillBrush"]).GradientStops)
            Require((Color)theme["OnBrandTextColor"], stop.Color, "on-brand text on gradient");
    }

    private static void Require(Color foreground, Color background, string label)
    {
        double first = Luminance(foreground);
        double second = Luminance(background);
        double ratio = (Math.Max(first, second) + 0.05) / (Math.Min(first, second) + 0.05);
        if (ratio < 4.5) throw new InvalidOperationException($"{label} contrast was {ratio:F2}:1.");
    }

    private static double Luminance(Color color)
    {
        static double Channel(byte channel)
        {
            double value = channel / 255.0;
            return value <= 0.04045 ? value / 12.92 : Math.Pow((value + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * Channel(color.R) + 0.7152 * Channel(color.G) + 0.0722 * Channel(color.B);
    }
}
