package rearth.oritech.block.entity.reactor;

import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.Pair;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector2i;
import rearth.oritech.block.blocks.reactor.*;
import rearth.oritech.client.init.ModScreens;
import rearth.oritech.client.ui.ReactorScreenHandler;
import rearth.oritech.init.BlockEntitiesContent;
import rearth.oritech.network.NetworkContent;
import rearth.oritech.util.Geometry;
import rearth.oritech.util.energy.EnergyApi;
import rearth.oritech.util.energy.containers.SimpleEnergyStorage;

import java.util.*;

public class ReactorControllerBlockEntity extends BlockEntity implements BlockEntityTicker<ReactorControllerBlockEntity>, EnergyApi.BlockProvider, ExtendedScreenHandlerFactory {
    
    public static final int MAX_SIZE = 64;
    public static final int RF_PER_PULSE = 32;
    public static final int ABSORBER_RATE = 10;
    public static final int VENT_BASE_RATE = 4;
    public static final int VENT_RELATIVE_RATE = 100;
    
    private final HashMap<Vector2i, BaseReactorBlock> activeComponents = new HashMap<>();   // 2d local position on the first layer containing the reactor blocks
    private final HashMap<Vector2i, ReactorFuelPortEntity> fuelPorts = new HashMap<>();     // same grid, but contains a reference to the port at the ceiling
    private final HashMap<Vector2i, ReactorAbsorberPortEntity> absorberPorts = new HashMap<>(); // same
    private final HashMap<Vector2i, Integer> componentHeats = new HashMap<>();              // same grid, contains the current heat of the component
    private final HashMap<Vector2i, ComponentStatistics> componentStats = new HashMap<>(); // mainly for client displays, same grid
    private final HashSet<Pair<BlockPos, Direction>> energyPorts = new HashSet<>();   // list of all energy port outputs (e.g. the targets to output to)
    
    public SimpleEnergyStorage energyStorage = new SimpleEnergyStorage(0, 1_000_000, 10_000_000, this::markDirty);
    public boolean active = false;
    private int reactorStackHeight;
    private BlockPos areaMin;
    private BlockPos areaMax;
    
    // client only
    public NetworkContent.ReactorUIDataPacket uiData;
    public NetworkContent.ReactorUISyncPacket uiSyncData;
    
    public ReactorControllerBlockEntity(BlockPos pos, BlockState state) {
        super(BlockEntitiesContent.REACTOR_CONTROLLER_BLOCK_ENTITY, pos, state);
    }
    
    // heat is only used for reactor rods and heat pipes
    // rods generate heat. Multi-cores and reflectors (expensive) change this
    // heat pipes move heat to themselves
    // vents remove heat from the hottest neighbor component
    // absorbers remove fixed heat amount from all neighboring blocks
    
