package autismclient.util.macro;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class MacroTemplateTest {
    @Test
    void expandsTypedPropertiesFormattersAndEscapedBraces() {
        MacroVariableContext context = new MacroVariableContext();
        context.set("item", MacroValue.structured(MacroValue.Kind.ITEM, "Obsidian", Map.of(
            "id", MacroValue.identifier("minecraft:obsidian"),
            "count", MacroValue.number(12),
            "slot", MacroValue.slot(7)
        )));
        context.set("amount", MacroValue.text("203M"));

        MacroTemplate.Resolution result = MacroTemplate.resolve(
            "{{item}} {item} {item.id} x{item.count} slot {item.slot} / {amount|number}", context, null);

        assertTrue(result.success());
        assertEquals("{item} Obsidian minecraft:obsidian x12 slot 7 / 203000000", result.value());
    }

    @Test
    void missingValuesFailUnlessDefaulted() {
        MacroVariableContext context = new MacroVariableContext();
        MacroTemplate.Resolution missing = MacroTemplate.resolve("/pay {playerName} {amount}", context, null);
        MacroTemplate.Resolution defaulted = MacroTemplate.resolve("{amount|default:0}", context, null);

        assertFalse(missing.success());
        assertEquals(2, missing.missing().size());
        assertTrue(defaulted.success());
        assertEquals("0", defaulted.value());
    }

    @Test
    void ordinaryJsonBracesRemainLiteral() {
        String json = "{\"item\":\"obsidian\",\"count\":4}";
        MacroTemplate.Resolution resolved = MacroTemplate.resolve(json, new MacroVariableContext(), null);
        assertTrue(resolved.success());
        assertEquals(json, resolved.value());
        assertFalse(MacroTemplate.hasVariables(json));
    }
}
