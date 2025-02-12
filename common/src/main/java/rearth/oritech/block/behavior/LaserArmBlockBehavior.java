package rearth.oritech.block.behavior;

import net.fabricmc.fabric.api.tag.convention.v1.ConventionalBlockTags;
import net.minecraft.block.*;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import rearth.oritech.block.blocks.interaction.LaserArmBlock;
import rearth.oritech.block.entity.interaction.LaserArmBlockEntity;
import rearth.oritech.client.init.ParticleContent;
import rearth.oritech.init.BlockContent;
import rearth.oritech.init.TagContent;
import rearth.oritech.util.energy.EnergyApi;
import rearth.oritech.util.energy.containers.DynamicEnergyStorage;

public class LaserArmBlockBehavior {
    static private LaserArmBlockBehavior noop;
    static private LaserArmBlockBehavior transferPowerBehavior;
    static private LaserArmBlockBehavior energizeBuddingBehavior;
    
    /**
     * Perform laser behavior on block
     */
    public boolean fireAtBlock(World world, LaserArmBlockEntity laserEntity, Block block, BlockPos blockPos, BlockState blockState, BlockEntity blockEntity) {
        if (laserEntity.hasCropFilterAddon && block instanceof CropBlock crop && !crop.isMature(blockState))
            return false;
        
        // has an energy storage, try to transfer power to it
        var storageCandidate = EnergyApi.BLOCK.find(world, blockPos, blockState, blockEntity, null);
        // if the storage is not exposed (e.g. catalyst / deep drill / atomic forge), get it directly
        if (storageCandidate == null && blockEntity instanceof EnergyApi.BlockProvider provider)
            storageCandidate = provider.getStorage(null);
        if (storageCandidate != null)
            return transferPowerBehavior.fireAtBlock(world, laserEntity, block, blockPos, blockState, blockEntity);
        
        // an unregistered budding block, attempt to energize it
        if (blockState.isIn(ConventionalBlockTags.BUDDING_BLOCKS))
            return energizeBuddingBehavior.fireAtBlock(world, laserEntity, block, blockPos, blockState, blockEntity);
        
        // passes through, stop targetting this block
        if (blockState.isIn(TagContent.LASER_PASSTHROUGH))
            return false;
        
        laserEntity.addBlockBreakProgress(laserEntity.energyRequiredToFire());
        if (laserEntity.getBlockBreakProgress() >= laserEntity.getTargetBlockEnergyNeeded())
            laserEntity.finishBlockBreaking(blockPos, blockState);
        return true;
    }
    
    public static void registerDefaults() {
        noop = new LaserArmBlockBehavior() {
            @Override
            public boolean fireAtBlock(World world, LaserArmBlockEntity laserEntity, Block block, BlockPos blockPos, BlockState blockState, BlockEntity blockEntity) {
                // don't do anything, and don't keep targetting this block
                return false;
            }
        };
        LaserArmBlock.registerBlockBehavior(Blocks.TARGET, noop);
        LaserArmBlock.registerBlockBehavior(Blocks.BEDROCK, noop);
        
        transferPowerBehavior = new LaserArmBlockBehavior() {
            @Override
            public boolean fireAtBlock(World world, LaserArmBlockEntity laserEntity, Block block, BlockPos blockPos, BlockState blockState, BlockEntity blockEntity) {
                var storageCandidate = EnergyApi.BLOCK.find(world, blockPos, blockState, blockEntity, null);
                
                if (storageCandidate == null && blockEntity instanceof EnergyApi.BlockProvider energyProvider)
                    storageCandidate = energyProvider.getStorage(null);
                
                var insertAmount = storageCandidate.getCapacity() - storageCandidate.getAmount();
                if (insertAmount < 10)
                    return false;
                
                var transferCapacity = Math.min(insertAmount, laserEntity.energyRequiredToFire());
                
                if (storageCandidate instanceof DynamicEnergyStorage dynamicStorage) {
                    var inserted = dynamicStorage.insertIgnoringLimit(transferCapacity, true);
                    if (inserted == transferCapacity) {
                        dynamicStorage.insertIgnoringLimit(transferCapacity, false);
                        dynamicStorage.update();
                        return true;
                    }
                    return false;
                } else {
                    var inserted = storageCandidate.insert(transferCapacity, true);
                    if (inserted == transferCapacity) {
                        storageCandidate.insert(transferCapacity, false);
                        storageCandidate.update();
                        return true;
                    }
                    return false;
                }
            }
        };
        LaserArmBlock.registerBlockBehavior(BlockContent.ATOMIC_FORGE_BLOCK, transferPowerBehavior);
        LaserArmBlock.registerBlockBehavior(BlockContent.DEEP_DRILL_BLOCK, transferPowerBehavior);
        LaserArmBlock.registerBlockBehavior(BlockContent.ENCHANTMENT_CATALYST_BLOCK, transferPowerBehavior);
        
        energizeBuddingBehavior = new LaserArmBlockBehavior() {
            @Override
            public boolean fireAtBlock(World world, LaserArmBlockEntity laserEntity, Block block, BlockPos blockPos, BlockState blockState, BlockEntity blockEntity) {
                if (buddingAmethystCanGrow(world, blockState, blockPos)) {
                    blockState.randomTick((ServerWorld) world, blockPos, world.random);
                    ParticleContent.ACCELERATING.spawn(world, Vec3d.of(blockPos));
                    return true;
                }
                return false;
            }
        };
        LaserArmBlock.registerBlockBehavior(Blocks.BUDDING_AMETHYST, energizeBuddingBehavior);
    }
    
    private static boolean buddingAmethystCanGrow(World world, BlockState blockState, BlockPos pos) {
        if (!blockState.isIn(ConventionalBlockTags.BUDDING_BLOCKS))
            return true;
        
        // returning true means the laser will keep firing at the budding amethyst block
        // this means that a laser arm will fire at a budding amethyst block for up to 20 ticks even if the clusters are already fully grown
        // it also means that it will only check the blockstates of the surrounding 6 blocks every 20 ticks instead of every tick
        if (world.getTime() % 20 != 0) {
            return true;
        }
        
        for (var direction : Direction.values()) {
            var growingPos = pos.offset(direction);
            var growingState = world.getBlockState(growingPos);
            if (BuddingAmethystBlock.canGrowIn(growingState) || blockState.isIn(ConventionalBlockTags.BUDS))
                return true;
        }
        
        return false;
    }
}