    @Override
    public void tick(World world, BlockPos pos, BlockState state, ReactorControllerBlockEntity blockEntity) {
        if (world.isClient || !active || activeComponents.isEmpty()) return;
        
        for (var localPos : activeComponents.keySet()) {
            var component = activeComponents.get(localPos);
            var componentHeat = componentHeats.get(localPos);
            
            if (component instanceof ReactorRodBlock rodBlock) {
                
                var ownRodCount = rodBlock.getRodCount();
                var receivedPulses = rodBlock.getInternalPulseCount();
                
                var portEntity = fuelPorts.get(localPos);
                if (portEntity == null|| portEntity.isRemoved()) {
                    continue;
                }
                
                var hasFuel = portEntity.tryConsumeFuel(ownRodCount * reactorStackHeight);
                var heatCreated = 0;
                
                if (hasFuel) {
                    // check how many pulses are received from neighbors / reflectors
                    for (var neighborPos : getNeighborsInBounds(localPos, activeComponents.keySet())) {
                        
                        var neighbor = activeComponents.get(neighborPos);
                        if (neighbor instanceof ReactorRodBlock neighborRod) {
                            receivedPulses += neighborRod.getRodCount();
                        } else if (neighbor instanceof ReactorReflectorBlock reflectorBlock) {
                            receivedPulses += rodBlock.getRodCount();
                        }
                    }
                    
                    // generate 5 RF per pulse
                    energyStorage.insertIgnoringLimit(RF_PER_PULSE * receivedPulses * reactorStackHeight, false);
                    
                    // generate heat per pulse
                    heatCreated = (receivedPulses / 2 * receivedPulses + 4);
                    componentHeat += heatCreated;
                } else {
                    receivedPulses = 0;
                }
                
                componentStats.put(localPos, new ComponentStatistics((short) receivedPulses, componentHeat, (short) heatCreated));
                
            } else if (component instanceof ReactorHeatPipeBlock heatPipeBlock) {
                
                var sumGainedHeat = 0;
                
                // take heat in from neighbors
                for (var neighbor : getNeighborsInBounds(localPos, activeComponents.keySet())) {
                    var neighborHeat = componentHeats.get(neighbor);
                    if (neighborHeat <= componentHeat) continue;
                    var diff = neighborHeat - componentHeat;
                    var gainedHeat = (int) Math.min(Math.sqrt(diff) + 6, diff);
                    neighborHeat -= gainedHeat;
                    componentHeats.put(neighbor, neighborHeat);
                    componentHeat += gainedHeat;
                    sumGainedHeat += gainedHeat;
                }
                
                componentStats.put(localPos, new ComponentStatistics((short) 0, componentHeat, (short) sumGainedHeat));
                
            } else if (component instanceof ReactorAbsorberBlock absorberBlock) {
                
                var sumRemovedHeat = 0;
                var portEntity = absorberPorts.get(localPos);
                if (portEntity == null|| portEntity.isRemoved()) {
                    continue;
                }
                var fuelAvailable = portEntity.getAvailableFuel();
                
                if (fuelAvailable >= reactorStackHeight) {
                    // take heat in from neighbors and remove it
                    for (var neighbor : getNeighborsInBounds(localPos, activeComponents.keySet())) {
                        var neighborHeat = componentHeats.get(neighbor);
                        if (neighborHeat <= 0) continue;
                        neighborHeat -= ABSORBER_RATE;
                        sumRemovedHeat += ABSORBER_RATE;
                        componentHeats.put(neighbor, neighborHeat);
                    }
                }
                
                if (sumRemovedHeat > 0) {
                    portEntity.consumeFuel(reactorStackHeight);
                }
                
                componentStats.put(localPos, new ComponentStatistics((short) 0, 0, (short) sumRemovedHeat));
            } else if (component instanceof ReactorHeatVentBlock ventBlock) {
                
                // remove heat from hottest neighbor
                
                var hottestPos = localPos;
                var max = 0;
                for (var neighbor : getNeighborsInBounds(localPos, activeComponents.keySet())) {
                    var neighborHeat = componentHeats.get(neighbor);
                    if (neighborHeat <= max) continue;
                    hottestPos = neighbor;
                    max = neighborHeat;
                }
                
                var removed = 0;
                if (max != 0) {
                    var neighborHeat = max;
                    removed = Math.min(neighborHeat / VENT_RELATIVE_RATE + VENT_BASE_RATE, neighborHeat);
                    neighborHeat -= removed;
                    componentHeats.put(hottestPos, neighborHeat);
                }
                
                componentStats.put(localPos, new ComponentStatistics((short) 0, 0, (short) removed));
                
            }
            
            componentHeats.put(localPos, componentHeat);
        }
        
        outputEnergy();
        
        if (world.getTime() % 2 == 0)
            sendUINetworkData();
        
    }
    
