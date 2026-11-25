package equipment_editor;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.enchanting.GetEnchantmentLevelEvent;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

public final class InnateEnchantments {
    private static final Logger LOGGER = EquipmentConfig.LOGGER;

    private static Map<ResourceLocation, Map<ResourceKey<Enchantment>, Integer>> byItem = Map.of();

    // per side, to get an enchantment's key
    private static volatile Registry<Enchantment> serverRegistry;
    private static volatile Registry<Enchantment> clientRegistry;

    private InnateEnchantments() {}

    static void rebuild(Map<ResourceLocation, EquipmentConfig.Rule> itemRules) {
        Map<ResourceLocation, Map<ResourceKey<Enchantment>, Integer>> index = new HashMap<>();
        itemRules.forEach((item, rule) -> {
            if (rule.enchantments == null || rule.enchantments.isEmpty()) return;
            Map<ResourceKey<Enchantment>, Integer> levels = new LinkedHashMap<>();
            rule.enchantments.forEach((key, level) -> {
                ResourceLocation id = key == null ? null : ResourceLocation.tryParse(key.trim());
                if (id == null) {
                    LOGGER.warn("{}: invalid enchantment ID \"{}\"; ignored", item, key);
                } else if (level == null || level < 1) {
                    LOGGER.warn("{}: enchantment {} needs a level of at least 1; ignored", item, id);
                } else {
                    levels.put(ResourceKey.create(Registries.ENCHANTMENT, id), level);
                }
            });
            if (!levels.isEmpty()) index.put(item, Map.copyOf(levels));
        });
        byItem = Map.copyOf(index);
    }

    private static Map<ResourceKey<Enchantment>, Integer> of(ItemStack stack) {
        if (stack.isEmpty() || byItem.isEmpty()) return Map.of();
        return byItem.getOrDefault(BuiltInRegistries.ITEM.getKey(stack.getItem()), Map.of());
    }

    public static void onTagsUpdated(TagsUpdatedEvent event) {
        Registry<Enchantment> registry = event.getRegistryAccess().registryOrThrow(Registries.ENCHANTMENT);
        if (event.getUpdateCause() == TagsUpdatedEvent.UpdateCause.CLIENT_PACKET_RECEIVED) {
            clientRegistry = registry;
            return;
        }
        serverRegistry = registry;
        byItem.forEach((item, levels) -> levels.keySet().forEach(key -> {
            if (!registry.containsKey(key)) {
                LOGGER.warn("{}: unknown enchantment \"{}\"; ignored", item, key.location());
            }
        }));
    }

    public static void onGetEnchantmentLevel(GetEnchantmentLevelEvent event) {
        Map<ResourceKey<Enchantment>, Integer> innate = of(event.getStack());
        if (innate.isEmpty()) return;
        ItemEnchantments applied = applied(event.getStack());
        ItemEnchantments.Mutable levels = event.getEnchantments();
        innate.forEach((key, level) -> {
            if (!event.isTargetting(key)) return;
            event.getHolder(key).ifPresent(holder -> {
                if (compatible(applied, holder)) levels.set(holder, Math.max(levels.getLevel(holder), level));
            });
        });
    }

    public static boolean blocks(ItemStack stack, Enchantment enchantment) {
        Map<ResourceKey<Enchantment>, Integer> innate = of(stack);
        if (innate.isEmpty()) return false;
        Registry<Enchantment> registry = registryOf(enchantment);
        if (registry == null) return false;
        Holder<Enchantment> candidate = registry.wrapAsHolder(enchantment);
        for (ResourceKey<Enchantment> key : innate.keySet()) {
            Optional<Holder.Reference<Enchantment>> holder = registry.getHolder(key);
            if (holder.isPresent() && !holder.get().equals(candidate)
                && !Enchantment.areCompatible(holder.get(), candidate)) {
                return true;
            }
        }
        return false;
    }

    public static boolean addTooltip(ItemStack stack, Item.TooltipContext context, Consumer<Component> lines,
                                     TooltipFlag flag) {
        Map<ResourceKey<Enchantment>, Integer> innate = of(stack);
        HolderLookup.Provider registries = context.registries();
        if (innate.isEmpty() || registries == null) return false;
        Optional<HolderLookup.RegistryLookup<Enchantment>> lookup = registries.lookup(Registries.ENCHANTMENT);
        if (lookup.isEmpty()) return false;

        ItemEnchantments applied = applied(stack);
        ItemEnchantments.Mutable merged = new ItemEnchantments.Mutable(applied);
        Map<Component, Component> italic = new HashMap<>();
        innate.forEach((key, level) -> lookup.get().get(key).ifPresent(holder -> {
            if (!compatible(applied, holder) || applied.getLevel(holder) >= level) return;
            merged.set(holder, level);
            Component line = Enchantment.getFullname(holder, level);
            italic.put(line, line.copy().withStyle(ChatFormatting.ITALIC));
        }));
        merged.toImmutable().addToTooltip(context, line -> lines.accept(italic.getOrDefault(line, line)), flag);
        return true;
    }

    private static ItemEnchantments applied(ItemStack stack) {
        return stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
    }

    // innate is off while stack has a conflicting enchant (quark rule)
    private static boolean compatible(ItemEnchantments applied, Holder<Enchantment> innate) {
        for (Holder<Enchantment> enchantment : applied.keySet()) {
            if (!enchantment.equals(innate) && !Enchantment.areCompatible(enchantment, innate)) return false;
        }
        return true;
    }

    private static Registry<Enchantment> registryOf(Enchantment enchantment) {
        Registry<Enchantment> server = serverRegistry;
        if (server != null && server.getResourceKey(enchantment).isPresent()) return server;
        Registry<Enchantment> client = clientRegistry;
        if (client != null && client.getResourceKey(enchantment).isPresent()) return client;
        return null;
    }
}
