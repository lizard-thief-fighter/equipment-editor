package equipment_editor.mixin;

import equipment_editor.InnateEnchantments;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

@Mixin(ItemStack.class)
public abstract class ItemStackMixin {
    @Inject(method = "addToTooltip", at = @At("HEAD"), cancellable = true)
    private void equipment_editor$innateTooltip(DataComponentType<?> type, Item.TooltipContext context,
                                                Consumer<Component> lines, TooltipFlag flag, CallbackInfo ci) {
        if (type == DataComponents.ENCHANTMENTS
            && InnateEnchantments.addTooltip((ItemStack) (Object) this, context, lines, flag)) {
            ci.cancel();
        }
    }
}
