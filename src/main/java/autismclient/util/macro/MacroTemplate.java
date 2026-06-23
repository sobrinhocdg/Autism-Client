package autismclient.util.macro;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;

public final class MacroTemplate {
    private static final Pattern EXPRESSION = Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]{0,63}(?:\\|[^{}|]+)*");
    public record Resolution(boolean success, String value, List<String> missing, String error) {
        public static Resolution ok(String value) { return new Resolution(true, value, List.of(), ""); }
        public static Resolution failed(List<String> missing, String error) {
            return new Resolution(false, "", List.copyOf(missing), error == null ? "" : error);
        }
    }

    private MacroTemplate() {}

    public static Resolution resolve(String template, MacroVariableContext context, Minecraft mc) {
        if (template == null || template.isEmpty()) return Resolution.ok(template == null ? "" : template);
        StringBuilder out = new StringBuilder(template.length());
        List<String> missing = new ArrayList<>();
        for (int i = 0; i < template.length();) {
            char ch = template.charAt(i);
            if (ch == '{' && i + 1 < template.length() && template.charAt(i + 1) == '{') {
                out.append('{');
                i += 2;
                continue;
            }
            if (ch == '}' && i + 1 < template.length() && template.charAt(i + 1) == '}') {
                out.append('}');
                i += 2;
                continue;
            }
            if (ch != '{') {
                out.append(ch);
                i++;
                continue;
            }
            int end = template.indexOf('}', i + 1);
            if (end < 0) {
                out.append(ch);
                i++;
                continue;
            }
            String expression = template.substring(i + 1, end).trim();
            if (!EXPRESSION.matcher(expression).matches()) {
                out.append(ch);
                i++;
                continue;
            }
            ValueResolution value = resolveExpression(expression, context, mc);
            if (!value.success) {
                missing.add(value.missingName.isEmpty() ? expression : value.missingName);
            } else {
                out.append(value.value);
            }
            i = end + 1;
        }
        return missing.isEmpty() ? Resolution.ok(out.toString()) : Resolution.failed(missing, "Missing macro value");
    }

    public static boolean hasVariables(String text) {
        if (text == null || text.isBlank()) return false;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) != '{' || (i + 1 < text.length() && text.charAt(i + 1) == '{')) continue;
            int end = text.indexOf('}', i + 1);
            if (end > i && EXPRESSION.matcher(text.substring(i + 1, end).trim()).matches()) return true;
        }
        return false;
    }

    private static ValueResolution resolveExpression(String expression, MacroVariableContext context, Minecraft mc) {
        String[] parts = expression.split("\\|", -1);
        String name = parts.length == 0 ? "" : parts[0].trim();
        Optional<MacroValue> found = context == null ? Optional.empty() : context.get(name);
        if (found.isEmpty()) found = builtIn(name, mc);
        String fallback = null;
        for (int i = 1; i < parts.length; i++) {
            String formatter = parts[i].trim();
            if (formatter.regionMatches(true, 0, "default:", 0, 8)) fallback = formatter.substring(8);
        }
        if (found.isEmpty()) {
            return fallback == null ? ValueResolution.missing(name) : ValueResolution.ok(fallback);
        }
        MacroValue value = found.get();
        String rendered = value.value();
        try {
            for (int i = 1; i < parts.length; i++) {
                String formatter = parts[i].trim();
                if (formatter.isEmpty() || formatter.regionMatches(true, 0, "default:", 0, 8)) continue;
                rendered = applyFormatter(rendered, formatter, value);
            }
            return ValueResolution.ok(rendered);
        } catch (IllegalArgumentException invalid) {
            return ValueResolution.missing(name);
        }
    }

    private static String applyFormatter(String text, String formatter, MacroValue value) {
        String key = formatter.toLowerCase(Locale.ROOT);
        return switch (key) {
            case "trim" -> text.trim();
            case "lower" -> text.toLowerCase(Locale.ROOT);
            case "upper" -> text.toUpperCase(Locale.ROOT);
            case "number" -> parseCompactNumber(text);
            case "name" -> value.property("name").map(MacroValue::value).orElse(text);
            case "id" -> value.property("id").map(MacroValue::value).orElseThrow(() -> new IllegalArgumentException("No id"));
            case "count" -> value.property("count").map(MacroValue::value).orElseThrow(() -> new IllegalArgumentException("No count"));
            case "slot" -> value.property("slot").map(MacroValue::value).orElseThrow(() -> new IllegalArgumentException("No slot"));

            default -> value.property(key).map(MacroValue::value)
                .orElseThrow(() -> new IllegalArgumentException("Unknown formatter: " + formatter));
        };
    }

    static String parseCompactNumber(String raw) {
        if (raw == null) throw new IllegalArgumentException("Empty number");
        String cleaned = raw.trim().replace(" ", "").replace(",", "").toUpperCase(Locale.ROOT);
        if (cleaned.isEmpty()) throw new IllegalArgumentException("Empty number");
        BigDecimal multiplier = BigDecimal.ONE;
        char suffix = cleaned.charAt(cleaned.length() - 1);
        multiplier = switch (suffix) {
            case 'K' -> new BigDecimal("1000");
            case 'M' -> new BigDecimal("1000000");
            case 'B' -> new BigDecimal("1000000000");
            case 'T' -> new BigDecimal("1000000000000");
            default -> BigDecimal.ONE;
        };
        if (multiplier.compareTo(BigDecimal.ONE) != 0) cleaned = cleaned.substring(0, cleaned.length() - 1);
        BigDecimal number = new BigDecimal(cleaned).multiply(multiplier).setScale(0, RoundingMode.DOWN);
        return number.toPlainString();
    }

    public static final java.util.Set<String> BUILT_IN_NAMES = java.util.Set.of(
        "timestamp", "player", "user", "username", "uuid",
        "x", "y", "z", "bx", "by", "bz", "pos",
        "yaw", "pitch", "rot", "facing",
        "dimension", "dim", "selected_slot", "target_slot", "server"
    );

    private static Optional<MacroValue> builtIn(String name, Minecraft mc) {
        if (name == null) return Optional.empty();
        String key = name.trim().toLowerCase(Locale.ROOT);
        if ("timestamp".equals(key)) return Optional.of(MacroValue.text(java.time.Instant.now().toString()));
        if (mc == null || mc.player == null) return Optional.empty();
        return switch (key) {
            case "player", "user", "username" -> Optional.of(MacroValue.text(mc.player.getName().getString()));
            case "uuid" -> Optional.of(MacroValue.text(mc.player.getUUID().toString()));
            case "x" -> Optional.of(MacroValue.text(String.format(Locale.ROOT, "%.3f", mc.player.getX())));
            case "y" -> Optional.of(MacroValue.text(String.format(Locale.ROOT, "%.3f", mc.player.getY())));
            case "z" -> Optional.of(MacroValue.text(String.format(Locale.ROOT, "%.3f", mc.player.getZ())));
            case "bx" -> Optional.of(MacroValue.text(Integer.toString(mc.player.blockPosition().getX())));
            case "by" -> Optional.of(MacroValue.text(Integer.toString(mc.player.blockPosition().getY())));
            case "bz" -> Optional.of(MacroValue.text(Integer.toString(mc.player.blockPosition().getZ())));
            case "pos" -> {
                net.minecraft.core.BlockPos pos = mc.player.blockPosition();
                yield Optional.of(MacroValue.text(pos.getX() + " " + pos.getY() + " " + pos.getZ()));
            }
            case "yaw" -> Optional.of(MacroValue.text(String.format(Locale.ROOT, "%.2f", mc.player.getYRot())));
            case "pitch" -> Optional.of(MacroValue.text(String.format(Locale.ROOT, "%.2f", mc.player.getXRot())));
            case "rot" -> Optional.of(MacroValue.text(String.format(Locale.ROOT, "%.2f %.2f", mc.player.getYRot(), mc.player.getXRot())));
            case "facing" -> Optional.of(MacroValue.text(net.minecraft.core.Direction.fromYRot(mc.player.getYRot()).getName()));
            case "dimension", "dim" -> mc.level == null
                ? Optional.empty()
                : Optional.of(MacroValue.text(mc.level.dimension().identifier().toString()));
            case "selected_slot", "target_slot" -> Optional.of(MacroValue.slot(mc.player.getInventory().getSelectedSlot()));
            case "server" -> {
                var server = mc.getCurrentServer();
                yield server == null || server.ip == null ? Optional.empty() : Optional.of(MacroValue.text(server.ip));
            }
            default -> Optional.empty();
        };
    }

    private record ValueResolution(boolean success, String value, String missingName) {
        static ValueResolution ok(String value) { return new ValueResolution(true, value, ""); }
        static ValueResolution missing(String name) { return new ValueResolution(false, "", name == null ? "" : name); }
    }
}
