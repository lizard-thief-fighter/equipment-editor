package equipment_editor.mixin;

import equipment_editor.InnateEnchantments;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// every neoforge enchantability check ends up here
@Mixin(Enchantment.class)
public abstract class EnchantmentMixin {
    @Inject(method = "isSupportedItem", at = @At("RETURN"), cancellable = true)
    private void equipment_editor$blockInnateConflicts(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && InnateEnchantments.blocks(stack, (Enchantment) (Object) this)) {
            cir.setReturnValue(false);
        }
    }
}
