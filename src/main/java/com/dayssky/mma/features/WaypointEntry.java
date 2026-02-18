package com.dayssky.mma.features;

import net.minecraft.core.BlockPos;

public record WaypointEntry(BlockPos pos) {
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof WaypointEntry other)) return false;
        return pos.equals(other.pos);
    }

    @Override
    public int hashCode() {
        return pos.hashCode();
    }
}