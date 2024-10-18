package rearth.oritech.item.tools.armor;

import net.fabricmc.fabric.api.transfer.v1.fluid.FluidConstants;
import net.minecraft.entity.Entity;
import net.minecraft.item.ArmorMaterial;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.text.Text;
import net.minecraft.world.World;

import java.util.List;

public class JetpackExoArmorItem extends BackstorageExoArmorItem implements BaseJetpackItem {
    public JetpackExoArmorItem(RegistryEntry<ArmorMaterial> material, Type type, Item.Settings settings) {
        super(material, type, settings);
    }
    
    @Override
    public void inventoryTick(ItemStack stack, World world, Entity entity, int slot, boolean selected) {
        
        if (world.isClient) {
            tickJetpack(stack, entity);
        } else {
            super.inventoryTick(stack, world, entity, slot, selected);
        }
    }
    
    @Override
    public int getItemBarColor(ItemStack stack) {
        return getJetpackBarColor(stack);
    }
    
    @Override
    public int getItemBarStep(ItemStack stack) {
        return getJetpackBarStep(stack);
    }
    
    @Override
    public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
        super.appendTooltip(stack, context, tooltip, type);
        addJetpackTooltip(stack, tooltip, false);
    }
    
    @Override
    public float getSpeed() {
        return 2f;
    }
    
    @Override
    public boolean requireUpward() {
        return false;
    }
    
    @Override
    public int getRfUsage() {
        return 256;
    }
    
    @Override
    public int getFuelUsage() {
        return (int) (10 * (FluidConstants.BUCKET / 1000));
    }
    
    @Override
    public long getFuelCapacity() {
        return 4 * FluidConstants.BUCKET;
    }
    
    @Override
    public long getEnergyMaxInput(ItemStack stack) {
        return 20_000;
    }
}