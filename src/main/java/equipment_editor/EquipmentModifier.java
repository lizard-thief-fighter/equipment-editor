package equipment_editor;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Equipable;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.component.Tool;
import net.minecraft.world.item.component.Unbreakable;
import net.neoforged.neoforge.event.ItemAttributeModifierEvent;
import net.neoforged.neoforge.event.ModifyDefaultComponentsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.slf4j.Logger;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class EquipmentModifier {
    private static final Logger LOGGER = EquipmentConfig.LOGGER;
    private static final String NAMESPACE = EquipmentEditorMod.MOD_ID;
    private static final String VANILLA_NAMESPACE = "minecraft";
    private static final int MAX_STACK_SIZE = 99;

    private static Map<ResourceLocation, EquipmentConfig.Rule> itemRules = Map.of();

    private EquipmentModifier() {}

    static void rebuildRuleIndex() {
        Map<ResourceLocation, EquipmentConfig.Rule> items = new HashMap<>();

        for (EquipmentConfig.Rule rule : EquipmentConfig.RULES) {
            if (rule == null || rule.items == null) continue;

            for (String item : rule.items) {
                if (item == null || item.isBlank()) continue;
                ResourceLocation id = ResourceLocation.tryParse(item.trim());
                if (id == null) {
                    LOGGER.warn("Invalid item ID \"{}\" in rules is ignored", item);
                    continue;
                }
                items.merge(id, rule, EquipmentModifier::mergeRules);
            }
        }

        itemRules = Map.copyOf(items);
        InnateEnchantments.rebuild(itemRules);
    }

    public static void modifyDefaultComponents(ModifyDefaultComponentsEvent event) {
        Set<EquipmentConfig.Rule> checkedAttributes = Collections.newSetFromMap(new IdentityHashMap<>());

        itemRules.forEach((id, rule) -> {
            if (!BuiltInRegistries.ITEM.containsKey(id)) {
                LOGGER.warn("Unknown item \"{}\" in rules is ignored", id);
                return;
            }
            if (checkedAttributes.add(rule)) warnUnknownAttributes(rule);

            Item item = BuiltInRegistries.ITEM.get(id);
            event.modify(item, builder -> applyComponents(id, item.components(), rule, builder));
        });
    }

    private static void applyComponents(ResourceLocation itemId, DataComponentMap base, EquipmentConfig.Rule rule,
                                        DataComponentPatch.Builder builder) {
        int baseMaxDamage = base.getOrDefault(DataComponents.MAX_DAMAGE, 0);

        Integer maxDamage = null;
        if (rule.durability != null || rule.durabilityMultiplier != null) {
            double durability = rule.durability != null ? rule.durability : baseMaxDamage;
            if (rule.durabilityMultiplier != null) durability *= rule.durabilityMultiplier;
            long rounded = Math.round(durability);
            if (rounded >= 1) {
                maxDamage = (int) Math.min(rounded, Integer.MAX_VALUE);
            } else {
                LOGGER.warn("{}: durability resolves to {} (needs at least 1); ignored", itemId, rounded);
            }
        }
        boolean damageable = maxDamage != null || baseMaxDamage > 0;

        int stackSize = base.getOrDefault(DataComponents.MAX_STACK_SIZE, 1);
        if (rule.maxStackSize != null) {
            int requested = Mth.clamp(rule.maxStackSize, 1, MAX_STACK_SIZE);
            if (requested != rule.maxStackSize) {
                LOGGER.warn("{}: maxStackSize {} is outside 1-{}; using {}",
                    itemId, rule.maxStackSize, MAX_STACK_SIZE, requested);
            }
            if (requested > 1 && damageable) {
                LOGGER.warn("{}: items with durability cannot stack; maxStackSize ignored", itemId);
            } else {
                builder.set(DataComponents.MAX_STACK_SIZE, requested);
                stackSize = requested;
            }
        }

        if (maxDamage != null) {
            builder.set(DataComponents.MAX_DAMAGE, maxDamage);
            // needs DAMAGE + no stacking to take damage
            if (!base.has(DataComponents.DAMAGE)) builder.set(DataComponents.DAMAGE, 0);
            if (stackSize > 1) builder.set(DataComponents.MAX_STACK_SIZE, 1);
        }

        if (rule.unbreakable != null) {
            if (rule.unbreakable) builder.set(DataComponents.UNBREAKABLE, new Unbreakable(true));
            else builder.remove(DataComponents.UNBREAKABLE);
        }
        if (rule.fireResistant != null) {
            if (rule.fireResistant) builder.set(DataComponents.FIRE_RESISTANT, Unit.INSTANCE);
            else builder.remove(DataComponents.FIRE_RESISTANT);
        }
        if (rule.rarity != null) {
            Rarity rarity = parseRarity(rule.rarity);
            if (rarity != null) builder.set(DataComponents.RARITY, rarity);
            else LOGGER.warn("{}: unknown rarity \"{}\"; ignored", itemId, rule.rarity);
        }
        if (rule.miningSpeed != null) {
            float speed = Math.max(0.0f, rule.miningSpeed.floatValue());
            Tool tool = base.get(DataComponents.TOOL);
            builder.set(DataComponents.TOOL,
                tool == null ? new Tool(List.of(), speed, 1) : withMiningSpeed(tool, speed));
        }
    }

    // proper blocks use rule speeds, so scale fastest rule to speed
    private static Tool withMiningSpeed(Tool tool, float speed) {
        float fastest = 0.0f;
        for (Tool.Rule rule : tool.rules()) {
            if (rule.speed().isPresent()) fastest = Math.max(fastest, rule.speed().get());
        }
        if (fastest <= 0.0f) return new Tool(tool.rules(), speed, tool.damagePerBlock());

        float scale = speed / fastest;
        List<Tool.Rule> rules = tool.rules().stream()
            .map(rule -> rule.speed().isEmpty() ? rule
                : new Tool.Rule(rule.blocks(), rule.speed().map(s -> s * scale), rule.correctForDrops()))
            .toList();
        return new Tool(rules, tool.defaultMiningSpeed(), tool.damagePerBlock());
    }

    public static void modifyAttributes(ItemAttributeModifierEvent event) {
        ItemStack stack = event.getItemStack();
        EquipmentConfig.Rule rule = resolveRule(stack);
        if (rule == null) return;

        EquipmentSlotGroup worn = slotGroupFor(stack);
        String itemNamespace = BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace();

        if (rule.attackDamage != null)
            replace(event, itemNamespace, Attributes.ATTACK_DAMAGE, Item.BASE_ATTACK_DAMAGE_ID,
                rule.attackDamage - 1.0, EquipmentSlotGroup.MAINHAND);
        if (rule.attackSpeed != null)
            replace(event, itemNamespace, Attributes.ATTACK_SPEED, Item.BASE_ATTACK_SPEED_ID,
                rule.attackSpeed - 4.0, EquipmentSlotGroup.MAINHAND);
        if (rule.attackKnockback != null)
            replace(event, itemNamespace, Attributes.ATTACK_KNOCKBACK, "attack_knockback",
                rule.attackKnockback, EquipmentSlotGroup.MAINHAND);
        if (rule.armor != null)
            replace(event, itemNamespace, Attributes.ARMOR, "armor", rule.armor, worn);
        if (rule.armorToughness != null)
            replace(event, itemNamespace, Attributes.ARMOR_TOUGHNESS, "armor_toughness",
                rule.armorToughness, worn);
        if (rule.knockbackResistance != null)
            replace(event, itemNamespace, Attributes.KNOCKBACK_RESISTANCE, "knockback_resistance",
                rule.knockbackResistance, worn);
        if (rule.entityInteractionRange != null)
            replace(event, itemNamespace, Attributes.ENTITY_INTERACTION_RANGE, "entity_interaction_range",
                rule.entityInteractionRange, worn);
        if (rule.blockInteractionRange != null)
            replace(event, itemNamespace, Attributes.BLOCK_INTERACTION_RANGE, "block_interaction_range",
                rule.blockInteractionRange, worn);
        if (rule.movementSpeed != null)
            replace(event, itemNamespace, Attributes.MOVEMENT_SPEED, "movement_speed",
                rule.movementSpeed, worn);

        if (rule.attributes != null) {
            for (Map.Entry<String, Double> entry : rule.attributes.entrySet()) {
                if (entry.getValue() == null) continue;
                Holder<Attribute> attribute = attributeHolder(entry.getKey());
                if (attribute != null) {
                    replace(event, itemNamespace, attribute, "attribute_" + entry.getKey(),
                        entry.getValue(), worn);
                }
            }
        }
    }

    public static void modifyMiningSpeed(PlayerEvent.BreakSpeed event) {
        EquipmentConfig.Rule rule = resolveRule(event.getEntity().getMainHandItem());
        if (rule == null || rule.miningSpeedMultiplier == null) return;

        event.setNewSpeed(Math.max(0.0f,
            event.getNewSpeed() * Math.max(0.0f, rule.miningSpeedMultiplier.floatValue())));
    }

    private static EquipmentConfig.Rule resolveRule(ItemStack stack) {
        return stack.isEmpty() ? null : itemRules.get(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }

    private static EquipmentSlotGroup slotGroupFor(ItemStack stack) {
        EquipmentSlot slot = stack.getEquipmentSlot();
        if (slot == null) {
            Equipable equipable = Equipable.get(stack);
            if (equipable != null) slot = equipable.getEquipmentSlot();
        }
        return slot == null ? EquipmentSlotGroup.MAINHAND : EquipmentSlotGroup.bySlot(slot);
    }

    private static Holder<Attribute> attributeHolder(String key) {
        ResourceLocation id = ResourceLocation.tryParse(key);
        return id == null ? null : BuiltInRegistries.ATTRIBUTE.getHolder(id).orElse(null);
    }

    private static void warnUnknownAttributes(EquipmentConfig.Rule rule) {
        if (rule.attributes == null) return;
        for (String key : rule.attributes.keySet()) {
            if (attributeHolder(key) == null) {
                LOGGER.warn("Unknown attribute \"{}\" in rules is ignored", key);
            }
        }
    }

    private static EquipmentConfig.Rule mergeRules(EquipmentConfig.Rule first,
                                                    EquipmentConfig.Rule second) {
        EquipmentConfig.Rule merged = new EquipmentConfig.Rule();
        merged.mergeFrom(first);
        merged.mergeFrom(second);
        return merged;
    }

    private static Rarity parseRarity(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "common" -> Rarity.COMMON;
            case "uncommon" -> Rarity.UNCOMMON;
            case "rare" -> Rarity.RARE;
            case "epic" -> Rarity.EPIC;
            default -> null;
        };
    }

    private static void replace(ItemAttributeModifierEvent event, String itemNamespace,
                                Holder<Attribute> attribute, String property, double amount,
                                EquipmentSlotGroup slot) {
        // id per slot so worn pieces stack
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(NAMESPACE,
            sanitize(property) + "." + slot.getSerializedName());
        replace(event, itemNamespace, attribute, id, amount, slot);
    }

    private static void replace(ItemAttributeModifierEvent event, String itemNamespace,
                                Holder<Attribute> attribute, ResourceLocation id, double amount,
                                EquipmentSlotGroup slot) {
        event.removeIf(entry -> {
            if (!entry.attribute().equals(attribute)) return false;
            String namespace = entry.modifier().id().getNamespace();
            return namespace.equals(VANILLA_NAMESPACE) || namespace.equals(itemNamespace)
                || namespace.equals(NAMESPACE);
        });
        event.addModifier(attribute,
            new AttributeModifier(id, amount, AttributeModifier.Operation.ADD_VALUE), slot);
    }

    private static String sanitize(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_./-]", "_");
    }
}
