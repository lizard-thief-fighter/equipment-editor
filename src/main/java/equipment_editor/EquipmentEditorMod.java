package equipment_editor;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;

@Mod(EquipmentEditorMod.MOD_ID)
public final class EquipmentEditorMod {
    public static final String MOD_ID = "equipment_editor";

    public EquipmentEditorMod(IEventBus modBus) {
        EquipmentConfig.load();
        modBus.addListener(EquipmentModifier::modifyDefaultComponents);
        NeoForge.EVENT_BUS.addListener(EquipmentModifier::modifyAttributes);
        NeoForge.EVENT_BUS.addListener(EquipmentModifier::modifyMiningSpeed);
        NeoForge.EVENT_BUS.addListener(InnateEnchantments::onTagsUpdated);
        NeoForge.EVENT_BUS.addListener(InnateEnchantments::onGetEnchantmentLevel);
    }
}
