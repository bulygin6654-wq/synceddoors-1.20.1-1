package com.example.synceddoors;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Синхронное открытие парных дверей и калиток + звук и частицы.
 *
 * Как это работает: мы не вмешиваемся в ванильную логику дверей. Вместо этого запоминаем
 * состояние двери (открыта/закрыта) в момент клика игрока или обновления соседних блоков
 * (редстоун), а в конце тика сравниваем. Если дверь изменила состояние, то её пара
 * получает то же самое состояние. Так работает и рука, и кнопки, и рычаги, и плиты,
 * и двери из других модов, основанные на ванильных классах.
 */
public class SyncHandler {

    // ---- настройки (при желании можно вынести в конфиг-файл) ----
    /** Двойные двери открываются вместе. */
    private static final boolean PAIR_DOORS = true;
    /** Две соседние калитки в ряд открываются вместе. */
    private static final boolean PAIR_GATES = true;
    /** Соседние люки в ряд открываются вместе. По умолчанию выключено. */
    private static final boolean PAIR_TRAPDOORS = false;
    /** Парой считаются только блоки одного типа (дубовая с дубовой). */
    private static final boolean REQUIRE_SAME_BLOCK = true;
    /** Едва заметная пылинка у основания при открытии и закрытии (false - совсем без пыли). */
    private static final boolean PARTICLES = true;
    /** Громкость звука у второй створки пары. */
    private static final float PARTNER_SOUND_VOLUME = 0.9F;

    /** Позиции дверей, замеченные за текущий тик, и их состояние "открыто" на тот момент. */
    private final Map<ResourceKey<Level>, Map<BlockPos, Boolean>> pending = new HashMap<>();
    /** Пока мы сами меняем состояние партнёров, свои же события игнорируем. */
    private boolean processing = false;

    // ------------------------------------------------------------------ события

    @SubscribeEvent
    public void onRightClick(PlayerInteractEvent.RightClickBlock event) {
        Level level = event.getLevel();
        if (level.isClientSide()) {
            predictOnClient(event);
            return;
        }
        if (processing) return;
        track(level, event.getPos());
    }

    /**
     * Клиентское предсказание: сразу показываем пару открытой/закрытой вместе с кликнутой дверью,
     * не дожидаясь ответа сервера. Сервер пришлёт то же состояние, поэтому разницы не видно,
     * а задержки у второй створки нет. Если сервер решит иначе, он сам всё поправит.
     */
    private void predictOnClient(PlayerInteractEvent.RightClickBlock event) {
        if (event.getHand() != InteractionHand.MAIN_HAND || event.isCanceled()) return;
        Level level = event.getLevel();
        Player player = event.getEntity();
        // шифт с предметом в руке - это установка блока, а не открытие
        if (player.isSecondaryUseActive() && (!player.getMainHandItem().isEmpty() || !player.getOffhandItem().isEmpty())) return;

        BlockPos pos = event.getPos();
        BlockState state = stateAt(level, pos);
        if (!isSyncable(state)) return;
        // металлические двери и люки руками не открываются
        if (state.getSoundType() == SoundType.METAL) return;
        if (state.getBlock() instanceof DoorBlock
                && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) {
            pos = pos.below();
            state = stateAt(level, pos);
            if (!isSyncable(state)) return;
        }
        boolean open = !state.getValue(BlockStateProperties.OPEN);
        for (BlockPos partnerPos : partners(level, pos, state)) {
            BlockState partner = stateAt(level, partnerPos);
            if (!isSyncable(partner) || partner.getValue(BlockStateProperties.OPEN) == open) continue;
            setOpenClient(level, partnerPos, partner, open);
        }
    }

    private void setOpenClient(Level level, BlockPos pos, BlockState state, boolean open) {
        level.setBlock(pos, state.setValue(BlockStateProperties.OPEN, open), 10);
        if (state.getBlock() instanceof DoorBlock) {
            BlockPos otherPos = state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER
                    ? pos.above() : pos.below();
            BlockState other = stateAt(level, otherPos);
            if (other.is(state.getBlock()) && other.hasProperty(BlockStateProperties.OPEN)) {
                level.setBlock(otherPos, other.setValue(BlockStateProperties.OPEN, open), 10);
            }
        }
    }