    public void init(PlayerEntity player) {
        
        active = false;
        
        // find low and high corners of reactor
        var cornerA = pos;
        cornerA = expandWall(cornerA, new Vec3i(0, -1, 0), true);   // first go down through other wall blocks
        cornerA = expandWall(cornerA, new Vec3i(0, 0, -1));
        cornerA = expandWall(cornerA, new Vec3i(-1, 0, 0));
        cornerA = expandWall(cornerA, new Vec3i(0, 0, -1)); // expand z again to support all rotations
        
        var cornerB = cornerA;
        cornerB = expandWall(cornerB, new Vec3i(0, 1, 0));
        cornerB = expandWall(cornerB, new Vec3i(0, 0, 1));
        cornerB = expandWall(cornerB, new Vec3i(1, 0, 0));
        
        if (cornerA == pos || cornerB == pos || cornerA == cornerB || onSameAxis(cornerA, cornerB)) {
            player.sendMessage(Text.translatable("message.oritech.reactor_invalid"));
            return;
        }
        
        System.out.println("corners valid");
        
        // verify and load all blocks in reactor area
        var finalCornerA = cornerA;
        var finalCornerB = cornerB;
        
        // these get loaded in the next step
        energyPorts.clear();
        
        // verify edges
        var wallsValid = BlockPos.stream(cornerA, cornerB).allMatch(pos -> {
            if (isAtEdgeOfBox(pos, finalCornerA, finalCornerB)) {
                var block = world.getBlockState(pos).getBlock();
                return block instanceof ReactorWallBlock;
            } else if (isOnWall(pos, finalCornerA, finalCornerB)) {
                var state = world.getBlockState(pos);
                var block = state.getBlock();
                
                // load wall energy ports
                if (block instanceof ReactorEnergyPortBlock) {
                    var facing = state.get(Properties.FACING);
                    var blockInFront = pos.add(Geometry.getForward(facing));
                    energyPorts.add(new Pair<>(blockInFront, Direction.fromVector(Geometry.getBackward(facing).getX(), Geometry.getBackward(facing).getY(), Geometry.getBackward(facing).getZ())));
                }
                
                return !(block instanceof BaseReactorBlock reactorBlock) || reactorBlock.validForWalls();
            }
            
            return true;
        });
        
        if (!wallsValid) {
            player.sendMessage(Text.translatable("message.oritech.reactor_invalid"));
            return;
        }
        
        // verify interior is identical in all layers
        var interiorHeight = cornerB.getY() - cornerA.getY() - 1;
        var cornerAFlat = cornerA.add(1, 1, 1);
        var cornerBFlat = new BlockPos(cornerB.getX() - 1, cornerA.getY() + 1, cornerB.getZ() - 1);
        
        // these get loaded in the next step
        fuelPorts.clear();
        absorberPorts.clear();
        reactorStackHeight = interiorHeight;
        
        var interiorStackedRight = BlockPos.stream(cornerAFlat, cornerBFlat).allMatch(pos -> {
            
            var offset = pos.subtract(cornerAFlat);
            var localPos = new Vector2i(offset.getX(), offset.getZ());
            
            var block = world.getBlockState(pos).getBlock();
            if (!(block instanceof BaseReactorBlock reactorBlock)) return true;
            
            for (int i = 1; i < interiorHeight; i++) {
                var candidatePos = pos.add(0, i, 0);
                var candidate = world.getBlockState(candidatePos);
                if (!candidate.getBlock().equals(block))
                    return false;
            }
            
            var requiredCeiling = reactorBlock.requiredStackCeiling();
            if (requiredCeiling != Blocks.AIR) {
                var ceilingPos = pos.add(0, interiorHeight, 0);
                var ceilingBlock = world.getBlockState(ceilingPos).getBlock();
                System.out.println("ceiling need: " + requiredCeiling + " got: " + ceilingBlock);
                if (!requiredCeiling.equals(ceilingBlock)) return false;
                
                if (block instanceof ReactorRodBlock) {
                    fuelPorts.put(localPos, (ReactorFuelPortEntity) world.getBlockEntity(ceilingPos));
                } else if (block instanceof ReactorAbsorberBlock) {
                    absorberPorts.put(localPos, (ReactorAbsorberPortEntity) world.getBlockEntity(ceilingPos));
                }
                
            }
            activeComponents.put(localPos, reactorBlock);
            componentHeats.putIfAbsent(localPos, 0);
            System.out.println(offset + ": " + reactorBlock);
            
            return true;
        });
        
        if (!interiorStackedRight) {
            player.sendMessage(Text.translatable("message.oritech.interior_invalid"));
            return;
        }
        
        areaMin = finalCornerA;
        areaMax = finalCornerB;
        active = true;
        
        System.out.println("walls: " + wallsValid + " interiorStack: " + interiorStackedRight + " height: " + interiorHeight);
        
    }
    
    private static Set<Vector2i> getNeighborsInBounds(Vector2i pos, Set<Vector2i> keys) {
        
        var res = new HashSet<Vector2i>(4);
        
        var a = new Vector2i(pos).add(-1, 0);
        if (keys.contains(a)) res.add(a);
        var b = new Vector2i(pos).add(0, 1);
        if (keys.contains(b)) res.add(b);
        var c = new Vector2i(pos).add(1, 0);
        if (keys.contains(c)) res.add(c);
        var d = new Vector2i(pos).add(0, -1);
        if (keys.contains(d)) res.add(d);
        
        return res;
    }
    