    @SubscribeEvent
    public void onNeighborNotify(BlockEvent.NeighborNotifyEvent event) {
        if (processing) return;
        if (!(event.getLevel() instanceof Level level) || level.isClientSide()) return;
        BlockPos source = event.getPos();
        for (Direction dir : event.getNotifiedSides()) {
            track(level, source.relative(dir));
        }
    }

    @SubscribeEvent
    public void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.side != LogicalSide.SERVER) return;
        if (!(event.level instanceof ServerLevel level)) return;

        Map<BlockPos, Boolean> map = pending.remove(level.dimension());
        if (map == null || map.isEmpty()) return;

        processing = true;
        try {
            for (Map.Entry<BlockPos, Boolean> entry : map.entrySet()) {
                BlockPos pos = entry.getKey();
                BlockState state = stateAt(level, pos);
                if (!isSyncable(state)) continue;

                boolean open = state.getValue(BlockStateProperties.OPEN);
                if (open == entry.getValue()) continue; // не изменилась

                onToggled(level, pos, state, open);
            }
        } finally {
            processing = false;
        }
    }

    // ------------------------------------------------------------------ логика

    private void track(Level level, BlockPos pos) {
        BlockState state = stateAt(level, pos);
        if (!isSyncable(state)) return;

        // у двери обе половины хранят одно и то же состояние, работаем с нижней
        if (state.getBlock() instanceof DoorBlock
                && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) {
            pos = pos.below();
        }
        pending.computeIfAbsent(level.dimension(), k -> new HashMap<>())
                .putIfAbsent(pos.immutable(), state.getValue(BlockStateProperties.OPEN));
    }

    private void onToggled(ServerLevel level, BlockPos pos, BlockState state, boolean open) {
        // у самого блока звук уже сыграла ваниль, добавляем только частицы
        effects(level, pos, state, open, false);

        for (BlockPos partnerPos : partners(level, pos, state)) {
            BlockState partner = stateAt(level, partnerPos);
            if (!isSyncable(partner) || partner.getValue(BlockStateProperties.OPEN) == open) continue;
            setOpen(level, partnerPos, partner, open);
            effects(level, partnerPos, partner, open, true);
        }
    }

    /** Находит парные блоки для двери, калитки или люка. */
    private List<BlockPos> partners(Level level, BlockPos pos, BlockState state) {
        List<BlockPos> result = new ArrayList<>(2);
        Block block = state.getBlock();

        if (block instanceof DoorBlock) {
            if (!PAIR_DOORS) return result;
            if (state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) != DoubleBlockHalf.LOWER) return result;

            Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
            DoorHingeSide hinge = state.getValue(BlockStateProperties.DOOR_HINGE);
            // дверь с петлёй справа стоит правее своей пары, значит пара слева от неё
            Direction side = hinge == DoorHingeSide.RIGHT ? facing.getCounterClockWise() : facing.getClockWise();

            BlockPos otherPos = pos.relative(side);
            BlockState other = stateAt(level, otherPos);
            if (other.getBlock() instanceof DoorBlock
                    && other.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER
                    && other.getValue(BlockStateProperties.HORIZONTAL_FACING) == facing
                    && other.getValue(BlockStateProperties.DOOR_HINGE) != hinge
                    && (!REQUIRE_SAME_BLOCK || other.is(block))) {
                result.add(otherPos);
            }
        } else if (block instanceof FenceGateBlock) {
            if (!PAIR_GATES) return result;
            addSideNeighbours(level, pos, state, result, false);
        } else if (block instanceof TrapDoorBlock) {
            if (!PAIR_TRAPDOORS) return result;
            addSideNeighbours(level, pos, state, result, true);
        }
        return result;
    }

    /** Соседи слева и справа с тем же направлением (и той же половиной для люков). */
    private void addSideNeighbours(Level level, BlockPos pos, BlockState state, List<BlockPos> result, boolean checkHalf) {
        Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
        for (Direction side : new Direction[]{facing.getClockWise(), facing.getCounterClockWise()}) {
            BlockPos otherPos = pos.relative(side);
            BlockState other = stateAt(level, otherPos);
            if (other.getBlock().getClass() != state.getBlock().getClass()) continue;
            if (other.getValue(BlockStateProperties.HORIZONTAL_FACING) != facing) continue;
            if (checkHalf && other.getValue(BlockStateProperties.HALF) != state.getValue(BlockStateProperties.HALF)) continue;
            if (REQUIRE_SAME_BLOCK && !other.is(state.getBlock())) continue;
            result.add(otherPos);
        }
    }

    private void setOpen(Level level, BlockPos pos, BlockState state, boolean open) {
        level.setBlock(pos, state.setValue(BlockStateProperties.OPEN, open), 10);

        // вторая половина двери обычно обновляется сама, но на всякий случай выравниваем явно
        if (state.getBlock() instanceof DoorBlock) {
            BlockPos otherPos = state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER
                    ? pos.above() : pos.below();
            BlockState other = stateAt(level, otherPos);
            if (other.is(state.getBlock()) && other.hasProperty(BlockStateProperties.OPEN)
                    && other.getValue(BlockStateProperties.OPEN) != open) {
                level.setBlock(otherPos, other.setValue(BlockStateProperties.OPEN, open), 10);
            }
        }
        // люк с водой: как и в ваниле, нужно обновить жидкость
        if (state.hasProperty(BlockStateProperties.WATERLOGGED) && state.getValue(BlockStateProperties.WATERLOGGED)) {
            level.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level));
        }
        level.gameEvent(null, open ? GameEvent.BLOCK_OPEN : GameEvent.BLOCK_CLOSE, pos);
    }

    // ------------------------------------------------------------------ звук и частицы

    private void effects(ServerLevel level, BlockPos pos, BlockState state, boolean open, boolean playSound) {
        if (playSound) {
            level.playSound(null, pos, soundFor(state, open), SoundSource.BLOCKS,
                    PARTNER_SOUND_VOLUME, level.random.nextFloat() * 0.1F + 0.9F);
        }
        // едва заметная пыль: одна частица, только у кликнутой створки (у пары не показываем)
        if (PARTICLES && !playSound) {
            level.sendParticles(ParticleTypes.CLOUD,
                    pos.getX() + 0.5, pos.getY() + 0.05, pos.getZ() + 0.5,
                    1, 0.2, 0.0, 0.2, 0.0);
        }
    }

    private SoundEvent soundFor(BlockState state, boolean open) {
        Block block = state.getBlock();
        boolean metal = state.getSoundType() == SoundType.METAL;
        if (block instanceof DoorBlock) {
            if (metal) return open ? SoundEvents.IRON_DOOR_OPEN : SoundEvents.IRON_DOOR_CLOSE;
            return open ? SoundEvents.WOODEN_DOOR_OPEN : SoundEvents.WOODEN_DOOR_CLOSE;
        }
        if (block instanceof TrapDoorBlock) {
            if (metal) return open ? SoundEvents.IRON_TRAPDOOR_OPEN : SoundEvents.IRON_TRAPDOOR_CLOSE;
            return open ? SoundEvents.WOODEN_TRAPDOOR_OPEN : SoundEvents.WOODEN_TRAPDOOR_CLOSE;
        }
        return open ? SoundEvents.FENCE_GATE_OPEN : SoundEvents.FENCE_GATE_CLOSE;
    }

    // ------------------------------------------------------------------ вспомогательное

    private static boolean isSyncable(BlockState state) {
        Block block = state.getBlock();
        return (block instanceof DoorBlock || block instanceof TrapDoorBlock || block instanceof FenceGateBlock)
                && state.hasProperty(BlockStateProperties.OPEN);
    }

    /** Читает блок, не заставляя сервер загружать незагруженные чанки. */
    private static BlockState stateAt(Level level, BlockPos pos) {
        if (!level.hasChunkAt(pos)) return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        return level.getBlockState(pos);
    }
}