    private static boolean onSameAxis(BlockPos A, BlockPos B) {
        return A.getX() == B.getX() || A.getY() == B.getY() || A.getZ() == B.getZ();
    }
    
    private static boolean isOnWall(BlockPos pos, BlockPos min, BlockPos max) {
        return onSameAxis(pos, min) || onSameAxis(pos, max);
    }
    
    private static boolean isAtEdgeOfBox(BlockPos pos, BlockPos min, BlockPos max) {
        int planesAligned = 0;
        
        if (pos.getX() == min.getX() || pos.getX() == max.getX()) planesAligned++;
        if (pos.getY() == min.getY() || pos.getY() == max.getY()) planesAligned++;
        if (pos.getZ() == min.getZ() || pos.getZ() == max.getZ()) planesAligned++;
        
        return planesAligned >= 2;
    }
    
    private BlockPos expandWall(BlockPos from, Vec3i direction) {
        return expandWall(from, direction, false);
    }
    
    private BlockPos expandWall(BlockPos from, Vec3i direction, boolean allReactorBlocks) {
        
        var result = from;
        for (int i = 1; i < MAX_SIZE; i++) {
            var candidate = from.add(direction.multiply(i));
            var candidateBlock = world.getBlockState(candidate).getBlock();
            
            if (!allReactorBlocks && !(candidateBlock instanceof ReactorWallBlock)) return result;
            if (allReactorBlocks && !(candidateBlock instanceof BaseReactorBlock)) return result;
            
            result = candidate;
        }
        
        return result;
        
    }
    
    private void outputEnergy() {
        
        var totalMoved = 0;
        
        var randomOrderedList = new ArrayList<>(energyPorts);
        Collections.shuffle(randomOrderedList);
        
        for (var candidateData : randomOrderedList) {
            var candidate = EnergyApi.BLOCK.find(world, candidateData.getLeft(), candidateData.getRight());
            if (candidate == null) continue;
            var moved = EnergyApi.transfer(energyStorage, candidate, energyStorage.getAmount(), false);
            
            if (moved > 0)
                candidate.update();
            
            totalMoved += moved;
        }
        
        if (totalMoved > 0)
            energyStorage.update();
    }
    
    @Override
    public EnergyApi.EnergyContainer getStorage(Direction direction) {
        return energyStorage;
    }
    
    private void sendUINetworkData() {
        
        if (!active || activeComponents.isEmpty() || !isActivelyViewed()) return;
        
        for (var port : fuelPorts.values()) port.updateNetwork();
        for (var port : absorberPorts.values()) port.updateNetwork();
        
        var positionsFlat = activeComponents.keySet();
        var positions = positionsFlat.stream().map(pos -> areaMin.add(pos.x + 1, 1, pos.y + 1)).toList();
        var heats = positionsFlat.stream().map(pos -> componentStats.getOrDefault(pos, ComponentStatistics.EMPTY)).toList();
        
        NetworkContent.MACHINE_CHANNEL.serverHandle(this).send(new NetworkContent.ReactorUISyncPacket(pos, positions, heats, energyStorage.getAmount()));
    }
    
    private boolean isActivelyViewed() {
        var closestPlayer = Objects.requireNonNull(world).getClosestPlayer(pos.getX(), pos.getY(), pos.getZ(), 5, false);
        return closestPlayer != null && closestPlayer.currentScreenHandler instanceof ReactorScreenHandler handler && getPos().equals(handler.reactorEntity.pos);
    }
    
    @Override
    public Object getScreenOpeningData(ServerPlayerEntity player) {
        var previewMax = new BlockPos(areaMax.getX(), areaMin.getY() + 1, areaMax.getZ());
        NetworkContent.MACHINE_CHANNEL.serverHandle(this).send(new NetworkContent.ReactorUIDataPacket(pos, areaMin, areaMax, previewMax));
        return new ModScreens.BasicData(pos);
    }
    
    @Override
    public Text getDisplayName() {
        return Text.of("");
    }
    
    @Nullable
    @Override
    public ScreenHandler createMenu(int syncId, PlayerInventory playerInventory, PlayerEntity player) {
        return new ReactorScreenHandler(syncId, playerInventory, this);
    }
    
    public record ComponentStatistics(short receivedPulses, int storedHeat, short heatChanged) {
        public static final ComponentStatistics EMPTY = new ComponentStatistics((short) 0, -1, (short) 0);
    }
}
